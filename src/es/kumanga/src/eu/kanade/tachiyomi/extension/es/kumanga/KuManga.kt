package eu.kanade.tachiyomi.extension.es.kumanga

import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class KuManga : KeiSource() {

    private val json = Json { ignoreUnknownKeys = true }

    // GET con Referer propio (el sitio redirige al inicio sin Referer de la ficha del manga)
    private suspend fun getHtml(url: String, referer: String = "$baseUrl/"): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Referer", referer).build()
            client.newCall(req).execute().use { it.body.string() }
        }

    private fun parse(html: String, url: String): Document = Jsoup.parse(html, url)

    // ---------- Listados ----------
    override suspend fun getPopularManga(page: Int): MangasPage =
        fetchList("$baseUrl/mangalist?page=$page", page)

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

        // Paso 2: si no es el lector, seguir el botón "Iniciar lectura" (/manga/leer/ID o /manga/c/ID)
        if (!html.contains("const jaja")) {
            val landing = parse(html, chapterUrl)
            val readerUrl = landing.selectFirst("a#leer[href]")?.absUrl("href")
                ?: landing.select("a[href]")
                    .firstOrNull { it.text().contains("iniciar", true) && it.attr("href").contains(READER_REGEX) }
                    ?.absUrl("href")
                ?: landing.selectFirst("a[href*=/manga/leer/], a[href*=/manga/c/]")?.absUrl("href")
                ?: READER_REGEX.find(html)?.value?.toAbsolute()
                ?: throw Exception("No se encontró el botón 'Iniciar lectura' del capítulo. Ábrelo en WebView.")
            html = getHtml(readerUrl, chapterUrl)
        }

        val encoded = Regex("""const\s+jaja\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw Exception("No se pudieron leer las imágenes del capítulo. Ábrelo en WebView.")

        val raw = Base64.decode(encoded, Base64.DEFAULT)
        val decoded = String(
            CharArray(raw.size) { i -> ((raw[i].toInt() and 0xFF) xor KEY[i % KEY.length].code).toChar() },
        )
        return json.parseToJsonElement(decoded).jsonArray.mapIndexed { i, e ->
            Page(i, imageUrl = decodeSrc(e.jsonPrimitive.content))
        }
    }

    private fun decodeSrc(s: String): String {
        val hex = s.substringAfter("src=", "")
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return s.toAbsolute()
        }
        val sb = StringBuilder(hex.length / 2)
        for (i in hex.indices step 2) sb.append(hex.substring(i, i + 2).toInt(16).toChar())
        return sb.toString()
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
    }
}
