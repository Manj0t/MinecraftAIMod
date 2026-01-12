import socket
import struct
import time
from PPOTrainer import PPOTrainer
from utils import set_conn, rollout, print_cuda_mem, plot_rewards, test_rollout
import state_pb2
from ActorCritic import *
from ContainerModel import *
import torch
import os
import numpy as np
from CNNDebugger import start_debugging
import sys

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

HOST = '127.0.0.1'

num_envs = int(sys.argv[1])
test = False
try:
    sys.argv[2]
    test = True
except IndexError:
    pass

startPort = 5000

PORTS = [startPort + i for i in range(num_envs)]


curr_best = float('-inf')
ep_rewards = []

env_sockets = []
headers = []

for port in PORTS:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.connect((HOST, port))
    print(f"Connected to env on port {port}")
    env_sockets.append(sock)

    expected = 4 * 4  # 16 bytes
    buffer = b""
    while len(buffer) < expected:
        chunk = sock.recv(expected - len(buffer))
        if not chunk:
            raise ConnectionError("Socket closed before receiving full header")
        buffer += chunk

    agent_info_dim, num_items, num_blocks, num_entities = struct.unpack(">4i", buffer)
    headers.append((agent_info_dim, num_items, num_blocks, num_entities))


# Create model
agent_info_dim = headers[0][0]   # assume same
num_items     = headers[0][1]
num_blocks    = headers[0][2]
num_entities  = headers[0][3]

print(f'agent_info_dim: {agent_info_dim}, num_items: {num_items}, num_blocks: {num_blocks}, num_entities {num_entities}')

set_conn(env_sockets, num_envs)

world_model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities)
world_model.to(DEVICE)

container_model = ContainerModel(agent_info_dim, num_items, num_entities)
container_model.to(DEVICE)

ppo = PPOTrainer(world_model, container_model)

load_path = "imitate/model.pth"
iteration = 0
curr_best = float('-inf')
if os.path.exists(load_path):
    print(f"<(-_-)> Loading checkpoint: {load_path}")
    checkpoint = torch.load(load_path, map_location=DEVICE, weights_only=False)

    model_dict = world_model.state_dict()
    pretrained_dict = checkpoint["world_model"]

    filtered_dict = {}

    for key, params in pretrained_dict.items():
        if key in model_dict and params.shape == model_dict[key].shape:
            filtered_dict[key] = params
        elif key in model_dict:
            with torch.no_grad():
                old_params = params
                new = model_dict[key].clone()

                slices = tuple(slice(0, min(o, n)) for o, n in zip(old_params.shape, new.shape))
                new[slices] = old_params[slices]

                filtered_dict[key] = new


    skipped_keys = set(pretrained_dict.keys()) - set(filtered_dict.keys()) # Should be empty now


    print("-> Loaded:", filtered_dict.keys())
    print("- Skipped:", skipped_keys)

    model_dict.update(filtered_dict)
    world_model.load_state_dict(model_dict, strict=False)
    # container_model.load_state_dict(checkpoint["cont_model"])

    if "optimizer_state_dict" in checkpoint:
        try:
            ppo.optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
            print("> Loaded optimizer state!")


        except Exception as e:
            print("/| Optimizer state incompatible — resetting optimizer.")
            # ppo.policy_optimizer = torch.optim.Adam(ppo.policy_optimizer.param_groups[0]["params"])
            # ppo.value_optimizer = torch.optim.Adam(ppo.value_optimizer.param_groups[0]["params"])
            ppo.optimizer = torch.optim.Adam(ppo.optimizer.param_groups[0]["params"])

    # curr_best = checkpoint['best_reward']
    ep_rewards.append(curr_best)

    print(f"> Loaded model! Best reward: {curr_best}")

else:
    print("- No checkpoint found, training from scratch")


start_debugging()


if test:
    print("Testing model with argmax")
    while True:
        for s in env_sockets:
            s.sendall(struct.pack(">i", 1))
        test_rollout(world_model)

for i in range(iteration, 10000):
    print("Loop ", i)
    for s in env_sockets:
        s.sendall(struct.pack(">i", 1))
    print_cuda_mem("Before Rollout")
    train_data, cont_train_data, ep_reward = rollout(world_model, container_model, ppo, ep_rewards, i, curr_best)
    ep_rewards.append(np.mean(ep_reward))
    print_cuda_mem("After Rollout")

    if ep_rewards[-1] >= curr_best or i % 5 == 0:
        if ep_rewards[-1] >= curr_best:
            curr_best = ep_rewards[-1]
            save_path = f"models/model_best_larger_model{curr_best:.2f}_iter{i}.pth"
        else:
            save_path = f"models/iter_saves/model_itr_larger_model{i}.pth"
        torch.save({
            "model_state_dict": world_model.state_dict(),
            "optimizer_state_dict": ppo.optimizer.state_dict(),
            "rewards": ep_rewards,
            "best_reward": curr_best,
            "iter": i,
        }, save_path)

        print(f" Saved best model → {save_path} with reward {curr_best}")

    plot_rewards(ep_rewards, path=f'model_{i}', window=1)

    flat = lambda x: np.concatenate(x, axis=0)

    num_samples = flat(train_data["BlocksObs"]).shape[0]
    permute_idxs = np.random.permutation(num_samples)

    obs = {
        'Inventory': torch.tensor(flat(train_data['InventoryObs'])[permute_idxs], dtype=torch.float32, device=DEVICE),
        'Blocks': torch.tensor(flat(train_data['BlocksObs'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'Entities': torch.tensor(flat(train_data['EntitiesObs'])[permute_idxs], dtype=torch.float32, device=DEVICE),
        'NearbyItemDrops': torch.tensor(flat(train_data['NearbyItemDropsObs'])[permute_idxs], dtype=torch.float32, device=DEVICE),
        'AgentInfo': torch.tensor(flat(train_data['AgentInfoObs'])[permute_idxs], dtype=torch.float32, device=DEVICE),
        'PrevActions': torch.tensor(flat(train_data['PrevActionsObs'])[permute_idxs], dtype=torch.float32, device=DEVICE),

    }
    #         'ContainerType': torch.tensor(flat(train_data['ContainerType'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    #         'Container': torch.tensor(flat(train_data['Container'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    #         'ContainerMask': torch.tensor(flat(train_data['Container'])[permute_idxs], dtype=torch.float32, device=DEVICE)

    act = {
        'inv_act': torch.tensor(flat(train_data['inv_act'])[permute_idxs], dtype=torch.int64, device=DEVICE),

        'movement': torch.tensor(flat(train_data['movement'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'side_movement': torch.tensor(flat(train_data['side_movement'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'item_use': torch.tensor(flat(train_data['item_use'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'hotbar': torch.tensor(flat(train_data['hotbar'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'pan_cam': torch.tensor(flat(train_data['pan_cam'])[permute_idxs], dtype=torch.int64, device=DEVICE),

        'from_slot': torch.tensor(flat(train_data['from_slot'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'to_slot': torch.tensor(flat(train_data['to_slot'])[permute_idxs], dtype=torch.int64, device=DEVICE),

        'drop_slot': torch.tensor(flat(train_data['drop_slot'])[permute_idxs], dtype=torch.int64, device=DEVICE),
        'drop_all_flag': torch.tensor(flat(train_data['drop_all_flag'])[permute_idxs], dtype=torch.int64, device=DEVICE),

        'craft_item_id': torch.tensor(flat(train_data['drop_all_flag'])[permute_idxs], dtype=torch.int64, device=DEVICE),
    }

    advantages = torch.tensor(flat(train_data['advantage'])[permute_idxs], dtype=torch.float32, device=DEVICE)
    advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)

    summed_log_probs = torch.tensor(flat(train_data['log_prob'])[permute_idxs], dtype=torch.float32, device=DEVICE)

    # Not needed anymore
    # log_probs = {
    #     "old_movement_lp": torch.tensor(flat(train_data['movement_log_prob'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    #     "old_item_use_lp": torch.tensor(flat(train_data['item_use_log_prob'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    #     "old_hotbar_lp": torch.tensor(flat(train_data['hotbar_log_prob'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    #     "old_pan_cam_lp": torch.tensor(flat(train_data['pan_cam_log_prob'])[permute_idxs], dtype=torch.float32, device=DEVICE),
    # }

    returns = torch.tensor(flat(train_data['returns'])[permute_idxs], dtype=torch.float32, device=DEVICE)

    print_cuda_mem("Before Training")

    # old_log_probs not used. temporarily set default value to check
    ppo.train_policy(obs, act, 0, summed_log_probs, advantages, returns)
    # ppo.train_value(obs, returns)
    print('********************************')
    print('****** Completed Training ******')
    print('********************************')
