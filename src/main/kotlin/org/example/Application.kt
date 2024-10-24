package org.example

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import org.example.database.DatabaseFactory
import org.example.database.DatabaseFactory.dbQuery
import org.example.models.Content
import org.example.models.ContentStorage
import org.example.parser.ContentParser
import org.example.parser.Response
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.PriorityBlockingQueue


data class ModerationResponse(
    val contentId: Int,
    val isNegative: Boolean,
    val toxicityScore: Double?,
)

data class ContentClass(
    val fileName: String? = null,
    val fileSize: Long? = null,
    val contentType: String,
    var status: String = null.toString(),
)

data class CacheItem(
    val contentId: Int,
    val time: LocalDateTime = LocalDateTime.now(),
    var status: String = null.toString()
)

data class QueueItem(val contentType: String, val content: ByteArray, val timestamp: Long = System.nanoTime()) :
    Comparable<QueueItem> {
    override fun compareTo(other: QueueItem): Int {
        return when {
            this.contentType.startsWith("image") && !other.contentType.startsWith("image") -> -1
            !this.contentType.startsWith("image") && other.contentType.startsWith("image") -> 1
            else -> this.timestamp.compareTo(other.timestamp)
        }
    }
}


class Queue {
    private val queue: PriorityBlockingQueue<QueueItem> = PriorityBlockingQueue()

    suspend fun enqueue(item: QueueItem, call: ApplicationCall) {
        try {
            queue.offer(item)
        } catch (e: Exception) {
            handleError(e, call)
        }
    }

    suspend fun removeHead(call: ApplicationCall) {
        try {
            queue.poll()
        } catch (e: Exception) {
            handleError(e, call)
        }
    }

    suspend fun removeItem(item: QueueItem, call: ApplicationCall) {
        try {
            queue.remove(item)
        } catch (e: Exception) {
            handleError(e, call)
        }
    }

    fun size(): Int {
        return queue.size
    }
}

class Cache {
    private val cache = ArrayList<CacheItem>()
    private val queue = Queue()
    private val max = 100
    fun addItem(item: CacheItem) {
        removeExpired()
        val now = LocalDateTime.now()
        if (ChronoUnit.MINUTES.between(item.time, now) <= 10) {
            cache.add(item)
        }
    }

    private fun removeExpired() {
        val now = LocalDateTime.now()
        cache.removeIf { ChronoUnit.MINUTES.between(it.time, now) > 10 }
    }

    fun stats(): Map<String, Any> {
        val processing = cache.filter { it.status == "processing" }
        val processed = cache.filter { it.status == "processed" }
        val errored = cache.filter { it.status == "errored" }

        val queueStatus = when {
            queue.size() == 0 -> "empty"
            queue.size() < max / 1.75 -> "light"
            else -> "heavy"
        }

        return mapOf(
            "size" to cache.size,
            "processing" to processing.map { formatCacheItem(it) },
            "processed" to processed.map { formatCacheItem(it) },
            "errored" to errored.map { formatCacheItem(it) },
            "queue_status" to queueStatus,
            "cache" to formatCacheDuration(10)
        )
    }

    private fun formatCacheItem(item: CacheItem): Map<String, Any> {
        return mapOf(
            "contentId" to item.contentId,
            "time" to item.time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")),
            "status" to item.status
        )
    }

    private fun formatCacheDuration(minutes: Int): String {
        return "$minutes minutes from ${LocalDateTime.now().minusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}"
    }
}

private suspend fun handleError(e: Exception, call: ApplicationCall) {
    call.respond(
        HttpStatusCode.InternalServerError, "Queue error occurred: $e"
    )
}

val cache = Cache()
val queue = Queue()

fun main() {
    DatabaseFactory.init()
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            jackson{
                registerModule(JavaTimeModule())
            }
        }
        install(StatusPages) {
            exception<Throwable> { call: ApplicationCall, cause: Throwable ->
                call.application.log.error("Unhandled exception", cause)
                call.respondText(
                    text = "500: $cause", status = HttpStatusCode.InternalServerError
                )
            }
        }


        configureRouting()
    }.start(wait = true)
}

fun Application.configureRouting() {
    routing {
        post("/content/moderate") {
            val contentType = call.request.contentType()
            val (contentBytes, contentClass) = when {
                contentType == ContentType.Application.Json -> {
                    val textContent = call.receiveText().toByteArray()
                    textContent to ContentClass(contentType = "text")
                }

                contentType.match(ContentType.MultiPart.FormData) -> {
                    var fileBytes: ByteArray? = null
                    var fileName: String? = null
                    call.receiveMultipart(50*1024*1024).forEachPart { part ->
                        if (part is PartData.FileItem) {
                            fileName = part.originalFileName ?: UUID.randomUUID().toString()
                            fileBytes = part.provider().readRemaining().readByteArray()
                        }
                        part.dispose()
                    }
                    if (fileBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, "No file was uploaded")
                        return@post
                    }
                    val detectedContentType = if (isImage(fileName, fileBytes!!)) "image" else "text"
                    fileBytes!! to ContentClass(
                        fileName = fileName, fileSize = fileBytes!!.size.toLong(), contentType = detectedContentType
                    )
                }

                else -> {
                    call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported content type")
                    return@post
                }
            }

            if (contentBytes.size > 50 * 1024 * 1024) {
                call.respond(HttpStatusCode.NotAcceptable, "Content size must be 50MB or below.")
                return@post
            }

            val contentId = Db.saveContentClass(contentClass)
            try {
                coroutineScope {
                    val item = QueueItem(
                        contentType = contentClass.contentType,
                        content = contentBytes,
                    )
                    val cacheItem = CacheItem(
                        contentId = contentId,
                    )
                    queue.enqueue(item, call)
                    contentClass.status = "processing"
                    cacheItem.status = contentClass.status
                    cache.addItem(cacheItem)
                    val processContentDeferred = async { processContent(contentBytes, contentClass) }
                    val response = processContentDeferred.await()
                    async { Db.saveContent(contentBytes, contentId, response.isNegative, response.toxicityScore) }
                    contentClass.status = "processed"
                    cacheItem.status = contentClass.status
                    cache.addItem(cacheItem)
                    if (queue.size() == 1) {
                        queue.removeHead(call)
                    } else {
                        queue.removeItem(item, call)
                    }
                    Db.updateStatus(contentId, contentClass.status)
                    val moderationResponse = ModerationResponse(
                        contentId = contentId,
                        isNegative = response.isNegative,
                        toxicityScore = response.toxicityScore,
                    )
                    call.respond(
                        HttpStatusCode.Accepted, mapOf(
                            "content_id" to moderationResponse.contentId.toString(),
                            "content_type" to contentClass.contentType,
                            "file_name" to contentClass.fileName,
                            "is_negative" to moderationResponse.isNegative,
                            "toxicity" to "%.10f".format(moderationResponse.toxicityScore)
                        )
                    )
                }
            } catch (e: Exception) {
                val cacheItem = CacheItem(
                    contentId = contentId,
                )
                contentClass.status = "errored"
                cacheItem.status = contentClass.status
                cache.addItem(cacheItem)
                Db.updateStatus(contentId, contentClass.status)
                call.respond(
                    HttpStatusCode.InternalServerError, "There has been an issue with processing content: ${e.message}"
                )
            }
        }

        get("/content/{content_id}/status") {
            val contentId = call.parameters["content_id"]!!.toInt()
            val status = Db.fetchContentStatus(contentId)
            call.respond(
                HttpStatusCode.Accepted, mapOf(
                    "content_id" to contentId, "status" to status
                )
            )
        }

        get("/cache/stats") {
            call.respond(HttpStatusCode.Accepted, cache.stats())
        }
    }
}

suspend fun processContent(parsedContent: ByteArray, contentClass: ContentClass): Response {
    val moderator = ContentParser()
    return moderator.parseContent(parsedContent, contentClass.contentType)
}

object Db {
    suspend fun fetchContentStatus(contentId: Int): String? {
        return dbQuery {
            Content.select(Content.status).where { Content.id eq contentId }.mapNotNull { it[Content.status] }
                .singleOrNull()
        }
    }

    suspend fun saveContentClass(contentClass: ContentClass): Int {
        return dbQuery {
            Content.insert {
                it[contentType] = contentClass.contentType
                it[fileName] = contentClass.fileName
                it[fileSize] = contentClass.fileSize
                it[status] = contentClass.status
            } get Content.id
        }
    }

    suspend fun updateStatus(contentId: Int, status: String) {
        return dbQuery {
            Content.update({ Content.id eq contentId }) {
                it[Content.status] = status
            }
        }
    }

    suspend fun saveContent(content: ByteArray, contentId: Int, isNegative: Boolean, toxicityScore: Double) {
        dbQuery {
            ContentStorage.insert {
                it[content_id] = contentId
                it[ContentStorage.content] = content
                it[is_toxic] = isNegative
                it[toxicity] = toxicityScore
            }
        }
    }
}

fun isImage(fileName: String?, content: ByteArray): Boolean {
    if (fileName != null) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in listOf("jpg", "jpeg", "png")) {
            return true
        }
    }

    return when {
        content.size > 2 && content[0] == 0xFF.toByte() && content[1] == 0xD8.toByte() -> true // JPEG
        content.size > 8 && String(content.take(8).toByteArray()) == "\u0089PNG\r\n\u001a\n" -> true // PNG
        else -> false
    }
}