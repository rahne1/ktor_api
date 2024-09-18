package org.example
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080)  {
        configureRouting()
    }.start(wait = true)
}

fun Application.configureRouting() {
    routing {
        post("/content/moderate") {
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
