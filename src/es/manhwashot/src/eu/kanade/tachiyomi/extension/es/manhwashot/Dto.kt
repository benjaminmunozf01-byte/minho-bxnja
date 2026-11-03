package eu.kanade.tachiyomi.extension.es.manhwashot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class ChapterReaderDto(
    val slug: String = "",
    val num: JsonPrimitive? = null,
    val images: List<String> = emptyList(),
    val locked: Boolean = false,
    val postId: Int = 0,
    val windowN: Int = 0,
    val pgCount: Int = 0,
    val pgUrls: List<String> = emptyList(),
    val pgKey: String = "",
)

@Serializable
class PgUrlsRequest(
    val postId: Int,
    val chapter: JsonPrimitive,
)

@Serializable
class PgUrlsResponse(
    val urls: List<String> = emptyList(),
    val key: String = "",
    val error: String? = null,
)

@Serializable
class PgSeenRequest(
    val postId: Int,
    val chapter: JsonPrimitive,
    val idx: Int,
)

@Serializable
class PgSeenResponse(
    val limit: Int? = null,
)
