import json
import queue
import random
import threading
import websocket

#TODO: Use a threading.Event as the loop variable instead of True. Have .get() use a timeout, and check for None before
#      sending anything. When Ctrl+c is received, set the event so the loop will end.

outgoing = queue.Queue()

# called from the send_thread. Loops forever, looking for data from the outgoing queue. When it
# finds data, it encodes it as a JSON string then sends it out in a publish message.
def process_send_queue(ws, q):
  print("q is {}".format(q))
  while True:
    next = q.get()
    ws.send(json.dumps(next))

url = "ws://192.168.7.2:8888/ws"

def on_open(ws):
  print("connected to server")
  name = "Heartbeat{}".format(6)
  print("Made name {}".format(name))
  payload = json.dumps({"name":name})
  print("Responding with {}".format(payload))
  ws.send(payload)
  send_thread = threading.Thread(target=process_send_queue, args=(ws, outgoing,))
  send_thread.start()
  outgoing.put({"publish": 45})

def on_close(ws):
  print("disconnected")

def on_message(ws, msg):
  print("got {}".format(msg))

def on_error(ws, err):
  print("error: {}".format(err))

ws = websocket.WebSocketApp(url, on_open=on_open, on_close=on_close, on_message=on_message, on_error=on_error)
ws.run_forever()
