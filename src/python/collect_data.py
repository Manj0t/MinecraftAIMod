import socket
import struct
import time
from PPOTrainer import PPOTrainer
from utils import set_conn, get_state, is_action_noOp
import utils
import state_pb2
import torch
import os
import numpy as np
import sys

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

HOST = '127.0.0.1'

PORT = 5000

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.connect((HOST, PORT))

set_conn([sock], 1)
print(f"Connected to env on port {PORT}")

data = []
utils.prevActions = torch.zeros(1, 4, dtype=torch.float32).to(DEVICE)
i = 0
while True:
    prev_act_copy = utils.prevActions.clone()

    obs = get_state() # Dictionary of tensors
    obs['PrevActions'] = prev_act_copy

    obs_np = {k: (v.cpu().numpy() if isinstance(v, torch.Tensor) else v) for k, v in obs.items()}
    print(obs_np)
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
    if not is_action_noOp(expertAction):

        action_t = torch.tensor(expertAction, dtype=torch.float32).to(DEVICE)

        data.append({
            "obs" : obs_np,
            "action" : action_t.cpu().numpy(),
        })

        print(expertAction)

        utils.prevActions = action_t.unsqueeze(0)

        i += 1
    if i % 500 == 0:
        print(f"Iteration {i} collected {len(data)} samples")
    if i >= 2048:
        continue_collection = input("Continue? (y/n): ")
        if continue_collection == 'y':
            i = 0
        else:
            sock.sendall(struct.pack(">i", 0))
            break
    sock.sendall(struct.pack(">i", 1))


print("SAVED DATA")
torch.save(data, "expert_data4.pt")