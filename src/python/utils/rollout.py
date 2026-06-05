import numpy as np
import torch
from torch.distributions import Categorical

from env.env_client import EnvClient
from utils.gae import compute_gaes
from models.ActorCritic import ActorCriticNetwork

from config import DEVICE


def _init_world_buffer(num_envs: int):
    return {
        "BlocksObs":    [[] for _ in range(num_envs)],
        "AgentInfoObs": [[] for _ in range(num_envs)],
        "HiddenStates": [[] for _ in range(num_envs)],

        "movement":      [[] for _ in range(num_envs)],
        "side_movement": [[] for _ in range(num_envs)],
        "pan_cam":       [[] for _ in range(num_envs)],

        "reward": [[] for _ in range(num_envs)],
        "value":  [[] for _ in range(num_envs)],
        "log_prob": [[] for _ in range(num_envs)],
        "done":   [[] for _ in range(num_envs)],
    }


def rollout(env_client: EnvClient, world_model: ActorCriticNetwork, ppo, ep_rewards, ppo_iter, curr_best, max_steps=1024):
    world_model.eval()

    num_envs = env_client.num_envs
    buf = _init_world_buffer(num_envs)

    obs = env_client.get_state()
    ep_reward = [0] * num_envs

    for i in range(max_steps):
        if i % 500 == 0:
            print(i)

        # Snapshot hidden state BEFORE the forward pass
        h_prev_snapshot = world_model.h_states[:num_envs].detach().clone().cpu()

        with torch.no_grad():
            logits_dict, value = world_model(obs, num_envs)

        # == Sample from 3 heads ==
        movement_dist = Categorical(logits=logits_dict['movement'])
        side_move_dist = Categorical(logits=logits_dict['side_movement'])
        pan_cam_dist = Categorical(logits=logits_dict['pan_camera'])

        movement_act = movement_dist.sample()
        side_move_act = side_move_dist.sample()
        pan_cam_act = pan_cam_dist.sample()

        # == Log probs (simple sum, no masking) ==
        move_lp = movement_dist.log_prob(movement_act)
        side_lp = side_move_dist.log_prob(side_move_act)
        cam_lp = pan_cam_dist.log_prob(pan_cam_act)

        act_log_prob = (move_lp + side_lp + cam_lp).detach().cpu()
        value = value.detach().cpu().view(-1)

        # == Store rollout data ==
        for env_i in range(num_envs):
            buf["BlocksObs"][env_i].append(obs["Blocks"][env_i].detach().cpu())
            buf["AgentInfoObs"][env_i].append(obs["AgentInfo"][env_i].detach().cpu())
            buf["HiddenStates"][env_i].append(h_prev_snapshot[env_i])

            buf["movement"][env_i].append(movement_act[env_i].detach().cpu())
            buf["side_movement"][env_i].append(side_move_act[env_i].detach().cpu())
            buf["pan_cam"][env_i].append(pan_cam_act[env_i].detach().cpu())

            buf["value"][env_i].append(value[env_i])
            buf["log_prob"][env_i].append(act_log_prob[env_i])

        # == Build action list for Java ==
        # Java expects 11 values: [invAct, move, side, itemUse, hotbar, panCam,
        #                           fromSlot, toSlot, dropSlot, dropAll, craftId]
        # Hardcode non-nav actions to defaults
        actions = []
        for env_i in range(num_envs):
            actions.append([
                0,                                      # inv_act = 0 (no inventory action)
                int(movement_act[env_i].item()),        # movement
                int(side_move_act[env_i].item()),       # side_movement
                2,                                      # item_use = 2 (neither)
                0,                                      # hotbar = 0
                int(pan_cam_act[env_i].item()),         # pan_camera
                0, 0, 0, 0, 0,                          # from/to/drop/craft = 0
            ])

        next_obs, reward, done = env_client.take_step(actions, max_steps, i)

        for env_i in range(num_envs):
            buf["reward"][env_i].append(reward[env_i])
            buf["done"][env_i].append(done[env_i])

        obs = next_obs
        for e in range(num_envs):
            ep_reward[e] += reward[e]

    # == Convert buffers to arrays ==
    for key in ["AgentInfoObs", "HiddenStates", "log_prob", "value", "reward", "done"]:
        buf[key] = np.array(buf[key], dtype=np.float32)

    for key in ["movement", "side_movement", "pan_cam", "BlocksObs"]:
        buf[key] = np.array(buf[key], dtype=np.int64)

    # == GAE ==
    advantages, returns = compute_gaes(buf["reward"], buf["value"], buf["done"], num_envs)
    buf["advantage"] = advantages
    buf["returns"] = returns

    world_model.reset_h_states()
    world_model.train()

    return buf, ep_reward