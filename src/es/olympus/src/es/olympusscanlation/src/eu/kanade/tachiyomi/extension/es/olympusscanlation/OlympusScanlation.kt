package eu.kanade.tachiyomi.extension.es.olympuspropio

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * Olympus Scanlation (propio)
 *
 * Cambios respecto a la versión original (web nueva de Olympus, oct-2026):
 *  - El subdominio `panel.` ya no existe (HTTP 520). Todo va por el dominio principal.
 *  - Las páginas de un capítulo ahora se piden por NOMBRE del capítulo:
 *    /api/capitulo/{slug}/{nombre}?type=comic  (antes: /api/capitulo/comic-{slug}/{id}).
 *  - Los slugs de muchas series rotan cada pocos días (ej. ...-20261014-... -> ...-20261017-...)
 *    y el slug viejo da 404. Se refresca la lista con cache-buster y se reintenta ante un 404.
 *  - Las URLs de imágenes relativas se resuelven contra el dominio base.
 */
@Source
abstract class OlympusScanlation :
    KeiSource(),
    ConfigurableSource {

    private fun fetchedDomainUrl() {
        if (!preferences.fetchDomainPref()) return
        try {
            val initClient = network.client
            val document = initClient.newCall(GET("https://olympus.pages.dev", headers)).execute().asJsoup()
            val domain = document.selectFirst("meta[property=og:url]")?.attr("content")
                ?: return
            val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
            val newDomain = "https://$host"
            preferences.edit().putString(BASE_URL_PREF, newDomain).apply()
        } catch (_: Exception) {
            return
        }
    }

    private val preferences = getPreferences()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        fetchedDomainUrl()
        return rateLimit(3, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    // ---------------------------------------------------------------- slugs

    private var seriesList: List<MangaDto> = emptyList()

    private var lastFetchTime: Long = 0L

    private val seriesListMutex = Mutex()

    private suspend fun fetchSeriesList(force: Boolean = false) = seriesListMutex.withLock {
        val now = System.currentTimeMillis()

        if (!force && seriesList.isNotEmpty() && (now - lastFetchTime) < CACHE_DURATION_MS) {
            return@withLock
        }

        // El parámetro cb evita que el CDN devuelva una lista con slugs viejos.
        val series = client.get("$baseUrl/api/series/list?cb=$now").parseAs<PayloadMangaDto>()

        val comics = series.data.filter { it.type == "comic" }

        seriesList = comics
        lastFetchTime = now

        preferences.slugMap = preferences.slugMap + comics.associate { it.id to it.slug }
    }

    private fun slugFor(mangaId: String): String? = mangaId.toIntOrNull()?.let { preferences.slugMap[it] }

    private fun Throwable.is404() = message?.contains("404") == true

    /**
     * Ejecuta [block] con el slug actual de la serie. Si el servidor responde 404
     * (slug rotado), refresca la lista de series y reintenta una vez.
     */
    private suspend fun <T> withFreshSlug(mangaId: String, block: suspend (String) -> T): T {
        fetchSeriesList()
        var slug = slugFor(mangaId)
        if (slug == null) {
            fetchSeriesList(force = true)
            slug = slugFor(mangaId)
        }
        if (slug == null) throw Exception("Serie no encontrada. Vuelve a abrirla desde Explorar.")

        return try {
            block(slug)
        } catch (e: Exception) {
            if (!e.is404()) throw e
            fetchSeriesList(force = true)
            val fresh = slugFor(mangaId)
            if (fresh == null || fresh == slug) throw e
            block(fresh)
        }
    }

    // ------------------------------------------------------------- listados

    override suspend fun getPopularManga(page: Int): MangasPage {
        fetchSeriesList()
        val result = client.get("$baseUrl/api/rankings?page=$page&period=total_ranking").parseAs<RankingDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data
            .filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, hasNextPage = result.hasNextPage())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        fetchSeriesList()
        val result = client.get("$baseUrl/api/new-chapters?page=$page").parseAs<NewChaptersDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data.filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, result.hasNextPage())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        fetchSeriesList()
        val filteredList = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        val paginatedList = filteredList.drop((page - 1) * 20).take(20)
        val hasNextPage = page * 20 < filteredList.size
        return MangasPage(paginatedList.map { it.toSManga() }, hasNextPage)
    }

    // -------------------------------------------------------------- detalle

    override fun getMangaUrl(manga: SManga): String {
        val slug = slugFor(manga.url) ?: return baseUrl
        return "$baseUrl/series/comic-$slug"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val series = url.pathSegments.getOrNull(1)?.takeIf { it.startsWith("comic-") } ?: return null
        val slug = series.removePrefix("comic-").takeIf(String::isNotBlank) ?: return null
        val manga = client.get("$baseUrl/api/series/$slug?type=comic").parseAs<MangaDetailDto>().data
        preferences.slugMap = preferences.slugMap + (manga.id to manga.slug)
        return manga.toSMangaDetails()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url
        return withFreshSlug(mangaId) { slug ->
            coroutineScope {
                val details = async {
                    if (fetchDetails) {
                        client.get("$baseUrl/api/series/$slug?type=comic")
                            .parseAs<MangaDetailDto>().data.toSMangaDetails()
                    } else {
                        manga
                    }
                }
                val chapterList = async {
                    if (fetchChapters) {
                        fetchChapterDtos(slug).map { it.toSChapter(mangaId) }
                    } else {
                        chapters
                    }
                }
                SMangaUpdate(details.await(), chapterList.await())
            }
        }
    }

    private suspend fun fetchChapterDtos(slug: String): List<ChapterDto> {
        val first = fetchChapterPage(slug, 1)
        val result = first.data.toMutableList()
        var page = 2
        while (first.meta.total > result.size) {
            val next = fetchChapterPage(slug, page)
            if (next.data.isEmpty()) break
            result += next.data
            page += 1
        }
        return result
    }

    // Ya no se usa el subdominio panel.: el mismo dominio sirve la API.
    private suspend fun fetchChapterPage(slug: String, page: Int): PayloadChapterDto = client.get("$baseUrl/api/series/$slug/chapters?page=$page&direction=desc&type=comic")
        .parseAs<PayloadChapterDto>()

    // ------------------------------------------------------------ capítulos

    // chapter.url = "<mangaId>/<chapterId>/<nombreCapitulo>"  (formato antiguo: "<mangaId>/<chapterId>")
    private fun parseChapterUrl(url: String): Triple<String, String, String?> {
        val parts = url.split("/", limit = 3)
        return Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrNull(2))
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (mangaId, _, name) = parseChapterUrl(chapter.url)
        val slug = slugFor(mangaId) ?: return baseUrl
        return if (name != null) {
            "$baseUrl/series/comic-$slug/capitulo/$name"
        } else {
            "$baseUrl/series/comic-$slug"
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (mangaId, chapterId, storedName) = parseChapterUrl(chapter.url)

        return withFreshSlug(mangaId) { slug ->
            // Capítulos guardados con el formato antiguo no traen el nombre: se busca por id.
            val name = storedName
                ?: fetchChapterDtos(slug).firstOrNull { it.id.toString() == chapterId }?.name
                ?: throw Exception("Capítulo no encontrado. Actualiza la serie.")

            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/capitulo")
                .addPathSegment(slug)
                .addPathSegment(name)
                .addQueryParameter("type", "comic")
                .build()
                .toString()

            val pages = client.get(url).parseAs<PayloadPagesDto>().chapter.pages
            if (pages.isEmpty()) throw Exception("El capítulo aún no tiene páginas.")

            pages.mapIndexed { i, img -> Page(i, imageUrl = absoluteUrl(img)) }
        }
    }

    private fun absoluteUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$baseUrl$path"
        else -> "$baseUrl/$path"
    }

    // ------------------------------------------------------------ preferencias

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = "Buscar dominio automáticamente"
            summary = "Intenta buscar el dominio automáticamente al abrir la fuente (desactivado por defecto)."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }
    }

    private fun SharedPreferences.fetchDomainPref() = getBoolean(FETCH_DOMAIN_PREF, false)

    private var slugMapCache: Map<Int, String>? = null
    private var SharedPreferences.slugMap: Map<Int, String>
        get() {
            slugMapCache?.let { return it }
            val json = getString(SLUG_MAP, "{}")!!
            slugMapCache = try {
                json.parseAs<Map<Int, String>>()
            } catch (_: SerializationException) {
                emptyMap()
            }
            return slugMapCache!!
        }
        set(map) {
            slugMapCache = map
            edit().putString(SLUG_MAP, map.toJsonString()).apply()
        }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val FETCH_DOMAIN_PREF = "fetchDomain"

        private const val SLUG_MAP = "slugMap"

        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutos
    }
}
