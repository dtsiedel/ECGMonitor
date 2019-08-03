import websocket

url = "ws://192.168.7.2:8888/ws"

def on_open(ws):
  print("connected")

def on_close(ws):
  print("disconnected")

def on_message(ws, msg):
  print("got {}".format(msg))

def on_error(ws, err):
  print("error: {}".format(err))

ws = websocket.WebSocketApp(url, on_open=on_open, on_close=on_close, on_message=on_message, on_error=on_error)
ws.run_forever()
