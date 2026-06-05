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

ppo = PPOTrainer(world_model)

iteration, curr_best = load_checkpoint(
    world_model, ppo, "checkpoints/iter_saves/model_itr_Phase1135.pth", DEVICE, ep_rewards=ep_rewards
)
# proxy_paths={"block_embedder": "checkpoints/proxy/block_embedder.pth", "block_encoder": "checkpoints/proxy/block_encoder.pth"}
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
    train_data, ep_reward = rollout(env_client, world_model, ppo, ep_rewards, i, curr_best)
    ep_rewards.append(np.mean(ep_reward))
    print_cuda_mem("After Rollout")

    if ep_rewards[-1] >= curr_best or i % 5 == 0:
        if ep_rewards[-1] >= curr_best:
            curr_best = ep_rewards[-1]
            save_path = f"checkpoints/model_best_Phase1_{curr_best:.2f}_iter{i}.pth"
        else:
            save_path = f"checkpoints/iter_saves/model_itr_Phase1{i}.pth"
        torch.save({
            "world_model": world_model.state_dict(),
            "optimizer_state_dict": ppo.optimizer.state_dict(),
            "rewards": ep_rewards,
            "best_reward": curr_best,
            "iter": i,
        }, save_path)

        print(f" Saved best model → {save_path} with reward {curr_best}")

    plot_rewards(ep_rewards, path=f'model_{i}', window=20)

    flat = lambda x: np.concatenate(x, axis=0)

    # num_samples = flat(train_data["BlocksObs"]).shape[0]
    # permute_idxs = np.random.permutation(num_samples)

    obs = {
        'Blocks': torch.tensor(train_data['BlocksObs'], dtype=torch.int64, device=DEVICE),
        'AgentInfo': torch.tensor(train_data['AgentInfoObs'], dtype=torch.float32, device=DEVICE),
    }

    act = {
        'movement': torch.tensor(train_data['movement'], dtype=torch.int64, device=DEVICE),
        'side_movement': torch.tensor(train_data['side_movement'], dtype=torch.int64, device=DEVICE),
        'pan_cam': torch.tensor(train_data['pan_cam'], dtype=torch.int64, device=DEVICE),
    }

    advantages = torch.tensor(train_data['advantage'], dtype=torch.float32, device=DEVICE)
    advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)

    summed_log_probs = torch.tensor(train_data['log_prob'], dtype=torch.float32, device=DEVICE)

    returns = torch.tensor(train_data['returns'], dtype=torch.float32, device=DEVICE)

    print_cuda_mem("Before Training")

    # old_log_probs not used. temporarily set default value to check
    ppo.train_policy(obs, act, summed_log_probs, advantages, returns)
    # ppo.train_value(obs, returns)
    print('********************************')
    print('****** Completed Training ******')
    print('********************************')
