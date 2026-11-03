package eu.kanade.tachiyomi.extension.es.manhwashot

import android.util.Log
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.decodeHex
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.xor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class ManhwaShot : KeiSource() {

    private val chapterSessions = ConcurrentHashMap<String, ChapterPgSession>()

    @Volatile
    private var sendCookiesOnPgImages = false

    // Limita las peticiones al CDN de imágenes para evitar descargas cortadas a medias
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addInterceptor(::interceptProtectedImage)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            if (req.tag(OmitCookiesTag::class.java) != null) {
                chain.proceed(req.newBuilder().removeHeader("Cookie").build())
            } else {
                chain.proceed(req)
            }
        }
        .rateLimit(permits = 2) { url ->
            url.host == "img.manhwashot.lat" || isProtectedImageUrl(url)
        }

    private fun Response.toDocument(): Document = use {
        if (it.code == 401 || it.code == 403) {
            logCookiesForDebug("HTTP ${it.code} en ${it.request.url}")
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }
        if (!it.isSuccessful) throw IOException("HTTP ${it.code} al cargar ${it.request.url}")
        Jsoup.parse(it.body.string(), it.request.url.toString())
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var section = ""
        var genre = ""
        filters.forEach { filter ->
            when (filter) {
                is SectionFilter -> section = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                else -> {}
            }
        }
        return fetchSeries(page, query.trim().ifEmpty { null }, section, genre)
    }

    private suspend fun fetchSeries(
        page: Int,
        query: String? = null,
        section: String = "",
        genre: String = "",
    ): MangasPage {
        val base = if (section.isEmpty()) "/explorar" else "/explorar/$section"
        val path = if (page <= 1) "$base/" else "$base/page/$page/"
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            if (query != null) addQueryParameter("q", query)
            if (genre.isNotEmpty()) addQueryParameter("genero", genre)
        }.build()

        var document = client.get(url, Fetch.DOCUMENT).toDocument()
        if (document.selectFirst("div.series-grid") == null) {
            // Posible respuesta en caché sin series: se reintenta una vez con un parámetro extra
            val retryUrl = url.newBuilder().addQueryParameter("_", System.currentTimeMillis().toString()).build()
            document = client.get(retryUrl, Fetch.DOCUMENT).toDocument()
        }
        if (document.selectFirst("div.series-grid") == null) {
            // La web devolvió otra cosa (bloqueo, error, página distinta): mostrarlo en vez de "sin resultados"
            val main = document.selectFirst("main") ?: document.body()
            throw Exception("Respuesta inesperada (${document.html().length} bytes): ${main.text().take(200)}")
        }
        val mangas = document.select("div.series-grid > div.s-card").map { it.toSManga() }
        val hasNextPage = document.select("a.pager-btn").any { it.text().contains("Siguiente", true) }

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga() = SManga.create().apply {
        val link = selectFirst("a.s-card-title")!!
        url = link.absUrl("href").removePrefix(baseUrl)
        title = link.text()
        thumbnail_url = selectFirst(".s-card-img img")?.absUrl("src")
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        val path = "/manga/$slug/"
        val document = client.get(baseUrl + path, Fetch.DOCUMENT).toDocument()
        return parseDetails(document).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url, Fetch.DOCUMENT).toDocument()
        return SMangaUpdate(
            parseDetails(document).apply { url = manga.url },
            parseChapters(document),
        )
    }

    private fun parseDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.series-title")!!.text()
        thumbnail_url = document.selectFirst(".series-cover img")?.absUrl("src")
        description = document.selectFirst("div.series-desc:not([style])")?.text()
        genre = document.select(".series-genres a.genre-tag").joinToString { it.text() }
        status = when (document.selectFirst(".series-badges .badge-pill")?.text()?.lowercase()) {
            "en emisión" -> SManga.ONGOING
            "finalizado", "completado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val nextJsPayload = document.select("script")
            .map { it.data() }
            .filter { it.contains("__next_f.push") }
            .joinToString("") {
                it.substringAfter("push([1,\"", "").substringBeforeLast("\"])", "")
            }

        val block = nextJsPayload.substringAfter("\\\"dates\\\":{", "").substringBefore("}")

        val dates = DATE_REGEX.findAll(block).associate {
            it.groupValues[1].toFloat() to it.groupValues[2].toLong() * 1000
        }

        return document.select("div.chapters-grid > a.ch-row").map { el ->
            SChapter.create().apply {
                setUrlWithoutDomain(el.absUrl("href"))
                name = el.selectFirst(".ch-num")!!.text()
                chapter_number = CHAPTER_REGEX.find(url)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = dates[chapter_number] ?: 0L
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url
        val document = client.get(chapterUrl, Fetch.DOCUMENT, NO_STORE).toDocument()

        val reader = document.extractNextJs<ChapterReaderDto> {
            it is JsonObject && "postId" in it && "pgCount" in it && "locked" in it
        } ?: extractReaderPropsFallback(document)

        // Capítulos públicos sin login: conservan las URLs directas del CDN (/WP-manga/data/)
        val publicImages = reader?.images?.filter { it.isNotBlank() }.orEmpty().ifEmpty {
            document.select("img[src*=\"/WP-manga/data/\"]")
                .map { it.absUrl("src") }
                .ifEmpty {
                    IMAGE_REGEX.findAll(document.html()).map { it.value }.toList()
                }
        }.distinct()

        if (publicImages.isNotEmpty()) {
            return publicImages.mapIndexed { i, imgUrl -> Page(i, url = chapterUrl, imageUrl = imgUrl) }
        }

        logCookiesForDebug("getPageList($chapterUrl)")

        if (reader?.locked == true || document.selectFirst(".bloqueado") != null) {
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }

        val postId = reader?.postId ?: 0
        val chapterNum = reader?.num ?: parseChapterPrimitive(chapter.url)
        if (postId <= 0 || chapterNum == null) {
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }

        val session = getOrCreateSession(
            postId = postId,
            chapter = chapterNum,
            chapterUrl = chapterUrl,
            windowN = reader?.windowN ?: 0,
        )

        // Si el SSR ya incluyó URLs firmadas vigentes las aprovechamos; si no, pedimos /api/pg/urls/
        withContext(Dispatchers.IO) {
            synchronized(session.lock) {
                val ssrUrls = reader?.pgUrls?.filter { it.isNotBlank() }.orEmpty()
                if (ssrUrls.isNotEmpty() && ssrUrls.firstOrNull()?.let(::isSignedUrlFresh) == true) {
                    session.urls = ssrUrls
                    session.keyHex = reader?.pgKey?.takeIf { it.length >= 32 }.orEmpty()
                    session.updatedAtMs = System.currentTimeMillis()
                } else {
                    refreshSignedUrlsLocked(session)
                }
            }
        }

        val totalPages = session.urls.size.takeIf { it > 0 } ?: (reader?.pgCount ?: 0)
        if (totalPages <= 0) {
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }

        // No fijamos imageUrl por adelantado: se resuelve al momento de descargar cada página en getImageUrl
        return List(totalPages) { idx ->
            val virtualUrl = baseUrl.toHttpUrl().newBuilder()
                .encodedPath("/manga-pg/$postId/${chapterNum.content}/$idx")
                .addQueryParameter("ref", chapterUrl)
                .addQueryParameter("win", session.windowN.toString())
                .build()
                .toString()
            Page(idx, url = virtualUrl)
        }
    }

    override suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        page.imageUrl?.let { return@withContext it }

        val pageHttpUrl = page.url.toHttpUrlOrNull()
            ?: throw IOException("URL de página inválida: ${page.url}")

        if (pageHttpUrl.pathSegments.firstOrNull() != "manga-pg") {
            return@withContext page.url
        }

        val postId = pageHttpUrl.pathSegments.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("postId inválido en ${page.url}")
        val chapterRaw = pageHttpUrl.pathSegments.getOrNull(2)
            ?: throw IOException("Capítulo inválido en ${page.url}")
        val idx = pageHttpUrl.pathSegments.getOrNull(3)?.toIntOrNull() ?: page.index
        val chapterUrl = pageHttpUrl.queryParameter("ref") ?: "$baseUrl/"
        val windowN = pageHttpUrl.queryParameter("win")?.toIntOrNull() ?: 0

        val session = getOrCreateSession(postId, toNumericPrimitive(chapterRaw), chapterUrl, windowN)
        resolveSignedImageUrl(session, idx, forceRefresh = false)
    }

    override fun getImageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!
        val httpUrl = imageUrl.toHttpUrlOrNull()
        if (httpUrl != null && isProtectedImageUrl(httpUrl)) {
            val session = findSessionForImageUrl(httpUrl)
            val referer = session?.chapterUrl ?: "$baseUrl/"
            return Request.Builder()
                .url(httpUrl)
                .headers(headers)
                .cacheControl(NO_STORE)
                .fetch(Fetch.XHR.from(referer))
                .build()
        }

        val referer = page.url.takeIf { it.startsWith("http") && "/manga-pg/" !in it } ?: "$baseUrl/"
        return Request.Builder()
            .url(imageUrl)
            .headers(headers)
            .fetch(Fetch.IMAGE.from(referer))
            .build()
    }

    private fun interceptProtectedImage(chain: Interceptor.Chain): Response {
        val initialRequest = chain.request()
        val initialUrl = initialRequest.url
        if (!isProtectedImageUrl(initialUrl)) {
            return chain.proceed(initialRequest)
        }

        val postId = initialUrl.pathSegments.getOrNull(2)?.toIntOrNull()
        val chapterRaw = initialUrl.pathSegments.getOrNull(3)
        val idx = initialUrl.pathSegments.getOrNull(4)?.toIntOrNull()
        val session = if (postId != null && chapterRaw != null) {
            getOrCreateSession(
                postId = postId,
                chapter = toNumericPrimitive(chapterRaw),
                chapterUrl = initialRequest.header("Referer") ?: "$baseUrl/",
                windowN = 0,
            )
        } else {
            null
        }

        if (session != null && idx != null && session.windowN > 0 && idx >= session.windowLimit) {
            advanceSeenWindow(session, idx)
        }

        var currentUrl = initialUrl
        if (!isSignedUrlFresh(currentUrl.toString()) && session != null && idx != null) {
            currentUrl = resolveSignedImageUrl(
                session = session,
                idx = idx,
                forceRefresh = true,
                staleUrl = currentUrl.toString(),
            ).toHttpUrl()
        }

        fun buildAttemptRequest(url: HttpUrl, omitCookies: Boolean): Request = initialRequest.newBuilder()
            .url(url)
            .cacheControl(NO_STORE)
            .tag(OmitCookiesTag::class.java, if (omitCookies) OmitCookiesTag else null)
            .build()

        var activeRequest = buildAttemptRequest(currentUrl, omitCookies = !sendCookiesOnPgImages)
        var response = chain.proceed(activeRequest)

        if (response.code == 429) {
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            response.close()
            try {
                Thread.sleep(((retryAfterSec ?: 2L).coerceIn(1L, 5L)) * 1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Descarga interrumpida esperando límite de peticiones")
            }
            response = chain.proceed(activeRequest)
        }

        // Si el servidor limita por ventana de lectura (x-pg-window), reportamos progreso en /api/pg/seen/
        if (response.code == 403 && response.header("x-pg-window") != null && session != null && idx != null) {
            response.close()
            session.windowN = maxOf(session.windowN, 1)
            session.windowLimit = minOf(session.windowLimit, idx)
            advanceSeenWindow(session, idx)
            response = chain.proceed(activeRequest)
        }

        // Si la firma e=... expiró en cola, renovamos con /api/pg/urls/ y reintentamos
        if (response.code == 403 && response.header("x-pg-window") == null && session != null && idx != null) {
            response.close()
            val renewedUrl = resolveSignedImageUrl(
                session = session,
                idx = idx,
                forceRefresh = true,
                staleUrl = activeRequest.url.toString(),
            ).toHttpUrl()
            activeRequest = buildAttemptRequest(renewedUrl, omitCookies = !sendCookiesOnPgImages)
            response = chain.proceed(activeRequest)
        }

        // Respaldo por si el servidor exige cookies directamente en el GET de /api/pg/...
        if (response.code == 403 && activeRequest.tag(OmitCookiesTag::class.java) != null) {
            response.close()
            activeRequest = buildAttemptRequest(activeRequest.url, omitCookies = false)
            response = chain.proceed(activeRequest)
            if (response.isSuccessful) {
                sendCookiesOnPgImages = true
            }
        }

        if (response.code == 401 || response.code == 403) {
            response.close()
            logCookiesForDebug("GET imagen protegida -> HTTP ${response.code}")
            throw IOException(LOGIN_REQUIRED_MESSAGE)
        }

        if (!response.isSuccessful) {
            val preview = runCatching { response.peekBody(64).string() }.getOrDefault("")
            response.close()
            if (preview.contains("Forbidden", ignoreCase = true) || preview.contains("login", ignoreCase = true)) {
                logCookiesForDebug("GET imagen protegida -> $preview")
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }
            throw IOException("HTTP ${response.code} al descargar imagen")
        }

        if (response.header("Content-Type")?.startsWith("text/plain", ignoreCase = true) == true) {
            val preview = runCatching { response.peekBody(32).string() }.getOrDefault("")
            if (preview.contains("Forbidden", ignoreCase = true)) {
                response.close()
                logCookiesForDebug("GET imagen protegida -> texto Forbidden")
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }
        }

        if (response.header("x-pg-enc") == "1") {
            val keyHex = extractKeyFromFragment(activeRequest.url)
                ?: session?.keyHex
                ?: ""
            if (keyHex.length < 32 || keyHex.length % 2 != 0) {
                response.close()
                throw IOException("Clave de descifrado ausente para la página")
            }
            val keyBytes = keyHex.decodeHex()
            return response.xor(keyBytes).newBuilder()
                .removeHeader("x-pg-enc")
                .build()
        }

        return response
    }

    private fun resolveSignedImageUrl(
        session: ChapterPgSession,
        idx: Int,
        forceRefresh: Boolean,
        staleUrl: String? = null,
    ): String = synchronized(session.lock) {
        val currentRaw = session.urls.getOrNull(idx)
        val staleNormalized = staleUrl?.let(::stripFragment)
        val currentNormalized = currentRaw?.let { stripFragment(resolveAgainstBase(it).toString()) }

        val alreadyReplacedByPeer = forceRefresh &&
            staleNormalized != null &&
            currentNormalized != null &&
            currentNormalized != staleNormalized &&
            isSignedUrlFresh(currentRaw)

        val needsNetworkRefresh = when {
            alreadyReplacedByPeer -> false
            currentRaw == null || !isSignedUrlFresh(currentRaw) -> true
            forceRefresh -> System.currentTimeMillis() - session.lastRenewMs >= FORCE_RENEW_COOLDOWN_MS
            else -> false
        }

        if (needsNetworkRefresh) {
            refreshSignedUrlsLocked(session)
        }

        val signedRaw = session.urls.getOrNull(idx)
            ?: throw IOException(LOGIN_REQUIRED_MESSAGE)

        val resolved = resolveAgainstBase(signedRaw)
        if (session.keyHex.isNotEmpty()) {
            resolved.newBuilder().fragment("key=${session.keyHex}").build().toString()
        } else {
            resolved.toString()
        }
    }

    private fun refreshSignedUrlsLocked(session: ChapterPgSession) {
        val request = Request.Builder()
            .url("$baseUrl/api/pg/urls/")
            .headers(headers)
            .cacheControl(NO_STORE)
            .post(PgUrlsRequest(session.postId, session.chapter).toJsonRequestBody())
            .fetch(Fetch.XHR.from(session.chapterUrl))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                logCookiesForDebug("POST /api/pg/urls/ -> HTTP ${response.code}")
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }

            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                if (bodyStr.contains("Forbidden", ignoreCase = true) || bodyStr.contains("login", ignoreCase = true)) {
                    logCookiesForDebug("POST /api/pg/urls/ -> $bodyStr")
                    throw IOException(LOGIN_REQUIRED_MESSAGE)
                }
                throw IOException("HTTP ${response.code} al consultar /api/pg/urls/")
            }

            if (bodyStr.trim().equals("Forbidden", ignoreCase = true)) {
                logCookiesForDebug("POST /api/pg/urls/ -> Forbidden")
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }

            val dto = bodyStr.parseAs<PgUrlsResponse>()
            if (dto.error == "login" || dto.urls.isEmpty()) {
                logCookiesForDebug("POST /api/pg/urls/ -> error=${dto.error}, urls=${dto.urls.size}")
                throw IOException(LOGIN_REQUIRED_MESSAGE)
            }

            session.urls = dto.urls
            session.keyHex = dto.key.takeIf { it.length >= 32 }.orEmpty()
            session.lastRenewMs = System.currentTimeMillis()
            session.updatedAtMs = session.lastRenewMs
        }
    }

    private fun advanceSeenWindow(session: ChapterPgSession, targetIdx: Int) {
        synchronized(session.lock) {
            var attempts = 0
            while (targetIdx >= session.windowLimit && attempts < 6) {
                attempts++
                val seenIdx = if (attempts == 1 && session.windowLimit > 0) {
                    (session.windowLimit - 1).coerceAtLeast(0)
                } else {
                    targetIdx.coerceAtLeast(0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/pg/seen/")
                    .headers(headers)
                    .cacheControl(NO_STORE)
                    .post(PgSeenRequest(session.postId, session.chapter, seenIdx).toJsonRequestBody())
                    .fetch(Fetch.XHR.from(session.chapterUrl))
                    .build()

                val newLimit = runCatching {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.parseAs<PgSeenResponse>().limit
                    }
                }.getOrNull()

                if (newLimit != null && newLimit > session.windowLimit) {
                    session.windowLimit = newLimit
                } else if (seenIdx < targetIdx) {
                    session.windowLimit = maxOf(session.windowLimit, seenIdx + 1)
                } else {
                    break
                }
            }
        }
    }

    private fun getOrCreateSession(
        postId: Int,
        chapter: JsonPrimitive,
        chapterUrl: String,
        windowN: Int,
    ): ChapterPgSession {
        val key = "$postId:${chapter.content}"
        val session = chapterSessions.compute(key) { _, existing ->
            if (existing != null) {
                if (chapterUrl != "$baseUrl/") existing.chapterUrl = chapterUrl
                if (windowN > 0 && existing.windowN == 0) {
                    existing.windowN = windowN
                    existing.windowLimit = windowN
                }
                existing.updatedAtMs = System.currentTimeMillis()
                existing
            } else {
                ChapterPgSession(postId, chapter, chapterUrl, windowN)
            }
        }!!

        if (chapterSessions.size > MAX_CACHED_SESSIONS) {
            chapterSessions.entries
                .minByOrNull { it.value.updatedAtMs }
                ?.key
                ?.let(chapterSessions::remove)
        }
        return session
    }

    private fun findSessionForImageUrl(url: HttpUrl): ChapterPgSession? {
        val postId = url.pathSegments.getOrNull(2)?.toIntOrNull() ?: return null
        val chapterRaw = url.pathSegments.getOrNull(3) ?: return null
        return chapterSessions["$postId:$chapterRaw"]
    }

    private fun isProtectedImageUrl(url: HttpUrl): Boolean {
        val segments = url.pathSegments
        return segments.size >= 5 &&
            segments[0] == "api" &&
            segments[1] == "pg" &&
            segments[2].toIntOrNull() != null
    }

    private fun isSignedUrlFresh(rawUrl: String): Boolean {
        val httpUrl = runCatching { resolveAgainstBase(rawUrl) }.getOrNull() ?: return false
        val expSeconds = httpUrl.queryParameter("e")?.toLongOrNull() ?: return true
        if (expSeconds <= 0L) return true
        return expSeconds * 1000L >= System.currentTimeMillis() + EXPIRY_MARGIN_MS
    }

    private fun resolveAgainstBase(rawUrl: String): HttpUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
        rawUrl.toHttpUrl()
    } else {
        baseUrl.toHttpUrl().resolve(rawUrl)
            ?: throw IOException("URL relativa inválida: $rawUrl")
    }

    private fun stripFragment(url: String): String = url.substringBefore('#')

    private fun extractKeyFromFragment(url: HttpUrl): String? {
        val fragment = url.fragment ?: return null
        return fragment.split('&')
            .firstOrNull { it.startsWith("key=") }
            ?.substringAfter("key=")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractReaderPropsFallback(document: Document): ChapterReaderDto? {
        val raw = document.select("script:not([src])").joinToString("\n") { it.data() }
        val postId = POST_ID_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val pgCount = PG_COUNT_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val windowN = WINDOW_N_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val locked = LOCKED_REGEX.find(raw)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
        val num = NUM_REGEX.find(raw)?.groupValues?.get(1)?.let(::toNumericPrimitive)
        return ChapterReaderDto(
            num = num,
            locked = locked,
            postId = postId,
            windowN = windowN,
            pgCount = pgCount,
        )
    }

    private fun parseChapterPrimitive(url: String): JsonPrimitive? = CHAPTER_REGEX.find(url)?.groupValues?.get(1)?.let(::toNumericPrimitive)

    private fun toNumericPrimitive(raw: String): JsonPrimitive = raw.toLongOrNull()?.let(::JsonPrimitive)
        ?: raw.toDoubleOrNull()?.let(::JsonPrimitive)
        ?: JsonPrimitive(raw)

    private fun logCookiesForDebug(context: String) {
        runCatching {
            val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            val summary = if (cookies.isEmpty()) {
                "ninguna"
            } else {
                cookies.joinToString { "${it.name}(domain=${it.domain}, len=${it.value.length})" }
            }
            Log.d(LOG_TAG, "[$context] Cookies en AndroidCookieJar: $summary")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SectionFilter(),
        GenreFilter(),
    )

    private class ChapterPgSession(
        val postId: Int,
        val chapter: JsonPrimitive,
        @Volatile var chapterUrl: String,
        @Volatile var windowN: Int,
    ) {
        val lock = Any()

        @Volatile
        var urls: List<String> = emptyList()

        @Volatile
        var keyHex: String = ""

        @Volatile
        var lastRenewMs: Long = 0L

        @Volatile
        var windowLimit: Int = if (windowN > 0) windowN else Int.MAX_VALUE

        @Volatile
        var updatedAtMs: Long = System.currentTimeMillis()
    }

    private object OmitCookiesTag

    companion object {
        private const val LOG_TAG = "ManhwaShot"
        private const val LOGIN_REQUIRED_MESSAGE = "Inicia sesión en el WebView para acceder a este contenido"
        private const val EXPIRY_MARGIN_MS = 30_000L
        private const val FORCE_RENEW_COOLDOWN_MS = 3_000L
        private const val MAX_CACHED_SESSIONS = 40

        private val NO_STORE: CacheControl = CacheControl.Builder().noStore().build()

        private val CHAPTER_REGEX = Regex("""capitulo-(\d+(?:\.\d+)?)""")
        private val DATE_REGEX = Regex("""\\"(\d+(?:\.\d+)?)\\":(\d{9,10})""")
        private val IMAGE_REGEX = Regex("""https://img\.manhwashot\.lat/img/WP-manga/data/[^"\\\s]+\.(?:webp|jpg|jpeg|png)""")
        private val POST_ID_REGEX = Regex("""\\"postId\\":(\d+)""")
        private val PG_COUNT_REGEX = Regex("""\\"pgCount\\":(\d+)""")
        private val WINDOW_N_REGEX = Regex("""\\"windowN\\":(\d+)""")
        private val LOCKED_REGEX = Regex("""\\"locked\\":(true|false)""")
        private val NUM_REGEX = Regex("""\\"num\\":(\d+(?:\.\d+)?),\\"prev\\":""")
    }
}
