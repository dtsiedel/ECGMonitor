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

// The keys are the connected "source" clients. One is added each time
// a ws client registers itself with the "register" command, and they
// are removed if the client sends the "unregister" command, or when
// that client disconnects. The values are each a list of the clients
// that are waiting for updates. Clients can add or remove themselves
// from that list with "subscribe"/"unsubscribe".
val connectedWebsockets =
	mutableMapOf<WebSocketServerSession, MutableList<WebSocketServerSession>>()

// Called on each websocket message. Parses out the type of command,
// and handles it appropriately.
suspend fun handleWSMessage(text: String, source: WebSocketServerSession) {
    println("Received ${text} from ${source}")
    for (client in connectedWebsockets.keys) {
	client.outgoing.send(Frame.Text("You sent ${text}!"))
    }
}

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
	    connectedWebsockets.put(
	        this, mutableListOf<WebSocketServerSession>()
            )
            try {
                while (true) {
                    val text = (incoming.receive() as Frame.Text).readText()
		    handleWSMessage(text, this)
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
