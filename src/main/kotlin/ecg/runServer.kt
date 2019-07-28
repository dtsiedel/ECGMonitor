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

import java.util.UUID

// Identifies a source with an ID and a name.
@Serializable
data class ClientDescription(val displayName: String, val uuid: String)

// Values in the connectedClients map.
@Serializable
data class ClientEntry(val source: ClientDescription,
                       val listeners: MutableSet<WebSocketServerSession>)

// Used by a client to register itself as a source. This will add it as
// a key in the map of possible sources. The server also will assign a
// UUID to the source, and report both the names and UUIDs when a browser
// requests it. There is no "unregister" command - a registed client
// will automatically by unregistered when the websocket connection
// is closed.
// Format: {"register": "some display name"}
@Serializable
data class RegisterMessage(val register: String)

// Used to respond to a RegisterMessage. Contains the UUID that the client
// should use in the future. Note that the UUID is not secret - the other
// clients use it to subscribe.
@Serializable
data class RegisterResponse(val uuid: String)

// Published to tell clients what sources are available. Sent every time a
// new source is registered. Also a new connection is sent one of these
// automatically, so that they can have a starting point.
@Serializable
data class SourcesResponse(val sources: Set<ClientDescription>)

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

// The keys are the connected "source" clients. One is added each time
// a ws client registers itself with the "register" command, and they
// are removed if the client sends the "unregister" command, or when
// that client disconnects. The values are each a list of the clients
// that are waiting for updates. Clients can add or remove themselves
// from that list with "subscribe"/"unsubscribe".
val connectedWebsockets =
	mutableMapOf<WebSocketServerSession, ClientEntry>()

// Handle a data message from a client. Publish to all clients that are
// subscribed to it.
suspend fun handlePublish(client: WebSocketServerSession, text: String) {
    val msg = Json.parse(PublishMessage.serializer(), text)

    if (!(connectedWebsockets.containsKey(client))) { return }

    for (listener in connectedWebsockets.get(client)!!.listeners) {
        try {
            listener.send(Frame.Text(text))
        } catch (e: java.io.IOException) {
            println("Failed to send to ${listener}")
        } catch (e: Exception) {
            println("Failed to send to ${listener}")
        }
    }
}

// Handle a websocket message that registers a new source. Throws a
// kotlinx.serialization.SerializationException if the string format
// is not valid. Idempotent, so the same client cannot register iteself
// multiple times.
suspend fun handleRegister(client: WebSocketServerSession, text: String) {
    val msg = Json.parse(RegisterMessage.serializer(), text)

    // Don't add another entry if the websocket registers itself again.
    if (connectedWebsockets.containsKey(client)) {
        return
    }

    val uuid = UUID.randomUUID().toString()
    val entry = ClientEntry(
        ClientDescription(msg.register, uuid),
        mutableSetOf<WebSocketServerSession>()
    )
    connectedWebsockets.put(client, entry)
    println("Registered ${entry} for ${client}")

    val response = RegisterResponse(uuid)
    val responseText = Json.stringify(RegisterResponse.serializer(), response)
    client.send(Frame.Text(responseText))
}

// Handle a websocket message that subscribes a client to another one
// by its UUID. Throws if the string format is not valid. Idempotent,
// so it doesn't matter if you're already subscribed. Is a no-op if
// the target UUID does not exist.
suspend fun handleSubscribe(client: WebSocketServerSession, text: String) {
    val msg = Json.parse(SubscribeMessage.serializer(), text)
    val targetUUID = msg.subscribe

    var found: ClientEntry? = null
    for ((_, entry) in connectedWebsockets) {
        if (entry.source.uuid == targetUUID) {
            found = entry
        }
    }
    if (found == null) { return }

    found.listeners.add(client)
    println("Subscribed ${client} to ${found}")
}


// Handle a websocket message that unsubscribes a client from another one
// by its UUID. Throws if the string format is not valid. Idempotent, so
// it doesn't matter if you are already subscribed or not.
suspend fun handleUnsubscribe(client: WebSocketServerSession, text: String) {
    val msg = Json.parse(UnsubscribeMessage.serializer(), text)
    val targetUUID = msg.unsubscribe

    var found: ClientEntry? = null
    for ((_, entry) in connectedWebsockets) {
        if (entry.source.uuid == targetUUID) {
            found = entry
        }
    }
    if (found == null) { return }

    found.listeners.remove(client)
    println("Unsubscribed ${client} from ${found}")
}

val handlerList = listOf(::handlePublish,
                         ::handleSubscribe,
                         ::handleRegister,
                         ::handleUnsubscribe)

// Called on each websocket message. Parses out the type of command,
// and handles it appropriately. We can't know the type of the message
// without trying to parse it, so each handler also tries to parse the
// message, and throws if it fails.
suspend fun handleWSMessage(text: String, source: WebSocketServerSession) {
    println("Received ${text} from ${source}")
    for (handler in handlerList) {
        try {
            handler(source, text)
            return //only one valid handler per message, return on success
        } catch (e: SerializationException) {
            println("Could not handle with ${handler}")
            continue
        }
    }
}

// Called when a websocket connection closes. Removes this from the
// connectedWebsockets list if it is in it. Removes this from the
// listener list of all clients
suspend fun unsubscribeAll(thisClient: WebSocketServerSession) {
    for ((client, entry) in connectedWebsockets) {
        if (client == thisClient) {
            connectedWebsockets.remove(thisClient)
        }
        if (thisClient in entry.listeners) {
            entry.listeners.remove(thisClient)
        }
    }
    println("Cleaned client list to ${connectedWebsockets}")
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
            try {
                while (true) {
                    val text = (incoming.receive() as Frame.Text).readText()
		    handleWSMessage(text, this)
                }
            } catch (e: Exception) {
               println("Socket connection closed.")
               unsubscribeAll(this)
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
