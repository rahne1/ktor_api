package org.example

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

data class QueueItem(val contentType: String, val content: ByteArray): Comparable<QueueItem> {
    override fun compareTo(other: QueueItem): Int {
        return when {
            this.contentType.startsWith("image") && !other.contentType.startsWith("image") -> -1
            !this.contentType.startsWith("image") && other.contentType.startsWith("image") -> 1
            else -> 0
        }
    }
}

fun main() {
    DatabaseFactory.init()
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            jackson()
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
                    call.receiveMultipart().forEachPart { part ->
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
            contentClass.status = "processing"
            Db.updateStatus(contentId, contentClass.status)
            try {
                coroutineScope {
                    val processContentDeferred = async { processContent(contentBytes, contentClass) }
                    val response = processContentDeferred.await()
                    async { Db.saveContent(contentBytes, contentId, response.isNegative, response.toxicityScore) }
                    contentClass.status = "processed"
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
                contentClass.status = "errored"
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
                    "content_id" to contentId,
                    "status" to status
                )
            )
        }

        post("/job/enqueue") {
            call.respondText("Hello from POST /job/enqueue", status = HttpStatusCode.OK)
        }

        get("/job/dequeue") {
            call.respondText("Hello from GET /job/dequeue", status = HttpStatusCode.OK)
        }

        get("/cache/stats") {
            call.respondText("Hello from GET /cache/stats", status = HttpStatusCode.OK)
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