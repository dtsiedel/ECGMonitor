# Building and Running

```
./gradlew
```

Then you can see the page at `localhost:8888` (it will redirect to the landing
page). Then you can open a websocket by typing in the web console:

```
var ws = new WebSocket();
ws.onmessage = msg => console.log(msg);
```

Send messages with:

```
ws.send("some text");
```

and you should see an echoed response from the server.
