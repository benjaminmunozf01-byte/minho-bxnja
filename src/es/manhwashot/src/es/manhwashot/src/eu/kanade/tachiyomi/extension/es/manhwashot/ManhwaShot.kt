package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class ManhwaShot : KeiSource() {

    private fun Response.toDocument(): Document = use { Jsoup.parse(it.body.string(), it.request.url.toString()) }

    // ---------- Listados ----------
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
        // /explorar/[seccion/][page/N/]?q=...&genero=...
        val base = if (section.isEmpty()) "/explorar" else "/explorar/$section"
        val path = if (page <= 1) "$base/" else "$base/page/$page/"
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            if (query != null) addQueryParameter("q", query)
            if (genre.isNotEmpty()) addQueryParameter("genero", genre)
        }.build()

        val document = client.get(url).toDocument()
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

    // ---------- Detalles y capítulos ----------
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        val path = "/manga/$slug/"
        val document = client.get(baseUrl + path).toDocument()
        return parseDetails(document).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).toDocument()
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
        // Concatenamos todos los fragmentos del payload de Next.js para que no se corten las fechas
        val nextJsPayload = document.select("script")
            .map { it.data() }
            .filter { it.contains("__next_f.push") }
            .joinToString("") {
                it.substringAfter("push([1,\"", "").substringBeforeLast("\"])", "")
            }

        val block = nextJsPayload.substringAfter("\\\"dates\\\":{", "").substringBefore("}")

        // Float como clave para emparejar bien los capítulos con decimales
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

    // ---------- Páginas del capítulo ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).toDocument()

        val images = document.select("img[src*=\"/WP-manga/data/\"]")
            .map { it.absUrl("src") }
            .ifEmpty {
                IMAGE_REGEX.findAll(document.html()).map { it.value }.toList()
            }
            .distinct()

        return images.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // ---------- Filtros ----------
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SectionFilter(),
        GenreFilter(),
    )

    companion object {
        private val CHAPTER_REGEX = Regex("""capitulo-(\d+(?:\.\d+)?)""")

        // Admite capítulos enteros y decimales (Ej. 208.5)
        private val DATE_REGEX = Regex("""\\"(\d+(?:\.\d+)?)\\":(\d{9,10})""")
        private val IMAGE_REGEX = Regex("""https://img\.manhwashot\.lat/img/WP-manga/data/[^"\\\s]+\.(?:webp|jpg|jpeg|png)""")
    }
}
