import json
import os
import queue
import random
import signal
import threading
import time
import websocket

#TODO: Add a commandline flag to indicate whether to read the data from the analog in, or
#      from a file (and the sleep duration between publishes for the latter). Make it a
#      parameter into gather_data, so it can split into one behavior or the other

outgoing = queue.Queue()
thread_sigil = threading.Event()
process_done = threading.Event()
gather_done = threading.Event()

# Called from the send_thread. Loops forever, looking for data from the outgoing queue. When it
# finds data, it encodes it as a JSON string then sends it out in a publish message.
def process_send_queue(ws, q):
  while not thread_sigil.isSet():
    next = q.get(timeout=2)
    if next is not None:
      try:
        ws.send(json.dumps(next))
      except websocket._exceptions.WebSocketConnectionClosedException:
        break # closes the thread, since loop and function end
  print("Process thread finished")
  process_done.set()

# Called from the gather_thread. Reads data in a loop from the selected input source, and puts
# each reading into the outgoing queue to be sent out.
def gather_data(ws):
  data_points = 5
  for d in range(data_points):
    if thread_sigil.isSet():
      break
    time.sleep(1)
    print("Sending data {}".format(random.randint(0, 100)))
    outgoing.put(d)
  print("Gather thread finished")
  gather_done.set()
  do_shutdown(ws)

url = "ws://192.168.7.2:8888/ws"

def on_open(ws):
  print("Connected to server.")
  name = "Heartbeat{}".format(random.randint(0, 100))
  payload = json.dumps({"name":name})
  print("Acknowledging with with {}".format(payload))
  ws.send(payload)
  send_thread = threading.Thread(target=process_send_queue, args=(ws, outgoing,))
  send_thread.start()
  gather_thread = threading.Thread(target=gather_data, args=(ws,))
  gather_thread.start()

def on_close(ws):
  print("Disconnected.")

def on_message(ws, msg):
  msg_json = json.loads(msg)
  print("Got {} from server.".format(msg_json))

def on_error(ws, err):
  print("Websocket Error: {}".format(err))

def do_shutdown(ws):
  print("Shutting Down {}".format(ws))
  thread_sigil.set()
  ws.close()

  waitCount = 0
  while True:
    if (gather_done.is_set() and process_done.is_set()) or waitCount > 5:
      # You cannot sys.exit from a thread, since exit waits for the thread to return
      # and you basically deadlock yourself. This exit doesn't do any cleanup, so
      # make sure that everything is cleaned up before calling it.
      os._exit(0)
    time.sleep(1)
    waitCount += 1

ws = websocket.WebSocketApp(url, on_open=on_open, on_close=on_close, on_message=on_message, on_error=on_error)
signal.signal(signal.SIGINT, lambda a,b : do_shutdown(ws))
ws.run_forever()
signal.pause()
