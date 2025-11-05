import socket
import struct

from PPOTrainer import PPOTrainer
from utils import set_conn, rollout
import state_pb2
from ActorCritic import *
import torch

import numpy as np

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

HOST = '127.0.0.1'
PORT = 5000

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen()
    print("Python Server ready...")

    conn, addr = s.accept()

    set_conn(conn)

    expected = 4 * 4  # 16 bytes
    buffer = b""
    while len(buffer) < expected:
        chunk = conn.recv(expected - len(buffer))
        if not chunk:
            raise ConnectionError("Socket closed before receiving full header")
        buffer += chunk

    # ✅ Now unpack correctly
    agent_info_dim, num_items, num_blocks, num_entities = struct.unpack(">4i", buffer)


    # print('agent_info_dim: ', agent_info_dim)
    # print('num_items: ', num_items)
    # print('num_blocks: ', num_blocks)
    # print('num_entities: ', num_entities)

    model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities)
    model.to(DEVICE)

    ppo = PPOTrainer(model)

    while True:
        train_data, ep_reward = rollout(model)

        permute_idxs = np.random.permutation(len(train_data['InventoryObs']))

        # Are already tensors
        # print(f'InventoryObs: {train_data["InventoryObs"]}')

        obs = {
            'Inventory' : torch.tensor(train_data['InventoryObs'][permute_idxs], dtype=torch.long, device=DEVICE),
            'Blocks'    : torch.tensor(train_data['BlocksObs'][permute_idxs], dtype=torch.long, device=DEVICE),
            'Entities'  : torch.tensor(train_data['EntitiesObs'][permute_idxs], dtype=torch.long, device=DEVICE),
            'AgentInfo' : torch.tensor(train_data['AgentInfoObs'][permute_idxs], dtype=torch.long, device=DEVICE)
        }

        print(f'obs: {obs}')

        act = {
            'movement'  : torch.tensor(train_data['movement'][permute_idxs], dtype=torch.long, device=DEVICE),
            'jump'      : torch.tensor(train_data['jump'][permute_idxs], dtype=torch.long, device=DEVICE),
            'item_use'  : torch.tensor(train_data['item_use'][permute_idxs], dtype=torch.long, device=DEVICE),
            'hotbar'    : torch.tensor(train_data['hotbar'][permute_idxs], dtype=torch.long, device=DEVICE)
        }

        advantages = torch.tensor(train_data['advantage'][permute_idxs], dtype=torch.float32, device=DEVICE)
        advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)
        log_probs = torch.tensor(train_data['log_prob'][permute_idxs], dtype=torch.float32, device=DEVICE)
        returns = torch.tensor(train_data['returns'][permute_idxs], dtype=torch.float32, device=DEVICE)

        ppo.train_policy(obs, act, log_probs, advantages)
        ppo.train_value(obs, returns)

























    # with conn:
    #     print("Connected by", addr)
    #     i = 0
    #     while True:
    #         size_bytes = conn.recv(4)
    #         if not size_bytes:
    #             print("Java closed connection, exiting loop.")
    #             break
    #
    #         size = struct.unpack(">i", size_bytes)[0]
    #
    #         print("Received ", size, "bytes")
    #
    #         buffer = bytearray()
    #         while len(buffer) < size:
    #             chunk = conn.recv(size - len(buffer))
    #             if not chunk: break
    #             buffer.extend(chunk)
    #
    #         if len(buffer) != size:
    #             print("Didn't receive exactly ", len(buffer), "bytes")
    #             print("Shutting down...")
    #
    #         state = state_pb2.State()
    #         state.ParseFromString(buffer)
    #
    #         action = state_pb2.Action()
    #
    #         agentInfo = [
    #             value for value in state.agentInfo
    #         ]
    #
    #         agentInventory = [
    #             [ value for value in row.values ] for row in state.inventory.rows
    #         ]
    #
    #         nearbyEntities = [
    #             [ value for value in row.values ] for row in state.nearbyEntities.rows
    #         ]
    #
    #         nearbyBlocks = [
    #             [ value for value in row.values ] for row in state.nearbyBlocks.rows
    #         ]
    #
    #         print("Agent Info: ", agentInfo)
    #         print()
    #         print("Agent Inventory: ", agentInventory)
    #         print()
    #         print("Nearby Entities: ", nearbyEntities)
    #         print()
    #         print("Nearby Blocks: ", nearbyBlocks)
    #         print()
    #
    #
    #         action.actions.extend([0.5, 0, 1, 0, 0.3])
    #         out = action.SerializeToString()
    #
    #         conn.sendall(struct.pack(">i", len(out)))
    #         conn.sendall(out)
    #
    #         if i >= 100:
    #             print("Thats enough, ending connection...")
    #             break
    #
    #         i += 1