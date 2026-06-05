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

proxyWalkData = []
i = 0

start_collection_thread()

while COLLECT:
    # Grab observation dictionary from the environment client
    obs = env_client.get_state()


    # Convert precisely what ActorCritic needs into NumPy format
    blocks_np = obs['Blocks'].cpu().numpy() if isinstance(obs['Blocks'], torch.Tensor) else obs['Blocks']
    agent_info_np = obs['AgentInfo'].cpu().numpy() if isinstance(obs['AgentInfo'], torch.Tensor) else obs['AgentInfo']

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

    # Pull out the target direction labels populated by Java's getDirectionLabels()
    if hasattr(env_client, 'raw_state') and env_client.raw_state:
        is_walkable_targets = np.array(list(env_client.raw_state.isWalkable), dtype=np.float32)
    else:
        is_walkable_targets = np.array(obs.get('IsWalkable', [0.0, 0.0, 0.0, 0.0]), dtype=np.float32)

    # Appending directly without checking if action is a noOp
    proxyWalkData.append({
        "obs": {
            "Blocks": blocks_np,
            "AgentInfo": agent_info_np
        },
        "is_walkable_target": is_walkable_targets  # The true y values [Forward, Backward, Left, Right]
    })

    i += 1
    if i % 500 == 0 or i % 2048 == 0:
        print(f"Iteration {i} collected {len(proxyWalkData)} proxy samples")

    sock.sendall(struct.pack(">i", 1))


print(f"SAVED {len(proxyWalkData)} PROXY SAMPLES")
torch.save(proxyWalkData, "data/proxy_walkable_data.pt")