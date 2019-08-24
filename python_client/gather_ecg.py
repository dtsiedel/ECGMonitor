import argparse
import json
import os
import queue
import random
import signal
import threading
import time
import websocket

outgoing = queue.Queue()
thread_sigil = threading.Event()
process_done = threading.Event()
gather_done = threading.Event()

url = "ws://192.168.7.2:8888/ws"

# Publish the given data point d by adding it to the publish queue
def publish(d):
  print("Sending data {}".format(d))
  outgoing.put(d)

# Called from the send_thread. Loops forever, looking for data from the outgoing queue. When it
# finds data, it encodes it as a JSON string then sends it out in a publish message.
def process_send_queue(ws, q):
  while not thread_sigil.isSet():
    try:
      next = q.get(timeout=2)
    except queue.Empty:
      continue
    if next is not None:
      try:
        ws.send(json.dumps({"publish": next}))
      except websocket._exceptions.WebSocketConnectionClosedException:
        break # closes the thread, since loop and function end
  print("Process thread finished")
  process_done.set()

# Called from the gather_thread. Reads data in a loop from the selected input source, and puts
# each reading into the outgoing queue to be sent out.
def gather_data(ws, args):
  if args.file is None:
    while True:
      d = random.randint(0, 100)
      if thread_sigil.is_set():
        break
      time.sleep(0.25)
      publish(d)
  else:
    try:
      with open(args.file, 'r') as in_file:
        data_points = [l.rstrip() for l in in_file.readlines()]
        for d in data_points:
          if thread_sigil.is_set():
            break
          time.sleep(1)
          publish(d)
    except Exception as e:
      print("Could not open input file: {}".format(e))
  print("Gather thread finished")
  gather_done.set()
  do_shutdown(ws)

def on_open(ws, args):
  print("Connected to server.")
  name = "HeartbeatSensor{}".format(random.randint(0, 100))
  payload = json.dumps({"name":name})
  print("Acknowledging with {}".format(payload))
  ws.send(payload)
  send_thread = threading.Thread(target=process_send_queue, args=(ws, outgoing,))
  send_thread.start()
  gather_thread = threading.Thread(target=gather_data, args=(ws, args))
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

parser = argparse.ArgumentParser(description='Read heartbeat data and send it out.')
parser.add_argument('-f', '--file',
                    help='An input file to read from, if requested. Otherwise read from the analog input.')
args = parser.parse_args()

ws = websocket.WebSocketApp(url,
			    on_open=lambda ws: on_open(ws, args),
			    on_close=on_close,
			    on_message=on_message,
			    on_error=on_error)
signal.signal(signal.SIGINT, lambda a,b : do_shutdown(ws))
ws.run_forever()
signal.pause()
