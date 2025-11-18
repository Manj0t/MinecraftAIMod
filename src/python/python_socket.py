import socket
import struct
import time
from PPOTrainer import PPOTrainer
from utils import set_conn, rollout, print_cuda_mem, plot_rewards
import state_pb2
from ActorCritic import *
import torch
import os
import numpy as np
from CNNDebugger import start_debugging

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

HOST = '127.0.0.1'
PORT = 5000

curr_best = float('-inf')
ep_rewards = []
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

    load_path = "models/model_best_cnn292.34.pth"

    if os.path.exists(load_path):
        print(f"🔁 Loading checkpoint: {load_path}")
        checkpoint = torch.load(load_path, map_location=DEVICE)

        model_dict = model.state_dict()
        pretrained_dict = checkpoint["model_state_dict"]

        # Filter out weights that don't match shape (like shared_layers.0.weight)
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


        print("✅ Loaded:", filtered_dict.keys())
        print("⚠️   Skipped:", skipped_keys)

        model_dict.update(filtered_dict)
        model.load_state_dict(model_dict, strict=False)

        # with torch.no_grad():
        #     # Nudge bias to prefer no jump
        #     # model.jump_policy.bias.add_(-2.5)
        #     print("⬇️  Applied anti-jump bias")

        if "optimizer_state_dict" in checkpoint:
            try:
                ppo.optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
                print("✅ Loaded optimizer state!")
                # ppo.policy_optimizer.load_state_dict(checkpoint["policy_optimizer_state_dict"])
                # ppo.value_optimizer.load_state_dict(checkpoint["value_optimizer_state_dict"])

                # VERIFY optimizer shapes match
                # for old_state, new_param in zip(
                #         checkpoint["policy_optimizer_state_dict"]["state"].values(),
                #         model.parameters()):
                #     for k, v in old_state.items():
                #         if isinstance(v, torch.Tensor) and v.shape != new_param.shape:
                #             raise RuntimeError("Optimizer state tensor shape mismatch")



            except Exception as e:
                print("⚠️ Optimizer state incompatible — resetting optimizer.")
                # ppo.policy_optimizer = torch.optim.Adam(ppo.policy_optimizer.param_groups[0]["params"])
                # ppo.value_optimizer = torch.optim.Adam(ppo.value_optimizer.param_groups[0]["params"])
                ppo.optimizer = torch.optim.Adam(ppo.optimizer.param_groups[0]["params"])

        # curr_best = checkpoint['best_reward']
        curr_best = float('-inf')
        ep_rewards.append(curr_best)

        print(f"✅ Loaded model! Best reward: {curr_best}")

    else:
        print("⚠️ No checkpoint found, training from scratch")


    start_debugging()


    for i in range(1000):
        print("Loop ", i)
        conn.sendall(struct.pack(">i", 1))
        print_cuda_mem("Before Rollout")
        train_data, ep_reward = rollout(model)
        ep_rewards.append(ep_reward)
        print_cuda_mem("After Rollout")

        if ep_reward >= curr_best:
            curr_best = ep_reward
            save_path = f"models/model_best_cnn{curr_best:.2f}.pth"
            torch.save({
                "model_state_dict": model.state_dict(),
                "optimizer_state_dict": ppo.optimizer.state_dict(),
                "rewards": ep_rewards,
                "best_reward": curr_best,
                "iter": i,
            }, save_path)

            print(f" ✅ Saved best model → {save_path} with reward {curr_best}")

        plot_rewards(ep_rewards, path=f'model_{i}', window=1)

        permute_idxs = np.random.permutation(len(train_data['InventoryObs']))

        # Are already tensors

        obs = {
            'Inventory' : torch.tensor(train_data['InventoryObs'][permute_idxs], dtype=torch.float32, device=DEVICE),
            'Blocks'    : torch.tensor(train_data['BlocksObs'][permute_idxs], dtype=torch.long, device=DEVICE),
            'Entities'  : torch.tensor(train_data['EntitiesObs'][permute_idxs], dtype=torch.float32, device=DEVICE),
            'AgentInfo' : torch.tensor(train_data['AgentInfoObs'][permute_idxs], dtype=torch.float32, device=DEVICE)
        }

        act = {
            'movement'  : torch.tensor(train_data['movement'][permute_idxs], dtype=torch.long, device=DEVICE),
            'jump'      : torch.tensor(train_data['jump'][permute_idxs], dtype=torch.float32, device=DEVICE),
            'item_use'  : torch.tensor(train_data['item_use'][permute_idxs], dtype=torch.long, device=DEVICE),
            'hotbar'    : torch.tensor(train_data['hotbar'][permute_idxs], dtype=torch.long, device=DEVICE),
            'pan_cam'   : torch.tensor(train_data['pan_cam'][permute_idxs], dtype=torch.long, device=DEVICE)
        }

        advantages = torch.tensor(train_data['advantage'][permute_idxs], dtype=torch.float32, device=DEVICE)
        advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)
        log_probs = torch.tensor(train_data['log_prob'][permute_idxs], dtype=torch.float32, device=DEVICE)
        returns = torch.tensor(train_data['returns'][permute_idxs], dtype=torch.float32, device=DEVICE)
        print_cuda_mem("Before Training")

        ppo.train_policy(obs, act, log_probs, advantages, returns)
        # ppo.train_value(obs, returns)
        print('********************************')
        print('****** Completed Training ******')
        print('********************************')
