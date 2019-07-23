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
import kotlinx.serialization.*
import kotlinx.serialization.json.*

// Used by a client to register itself as a source. This will add it as
// a key in the map of possible sources. The server also will assign a
// UUID to the source, and report both the names and UUIDs when a browser
// requests it. There is no "unregister" command - a registed client
// will automatically by unregistered when the websocket connection
// is closed.
// Format: {"register": "some display name"}
@Serializable
data class RegisterMessage(val register: String)

// Used by a client to subscribe itself to a source by its UUID. This
// will add the client to the listeners list of the source, and ensure
// that they get any message published by that source. There is a
// matching command to unsubscribe, or the client will automatically
// be unsubscribed when its websocket disconnects.
// Format: {"subscribe": "some UUID"}
@Serializable
data class SubscribeMessage(val subscribe: String)

// Used by a client to unsubscribe itself from a source by its UUID.
// The client will then no longer receive messages published by that
// source. If you are shutting down a client there is no need to
// unsubscribe manually, as closing the websocket will automatically
// unsubscibe it from everything.
// Format: {"unsubscribe": "some UUID"}
@Serializable
data class UnsubscribeMessage(val unsubscribe: String)

// Used by a source to publish data to all of its listeners. Is only
// published to its listeners at the exact moment that it is sent,
// meaning that the message can be swallowed if there are no listeners.
// In our context, the data will be a single analog reading from the
// ECG monitor, which is why it is of type Double.
@Serializable
data class PublishMessage(val publish: Double)

// TODO: will likely need two maps - one maps sessions to their UUID/name
// stored in an object, and the other maps to listeners. It cannot be
// easily stored in a single map because Kotlin doesn't have heterogenous
// map values

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

    //TODO: note that you can catch kotlinx.serialization.SerializationException to handle bad format
    println(Json.parse(RegisterMessage.serializer(), "{register: test}"))
    println(Json.parse(SubscribeMessage.serializer(), "{subscribe: abc123}"))
    println(Json.parse(UnsubscribeMessage.serializer(), "{unsubscribe: 456def}"))
    println(Json.parse(UnsubscribeMessage.serializer(), "{unsubscribe: 456def}"))
    println(Json.parse(PublishMessage.serializer(), "{publish: 78.1}"))
    server.start(wait = true)
}
