import torch
import random
import numpy as np
from training.PPOTrainer import PPOTrainer

from models.ActorCritic import *
from models.ContainerModel import *

from config import DEVICE

def convert_obs(obs_np):
    obs_t = {}
    for k, v in obs_np.items():
        if k == 'Blocks':
            obs_t[k] = torch.tensor(v, dtype=torch.long).to(DEVICE)
        else:
            obs_t[k] = torch.tensor(v, dtype=torch.float32).to(DEVICE)

    return obs_t


data1 = torch.load("world_expert_data4.pt", weights_only=False)
data2 = torch.load("world_expert_data5.pt", weights_only=False)
data3 = torch.load("world_expert_data6.pt", weights_only=False)
data4 = torch.load("world_expert_data7.pt", weights_only=False)
data5 = torch.load("world_expert_data8.pt", weights_only=False)
data6 = torch.load("world_expert_data9.pt", weights_only=False)
data7 = torch.load("world_expert_data10.pt", weights_only=False)


data1cont = torch.load("cont_expert_data3.pt", weights_only=False)
data2cont = torch.load("cont_expert_data4.pt", weights_only=False)
data3cont = torch.load("cont_expert_data5.pt", weights_only=False)
data4cont = torch.load("cont_expert_data6.pt", weights_only=False)


combined_data = data1 + data2 + data3 + data4 + data5 + data6 + data7
combined_datac_cont = data1cont + data2cont + data3cont + data4cont

agent_info_dim = 21
num_items = 1488
num_blocks = 1166
num_entities = 153

world_model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities)
world_model.to(DEVICE)

cont_model = ContainerModel(agent_info_dim, num_items, num_entities)
cont_model.to(DEVICE)

ppo = PPOTrainer(world_model, cont_model, max_policy_train_iters=5)

world_data = combined_data
cont_data  = combined_datac_cont

world_batch_size = 512
cont_batch_size  = min(32, len(cont_data))

def build_batch(entries):
    obs_batch = []
    act_batch = []

    for e in entries:
        obs_batch.append(convert_obs(e["obs"]))
        act_batch.append(torch.tensor(e["action"], dtype=torch.float32).to(DEVICE))

    batched_obs = {
        k: torch.stack([o[k] for o in obs_batch], dim=0).squeeze(1)
        for k in obs_batch[0].keys()
    }

    batched_actions = torch.stack(act_batch, dim=0)
    return batched_obs, batched_actions


epochs = 100
epoch = 0

while True:
    random.shuffle(world_data)

    # -------- WORLD POLICY training --------
    for start in range(0, len(world_data), world_batch_size):
        batch = world_data[start:start + world_batch_size]
        obs, actions = build_batch(batch)
        ppo.imitation_train_policy(obs, actions)

    acc1 = ppo.test_accuracy(obs, actions, True)
    print(f"Epoch {epoch}, Accuracy {acc1:.4f}")

    # -------- CONTAINER POLICY training --------
    if len(cont_data) > 0:
        for _ in range(10):
            batch = random.sample(cont_data, cont_batch_size)
            obs, actions = build_batch(batch)

            ppo.imitation_train_policy_cont(obs, actions)

    # -------- eval --------
    acc2 = ppo.test_accuracy(obs, actions, False)
    print(f"Epoch {epoch}, Accuracy {acc2:.4f}")

    if epoch % 10 == 0:
        torch.save({
            "world_model": world_model.state_dict(),
            "cont_model": cont_model.state_dict(),
            "best_acc_world": acc1,
            "best_acc_cont": acc2,
        }, f"imitate/model_epoch_new_larger_{epoch}.pth")
        print(f"Saved checkpoint at epoch {epoch}")

    epoch += 1