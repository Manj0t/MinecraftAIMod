import socket
import struct

from PPOTrainer import PPOTrainer
from utils import set_conn
import state_pb2
from ActorCritic import *
import torch

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

    data = conn.recv(4 * 4)
    struct.unpack(f">{4}i", data)
    agent_info_dim, num_items, num_blocks, num_entities = data

    model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities)
    model.to(DEVICE)

    ppo = PPOTrainer(model)

























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