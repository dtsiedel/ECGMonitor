package ecg

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.*

//TODO: make a protocol for adding websocket clients to this list
//TODO: don't forget there will be two different types of websockets connecting,
//      the Python one (which should be registered as a source), and the browser
//      one (which should be registered as a sink). Use the protocol to distinguish
val connectedWebsockets = listOf<String>()

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

fun Application.websocketModule() {
    install(WebSockets)
    routing {
        webSocket("/ws") {
            try {
                while (true) {
                    val text = (incoming.receive() as Frame.Text).readText()
                    println("Received ${text}")
		    outgoing.send(Frame.Text("You sent ${text}!"))
                }
            } catch (e: ClosedReceiveChannelException) {
               println("Socket connection closed.")
            }
        }
    }
}

fun main(args: Array<String>) {
    val server = embeddedServer(Netty, 8888) {
	landingModule()
	staticModule()
        websocketModule()
    }
    server.start(wait = true)
}
