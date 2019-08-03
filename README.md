# Building and Running

## The Webserver
```
cd webserver
./gradlew
```

Then you can see the page at `localhost:8888` (it will redirect to the landing
page). Then you can open a websocket by typing in the web console:

```
var ws = new WebSocket("ws://localhost:8888/ws");
ws.onmessage = msg => console.log(msg);
```

Then, you can recieve messages from the webserver as others connect, send
your own messages, etc.

## The ECG Monitor
```
cd python_client
pip3 install -r requirements.txt
python3 gather_ecg.py # python 3.4 or greater
```

This code expects to run on a BeagleBone, connected to a serial output from
a heart monitor device like the AD8232. If it's not running on that device
it will fail in some way (be unable to include the libraries, not read any
data, etc.). If you want to adapt this to use other hardware you could do
so by swapping the serial libraries to ones that your platform can use.
