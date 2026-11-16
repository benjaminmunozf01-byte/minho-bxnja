package eu.kanade.tachiyomi.extension.es.kumanga

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
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import keiyoushi.utils.xor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class KuManga : KeiSource() {

    private suspend fun getHtml(url: String, referer: String = "$baseUrl/"): String = client.get(url, Fetch.DOCUMENT.from(referer)).use { it.body.string() }

    private fun parse(html: String, url: String): Document = Jsoup.parse(html, url)

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
                thumbnail_url = BG_URL_REGEX.find(style)?.groupValues?.get(1)?.toAbsolute()
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
            val link = WINDOW_OPEN_REGEX
                .find(el.attr("onclick") + el.select("[onclick]").joinToString { it.attr("onclick") })
                ?.groupValues?.get(1)
                ?: el.selectFirst("a[href*=/manga/]")?.absUrl("href")?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            SManga.create().apply {
                this.url = link.toAbsolute().removePrefix(baseUrl)
                title = el.selectFirst("p.km-title-p-card")?.text().orEmpty()
                thumbnail_url = el.selectFirst("img.km-img-crd")?.let {
                    it.absUrl("data-src").ifEmpty { it.absUrl("src") }
                }
            }
        }
        val hasNext = doc.select(".section-nav-pagination a[href]").any {
            !it.hasAttr("disabled") &&
                PAGE_NUMBER_REGEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    .let { p -> p != null && p > page }
        }
        return MangasPage(mangas, hasNext)
    }

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
        title = doc.selectFirst("h1.media-name__main")?.ownText().orEmpty()
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
            it.selectFirst(".media-info-list__title")?.text().orEmpty() to
                it.selectFirst(".media-info-list__value")?.text().orEmpty()
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
        val mangaId = MANGA_ID_REGEX.find(mangaPath)?.groupValues?.get(1) ?: return emptyList()
        val chapters = LinkedHashMap<String, String?>()

        doc.select(".media-chapter").forEach { el ->
            val num = el.attr("data-sort").ifEmpty {
                CHAPTER_NUM_REGEX.find(
                    el.selectFirst("a.media-chapter__link")?.attr("href").orEmpty(),
                )?.groupValues?.get(1).orEmpty()
            }
            if (num.isNotEmpty()) {
                val inlineTitle = el.selectFirst(".media-chapter__name font")?.let { font ->
                    font.ownText().ifEmpty { font.select("span").text() }
                }?.removePrefix(":")?.removePrefix("-")?.trim()?.takeIf { it.isNotEmpty() }
                chapters.putIfAbsent(num, inlineTitle)
            }
        }

        OTHER_CHAPTERS_REGEX.find(html)?.let { match ->
            runCatching {
                match.groupValues[1].parseAs<List<ChapterDto>>().forEach { ch ->
                    chapters[ch.numCap] = ch.title?.takeIf { t -> t != "null" && t.isNotBlank() }
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = MANGA_ID_REGEX.find(chapter.url)?.groupValues?.get(1)
        val mangaPage = if (mangaId != null) "$baseUrl/manga/$mangaId" else "$baseUrl/"
        val chapterUrl = baseUrl + chapter.url

        var html = getHtml(chapterUrl, mangaPage)
        var doc = parse(html, chapterUrl)
        var pages = extractPagesFromReader(doc)

        if (pages == null) {
            val landingReferer = doc.selectFirst("meta[property=og:url]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: chapterUrl
            val readerUrl = doc.selectFirst("a#leer[href], a.readButton[href]")?.absUrl("href")
                ?.takeIf { it.isNotEmpty() }
                ?: doc.select("a[href]")
                    .firstOrNull { it.text().contains("iniciar", true) && it.attr("href").contains(READER_REGEX) }
                    ?.absUrl("href")?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("a[href*=/manga/leer/], a[href*=/manga/c/]")?.absUrl("href")
                    ?.takeIf { it.isNotEmpty() }
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

    override fun getImageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!
        val host = imageUrl.toHttpUrlOrNull()?.host.orEmpty()
        val reqHeaders = if (NO_REFERER_HOSTS.any { host == it || host.endsWith(".$it") }) {
            headers.newBuilder().removeAll("Referer").build()
        } else {
            headers
        }
        return Request.Builder()
            .url(imageUrl)
            .headers(reqHeaders)
            .build()
    }

    // Evaluates top-level JS declarations and functions while ignoring bare block-scoped
    // honeypots such as `{const jaja2="...";const rpics=jaja2;}`. Prioritizes scripts
    // preceding kx.reader*.js and falls back to later scripts only if needed.
    private fun extractPagesFromReader(doc: Document): List<String>? {
        val preReader = mutableListOf<String>()
        val postReader = mutableListOf<String>()
        var seenReader = false

        for (script in doc.select("script")) {
            if (script.parents().any { it.tagName().equals("template", true) || it.tagName().equals("noscript", true) }) {
                continue
            }
            val src = script.attr("src")
            if (src.contains("kx.reader", ignoreCase = true)) {
                seenReader = true
                continue
            }
            if (src.isNotEmpty()) {
                continue
            }
            val type = script.attr("type").trim().lowercase()
            if (type.isNotEmpty() && type != "text/javascript" && type != "application/javascript") {
                continue
            }
            if (seenReader) {
                postReader.add(script.data())
            } else {
                preReader.add(script.data())
            }
        }

        val globals = LinkedHashMap<String, String>()
        val functions = LinkedHashMap<String, JsFunction>()

        for (code in preReader) {
            val tokens = tokenizeJs(code)
            scanScope(tokens, 0, tokens.size, globals, globals, functions)
        }
        resolveFromBindings(globals, functions)?.let { return it }

        if (postReader.isNotEmpty()) {
            for (code in postReader) {
                val tokens = tokenizeJs(code)
                scanScope(tokens, 0, tokens.size, globals, globals, functions)
            }
            resolveFromBindings(globals, functions)?.let { return it }
        }
        return null
    }

    private fun resolveFromBindings(
        globals: Map<String, String>,
        functions: Map<String, JsFunction>,
    ): List<String>? {
        val primaryCandidates = listOfNotNull(
            globals["rpics"],
            globals["readerpics"],
            globals["jaja"],
        )
        for (candidate in primaryCandidates) {
            decodeEncodedList(candidate)?.let { return it }
        }
        for (candidate in globals.values.reversed()) {
            decodeEncodedList(candidate)?.let { return it }
        }
        for (fn in functions.values.reversed()) {
            val candidate = invokeFunction(fn, globals, functions, 0) ?: continue
            decodeEncodedList(candidate)?.let { return it }
        }
        return null
    }

    private fun decodeEncodedList(encoded: String): List<String>? {
        val raw = encoded.trim().decodeBase64()?.toByteArray()
            ?.takeIf { it.size >= 4 }
            ?: return null

        for (keyBytes in KNOWN_KEY_BYTES) {
            tryXorKey(raw, keyBytes)?.let { return it }
        }

        // Fallback: recover repeating XOR key via known JSON/URL prefix cribs if the site rotates the key.
        for (crib in KNOWN_CRIBS) {
            if (raw.size < crib.size + 2) continue
            val stream = ByteArray(crib.size) { i ->
                (raw[i].toInt() xor crib[i].toInt()).toByte()
            }
            val maxKeyLen = (stream.size - 4).coerceAtLeast(stream.size / 2).coerceAtLeast(1)
            for (keyLen in 1..maxKeyLen) {
                var periodic = true
                for (i in keyLen until stream.size) {
                    if (stream[i] != stream[i % keyLen]) {
                        periodic = false
                        break
                    }
                }
                if (periodic) {
                    tryXorKey(raw, stream.copyOf(keyLen))?.let { return it }
                }
            }
        }
        return null
    }

    private fun tryXorKey(raw: ByteArray, key: ByteArray): List<String>? {
        if (key.isEmpty() || raw.size < 4) return null
        val first0 = (raw[0].toInt() xor key[0].toInt()) and 0xFF
        val first1 = (raw[1].toInt() xor key[1 % key.size].toInt()) and 0xFF
        val last1 = (raw[raw.size - 2].toInt() xor key[(raw.size - 2) % key.size].toInt()) and 0xFF
        val last0 = (raw[raw.size - 1].toInt() xor key[(raw.size - 1) % key.size].toInt()) and 0xFF
        if (first0 != '['.code || first1 != '"'.code || last1 != '"'.code || last0 != ']'.code) {
            return null
        }
        return runCatching {
            val decoded = raw.xor(key).decodeToString()
            decoded.parseAs<List<String>>().takeIf { list ->
                list.isNotEmpty() &&
                    list.all { url -> url.startsWith("/") || url.startsWith("http://") || url.startsWith("https://") } &&
                    list.none { url -> HONEYPOT_MARKERS.any { url.contains(it, ignoreCase = true) } }
            }
        }.getOrNull()
    }

    private sealed interface JsToken {
        class Ident(val name: String) : JsToken
        class Str(val value: String) : JsToken
        class Punct(val ch: Char) : JsToken
        data object Arrow : JsToken
        data object Other : JsToken
    }

    private class JsFunction(
        val tokens: List<JsToken>,
        val bodyStart: Int,
        val bodyEnd: Int,
        val isBlock: Boolean,
    )

    private fun JsToken?.isPunct(c: Char): Boolean = this is JsToken.Punct && ch == c

    private fun tokenizeJs(code: String): List<JsToken> {
        val tokens = mutableListOf<JsToken>()
        val n = code.length
        var i = 0
        var braceDepth = 0
        var canStartRegex = true
        val templateBraceDepths = ArrayDeque<Int>()

        fun scanStringLiteral(quote: Char) {
            val sb = StringBuilder()
            while (i < n && code[i] != quote) {
                val ch = code[i]
                if (quote == '`' && ch == '$' && i + 1 < n && code[i + 1] == '{') {
                    tokens.add(JsToken.Str(sb.toString()))
                    tokens.add(JsToken.Punct('+'))
                    tokens.add(JsToken.Punct('('))
                    braceDepth++
                    templateBraceDepths.addLast(braceDepth)
                    canStartRegex = true
                    i += 2
                    return
                }
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
            tokens.add(JsToken.Str(sb.toString()))
            canStartRegex = false
        }

        while (i < n) {
            val c = code[i]
            if (c == '\n') {
                if (!canStartRegex) {
                    tokens.add(JsToken.Punct('\n'))
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
                i++
                scanStringLiteral(c)
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
                tokens.add(JsToken.Other)
                canStartRegex = false
                continue
            }
            if (c == '{') {
                braceDepth++
                tokens.add(JsToken.Punct('{'))
                canStartRegex = true
                i++
                continue
            }
            if (c == '}') {
                if (templateBraceDepths.lastOrNull() == braceDepth) {
                    templateBraceDepths.removeLast()
                    braceDepth--
                    tokens.add(JsToken.Punct(')'))
                    tokens.add(JsToken.Punct('+'))
                    i++
                    scanStringLiteral('`')
                    continue
                }
                braceDepth = (braceDepth - 1).coerceAtLeast(0)
                tokens.add(JsToken.Punct('}'))
                canStartRegex = true
                i++
                continue
            }
            if (c == '(' || c == '[') {
                tokens.add(JsToken.Punct('('))
                canStartRegex = true
                i++
                continue
            }
            if (c == ')' || c == ']') {
                tokens.add(JsToken.Punct(')'))
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
                tokens.add(JsToken.Ident(ident))
                canStartRegex = ident in REGEX_KEYWORDS
                continue
            }
            if (c.isDigit()) {
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) {
                    i++
                }
                tokens.add(JsToken.Other)
                canStartRegex = false
                continue
            }
            if (c == '=') {
                val prevC = if (i > 0) code[i - 1] else '\u0000'
                val nextC = if (i + 1 < n) code[i + 1] else '\u0000'
                if (nextC == '>') {
                    tokens.add(JsToken.Arrow)
                    canStartRegex = true
                    i += 2
                    continue
                }
                val isAssign = prevC !in "!<>=+-*/%&|^~?" && nextC != '='
                tokens.add(if (isAssign) JsToken.Punct('=') else JsToken.Other)
                canStartRegex = true
                i++
                continue
            }
            if (c == ';' || c == ',' || c == '+') {
                val prevC = if (i > 0) code[i - 1] else '\u0000'
                val nextC = if (i + 1 < n) code[i + 1] else '\u0000'
                val isPlusOp = c == '+' && (prevC == '+' || nextC == '+' || nextC == '=')
                tokens.add(if (isPlusOp) JsToken.Other else JsToken.Punct(c))
                canStartRegex = true
                i++
                continue
            }
            tokens.add(JsToken.Other)
            canStartRegex = true
            i++
        }
        return tokens
    }

    private fun findMatchingClose(tokens: List<JsToken>, openIdx: Int, endIdx: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        for (i in openIdx until endIdx) {
            val tok = tokens[i]
            if (tok.isPunct(openChar)) {
                depth++
            } else if (tok.isPunct(closeChar)) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun scanScope(
        tokens: List<JsToken>,
        startIdx: Int,
        endIdx: Int,
        locals: MutableMap<String, String>,
        globals: Map<String, String>,
        functions: MutableMap<String, JsFunction>,
        callDepth: Int = 0,
    ): String? {
        // Pass 1: hoisted function declarations (`function name(...) { ... }`) at this scope's top level.
        var idx = startIdx
        while (idx < endIdx) {
            val tok = tokens[idx]
            if (tok.isPunct('{')) {
                val close = findMatchingClose(tokens, idx, endIdx, '{', '}')
                idx = if (close >= 0) close + 1 else endIdx
                continue
            }
            if (tok.isPunct('(')) {
                val close = findMatchingClose(tokens, idx, endIdx, '(', ')')
                idx = if (close >= 0) close + 1 else endIdx
                continue
            }
            if (tok is JsToken.Ident && tok.name == "function") {
                val nameTok = tokens.getOrNull(idx + 1)
                if (nameTok is JsToken.Ident && tokens.getOrNull(idx + 2).isPunct('(')) {
                    val closeParen = findMatchingClose(tokens, idx + 2, endIdx, '(', ')')
                    if (closeParen >= 0 && tokens.getOrNull(closeParen + 1).isPunct('{')) {
                        val closeBrace = findMatchingClose(tokens, closeParen + 1, endIdx, '{', '}')
                        if (closeBrace >= 0) {
                            functions[nameTok.name] = JsFunction(tokens, closeParen + 2, closeBrace, isBlock = true)
                            idx = closeBrace + 1
                            continue
                        }
                    }
                }
            }
            idx++
        }

        // Pass 2: statements and variable bindings at this scope's top level.
        idx = startIdx
        var skipUntilSemi = false
        while (idx < endIdx) {
            val tok = tokens[idx]
            if (tok.isPunct(';')) {
                skipUntilSemi = false
                idx++
                continue
            }
            if (tok.isPunct('{')) {
                val close = findMatchingClose(tokens, idx, endIdx, '{', '}')
                idx = if (close >= 0) close + 1 else endIdx
                skipUntilSemi = false
                continue
            }
            if (tok.isPunct('(')) {
                val close = findMatchingClose(tokens, idx, endIdx, '(', ')')
                idx = if (close >= 0) close + 1 else endIdx
                continue
            }
            if (skipUntilSemi) {
                idx++
                continue
            }
            if (tok is JsToken.Ident && tok.name == "function") {
                var j = idx + 1
                if (tokens.getOrNull(j) is JsToken.Ident) j++
                if (tokens.getOrNull(j).isPunct('(')) {
                    val closeParen = findMatchingClose(tokens, j, endIdx, '(', ')')
                    if (closeParen >= 0 && tokens.getOrNull(closeParen + 1).isPunct('{')) {
                        val closeBrace = findMatchingClose(tokens, closeParen + 1, endIdx, '{', '}')
                        if (closeBrace >= 0) {
                            idx = closeBrace + 1
                            continue
                        }
                    }
                }
                idx++
                continue
            }
            if (tok is JsToken.Ident && tok.name in COND_KEYWORDS) {
                skipUntilSemi = true
                idx++
                continue
            }
            if (tok is JsToken.Ident && tok.name == "return") {
                val res = evalExpr(tokens, idx + 1, endIdx, locals, globals, functions, callDepth)
                if (res != null) return res.first
                idx++
                continue
            }
            if (tok is JsToken.Ident && idx + 1 < endIdx && tokens[idx + 1].isPunct('=')) {
                val prev = if (idx > startIdx) tokens[idx - 1] else null
                val validStart = prev == null ||
                    prev.isPunct(';') ||
                    prev.isPunct('\n') ||
                    prev.isPunct(',') ||
                    prev.isPunct('{') ||
                    prev.isPunct('}') ||
                    (prev is JsToken.Ident && prev.name in DECL_KEYWORDS)
                if (validStart) {
                    val fnExpr = parseFunctionExpr(tokens, idx + 2, endIdx)
                    if (fnExpr != null && !tokens.getOrNull(fnExpr.second).isPunct('(')) {
                        functions[tok.name] = fnExpr.first
                        idx = fnExpr.second
                        continue
                    }
                    val res = evalExpr(tokens, idx + 2, endIdx, locals, globals, functions, callDepth)
                    if (res != null) {
                        locals[tok.name] = res.first
                        idx = res.second
                    } else {
                        locals.remove(tok.name)
                        idx += 2
                    }
                    continue
                }
            }
            idx++
        }
        return null
    }

    private fun invokeFunction(
        fn: JsFunction,
        globals: Map<String, String>,
        functions: Map<String, JsFunction>,
        callDepth: Int,
    ): String? {
        if (callDepth >= MAX_CALL_DEPTH) return null
        val fnFuncs = LinkedHashMap(functions)
        return if (fn.isBlock) {
            scanScope(fn.tokens, fn.bodyStart, fn.bodyEnd, LinkedHashMap(), globals, fnFuncs, callDepth + 1)
        } else {
            evalExpr(fn.tokens, fn.bodyStart, fn.bodyEnd, emptyMap(), globals, fnFuncs, callDepth + 1)?.first
        }
    }

    private fun parseFunctionExpr(
        tokens: List<JsToken>,
        startIdx: Int,
        endIdx: Int,
    ): Pair<JsFunction, Int>? {
        var j = startIdx
        while (j < endIdx && tokens[j].isPunct('\n')) j++
        if (j >= endIdx) return null

        val tok = tokens[j]
        if (tok is JsToken.Ident && tok.name == "function") {
            var k = j + 1
            if (tokens.getOrNull(k) is JsToken.Ident) k++
            if (!tokens.getOrNull(k).isPunct('(')) return null
            val closeParen = findMatchingClose(tokens, k, endIdx, '(', ')')
            if (closeParen < 0 || !tokens.getOrNull(closeParen + 1).isPunct('{')) return null
            val closeBrace = findMatchingClose(tokens, closeParen + 1, endIdx, '{', '}')
            if (closeBrace < 0) return null
            return JsFunction(tokens, closeParen + 2, closeBrace, isBlock = true) to (closeBrace + 1)
        }

        val arrowIdx = when {
            tok.isPunct('(') -> {
                val closeParen = findMatchingClose(tokens, j, endIdx, '(', ')')
                if (closeParen >= 0 && tokens.getOrNull(closeParen + 1) == JsToken.Arrow) closeParen + 1 else -1
            }
            tok is JsToken.Ident && tokens.getOrNull(j + 1) == JsToken.Arrow -> j + 1
            else -> -1
        }
        if (arrowIdx >= 0) {
            var bodyIdx = arrowIdx + 1
            while (bodyIdx < endIdx && tokens[bodyIdx].isPunct('\n')) bodyIdx++
            if (tokens.getOrNull(bodyIdx).isPunct('{')) {
                val closeBrace = findMatchingClose(tokens, bodyIdx, endIdx, '{', '}')
                if (closeBrace < 0) return null
                return JsFunction(tokens, bodyIdx + 1, closeBrace, isBlock = true) to (closeBrace + 1)
            }
            val exprEnd = findExprEnd(tokens, bodyIdx, endIdx)
            return JsFunction(tokens, bodyIdx, exprEnd, isBlock = false) to exprEnd
        }
        return null
    }

    private fun findExprEnd(tokens: List<JsToken>, startIdx: Int, endIdx: Int): Int {
        var idx = startIdx
        var parenDepth = 0
        var braceDepth = 0
        while (idx < endIdx) {
            val tok = tokens[idx]
            when {
                tok.isPunct('(') -> parenDepth++
                tok.isPunct(')') -> {
                    if (parenDepth == 0) return idx
                    parenDepth--
                }
                tok.isPunct('{') -> braceDepth++
                tok.isPunct('}') -> {
                    if (braceDepth == 0) return idx
                    braceDepth--
                }
                parenDepth == 0 && braceDepth == 0 &&
                    (tok.isPunct(';') || tok.isPunct('\n') || tok.isPunct(',')) -> return idx
            }
            idx++
        }
        return endIdx
    }

    private fun evalExpr(
        tokens: List<JsToken>,
        startIdx: Int,
        endIdx: Int,
        locals: Map<String, String>,
        globals: Map<String, String>,
        functions: Map<String, JsFunction>,
        callDepth: Int = 0,
    ): Pair<String, Int>? {
        var j = startIdx
        val rhsBuf = StringBuilder()
        var validRhs = false

        while (j < endIdx) {
            while (j < endIdx && tokens[j].isPunct('\n')) j++
            if (j >= endIdx) break

            when (val rhs = tokens[j]) {
                is JsToken.Str -> {
                    rhsBuf.append(rhs.value)
                    j++
                }
                is JsToken.Ident -> {
                    if (rhs.name in INVALID_RHS_IDENTS) {
                        validRhs = false
                        break
                    }
                    if (tokens.getOrNull(j + 1).isPunct('(')) {
                        val closeParen = findMatchingClose(tokens, j + 1, endIdx, '(', ')')
                        val fn = functions[rhs.name]
                        val fnVal = if (closeParen >= 0 && fn != null) {
                            invokeFunction(fn, globals, functions, callDepth)
                        } else {
                            null
                        }
                        if (closeParen < 0 || fnVal == null) {
                            validRhs = false
                            break
                        }
                        rhsBuf.append(fnVal)
                        j = closeParen + 1
                    } else {
                        val resolved = locals[rhs.name] ?: globals[rhs.name]
                        if (resolved == null) {
                            validRhs = false
                            break
                        }
                        rhsBuf.append(resolved)
                        j++
                    }
                }
                is JsToken.Punct -> {
                    if (!rhs.isPunct('(')) {
                        validRhs = false
                        break
                    }
                    val closeParen = findMatchingClose(tokens, j, endIdx, '(', ')')
                    if (closeParen < 0) {
                        validRhs = false
                        break
                    }
                    val fnInParen = parseFunctionExpr(tokens, j + 1, closeParen)
                    if (tokens.getOrNull(closeParen + 1).isPunct('(')) {
                        val callClose = findMatchingClose(tokens, closeParen + 1, endIdx, '(', ')')
                        val fnVal = if (callClose >= 0 && fnInParen != null) {
                            invokeFunction(fnInParen.first, globals, functions, callDepth)
                        } else {
                            null
                        }
                        if (callClose < 0 || fnVal == null) {
                            validRhs = false
                            break
                        }
                        rhsBuf.append(fnVal)
                        j = callClose + 1
                    } else if (fnInParen != null && tokens.getOrNull(fnInParen.second).isPunct('(')) {
                        val callClose = findMatchingClose(tokens, fnInParen.second, closeParen, '(', ')')
                        val fnVal = if (callClose == closeParen - 1) {
                            invokeFunction(fnInParen.first, globals, functions, callDepth)
                        } else {
                            null
                        }
                        if (fnVal == null) {
                            validRhs = false
                            break
                        }
                        rhsBuf.append(fnVal)
                        j = closeParen + 1
                    } else {
                        val inner = evalExpr(tokens, j + 1, closeParen, locals, globals, functions, callDepth)
                        if (inner == null) {
                            validRhs = false
                            break
                        }
                        rhsBuf.append(inner.first)
                        j = closeParen + 1
                    }
                }
                else -> {
                    validRhs = false
                    break
                }
            }

            validRhs = true
            var nextIdx = j
            while (nextIdx < endIdx && tokens[nextIdx].isPunct('\n')) nextIdx++
            if (nextIdx < endIdx && tokens[nextIdx].isPunct('+')) {
                j = nextIdx + 1
                validRhs = false
                continue
            }
            break
        }

        val after = if (j < endIdx) tokens[j] else null
        val validEnd = after == null ||
            after.isPunct(';') ||
            after.isPunct('\n') ||
            after.isPunct(',') ||
            after.isPunct('}') ||
            after.isPunct(')')
        return if (validRhs && validEnd) rhsBuf.toString() to j else null
    }

    private fun String.isHex(): Boolean = isNotEmpty() && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun decodeSrc(s: String): String {
        val absolute = s.toAbsolute()
        val hex = absolute.toHttpUrlOrNull()?.queryParameter("src")
        if (hex.isNullOrEmpty() || hex.length % 2 != 0 || !hex.isHex()) {
            return absolute
        }
        return hex.decodeHex().decodeToString().toAbsolute()
    }

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

    @Serializable
    private class ChapterDto(
        @SerialName("NumCap") val numCap: String,
        val title: String? = null,
    )

    companion object {
        private const val MAX_CALL_DEPTH = 16

        private val KNOWN_KEY_BYTES = listOf(
            "f0r_m3_1t_1s_fun",
            "Jr54VwepF4La",
        ).map { it.encodeToByteArray() }

        private val KNOWN_CRIBS = listOf(
            """["\/img.php?src=68747470733A2F2F""",
            """["\/img.php?src=68747470733a2f2f""",
            """["\/img.php?src=687474703A2F2F""",
            """["\/img.php?src=687474703a2f2f""",
            """["/img.php?src=68747470733A2F2F""",
            """["/img.php?src=68747470733a2f2f""",
            """["\/img.php?src=68747470733A2F2F6461746162616E6B2E6B756D616E67612E636F6D2F6D616E67612F""",
            """["https:\/\/databank.kumanga.com\/""",
            """["https:\/\/lh3.googleusercontent.com\/""",
            """["https:\/\/""",
            """["https://""",
            """["http:\/\/""",
            """["http://""",
        ).map { it.encodeToByteArray() }

        private val HONEYPOT_MARKERS = listOf(
            "memedroid.com",
            "M5vQtTw.gif",
        )

        private val NO_REFERER_HOSTS = listOf(
            "blogspot.com",
            "googleusercontent.com",
            "ggpht.com",
            "imageshack.com",
            "imgur.com",
        )

        private val BG_URL_REGEX = Regex("""url\(['"]?([^'")]+)['"]?\)""")
        private val WINDOW_OPEN_REGEX = Regex("""window\.open\('([^']+)'\)""")
        private val PAGE_NUMBER_REGEX = Regex("""page=(\d+)""")
        private val MANGA_ID_REGEX = Regex("""/manga/(\d+)""")
        private val CHAPTER_NUM_REGEX = Regex("""capitulo/([^/"?]+)""")
        private val OTHER_CHAPTERS_REGEX = Regex("""OTHER_CHAPTERS\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        private val READER_REGEX = Regex("""/?manga/(?:leer|c)/\d+""")
        private val DECL_KEYWORDS = setOf("const", "let", "var")
        private val COND_KEYWORDS = setOf("if", "while", "for", "with")
        private val INVALID_RHS_IDENTS = setOf("null", "undefined", "true", "false")
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
