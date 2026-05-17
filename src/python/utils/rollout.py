import numpy as np
import torch
from torch.distributions import Categorical, Bernoulli

from env.env_client import EnvClient
from utils.gae import compute_gaes
from models.ActorCritic import ActorCriticNetwork
from models.ContainerModel import ContainerModel
from training.PPOTrainer import PPOTrainer

from config import DEVICE

def _init_container_buffer(num_envs: int):
    return {
        "InventoryObs": [[] for _ in range(num_envs)],
        "EntitiesObs": [[] for _ in range(num_envs)],
        "AgentInfoObs": [[] for _ in range(num_envs)],
        "PrevActionsObs": [[] for _ in range(num_envs)],

        "ContainerObs": [[] for _ in range(num_envs)],
        "ContainerMaskObs": [[] for _ in range(num_envs)],
        "ContainerTypeObs": [[] for _ in range(num_envs)],

        "close_container": [[] for _ in range(num_envs)],
        "inventory_slot": [[] for _ in range(num_envs)],
        "container_slot": [[] for _ in range(num_envs)],

        "value": [[] for _ in range(num_envs)],
        "log_prob": [[] for _ in range(num_envs)],

        "close_container_log_prob": [[] for _ in range(num_envs)],
        "inventory_slot_log_prob": [[] for _ in range(num_envs)],
        "container_slot_log_prob": [[] for _ in range(num_envs)],

        "reward": [[] for _ in range(num_envs)],
        "done": [[] for _ in range(num_envs)],
    }

def _init_world_buffer(num_envs: int):
    return {
        "InventoryObs": [[] for _ in range(num_envs)],
        "BlocksObs": [[] for _ in range(num_envs)],
        "EntitiesObs": [[] for _ in range(num_envs)],
        "NearbyItemDropsObs": [[] for _ in range(num_envs)],
        "AgentInfoObs": [[] for _ in range(num_envs)],
        "PrevActionsObs": [[] for _ in range(num_envs)],

        "inv_act": [[] for _ in range(num_envs)],

        "movement": [[] for _ in range(num_envs)],
        "side_movement": [[] for _ in range(num_envs)],
        "item_use": [[] for _ in range(num_envs)],
        "hotbar": [[] for _ in range(num_envs)],
        "pan_cam": [[] for _ in range(num_envs)],

        "from_slot": [[] for _ in range(num_envs)],
        "to_slot": [[] for _ in range(num_envs)],

        "drop_slot": [[] for _ in range(num_envs)],
        "drop_all_flag": [[] for _ in range(num_envs)],

        "craft_item_id": [[] for _ in range(num_envs)],

        "reward": [[] for _ in range(num_envs)],
        "value": [[] for _ in range(num_envs)],

        "log_prob": [[] for _ in range(num_envs)],

        "inv_act_log_prob": [[] for _ in range(num_envs)],

        "movement_log_prob": [[] for _ in range(num_envs)],
        "item_use_log_prob": [[] for _ in range(num_envs)],
        "hotbar_log_prob": [[] for _ in range(num_envs)],
        "pan_cam_log_prob": [[] for _ in range(num_envs)],

        "from_slot_log_prob": [[] for _ in range(num_envs)],
        "to_slot_log_prob": [[] for _ in range(num_envs)],

        "drop_slot_log_prob": [[] for _ in range(num_envs)],
        "drop_all_flag_log_prob": [[] for _ in range(num_envs)],

        "craft_item_id_log_prob": [[] for _ in range(num_envs)],

        "done": [[] for _ in range(num_envs)]
    }  # obs, act, reward, value, act_log_prob, dones

def rollout(env_client: EnvClient, world_model: ActorCriticNetwork, container_model: ContainerModel, ppo: PPOTrainer, ep_rewards, ppo_iter, curr_best, max_steps=20):
    world_model.eval()
    container_model.eval()

    num_envs = env_client.num_envs

    world_rollout_buffer = _init_world_buffer(num_envs)

    cont_buf = _init_container_buffer(num_envs)

    obs = env_client.get_state() # Should return a tensor for obs
    print(max_steps)
    ep_reward = [0] * num_envs

    for i in range(max_steps):
        actions = [[0]*10 for _ in range(num_envs)]
        # debug_cnn(world_model, obs["Blocks"], ppo, ep_rewards, ppo_iter, curr_best)
        if i % 500 == 0:
            print(i)

        container_type = obs['ContainerType']
        container_open = (container_type != 0)

        if container_open.any():
            print("Container")
            idx = torch.nonzero(container_open, as_tuple=False).squeeze(-1)

            cont_obs = {
                "Inventory": obs["Inventory"][idx],
                "Entities": obs["Entities"][idx],
                "AgentInfo": obs["AgentInfo"][idx],
                "PrevActions": obs["PrevActions"][idx],
                "Container": obs["Container"][idx],
                "ContainerMask": obs["ContainerMask"][idx],
                "ContainerType": obs["ContainerType"][idx],
            }

            with torch.no_grad():
                logits_dict, value = container_model(cont_obs)  # logits dict, value [B,1] or [B]

            mask = cont_obs["ContainerMask"].bool()
            container_logits = logits_dict["container_slot"].masked_fill(~mask, -1e9)

            close_container_dist = Bernoulli(logits=logits_dict['close_container'])
            container_slot_dist = Categorical(logits=container_logits)
            inventory_slot_dist = Categorical(logits=logits_dict['inventory_slot'])

            close_container_act = close_container_dist.sample()
            container_slot_act = container_slot_dist.sample()
            inventory_slot_act = inventory_slot_dist.sample()

            close_container_act_flat = close_container_act.view(-1)  # [B]

            close_container_log_prob = close_container_dist.log_prob(close_container_act).view(-1)     # [B]
            container_slot_log_prob = container_slot_dist.log_prob(container_slot_act)
            inventory_slot_log_prob = inventory_slot_dist.log_prob(inventory_slot_act)

            slot_lp = container_slot_log_prob + inventory_slot_log_prob

            close_container_flag = (close_container_act_flat == 0).float()

            act_log_prob = close_container_log_prob + slot_lp * close_container_flag

            act_log_prob = act_log_prob.detach().cpu()
            value = value.detach().cpu()

            value = value.view(-1)

            close_lp = close_container_log_prob.detach().cpu()
            cont_lp = container_slot_log_prob.detach().cpu()
            inv_lp = inventory_slot_log_prob.detach().cpu()

            for k, env_i in enumerate(idx.tolist()):
                # Store container rollout
                cont_buf["InventoryObs"][env_i].append(cont_obs["Inventory"][k].detach().cpu())
                cont_buf["EntitiesObs"][env_i].append(cont_obs["Entities"][k].detach().cpu())
                cont_buf["AgentInfoObs"][env_i].append(cont_obs["AgentInfo"][k].detach().cpu())
                cont_buf["PrevActionsObs"][env_i].append(cont_obs["PrevActions"][k].detach().cpu())
                cont_buf["ContainerObs"][env_i].append(cont_obs["Container"][k].detach().cpu())
                cont_buf["ContainerMaskObs"][env_i].append(cont_obs["ContainerMask"][k].detach().cpu())
                cont_buf["ContainerTypeObs"][env_i].append(cont_obs["ContainerType"][k].detach().cpu())

                cont_buf["close_container"][env_i].append(int(close_container_act_flat[k].item()))
                cont_buf["container_slot"][env_i].append(int(container_slot_act[k].item()))
                cont_buf["inventory_slot"][env_i].append(int(inventory_slot_act[k].item()))

                cont_buf["value"][env_i].append(value[k])
                cont_buf["log_prob"][env_i].append(act_log_prob[k])

                cont_buf["close_container_log_prob"][env_i].append(close_lp[k])
                cont_buf["container_slot_log_prob"][env_i].append(cont_lp[k])
                cont_buf["inventory_slot_log_prob"][env_i].append(inv_lp[k])

                actions[env_i][0] = 4
                actions[env_i][1] = int(inventory_slot_act[k].item())  # from_slot
                actions[env_i][2] = int(container_slot_act[k].item())  # to_slot
                actions[env_i][3] = int(close_container_act_flat[k].item())  # drop_all_flag used as close flag


        world_open = ~container_open
        if world_open.any():
            idx = torch.nonzero(world_open, as_tuple=False).squeeze(-1)

            world_obs = {
                "Inventory": obs["Inventory"][idx],
                "Blocks": obs["Blocks"][idx],
                "Entities": obs["Entities"][idx],
                "NearbyItemDrops": obs["NearbyItemDrops"][idx],
                "AgentInfo": obs["AgentInfo"][idx],
                "PrevActions": obs["PrevActions"][idx],
            }
            with torch.no_grad():
                logits_dict, value = world_model(world_obs)


            inv_act_dist = Categorical(logits=logits_dict['inv_act'])
            inv_act = inv_act_dist.sample()
            inv_act_log_prob = inv_act_dist.log_prob(inv_act)

            movement_dist = Categorical(logits=logits_dict['movement'])
            movement_side_dist = Categorical(logits=logits_dict['side_movement'])
            item_use_dist = Categorical(logits=logits_dict['item_use'])
            hotbar_dist = Categorical(logits=logits_dict['hotbar'])
            pan_cam_dist = Categorical(logits=logits_dict['pan_camera'])

            from_slot_dist = Categorical(logits=logits_dict['from_slot'])
            to_slot_dist = Categorical(logits=logits_dict['to_slot'])

            drop_slot_dist = Categorical(logits=logits_dict['drop_slot'])
            drop_all_flag_dist = Bernoulli(logits=logits_dict['drop_all_flag'])

            craft_item_id_dist = Categorical(logits=logits_dict['craft_item_id'])

            movement_act = movement_dist.sample()
            movement_side_act = movement_side_dist.sample()
            item_use_act = item_use_dist.sample()
            hotbar_act = hotbar_dist.sample()
            pan_cam_act = pan_cam_dist.sample()

            from_slot_act = from_slot_dist.sample()
            to_slot_act = to_slot_dist.sample()

            drop_slot_act = drop_slot_dist.sample()
            drop_all_flag_act = drop_all_flag_dist.sample().view(-1)

            craft_item_id_act = craft_item_id_dist.sample()

            movement_log_prob = movement_dist.log_prob(movement_act)
            movement_side_log_prob = movement_side_dist.log_prob(movement_side_act)
            item_use_log_prob = item_use_dist.log_prob(item_use_act)
            hotbar_log_prob = hotbar_dist.log_prob(hotbar_act)
            pan_cam_log_prob = pan_cam_dist.log_prob(pan_cam_act)

            from_slot_log_prob = from_slot_dist.log_prob(from_slot_act)
            to_slot_log_prob = to_slot_dist.log_prob(to_slot_act)

            drop_slot_log_prob = drop_slot_dist.log_prob(drop_slot_act)
            drop_all_flag_log_prob = drop_all_flag_dist.log_prob(drop_all_flag_act)

            inv_lp = inv_act_dist.log_prob(inv_act)

            move_lp = (
                    movement_log_prob
                    + movement_side_log_prob
                    + item_use_log_prob
                    + hotbar_log_prob
                    + pan_cam_log_prob
            )

            swap_lp = (
                    from_slot_log_prob
                    + to_slot_log_prob
            )

            drop_lp = (
                    drop_slot_log_prob
                    + drop_all_flag_log_prob
            )

            craft_lp = craft_item_id_dist.log_prob(craft_item_id_act)

            is_move = (inv_act == 0).float()
            is_swap = (inv_act == 1).float()
            is_drop = (inv_act == 2).float()
            is_craft = (inv_act == 3).float()

            act_log_prob = (
                    inv_lp
                    + is_move * move_lp
                    + is_swap * swap_lp
                    + is_drop * drop_lp
                    + is_craft * craft_lp
            )

            act_log_prob = act_log_prob.detach().cpu()
            value = value.detach().cpu()

            value = value.view(-1)

            for k, env_i in enumerate(idx.tolist()):
                world_rollout_buffer['InventoryObs'][env_i].append(obs['Inventory'][env_i].detach().cpu())
                world_rollout_buffer['BlocksObs'][env_i].append(obs['Blocks'][env_i].detach().cpu())
                world_rollout_buffer['EntitiesObs'][env_i].append(obs['Entities'][env_i].detach().cpu())
                world_rollout_buffer['NearbyItemDropsObs'][env_i].append(obs['NearbyItemDrops'][env_i].detach().cpu())
                world_rollout_buffer['AgentInfoObs'][env_i].append(obs['AgentInfo'][env_i].detach().cpu())
                world_rollout_buffer['PrevActionsObs'][env_i].append(obs['PrevActions'][env_i].detach().cpu())

                world_rollout_buffer["inv_act"][env_i].append(inv_act[k].detach().cpu())

                world_rollout_buffer["movement"][env_i].append(movement_act[k].detach().cpu())
                world_rollout_buffer["side_movement"][env_i].append(movement_side_act[k].detach().cpu())
                world_rollout_buffer["item_use"][env_i].append(item_use_act[k].detach().cpu())
                world_rollout_buffer["hotbar"][env_i].append(hotbar_act[k].detach().cpu())
                world_rollout_buffer["pan_cam"][env_i].append(pan_cam_act[k].detach().cpu())

                world_rollout_buffer["from_slot"][env_i].append(from_slot_act[k].detach().cpu())
                world_rollout_buffer["to_slot"][env_i].append(to_slot_act[k].detach().cpu())

                world_rollout_buffer["drop_slot"][env_i].append(drop_slot_act[k].detach().cpu())
                world_rollout_buffer["drop_all_flag"][env_i].append(drop_all_flag_act[k].detach().cpu())

                world_rollout_buffer["craft_item_id"][env_i].append(craft_item_id_act[k].detach().cpu())

                world_rollout_buffer["value"][env_i].append(value[k])
                world_rollout_buffer["log_prob"][env_i].append(act_log_prob[k])

                world_rollout_buffer["inv_act_log_prob"][env_i].append(inv_act_log_prob[k].detach().cpu())

                world_rollout_buffer["movement_log_prob"][env_i].append(movement_log_prob[k].detach().cpu())
                world_rollout_buffer["item_use_log_prob"][env_i].append(item_use_log_prob[k].detach().cpu())
                world_rollout_buffer["hotbar_log_prob"][env_i].append(hotbar_log_prob[k].detach().cpu())
                world_rollout_buffer["pan_cam_log_prob"][env_i].append(pan_cam_log_prob[k].detach().cpu())

                world_rollout_buffer["from_slot_log_prob"][env_i].append(from_slot_log_prob[k].detach().cpu())
                world_rollout_buffer["to_slot_log_prob"][env_i].append(to_slot_log_prob[k].detach().cpu())

                world_rollout_buffer["drop_slot_log_prob"][env_i].append(drop_slot_log_prob[k].detach().cpu())
                world_rollout_buffer["drop_all_flag_log_prob"][env_i].append(drop_all_flag_log_prob[k].detach().cpu())

                world_rollout_buffer["craft_item_id_log_prob"][env_i].append(craft_lp[k].detach().cpu())

                actions[env_i] = [
                    int(inv_act[k].item()),
                    int(movement_act[k].item()),
                    int(movement_side_act[k].item()),
                    int(item_use_act[k].item()),
                    int(hotbar_act[k].item()),
                    int(pan_cam_act[k].item()),
                    int(from_slot_act[k].item()),
                    int(to_slot_act[k].item()),
                    int(drop_slot_act[k].item()),
                    int(drop_all_flag_act[k].item()),
                    int(craft_item_id_act[k].item()),
                ]

        next_obs, reward, done = env_client.take_step(actions, max_steps, i)

        for env_i in range(num_envs):
            if container_open[env_i]:
                cont_buf["reward"][env_i].append(reward[env_i])
                cont_buf["done"][env_i].append(done[env_i])
            else:
                world_rollout_buffer["reward"][env_i].append(reward[env_i])
                world_rollout_buffer["done"][env_i].append(done[env_i])

        obs = next_obs
        for e in range(num_envs):
            ep_reward[e] += reward[e]

    for key in ["AgentInfoObs", "PrevActionsObs", "InventoryObs", "NearbyItemDropsObs", "EntitiesObs", "log_prob", "item_use_log_prob", "hotbar_log_prob", "movement_log_prob", "pan_cam_log_prob", "value", "reward", "done"]:
        world_rollout_buffer[key] = np.array(world_rollout_buffer[key], dtype=np.float32)
        #
    for key in ["inv_act", "movement", "side_movement", "pan_cam", "BlocksObs", "item_use", "hotbar", "from_slot", "to_slot", "drop_slot", "drop_all_flag", "craft_item_id"]:
        world_rollout_buffer[key] = np.array(world_rollout_buffer[key], dtype=np.int64)

    advantages, returns = compute_gaes(world_rollout_buffer['reward'], world_rollout_buffer['value'], world_rollout_buffer['done'], num_envs)
    advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)
    world_rollout_buffer['advantage'] = advantages
    world_rollout_buffer['returns'] = returns

    world_model.train()
    container_model.train()
    return world_rollout_buffer, cont_buf, ep_reward






