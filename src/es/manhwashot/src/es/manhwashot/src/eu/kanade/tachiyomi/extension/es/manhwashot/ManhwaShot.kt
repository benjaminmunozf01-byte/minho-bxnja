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

    // ---------- Listados (selectores SIN verificar: falta el HTML de /explorar/) ----------
    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries("$baseUrl/explorar/?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries("$baseUrl/explorar/?page=$page")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/explorar/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return fetchSeries(url.toString())
    }

    private suspend fun fetchSeries(url: String): MangasPage {
        val document = client.get(url).toDocument()
        val mangas = document.select("a.s-card").map { it.toSManga() }
        return MangasPage(mangas, false) // paginación pendiente
    }

    private fun Element.toSManga() = SManga.create().apply {
        url = absUrl("href").removePrefix(baseUrl)
        title = selectFirst(".s-card-title")!!.text()
        thumbnail_url = selectFirst("img")?.absUrl("src")
    }

    // ---------- Detalles y capítulos (verificado) ----------
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
        // Las fechas vienen en el payload de Next.js: \"dates\":{\"1\":1772812744,...} (segundos)
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val block = scripts.substringAfter("\\\"dates\\\":{", "").substringBefore("}")
        val dates = DATE_REGEX.findAll(block).associate {
            it.groupValues[1].toInt() to it.groupValues[2].toLong() * 1000
        }

        return document.select("div.chapters-grid > a.ch-row").map { el ->
            SChapter.create().apply {
                setUrlWithoutDomain(el.absUrl("href"))
                name = el.selectFirst(".ch-num")!!.text()
                chapter_number = CHAPTER_REGEX.find(url)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = dates[chapter_number.toInt()] ?: 0L
            }
        }
    }

    // ---------- Páginas del capítulo (verificado con el HTML de capitulo-37) ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).toDocument()

        val images = document.select("img[src*=\"/WP-manga/data/\"]")
            .map { it.absUrl("src") }
            .ifEmpty {
                // Respaldo: buscar las URLs en cualquier parte del HTML (payload de Next.js)
                IMAGE_REGEX.findAll(document.html()).map { it.value }.toList()
            }
            .distinct()

        return images.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    companion object {
        private val CHAPTER_REGEX = Regex("""capitulo-(\d+(?:\.\d+)?)""")
        private val DATE_REGEX = Regex("""\\"(\d+)\\":(\d{9,10})""")
        private val IMAGE_REGEX = Regex("""https://img\.manhwashot\.lat/img/WP-manga/data/[^"\\\s]+\.(?:webp|jpg|jpeg|png)""")
    }
}
