import matplotlib.pyplot as plt
import numpy as np
import torch
from torch.distributions import Categorical, Bernoulli
import os

import socket
import struct
import state_pb2

import threading

from CNNDebugger import debug_cnn



DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')
prevActions = None
# def discount_rewards(rewards, gamma=0.99):
#     reward_t = [float(rewards[-1])]
#     for t in reversed(range(len(rewards) - 1)):
#         reward_t.append(rewards[t] + gamma * reward_t[-1])
#
#     return np.array(reward_t[::-1])

conns = None
num_envs = None

def set_conn(connections, number_of_connections):
    global conns
    global num_envs
    conns = connections
    num_envs = number_of_connections


def get_state():
    obs = {
        'Inventory': [],
        'Blocks': [],
        'Entities': [],
        'AgentInfo': [],
        'PrevActions': []
    }
    for conn in conns:
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
            [
                [value for value in row.values] for row in matrix.rows
            ] for matrix in state.nearbyBlocks.matrix
        ]

        agentInfo_t = torch.tensor(agentInfo, dtype=torch.float32).to(DEVICE)
        agentInventory_t = torch.tensor(agentInventory, dtype=torch.float32).to(DEVICE)
        nearbyEntities_t = torch.tensor(nearbyEntities, dtype=torch.float32).to(DEVICE)
        nearbyBlocks_t = torch.tensor(nearbyBlocks, dtype=torch.int64).to(DEVICE)

        obs['Inventory'].append(agentInventory_t)
        obs['Blocks'].append(nearbyBlocks_t)
        obs['Entities'].append(nearbyEntities_t)
        obs['AgentInfo'].append(agentInfo_t)

    for key in ['Inventory', 'Entities', 'AgentInfo']:
        obs[key] = torch.stack(obs[key], dim=0).to(DEVICE)
    # obs['AgentInfo'] = torch.stack(obs['AgentInfo'], dim=0).to(DEVICE)
    obs['Blocks'] = torch.stack(obs['Blocks'], dim=0).to(DEVICE)
    obs['PrevActions'] = prevActions

    return obs


def take_step(actions, max_steps, itr):
    global prevActions
    prevActions = torch.tensor(actions, dtype=torch.float32, device=DEVICE)  # (num_envs,4)

    rewards, dones = [], []

    # send actions
    for env_idx, conn in enumerate(conns):
        out_action = state_pb2.Action()
        out_action.actions.extend(actions[env_idx])
        out = out_action.SerializeToString()
        conn.sendall(struct.pack(">i", len(out)))
        conn.sendall(out)

    # recv reward/done
    for env_idx, conn in enumerate(conns):
        reward = struct.unpack(">f", conn.recv(4))[0]
        done   = struct.unpack(">i", conn.recv(4))[0]
        rewards.append(float(reward))
        dones.append(int(done))

        if done:
            prevActions[env_idx].zero_()  # reset ONLY that env row

    # send continue/stop
    cont_flag = 0 if itr == max_steps - 1 else 1
    for conn in conns:
        conn.sendall(struct.pack(">i", cont_flag))

    # get next obs once
    next_obs = None if cont_flag == 0 else get_state()
    return next_obs, rewards, dones

def take_step_test(actions, max_steps, itr):
    global prevActions
    prevActions = torch.tensor(actions, dtype=torch.float32, device=DEVICE)  # (num_envs,4)

    rewards, dones = [], []

    # send actions
    for env_idx, conn in enumerate(conns):
        out_action = state_pb2.Action()
        out_action.actions.extend(actions)
        out = out_action.SerializeToString()
        conn.sendall(struct.pack(">i", len(out)))
        conn.sendall(out)

    # recv reward/done
    for env_idx, conn in enumerate(conns):
        reward = struct.unpack(">f", conn.recv(4))[0]
        done   = struct.unpack(">i", conn.recv(4))[0]
        rewards.append(float(reward))
        dones.append(int(done))

        if done:
            prevActions[env_idx].zero_()  # reset ONLY that env row

    # send continue/stop
    cont_flag = 0 if itr == max_steps - 1 else 1
    for conn in conns:
        conn.sendall(struct.pack(">i", cont_flag))

    # get next obs once
    next_obs = None if cont_flag == 0 else get_state()
    return next_obs, rewards, dones

def compute_gaes(rewards, values, dones, gamma=0.99, lam=0.95):
    rewards = np.asarray(rewards, dtype=np.float32)
    values  = np.asarray(values, dtype=np.float32)
    dones   = np.asarray(dones, dtype=np.float32)

    # If single env, make it (E,T)
    if rewards.ndim == 1:
        rewards = rewards[None, :]
        values  = values[None, :]
        dones   = dones[None, :]

    # stored as (E,T) convert to (T,E)
    if rewards.shape[0] == num_envs:
        rewards = rewards.T
        values  = values.T
        dones   = dones.T

    T, E = rewards.shape
    advantages = np.zeros((T, E), dtype=np.float32)
    returns    = np.zeros((T, E), dtype=np.float32)

    for e in range(E):
        gae = 0.0
        for t in reversed(range(T)):
            next_value = values[t+1, e] if t < T-1 else 0.0
            delta = rewards[t, e] + gamma * next_value * (1 - dones[t, e]) - values[t, e]
            gae = delta + gamma * lam * (1 - dones[t, e]) * gae
            advantages[t, e] = gae
        returns[:, e] = advantages[:, e] + values[:, e]

    return advantages, returns

def test_rollout(model, max_steps=2048):
    global prevActions

    prevActions = torch.zeros(num_envs, 4, dtype=torch.float32).to(DEVICE)
    obs = get_state()  # Should return a tensor for obs

    for i in range(max_steps):
        if i % 500 == 0:
            print(i)
        with torch.no_grad():
            logits_dict, value = model(obs)


        # logits_dict['jump'] = logits_dict['jump'].squeeze(-1)

        movement_dist = Categorical(logits=logits_dict['movement'])
        item_use_dist = Categorical(logits=logits_dict['item_use'])
        hotbar_dist = Categorical(logits=logits_dict['hotbar'])
        pan_cam_dist = Categorical(logits=logits_dict['pan_camera'])

        # jump_logits = logits_dict['jump']
        # print("Jump logits mean:", jump_logits.mean().item())
        # print("Jump prob mean:", torch.sigmoid(jump_logits).mean().item())

        movement_act = torch.argmax(logits_dict['movement'])
        item_use_act = item_use_dist.sample()
        hotbar_act = hotbar_dist.sample()
        pan_cam_act = torch.argmax(logits_dict['pan_camera'])


        action = [movement_act, 0, 0, pan_cam_act]

        next_obs, reward, done = take_step_test(action, max_steps, i)

        obs = next_obs


def rollout(model, ppo, ep_rewards, ppo_iter, curr_best, max_steps=2048):
    global prevActions
    rollout_buffer = {
        "InventoryObs": [[] for _ in range(num_envs)],
        "BlocksObs": [[] for _ in range(num_envs)],
        "EntitiesObs": [[] for _ in range(num_envs)],
        "AgentInfoObs": [[] for _ in range(num_envs)],
        "PrevActionsObs" : [[] for _ in range(num_envs)],
        "movement": [[] for _ in range(num_envs)],
        # "jump": [[] * num_envs],
        "item_use": [[] for _ in range(num_envs)],
        "hotbar": [[] for _ in range(num_envs)],
        "pan_cam": [[] for _ in range(num_envs)],
        "reward": [[] for _ in range(num_envs)],
        "value": [[] for _ in range(num_envs)],
        "log_prob": [[] for _ in range(num_envs)],
        "movement_log_prob": [[] for _ in range(num_envs)],
        "item_use_log_prob": [[] for _ in range(num_envs)],
        "hotbar_log_prob": [[] for _ in range(num_envs)],
        "pan_cam_log_prob": [[] for _ in range(num_envs)],
        "done": [[] for _ in range(num_envs)]
    }  # obs, act, reward, value, act_log_prob, dones

    prevActions = torch.zeros(num_envs, 4, dtype=torch.float32).to(DEVICE)
    obs = get_state() # Should return a tensor for obs
    print(max_steps)
    ep_reward = [0] * num_envs
    # (batch_size (num_envs), 4)

    for i in range(max_steps):
        debug_cnn(model, obs["Blocks"], ppo, ep_rewards, ppo_iter, curr_best)
        if i % 500 == 0:
            print(i)
        with torch.no_grad():
            logits_dict, value = model(obs)



        movement_dist = Categorical(logits=logits_dict['movement'])
        item_use_dist = Categorical(logits=logits_dict['item_use'])
        hotbar_dist = Categorical(logits=logits_dict['hotbar'])
        pan_cam_dist = Categorical(logits=logits_dict['pan_camera'])

        # jump_logits = logits_dict['jump']
        # print("Jump logits mean:", jump_logits.mean().item())
        # print("Jump prob mean:", torch.sigmoid(jump_logits).mean().item())

        movement_act = movement_dist.sample()
        # jump_act = jump_dist.sample()
        item_use_act = item_use_dist.sample()
        hotbar_act = hotbar_dist.sample()
        pan_cam_act = pan_cam_dist.sample()

        movement_log_prob = movement_dist.log_prob(movement_act)
        # jump_log_prob = jump_dist.log_prob(jump_act)
        item_use_log_prob = item_use_dist.log_prob(item_use_act)
        hotbar_log_prob = hotbar_dist.log_prob(hotbar_act)
        pan_cam_log_prob = pan_cam_dist.log_prob(pan_cam_act)

        act_log_prob = (
                movement_log_prob +
                item_use_log_prob +
                hotbar_log_prob +
                pan_cam_log_prob
        )

        act_log_prob = act_log_prob.detach().cpu()
        value = value.detach().cpu()

        if value.ndim == 0:
            value = value.view(1)
        else:
            value = value.view(num_envs)
        #
        # logits_np = {
        #     "movement": logits_dict["movement"].detach().cpu().numpy(),
        #     "jump": logits_dict["jump"].detach().cpu().numpy(),
        #     "item_use": logits_dict["item_use"].detach().cpu().numpy(),
        #     "hotbar": logits_dict["hotbar"].detach().cpu().numpy(),
        # }

        # rollout_buffer["obs"].append({
        #     'Inventory' : obs['Inventory'].clone(),
        #     'Blocks'    : obs['Blocks'].clone(),
        #     'Entities'  : obs['Entities'].clone(),
        #     'AgentInfo' : obs['AgentInfo'].clone()
        # })

        for j in range(num_envs):
            rollout_buffer['InventoryObs'][j].append(obs['Inventory'][j].detach().cpu())
            rollout_buffer['BlocksObs'][j].append(obs['Blocks'][j].detach().cpu())
            rollout_buffer['EntitiesObs'][j].append(obs['Entities'][j].detach().cpu())
            rollout_buffer['AgentInfoObs'][j].append(obs['AgentInfo'][j].detach().cpu())
            rollout_buffer['PrevActionsObs'][j].append(obs['PrevActions'][j].detach().cpu())

            rollout_buffer["movement"][j].append(movement_act[j].detach().cpu())
            rollout_buffer["item_use"][j].append(item_use_act[j].detach().cpu())
            rollout_buffer["hotbar"][j].append(hotbar_act[j].detach().cpu())
            rollout_buffer["pan_cam"][j].append(pan_cam_act[j].detach().cpu())

            rollout_buffer["value"][j].append(value[j])

            rollout_buffer["log_prob"][j].append(act_log_prob[j])
            rollout_buffer["movement_log_prob"][j].append(movement_log_prob[j].detach().cpu())
            rollout_buffer["item_use_log_prob"][j].append(item_use_log_prob[j].detach().cpu())
            rollout_buffer["hotbar_log_prob"][j].append(hotbar_log_prob[j].detach().cpu())
            rollout_buffer["pan_cam_log_prob"][j].append(pan_cam_log_prob[j].detach().cpu())

        actions = []
        for j in range(len(movement_act)):
            movement = int(movement_act[j].item())
            item_use = int(item_use_act[j].item())
            hotbar = int(hotbar_act[j].item())
            pan_cam = int(pan_cam_act[j].item())

            action = [movement, item_use, hotbar, pan_cam]
            actions.append(action)

        next_obs, reward, done = take_step(actions, max_steps, i)

        for j in range(num_envs):
            rollout_buffer["reward"][j].append(reward[j])
            rollout_buffer["done"][j].append(done[j])

        obs = next_obs
        for e in range(num_envs):
            ep_reward[e] += reward[e]

        # Don't need done? I think the take_step gets the new starting state anyway after reset?
        # if done:
            # obs = get_state()

    # logits is dict, won't work
    #
    #
    for key in ["AgentInfoObs", "PrevActionsObs", "InventoryObs", "EntitiesObs", "log_prob", "item_use_log_prob", "hotbar_log_prob", "movement_log_prob", "pan_cam_log_prob", "value", "reward", "done"]:
        rollout_buffer[key] = np.array(rollout_buffer[key], dtype=np.float32)
        #
    for key in ["movement", "pan_cam", "BlocksObs", "item_use", "hotbar",]:
        rollout_buffer[key] = np.array(rollout_buffer[key], dtype=np.int64)

    advantages, returns = compute_gaes(rollout_buffer['reward'], rollout_buffer['value'], rollout_buffer['done'])
    advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)
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

    plt.close()



