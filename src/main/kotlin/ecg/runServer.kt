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

//TODO: It seems like the server hangs when the first websocket to be opened closes. This means other clients never hear about it closing. It seems to only
//      apply to the first websocket ever opened against the server. Very odd behavior, maybe related to the first request to a route being slow?

// Identifies a source with an ID and a name.
@Serializable
data class ClientDescription(val displayName: String, val uuid: String)

// Values in the connectedClients map.
@Serializable
data class ClientEntry(
    val source: ClientDescription,
    val isSource: Boolean,
    val listeners: MutableSet<WebSocketServerSession>
)

// Used by a client to set its name and declare itself as a source. By
// default, any client that connects is not a source. With this, you can
// set your display name and other clients can subscribe.
// name for viewing by other clients.
// Format: {"name": "some display name"}
@Serializable
data class SetNameMessage(val name: String)

// Sent to a client when it registers so that it knows its UUID. The client
// can set its name with this. Not secure since any other client can learn
// this and set its name as well, but since I'm controlling all clients I
// can safely ignore that.
@Serializable
data class ConnectResponse(val uuid: String)

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
val connectedWebsockets = mutableMapOf<WebSocketServerSession, ClientEntry>()

// Send a message to all sources and listeners.
suspend fun publishToAll(message: String) {
    for (session in connectedWebsockets.keys) {
        session.send(Frame.Text(message))
    }
}

// Send a message to all clients telling them what the current list of
// sources is. Should be called whenever that list is changed, so that
// the clients are all up-to-date.
suspend fun pushSources() {
    val sources = SourcesResponse(
	connectedWebsockets.values.filter{ it.isSource }.map{ it.source }.toSet()
    )
    println("Pushing list ${sources}")
    publishToAll(Json.stringify(SourcesResponse.serializer(), sources))
}

// Handle a data message from a client. Publish to all clients that are
// subscribed to it.
suspend fun handlePublish(client: WebSocketServerSession, text: String) {
    Json.parse(PublishMessage.serializer(), text)

    // should never happen but handle anyway
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

// Set this client's display name and make it a source. Then pushSources
// so that the new list is sent out to clients. Throws if the format of
// the message is wrong
suspend fun handleSetName(client: WebSocketServerSession, text: String) {
    val msg = Json.parse(SetNameMessage.serializer(), text)

    val found = connectedWebsockets.get(client)
    if (found == null) { return }

    // set the name and make it a source
    var newEntry = ClientEntry(
        ClientDescription(msg.name, found.source.uuid),
        true,
        found.listeners
    )

    connectedWebsockets.put(client, newEntry)
    pushSources()
}

// Add a new client to the list, with no listeners. Throws a
// kotlinx.serialization.SerializationException if the string format
// is not valid.
suspend fun addClient(client: WebSocketServerSession) {
    // Don't add another entry if the websocket is somehow already in the list
    if (connectedWebsockets.containsKey(client)) {
        return
    }

    val uuid = UUID.randomUUID().toString()
    val entry = ClientEntry(
        // by default both name and ID are the UUID, and it is not a source
        ClientDescription(uuid, uuid),
        false,
        mutableSetOf<WebSocketServerSession>()
    )
    connectedWebsockets.put(client, entry)
    println("Registered ${entry} for ${client}")

    val response = ConnectResponse(uuid)
    val responseText = Json.stringify(ConnectResponse.serializer(), response)
    client.send(Frame.Text(responseText))
    pushSources()
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
                         ::handleSetName,
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
    println("Cleaned client list of ${thisClient}")
    pushSources()
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
            addClient(this)
            try {
                while (true) {
                    val text = (incoming.receive() as Frame.Text).readText()
		    handleWSMessage(text, this)
                }
            } catch (e: Exception) {
               // socket throws an exception when it closes.
               unsubscribeAll(this)
               println("Socket connection closed because of ${e}.")
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
