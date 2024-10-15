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
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import org.example.database.DatabaseFactory
import org.example.database.DatabaseFactory.dbQuery
import org.example.models.Content
import org.example.models.ContentStorage
import org.example.parser.ContentParser
import org.jetbrains.exposed.sql.insert
import java.util.*

data class ModerationResponse(
    val contentId: Int,
    val isNegative: Boolean,
    val toxicityScore: Double?,
)

data class ContentClass(
    val fileName: String? = null,
    val fileSize: Long? = null,
    val contentType: String,
)

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
                    text = "500: $cause",
                    status = HttpStatusCode.InternalServerError
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
            if (contentType == ContentType.Application.Json) {
                val textContent = call.receiveText().toByteArray()
                val contentClass = ContentClass(contentType = "text")

                try {
                    if (textContent.size > 50 * 1024 * 1024) {
                        call.respond(HttpStatusCode.NotAcceptable, "Text content size must be 50MB or below.")
                        return@post
                    }

                    val contentId = Db.saveContentClass(contentClass)
                    launch {
                        Db.saveContent(textContent, contentId)
                        processContent(textContent, contentClass.contentType)
                    }
                    call.respond(
                        HttpStatusCode.Accepted, mapOf(
                            "content_id" to contentId.toString(),
                            "content_type" to contentClass.contentType,
                            "content" to textContent
                        )
                    )
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error during processing of content: ${e.message}")
                }
            } else {
                val multipart = call.receiveMultipart()
                var fileProcessed = false

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: UUID.randomUUID().toString()
                            val byteReadChannel: ByteReadChannel = part.provider()
                            val fileBytes = byteReadChannel.readRemaining().readByteArray()
                            if (fileBytes.size > 50 * 1024 * 1024) {
                                call.respond(
                                    HttpStatusCode.NotAcceptable,
                                    "File size too large, files must be 50MB or below."
                                )
                                return@forEachPart
                            }

                            val contentType = if (isImage(fileName, fileBytes)) {
                                "image"
                            } else {
                                "text"
                            }

                            fileProcessed = true
                            val contentClass = ContentClass(
                                fileName = fileName,
                                fileSize = fileBytes.size.toLong(),
                                contentType = contentType.toString()
                            )
                            val contentId = Db.saveContentClass(contentClass)

                            launch {
                                Db.saveContent(fileBytes, contentId)
                                processContent(fileBytes, contentClass.contentType)
                            }

                            val encodedContent = Base64.getEncoder().encodeToString(fileBytes)
                            call.respond(
                                HttpStatusCode.Accepted, mapOf(
                                    "content_id" to contentId.toString(),
                                    "content_type" to contentClass.contentType,
                                    "file_name" to contentClass.fileName,
                                    "content" to encodedContent
                                )
                            )
                        }

                        else -> {}
                    }
                    part.dispose()
                }

                if (!fileProcessed) {
                    call.respond(HttpStatusCode.BadRequest, "No file was uploaded")
                }
            }
        }

        get("/content/{content_id}/status") {
            val contentId = call.parameters["content_id"]
            call.respondText("Hello from GET /content/$contentId/status", status = HttpStatusCode.OK)
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

suspend fun processContent(passedContent: ByteArray, contentType: String) {
    val moderator = ContentParser()
    moderator.parseContent(passedContent, contentType)
}

object Db {
    suspend fun saveContentClass(contentClass: ContentClass): Int {
        return dbQuery {
            Content.insert {
                it[contentType] = contentClass.contentType
                it[fileName] = contentClass.fileName
                it[fileSize] = contentClass.fileSize
            } get Content.id
        }
    }

    suspend fun saveContent(content: ByteArray, contentId: Int) {
        dbQuery {
            ContentStorage.insert {
                it[content_id] = contentId
                it[ContentStorage.content] = content
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
