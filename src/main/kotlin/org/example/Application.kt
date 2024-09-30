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
import org.jetbrains.exposed.sql.*
import java.util.*

data class ModerationResponse(
    val isNegative: Boolean,
    val toxicityScore: Double?,
    val sentiment: String,
)

data class ContentClass(
    val fileName: String? = null,
    val fileSize: Long? = null,
    val contentType: String,
)

fun main() {
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
            val multipart = call.receiveMultipart()
            var fileName: String? = null
            var fileSize: Long = 0
            var contentType: ContentType = ContentType.Text.Plain

            if (call.request.contentType() == ContentType.Application.Json) {
                var content = call.receiveText().toByteArray()
                val contentClass = ContentClass(
                    contentType = "text"
                )
                try {
//                launch {
//                     processContent(content)
//                }
//                database.saveContentClass(contentClass)
                    call.respond(
                        HttpStatusCode.Accepted, mapOf(
                            "content_id" to fileName,
                            "content_type" to contentClass.contentType
                        )
                    )
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error during processing of content: ${e.message}")
                }

            }
            else {
                multipart.forEachPart { part: PartData ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName ?: UUID.randomUUID().toString()
                            val bytes = part.streamProvider().readBytes()
                            fileSize = bytes.size.toLong()

                            if (fileSize > 52428800) {
                                call.respond(
                                    HttpStatusCode.NotAcceptable, "File size too large, files must be 50mb or below."
                                )
                                return@forEachPart
                            }

                            contentType = when {
                                isImage(fileName, bytes) -> ContentType.Image.Any
                                else -> ContentType.Text.Plain
                            }
                            // pass a content storage object which contains the file's contents stored in a byte array into db here
                        }

                        else -> {}
                    }
                    part.dispose()
                }
                val contentClass = ContentClass(
                    fileName = fileName ?: "unknown",
                    fileSize = fileSize,
                    contentType = contentType.toString()
                )
                try {
//                database.saveContentClass(contentClass)
//                launch {
//                     processContent(content)
//                }
                    call.respond(
                        HttpStatusCode.Accepted, mapOf(
                            "content_id" to fileName,
                            "content_type" to contentClass.contentType,
                            "message" to "File queued successfully. Check /content/$contentId/status for it's status.",
                        )
                    )
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error during processing of content: ${e.message}")
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

//fun processContent(contentId: String) {
//    process(contentId)
//    TODO("Not yet implemented")
//}
//
object database {
    suspend fun saveContentClass(contentClass: ContentClass) {
        pass
    }
    suspend fun saveContent(content: ByteArray) {
        pass
    }
}

fun isImage(fileName: String?, bytes: ByteArray): Boolean {
    if (fileName != null) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in listOf("jpg", "jpeg", "png")) {
            return true
        }
    }

    return when {
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true // JPEG
        bytes.size > 8 && String(bytes.take(8).toByteArray()) == "\u0089PNG\r\n\u001a\n" -> true // PNG
        else -> false
    }
}

