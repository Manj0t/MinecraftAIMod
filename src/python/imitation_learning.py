import torch
import random
import numpy as np
from PPOTrainer import PPOTrainer

from ActorCritic import *

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

def convert_obs(obs_np):
    obs_t = {}
    for k, v in obs_np.items():
        if k == 'Blocks':
            obs_t[k] = torch.tensor(v, dtype=torch.long).to(DEVICE)
        else:
            obs_t[k] = torch.tensor(v, dtype=torch.float32).to(DEVICE)

    return obs_t


data1 = torch.load("expert_data2.pt", weights_only=False)
data2 = torch.load("expert_data3.pt", weights_only=False)
data3 = torch.load("expert_data4.pt", weights_only=False)


combined_data = data1 + data2 + data3

agent_info_dim = 21
num_items = 1488
num_blocks = 1166
num_entities = 153

model = ActorCriticNetwork(agent_info_dim, num_items, num_blocks, num_entities)
model.to(DEVICE)

ppo = PPOTrainer(model, max_policy_train_iters=5)

batch_size = 512
n = len(combined_data)
best = -1
epoch = 0

while True:
    # shuffle indices, not the dicts
    indices = list(range(n))
    random.shuffle(indices)

    for start in range(0, n, batch_size):
        batch_idx = indices[start:start + batch_size]

        obs_batch = []
        act_batch = []

        for idx in batch_idx:
            entry = combined_data[idx]
            obs_batch.append(convert_obs(entry["obs"]))

            act_batch.append(torch.tensor(entry["action"], dtype=torch.int64).to(DEVICE))

        batched_obs = {
            key: torch.stack([o[key] for o in obs_batch], dim=0)
            for key in obs_batch[0].keys()
        }
        for k in batched_obs.keys():
            batched_obs[k] = batched_obs[k].squeeze(1)
            # print(f'{k}: {batched_obs[k].shape}')

        batched_actions = torch.stack(act_batch, dim=0)  # shape (batch,4)

        ppo.imitation_train_policy(batched_obs, batched_actions)

    acc = ppo.test_accuracy(batched_obs, batched_actions)
    print(f"Epoch {epoch}, Accuracy {acc:.4f}")

    best = acc
    if epoch % 10 == 0:
        torch.save({
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": ppo.optimizer.state_dict(),
            "best_acc": best,
        }, f"imitate/model_{acc}_epoch_{epoch}.pth")
        print(f'Saved model to imitate/model_{acc}_epoch_{epoch}.pth')
    print()
    epoch += 1


