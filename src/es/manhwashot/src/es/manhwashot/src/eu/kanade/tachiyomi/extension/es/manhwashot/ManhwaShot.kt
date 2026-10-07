package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
class ManhwaShot : HttpSource() {
    override val name = "ManhwaShot"
    override val baseUrl = "https://manhwashot.lat"
    override val lang = "es"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2.seconds)
        .build()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/?m_orderby=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manga/?m_orderby=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/page/$page/?s=$query&post_type=wp-manga", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.row.c-tabs-item__content, div.page-item-detail").map { element ->
            SManga.create().apply {
                val titleElement = element.selectFirst("div.tab-summary div.post-title h3 a, h4.h3 a, a")
                title = titleElement?.text() ?: "Sin título"
                setUrlWithoutDomain(titleElement?.attr("abs:href") ?: "")
                thumbnail_url = element.selectFirst("img")?.let {
                    it.absUrl("data-src").ifEmpty { it.absUrl("src") }
                }
            }
        }
        val hasNextPage = document.selectFirst("div.nav-previous, a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("div.post-title h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("div.summary_image img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        description = document.select("div.description-summary, div.summary__content").text()
        author = document.selectFirst("div.author-content span")?.text()
        artist = document.selectFirst("div.artist-content span")?.text()
        status = parseStatus(document.selectFirst("div.post-content_item:contains(Estado) div.summary-content")?.text())
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("En emisión", true) || status.contains("Ongoing", true) -> SManga.ONGOING
        status.contains("Finalizado", true) || status.contains("Completed", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("li.wp-manga-chapter, div.uni-item").map { element ->
            SChapter.create().apply {
                val a = element.selectFirst("a")
                name = a?.text() ?: "Capítulo"
                setUrlWithoutDomain(a?.attr("abs:href") ?: "")
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.read-container img, div.page-break img.wp-manga-chapter-img").mapIndexed { index, element ->
            val imageUrl = element.absUrl("data-src").ifEmpty { element.absUrl("src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = ""
}
