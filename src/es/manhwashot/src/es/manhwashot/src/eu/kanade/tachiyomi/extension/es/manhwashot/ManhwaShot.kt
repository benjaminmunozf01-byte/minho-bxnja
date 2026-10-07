package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ManhwaShot : MadaraNoAjax() {
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()

            val isImageRequest = request.url.toString().substringBefore("?").let {
                it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) ||
                    it.endsWith(".png", true) || it.endsWith(".webp", true)
            }

            val newRequest = if (isImageRequest) {
                request.newBuilder().removeHeader("Accept-Encoding").build()
            } else {
                request
            }

            val response = chain.proceed(newRequest)

            if (isImageRequest && response.header("Content-Type")?.contains("application/octet-stream", true) == true) {
                val orgBody = response.body
                val newBody = orgBody.source().asResponseBody("image/jpeg".toMediaType())
                return@addInterceptor response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .body(newBody)
                    .build()
            }

            return@addInterceptor response
        }
        .rateLimit(1, 2.seconds)
        .build()

    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus = "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription = "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"
}
