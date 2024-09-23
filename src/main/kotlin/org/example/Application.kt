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
data class FileClass(
    val fileName: UUID,
    val fileSize: Double,
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
        post("/content/moderate") { // NOTE(rahne1): need to actually save the fileclass to database so user can  check status in /content/{content_id}/status and stats of all content classified can be returned by the cache (cached data is classified as all data that is not fully procesed and the last 5 minutes worth of processed data)
            val multipart = call.recieveMultipart()
            val contentType: String? = null
            var imageFile: File? = null
            multipart.forEachPart { part - >
            when (part) {
                is PartData.FormItem -> {
                    fileDescription = part.value
                }
                is PartData.FileItem -> {
                    fileName = part
                    val fileName = UUID.randomUUID().toString()
                    val bytes = part.streamProvider().readBytes()
                    if (bytes > 52428800) {
                        call.respond(HttpStatusCode.NotAcceptable, "File size too large. Files must be below 50mb.")call.
                    }
                    val fileClass = FileClass(fileName, FileSize)

                    File("uploads/$fileName").writeBytes(fileBytes)
                }

                else -> {}
            }
            part.dispose()
        }

        call.respondText("Hello from POST /content/moderate", status = HttpStatusCode.OK)
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
