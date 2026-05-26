import torch
import numpy as np

import struct

from training.PPOTrainer import PPOTrainer
from models.ActorCritic import ActorCriticNetwork
from models.ContainerModel import ContainerModel

from utils.test_rollout import test_rollout
from utils.debug import start_debugging, print_cuda_mem
from utils.plotting import plot_rewards
from utils.rollout import rollout

from training.weight_loading import load_checkpoint

from env.setup import setup_env

from env.env_client import EnvClient
from config import DEVICE

# Create model
num_envs, test, env_sockets, headers = setup_env()
ep_rewards = []

agent_info_dim = headers[0][0]   # assume same
num_items     = headers[0][1]
num_blocks    = headers[0][2]
num_entities  = headers[0][3]

print(f'agent_info_dim: {agent_info_dim}, num_items: {num_items}, num_blocks: {num_blocks}, num_entities {num_entities}')

env_client = EnvClient(num_envs, env_sockets)

# --- Load Models --- #
world_model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities, num_envs)
world_model.to(DEVICE)

container_model = ContainerModel(agent_info_dim, num_items, num_entities)
container_model.to(DEVICE)

ppo = PPOTrainer(world_model, container_model)

iteration, curr_best = load_checkpoint(
    world_model, ppo, "", DEVICE, proxy_paths={"block_embedder": "checkpoints/proxy/block_embedder.pth", "block_encoder": "checkpoints/proxy/block_encoder.pth"}, ep_rewards=ep_rewards
)

# -- Start Debugging --
start_debugging()

if test:
    print("Testing model with argmax")
    while True:
        for s in env_sockets:
            s.sendall(struct.pack(">i", 1))
        test_rollout(env_client, world_model)

for i in range(iteration, 10000):
    print("Loop ", i)
    for s in env_sockets:
        s.sendall(struct.pack(">i", 1))
    print_cuda_mem("Before Rollout")
    train_data, cont_train_data, ep_reward = rollout(env_client, world_model, container_model, ppo, ep_rewards, i, curr_best)
    ep_rewards.append(np.mean(ep_reward))
    print_cuda_mem("After Rollout")

    if ep_rewards[-1] >= curr_best or i % 5 == 0:
        if ep_rewards[-1] >= curr_best:
            curr_best = ep_rewards[-1]
            save_path = f"checkpoints/model_best_GRU_model{curr_best:.2f}_iter{i}.pth"
        else:
            save_path = f"checkpoints/iter_saves/model_itr_GRU_model{i}.pth"
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
        'HiddenStates': torch.tensor(flat(train_data['HiddenStates'])[permute_idxs], dtype=torch.float32, device=DEVICE),

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

        'craft_item_id': torch.tensor(flat(train_data['craft_item_id'])[permute_idxs], dtype=torch.int64, device=DEVICE),
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
