import socket
import struct
from env.env_client import EnvClient
from env import state_pb2
import torch
import numpy as np
import sys
import threading

from config import DEVICE

COLLECT = True
def start_collection_thread():
    threading.Thread(target=debug_input_listener, daemon=True).start()

def debug_input_listener():
    global COLLECT
    print("-> Data Collection Listener listening... Enter 's' to stop collection.")
    while True:
        user_input = sys.stdin.readline().strip()
        if user_input.lower() == "s":
            COLLECT = False

HOST = '127.0.0.1'

PORT = 5000

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.connect((HOST, PORT))

env_client = EnvClient(1, [sock])
print(f"Connected to env on port {PORT}")

worldData = []
contData = []
i = 0

start_collection_thread()

while COLLECT:
    prev_act_copy = env_client.prev_actions.clone()

    obs = env_client.get_state() # Dictionary of tensors
    obs['PrevActions'] = prev_act_copy

    obs_np = {k: (v.cpu().numpy() if isinstance(v, torch.Tensor) else v) for k, v in obs.items()}
    sock.sendall(struct.pack(">i", 1))  # Let java know to continue and python is ready

    size_bytes = sock.recv(4)
    if not size_bytes:
        print("Java closed connection, exiting loop.")
        exit(0)

    size = struct.unpack(">i", size_bytes)[0]
    buffer = bytearray()
    while len(buffer) < size:
        chunk = sock.recv(size - len(buffer))
        if not chunk: break
        buffer.extend(chunk)

    if len(buffer) != size:
        print("Didn't receive exactly ", len(buffer), "bytes")
        print("Shutting down...")

    action = state_pb2.Action()
    action.ParseFromString(buffer)

    expertAction = list(action.actions)
    if not env_client.is_action_noOp(expertAction):

        action_t = torch.tensor(expertAction, dtype=torch.float32).to(DEVICE)
        if len(action_t) == 3:
            print('cont action')
            contData.append({
                "obs" : obs_np,
                "action" : action_t.cpu().numpy(),
            })
        else:
            worldData.append({
                "obs" : obs_np,
                "action" : action_t.cpu().numpy(),
            })

            env_client.prev_actions = action_t.unsqueeze(0)
        print(expertAction)
        i += 1
    if i % 500 == 0:
        print(f"Iteration {i} collected {len(worldData)} samples")
    # if i >= 2048:
    #     continue_collection = input("Continue? (y/n): ")
    #     if continue_collection == 'y':
    #         i = 0
    #     else:
    #         sock.sendall(struct.pack(">i", 0))
    #         break
    sock.sendall(struct.pack(">i", 1))


print("SAVED DATA")
torch.save(worldData, "world_expert_data10.pt")
torch.save(contData, "cont_expert_data7.pt")