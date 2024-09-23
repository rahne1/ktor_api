package org.example
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.*
import io.ktor.jackson.jackson
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.utils.io.streamas.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

data class ModerationResponse(
    val isNegative: Boolean, 
    val ToxicityScore: Double?, 
    val sentiment: String,
)

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation)
        {
            jackson {}
        }
        install(StatusPages) {
            exception<Throwable>
            { cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to cause.localizedMessage))}
            LoggerFactory.getLogger("Application").error("Unhandled exception," cause)
        }
    }
}

fun Application.configureRouting() {
    routing {
        post("/content/moderate") {
            val multipart = call.receiveMultipart() 
            var fileDescription: String? = null
            var fileName: String? = null 
            var fileSize: Long = 0 
            var contentType: ContentType = ContentType.Text.Plain
        
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        fileDescription = part.value
                    }
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
                        
                        val uploadDir = File("uploads")
                        if (!uploadDir.exists()) {
                            uploadDir.mkdirs()
                        }
                        File("uploads/$fileName").writeBytes(bytes)
                    }
                    else -> {}
                }
                part.dispose()
            }
        
            val contentId = UUID.randomUUID().toString() 
            val fileClass = FileClass(
                id = contentId,
                fileName = fileName ?: "unknown",
                fileSize = fileSize,
                contentType = contentType.toString(),
                description = fileDescription
            )
        
            try {
                database.saveFileClass(fileClass)
                launch {
                    processContent(contentId)
                }
                call.respond(HttpStatusCode.Accepted, mapOf(
                    "content_id" to contentId,
                    "content_type" to contentType.toString()
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error during processing of content: ${e.message}")
            }
        }
        
        fun isImage(fileName: String?, bytes: ByteArray): Boolean {
            if (fileName != null) {
                val extension = fileName.substringAfterLast('.', "").toLowerCase()
                if (extension in listOf("jpg", "jpeg", "png")) {
                    return true
                }
            }
        
            return when {
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true //jpeg
                bytes.size > 8 && String(bytes.take(8).toByteArray()) == "\u0089PNG\r\n\u001a\n" -> true // png
                else -> false
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
