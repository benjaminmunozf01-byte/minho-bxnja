package eu.kanade.tachiyomi.extension.es.kumanga

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class KuManga : KeiSource() {

    private val json = Json { ignoreUnknownKeys = true }

    // GET con Referer propio usando Fetch.DOCUMENT.from(referer) (el sitio redirige al inicio sin Referer de la ficha del manga)
    private suspend fun getHtml(url: String, referer: String = "$baseUrl/"): String = client.get(url, Fetch.DOCUMENT.from(referer)).use { it.body.string() }

    private fun parse(html: String, url: String): Document = Jsoup.parse(html, url)

    // ---------- Listados ----------
    override suspend fun getPopularManga(page: Int): MangasPage = fetchList("$baseUrl/mangalist?page=$page", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = parse(getHtml("$baseUrl/"), "$baseUrl/")
        val seen = HashSet<String>()
        val mangas = doc.select("div.update_item").mapNotNull { el ->
            val href = el.selectFirst(".update_left a[href]")?.attr("href") ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            SManga.create().apply {
                url = href.toAbsolute().removePrefix(baseUrl)
                title = el.selectFirst("h4 a")?.text().orEmpty()
                val style = el.selectFirst(".update_left div[style]")?.attr("style").orEmpty()
                thumbnail_url = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)?.toAbsolute()
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/mangalist".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query.trim())
            .addQueryParameter("page", page.toString())
        filters.forEach { f ->
            when (f) {
                is TypeFilter -> f.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("types", it) }
                is StatusFilter -> f.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("status", it) }
                is YearFilter -> f.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("years", it) }
                is GenreFilter -> f.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("categories", it) }
                else -> {}
            }
        }
        return fetchList(url.build().toString(), page)
    }

    private suspend fun fetchList(url: String, page: Int): MangasPage {
        val doc = parse(getHtml(url), url)
        val mangas = doc.select("ul.listUl.results li.km-li-crd").mapNotNull { el ->
            val link = Regex("""window\.open\('([^']+)'\)""")
                .find(el.attr("onclick") + el.select("[onclick]").joinToString { it.attr("onclick") })
                ?.groupValues?.get(1)
                ?: el.selectFirst("a[href*=/manga/]")?.absUrl("href")
                ?: return@mapNotNull null
            SManga.create().apply {
                this.url = link.toAbsolute().removePrefix(baseUrl)
                title = el.selectFirst("p.km-title-p-card")?.text().orEmpty()
                thumbnail_url = el.selectFirst("img.km-img-crd")?.let {
                    it.absUrl("src").ifEmpty { it.absUrl("data-src") }
                }
            }
        }
        val hasNext = doc.select(".section-nav-pagination a[href]").any {
            !it.hasAttr("disabled") &&
                Regex("""page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    .let { p -> p != null && p > page }
        }
        return MangasPage(mangas, hasNext)
    }

    // ---------- Detalles y capítulos ----------
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val seg = url.pathSegments
        if (seg.getOrNull(0) != "manga" || seg.getOrNull(1)?.toIntOrNull() == null) return null
        val path = "/" + seg.filter { it.isNotEmpty() }.take(3).joinToString("/")
        val doc = parse(getHtml(baseUrl + path), baseUrl + path)
        return parseDetails(doc).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val html = getHtml(baseUrl + manga.url)
        val doc = parse(html, baseUrl + manga.url)
        return SMangaUpdate(
            parseDetails(doc).apply { url = manga.url },
            parseChapters(html, doc, manga.url),
        )
    }

    private fun parseDetails(doc: Document) = SManga.create().apply {
        title = doc.selectFirst("h1.media-name__main")?.let { h ->
            h.clone().also { it.select("small").remove() }.text()
        }.orEmpty()
        thumbnail_url = doc.selectFirst(".media-sidebar__cover img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        description = buildString {
            append(doc.selectFirst("p.Mdesc")?.text().orEmpty())
            val alt = doc.selectFirst(".media-name__alt")?.text()
            if (!alt.isNullOrBlank()) append("\n\nTítulo alternativo: $alt")
        }.trim()
        genre = doc.select(".media-cats a.btn-dark").joinToString { it.text() }
        val info = doc.select(".media-info-list__item").associate {
            it.selectFirst(".media-info-list__title")?.text()?.trim().orEmpty() to
                it.selectFirst(".media-info-list__value")?.text()?.trim().orEmpty()
        }
        val st = info.entries.firstOrNull { it.key.contains("Estado", true) }?.value.orEmpty()
        status = when {
            st.contains("emisi", true) || st.contains("activo", true) -> SManga.ONGOING
            st.contains("finaliz", true) -> SManga.COMPLETED
            st.contains("inconcluso", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun parseChapters(html: String, doc: Document, mangaPath: String): List<SChapter> {
        val mangaId = Regex("""/manga/(\d+)""").find(mangaPath)?.groupValues?.get(1) ?: return emptyList()
        val chapters = LinkedHashMap<String, String?>()

        doc.select(".media-chapter").forEach { el ->
            val num = el.attr("data-sort").ifEmpty {
                Regex("""capitulo/([^/"?]+)""").find(
                    el.selectFirst("a.media-chapter__link")?.attr("href").orEmpty(),
                )?.groupValues?.get(1).orEmpty()
            }
            if (num.isNotEmpty()) chapters.putIfAbsent(num, null)
        }

        Regex("""OTHER_CHAPTERS\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)?.let {
            runCatching {
                json.parseToJsonElement(it.groupValues[1]).jsonArray.forEach { e ->
                    val o = e.jsonObject
                    val num = o["NumCap"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    chapters[num] = o["title"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { t -> t != "null" && t.isNotBlank() }
                }
            }
        }

        return chapters.map { (num, title) ->
            SChapter.create().apply {
                url = "/manga/$mangaId/capitulo/$num"
                name = "Capítulo $num" + (title?.let { " - $it" } ?: "")
                chapter_number = num.toFloatOrNull() ?: -1f
            }
        }.sortedByDescending { it.chapter_number }
    }

    // ---------- Páginas del capítulo ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = Regex("""/manga/(\d+)""").find(chapter.url)?.groupValues?.get(1)
        val mangaPage = if (mangaId != null) "$baseUrl/manga/$mangaId" else "$baseUrl/"
        val chapterUrl = baseUrl + chapter.url

        // Paso 1: página intermedia del capítulo ("INICIAR LECTURA / VER CAPÍTULOS")
        var html = getHtml(chapterUrl, mangaPage)
        var doc = parse(html, chapterUrl)
        var pages = extractPagesFromReader(doc)

        // Paso 2: si no es el lector, seguir el botón "Iniciar lectura" (/manga/leer/ID o /manga/c/ID)
        if (pages == null) {
            val landingReferer = doc.selectFirst("meta[property=og:url]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: chapterUrl
            val readerUrl = doc.selectFirst("a#leer[href], a.readButton[href]")?.absUrl("href")
                ?: doc.select("a[href]")
                    .firstOrNull { it.text().contains("iniciar", true) && it.attr("href").contains(READER_REGEX) }
                    ?.absUrl("href")
                ?: doc.selectFirst("a[href*=/manga/leer/], a[href*=/manga/c/]")?.absUrl("href")
                ?: READER_REGEX.find(html)?.value?.toAbsolute()
                ?: throw Exception("No se encontró el botón 'Iniciar lectura' del capítulo. Ábrelo en WebView.")
            html = getHtml(readerUrl, landingReferer)
            doc = parse(html, readerUrl)
            pages = extractPagesFromReader(doc)
            if (pages == null && readerUrl.contains("/manga/c/")) {
                val leerUrl = readerUrl.replace("/manga/c/", "/manga/leer/")
                html = getHtml(leerUrl, readerUrl)
                doc = parse(html, leerUrl)
                pages = extractPagesFromReader(doc)
            }
        }

        val rawPages = pages
            ?: throw Exception("No se pudieron leer las imágenes del capítulo. Ábrelo en WebView.")

        return rawPages.mapIndexed { i, url ->
            Page(i, imageUrl = decodeSrc(url))
        }
    }

    // Extrae el arreglo de páginas evaluando únicamente asignaciones JS de nivel global (fuera de '{...}')
    // previas a kx.reader.js, ignorando honeypots en scopes de bloque como '{const jaja2="...";const rpics=jaja2;}'.
    private fun extractPagesFromReader(doc: Document): List<String>? {
        val globals = mutableMapOf<String, String>()
        for (script in doc.select("script")) {
            if (script.parents().any { it.tagName().equals("template", true) || it.tagName().equals("noscript", true) }) {
                continue
            }
            val src = script.attr("src")
            if (src.contains("kx.reader", ignoreCase = true)) {
                break
            }
            if (src.isNotEmpty()) {
                continue
            }
            val type = script.attr("type").trim().lowercase()
            if (type.isNotEmpty() && type != "text/javascript" && type != "application/javascript") {
                continue
            }
            scanTopLevelBindings(script.data(), globals)
        }
        val encoded = globals["rpics"] ?: globals["jaja"] ?: return null
        return decodeEncodedList(encoded)
    }

    private fun decodeEncodedList(encoded: String): List<String>? = runCatching {
        val raw = Base64.decode(encoded, Base64.DEFAULT)
        val decoded = ByteArray(raw.size) { i ->
            (raw[i].toInt() xor KEY[i % KEY.length].code).toByte()
        }.decodeToString()
        json.parseToJsonElement(decoded).jsonArray
            .map { it.jsonPrimitive.content }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private sealed interface JsToken {
        data class Ident(val name: String) : JsToken
        data class Str(val value: String) : JsToken
        data class Punct(val ch: Char) : JsToken
        data object Other : JsToken
    }

    private fun scanTopLevelBindings(code: String, globals: MutableMap<String, String>) {
        val tokens = mutableListOf<JsToken>()
        val n = code.length
        var i = 0
        var braceDepth = 0
        var parenDepth = 0
        var canStartRegex = true

        fun emit(tok: JsToken) {
            if (braceDepth == 0 && parenDepth == 0) {
                tokens.add(tok)
            }
        }

        while (i < n) {
            val c = code[i]
            if (c == '\n') {
                if (!canStartRegex) {
                    emit(JsToken.Punct('\n'))
                }
                i++
                continue
            }
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c == '/' && i + 1 < n && code[i + 1] == '/') {
                i += 2
                while (i < n && code[i] != '\n') {
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < n && code[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(code[i] == '*' && code[i + 1] == '/')) {
                    i++
                }
                i = (i + 2).coerceAtMost(n)
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                val quote = c
                i++
                val sb = StringBuilder()
                while (i < n && code[i] != quote) {
                    val ch = code[i]
                    if (ch == '\\' && i + 1 < n) {
                        val nxt = code[i + 1]
                        if (nxt == 'x' && i + 3 < n && code.substring(i + 2, i + 4).isHex()) {
                            sb.append(code.substring(i + 2, i + 4).toInt(16).toChar())
                            i += 4
                            continue
                        }
                        if (nxt == 'u' && i + 5 < n && code.substring(i + 2, i + 6).isHex()) {
                            sb.append(code.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 6
                            continue
                        }
                        val escaped = when (nxt) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000c'
                            else -> nxt
                        }
                        sb.append(escaped)
                        i += 2
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                if (i < n) {
                    i++
                }
                emit(JsToken.Str(sb.toString()))
                canStartRegex = false
                continue
            }
            if (c == '/' && canStartRegex) {
                i++
                var inClass = false
                while (i < n && code[i] != '\n') {
                    val rc = code[i]
                    if (rc == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    when {
                        rc == '[' -> inClass = true
                        rc == ']' -> inClass = false
                        rc == '/' && !inClass -> {
                            i++
                            while (i < n && code[i].isLetter()) {
                                i++
                            }
                            break
                        }
                    }
                    i++
                }
                emit(JsToken.Other)
                canStartRegex = false
                continue
            }
            if (c == '{') {
                emit(JsToken.Punct(';'))
                braceDepth++
                canStartRegex = true
                i++
                continue
            }
            if (c == '}') {
                braceDepth = (braceDepth - 1).coerceAtLeast(0)
                emit(JsToken.Punct(';'))
                canStartRegex = true
                i++
                continue
            }
            if (c == '(' || c == '[') {
                emit(JsToken.Punct('('))
                parenDepth++
                canStartRegex = true
                i++
                continue
            }
            if (c == ')' || c == ']') {
                parenDepth = (parenDepth - 1).coerceAtLeast(0)
                emit(JsToken.Punct(')'))
                canStartRegex = false
                i++
                continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                val start = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) {
                    i++
                }
                val ident = code.substring(start, i)
                emit(JsToken.Ident(ident))
                canStartRegex = ident in REGEX_KEYWORDS
                continue
            }
            if (c.isDigit()) {
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) {
                    i++
                }
                emit(JsToken.Other)
                canStartRegex = false
                continue
            }
            if (c == '=') {
                val prevC = if (i > 0) code[i - 1] else '\u0000'
                val nextC = if (i + 1 < n) code[i + 1] else '\u0000'
                val isAssign = prevC !in "!<>=+-*/%&|^~?" && nextC != '=' && nextC != '>'
                emit(if (isAssign) JsToken.Punct('=') else JsToken.Other)
                canStartRegex = true
                i++
                continue
            }
            if (c == ';' || c == ',' || c == '+') {
                val prevC = if (i > 0) code[i - 1] else '\u0000'
                val nextC = if (i + 1 < n) code[i + 1] else '\u0000'
                val isPlusOp = c == '+' && (prevC == '+' || nextC == '+' || nextC == '=')
                emit(if (isPlusOp) JsToken.Other else JsToken.Punct(c))
                canStartRegex = true
                i++
                continue
            }
            emit(JsToken.Other)
            canStartRegex = true
            i++
        }

        var idx = 0
        var skipUntilSemi = false
        while (idx < tokens.size) {
            val tok = tokens[idx]
            if (tok == JsToken.Punct(';')) {
                skipUntilSemi = false
                idx++
                continue
            }
            if (skipUntilSemi) {
                idx++
                continue
            }
            if (tok is JsToken.Ident && tok.name in COND_KEYWORDS) {
                skipUntilSemi = true
                idx++
                continue
            }
            if (tok is JsToken.Ident && idx + 1 < tokens.size && tokens[idx + 1] == JsToken.Punct('=')) {
                val prev = tokens.getOrNull(idx - 1)
                val validStart = prev == null ||
                    prev == JsToken.Punct(';') ||
                    prev == JsToken.Punct('\n') ||
                    prev == JsToken.Punct(',') ||
                    (prev is JsToken.Ident && prev.name in DECL_KEYWORDS)
                if (validStart) {
                    var j = idx + 2
                    val rhsBuf = StringBuilder()
                    var validRhs = false
                    while (j < tokens.size) {
                        when (val rhs = tokens[j]) {
                            is JsToken.Str -> rhsBuf.append(rhs.value)
                            is JsToken.Ident -> {
                                val resolved = globals[rhs.name]
                                if (resolved == null) {
                                    validRhs = false
                                    break
                                }
                                rhsBuf.append(resolved)
                            }
                            else -> {
                                validRhs = false
                                break
                            }
                        }
                        validRhs = true
                        if (j + 1 < tokens.size && tokens[j + 1] == JsToken.Punct('+')) {
                            j += 2
                            validRhs = false
                            continue
                        }
                        j++
                        break
                    }
                    val after = tokens.getOrNull(j)
                    val validEnd = after == null ||
                        after == JsToken.Punct(';') ||
                        after == JsToken.Punct('\n') ||
                        after == JsToken.Punct(',')
                    if (validRhs && validEnd) {
                        globals[tok.name] = rhsBuf.toString()
                    }
                    idx = maxOf(idx + 2, j)
                    continue
                }
            }
            idx++
        }
    }

    private fun String.isHex(): Boolean = isNotEmpty() && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun decodeSrc(s: String): String {
        val hex = s.substringAfter("src=", "").substringBefore("&")
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.isHex()) {
            return s.toAbsolute()
        }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }.decodeToString()
    }

    // ---------- Filtros ----------
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        YearFilter(),
        GenreFilter(),
    )

    private fun String.toAbsolute(): String = when {
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    companion object {
        private const val KEY = "Jr54VwepF4La"
        private val READER_REGEX = Regex("""/?manga/(?:leer|c)/\d+""")
        private val DECL_KEYWORDS = setOf("const", "let", "var")
        private val COND_KEYWORDS = setOf("if", "while", "for", "with")
        private val REGEX_KEYWORDS = setOf(
            "return",
            "typeof",
            "void",
            "delete",
            "throw",
            "case",
            "in",
            "of",
            "instanceof",
            "new",
            "yield",
            "await",
            "else",
            "do",
        )
    }
}
