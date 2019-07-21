package ecg

import io.ktor.http.*
import io.ktor.response.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.content.*
import io.ktor.routing.*
import io.ktor.util.*
import java.time.*

fun Application.landingModule() {
    routing {
        get("/") {
	    call.respondRedirect("/index.html", permanent = true)
        }
    }
}

fun Application.staticModule() {
    routing {
        static {
            files("resources/html")
	    files("resources/css")
	    files("resources/js")
        }
    }
}

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, 8888) {
	landingModule()
	staticModule()
    }
    server.start(wait = true)
}
