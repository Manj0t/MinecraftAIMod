import matplotlib.pyplot as plt
import numpy as np
import torch
from torch.distributions import Categorical, Bernoulli
import os

import socket
import struct
import state_pb2

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')

# def discount_rewards(rewards, gamma=0.99):
#     reward_t = [float(rewards[-1])]
#     for t in reversed(range(len(rewards) - 1)):
#         reward_t.append(rewards[t] + gamma * reward_t[-1])
#
#     return np.array(reward_t[::-1])

conn = None

def set_conn(connection):
    global conn
    conn = connection


def get_state():
    size_bytes = conn.recv(4)
    if not size_bytes:
        print("Java closed connection, exiting loop.")
        return

    size = struct.unpack(">i", size_bytes)[0]
    buffer = bytearray()
    while len(buffer) < size:
        chunk = conn.recv(size - len(buffer))
        if not chunk: break
        buffer.extend(chunk)

    if len(buffer) != size:
        print("Didn't receive exactly ", len(buffer), "bytes")
        print("Shutting down...")

    state = state_pb2.State()
    state.ParseFromString(buffer)

    agentInfo = [
        value for value in state.agentInfo
    ]

    agentInventory = [
        [value for value in row.values] for row in state.inventory.rows
    ]

    nearbyEntities = [
        [value for value in row.values] for row in state.nearbyEntities.rows
    ]

    nearbyBlocks = [
        [value for value in row.values] for row in state.nearbyBlocks.rows
    ]

    agentInfo_t = torch.tensor(agentInfo, dtype=torch.float32).to(DEVICE)
    agentInventory_t = torch.tensor(agentInventory, dtype=torch.float32).to(DEVICE)
    nearbyEntities_t = torch.tensor(nearbyEntities, dtype=torch.float32).to(DEVICE)
    nearbyBlocks_t = torch.tensor(nearbyBlocks, dtype=torch.float32).to(DEVICE)

    obs = {
        'Inventory': agentInventory_t,
        'Blocks' : nearbyBlocks_t,
        'Entities' : nearbyEntities_t,
        'AgentInfo' : agentInfo_t
    }

    return obs


def take_step(action, max_steps, i):
    out_action = state_pb2.Action()
    out_action.actions.extend(action)
    out = out_action.SerializeToString()

    conn.sendall(struct.pack(">i", len(out)))
    conn.sendall(out)

    reward = struct.unpack(">f", conn.recv(4))[0]
    done = struct.unpack(">i", conn.recv(4))[0]

    # Ending rollout
    if i == max_steps - 1:
        conn.sendall(struct.pack(">i", 0))
        done = 1
        return None, float(reward), int(done)
    # Continue Rollout
    else:
        conn.sendall(struct.pack(">i", 1))
    obs = get_state()

    return obs, float(reward), int(done)


def compute_gaes(rewards, values, dones, gamma=0.99, lam=0.95):
    T = len(rewards)
    advantages = np.zeros(T)
    gae = 0
    for t in reversed(range(T)):
        next_value = values[t + 1] if t < T - 1 else 0
        delta = rewards[t] + gamma * next_value * (1 - dones[t]) - values[t] # If done, no reward
        advantages[t] = delta + (gamma * lam) * (1 - dones[t]) * gae

        gae = delta + gamma * lam * (1 - dones[t]) * gae
        advantages[t] = gae

    returns = advantages + values
    return advantages, returns


def rollout(model, max_steps=2048):
    rollout_buffer = {
        "InventoryObs": [],
        "BlocksObs": [],
        "EntitiesObs": [],
        "AgentInfoObs": [],
        "movement": [],
        "jump": [],
        "item_use": [],
        "hotbar": [],
        "pan_cam": [],
        "reward": [],
        "value": [],
        "log_prob": [],
        "done": []
    }  # obs, act, reward, value, act_log_prob, dones


    obs = get_state() # Should return a tensor for obs
    print(max_steps)
    ep_reward = 0
    for i in range(max_steps):
        if i % 500 == 0:
            print(i)
        with torch.no_grad():
            logits_dict, value = model(obs)

        logits_dict['jump'] = logits_dict['jump'].squeeze(-1)

        movement_dist = Categorical(logits=logits_dict['movement'])
        jump_dist = Bernoulli(logits=logits_dict['jump'])
        item_use_dist = Categorical(logits=logits_dict['item_use'])
        hotbar_dist = Categorical(logits=logits_dict['hotbar'])
        pan_cam_dist = Categorical(logits=logits_dict['pan_camera'])

        movement_act = movement_dist.sample()
        jump_act = jump_dist.sample()
        item_use_act = item_use_dist.sample()
        hotbar_act = hotbar_dist.sample()
        pan_cam_act = pan_cam_dist.sample()

        movement_log_prob = movement_dist.log_prob(movement_act)
        jump_log_prob = jump_dist.log_prob(jump_act)
        item_use_log_prob = item_use_dist.log_prob(item_use_act)
        hotbar_log_prob = hotbar_dist.log_prob(hotbar_act)
        pan_cam_log_prob = pan_cam_dist.log_prob(pan_cam_act)

        act_log_prob = movement_log_prob + jump_log_prob + item_use_log_prob + hotbar_log_prob + pan_cam_log_prob

        act_log_prob = act_log_prob.detach().cpu().item()
        value = value.detach().cpu().squeeze().item()

        # logits_np = {
        #     "movement": logits_dict["movement"].detach().cpu().numpy(),
        #     "jump": logits_dict["jump"].detach().cpu().numpy(),
        #     "item_use": logits_dict["item_use"].detach().cpu().numpy(),
        #     "hotbar": logits_dict["hotbar"].detach().cpu().numpy(),
        # }
        #
        # rollout_buffer["obs"].append({
        #     'Inventory' : obs['Inventory'].clone(),
        #     'Blocks'    : obs['Blocks'].clone(),
        #     'Entities'  : obs['Entities'].clone(),
        #     'AgentInfo' : obs['AgentInfo'].clone()
        # })

        rollout_buffer['InventoryObs'].append(obs['Inventory'].detach().cpu())
        rollout_buffer['BlocksObs'].append(obs['Blocks'].detach().cpu())
        rollout_buffer['EntitiesObs'].append(obs['Entities'].detach().cpu())
        rollout_buffer['AgentInfoObs'].append(obs['AgentInfo'].detach().cpu())
        rollout_buffer["movement"].append(movement_act.detach().cpu())
        rollout_buffer["jump"].append(jump_act.detach().cpu())
        rollout_buffer["item_use"].append(item_use_act.detach().cpu())
        rollout_buffer["hotbar"].append(hotbar_act.detach().cpu())
        rollout_buffer["pan_cam"].append(pan_cam_act.detach().cpu())
        rollout_buffer["value"].append(value)
        rollout_buffer["log_prob"].append(act_log_prob)

        movement = int(movement_act.item())
        jump = int(jump_act.item())
        item_use = int(item_use_act.item())
        hotbar = int(hotbar_act.item())
        pan_cam = int(pan_cam_act.item())

        action = [movement, jump, item_use, hotbar, pan_cam]

        next_obs, reward, done = take_step(action, max_steps, i)

        rollout_buffer["reward"].append(reward)
        rollout_buffer["done"].append(done)

        obs = next_obs
        ep_reward += reward

        if done:
            break

    # logits is dict, won't work
    for key in ["InventoryObs", "BlocksObs", "EntitiesObs", "AgentInfoObs", "movement", "jump", "item_use", "hotbar", "pan_cam", "log_prob", "value", "reward", "done"]:
        rollout_buffer[key] = np.array(rollout_buffer[key], dtype=np.float32)

    advantages, returns = compute_gaes(rollout_buffer['reward'], rollout_buffer['value'], rollout_buffer['done'])

    rollout_buffer['advantage'] = advantages
    rollout_buffer['returns'] = returns

    return rollout_buffer, ep_reward


def print_cuda_mem(tag=""):
    if torch.cuda.is_available():
        allocated = torch.cuda.memory_allocated() / (1024**2)
        reserved  = torch.cuda.memory_reserved() / (1024**2)
        print(f"[{tag}] CUDA Memory: allocated={allocated:.2f} MB, reserved={reserved:.2f} MB")
    else:
        print("No CUDA device available.")

def plot_rewards(ep_rewards, window=20, path='graph'):
    plt.figure(figsize=(10, 5))

    # Moving average for smoothing
    rewards_moving_avg = np.convolve(ep_rewards, np.ones(window) / window, mode="valid")

    plt.plot(ep_rewards, label="Episode Reward", alpha=0.4)
    plt.plot(range(window - 1, len(ep_rewards)), rewards_moving_avg, label=f"Moving Avg (window={window})", linewidth=2)

    plt.xlabel("Episode")
    plt.ylabel("Reward")
    plt.title("Training Progress")
    plt.legend()
    plt.grid(True)
    os.makedirs("graphs", exist_ok=True)
    plt.savefig(f'graphs/{path}.png',dpi=300, bbox_inches='tight')