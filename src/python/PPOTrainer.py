import torch
from torch import nn
from torch import optim
from torch.distributions import Categorical, Bernoulli
import numpy as np
import CNNDebugger

batch_size = 1024


class PPOTrainer():
    def __init__(self, actor_critic, ppo_clip_val=0.2, target_kl_div=0.15, max_policy_train_iters=8, value_train_iters=10, policy_lr=3e-3):
        self.ac = actor_critic
        self.ppo_clip_val = ppo_clip_val
        self.target_kl_div = target_kl_div
        self.max_policy_train_iters = max_policy_train_iters
        self.value_train_iters = value_train_iters

        shared_params = list(self.ac.shared_layers.parameters())
        value_params = list(self.ac.value_layer.parameters())
        policy_params = list(self.ac.movement_policy.parameters()) + list(self.ac.pan_camera.parameters()) + list(self.ac.item_use_policy.parameters()) + list(self.ac.hotbar_policy.parameters())

        # self.value_optimizer = optim.Adam(value_params, lr=value_lr)
        # self.policy_optimizer = optim.Adam(policy_params, lr=policy_lr)

        self.optimizer = optim.Adam(shared_params + value_params + policy_params, lr=policy_lr)

    def imitation_train_policy(self, obs, actions):
        for _ in range(self.max_policy_train_iters):
            logits_dict, _ = self.ac(obs)

            movement_logits = logits_dict["movement"]
            item_logits = logits_dict["item_use"]
            hotbar_logits = logits_dict["hotbar"]
            pan_logits = logits_dict["pan_camera"]

            movement_targets = actions[:, 0]
            item_targets = actions[:, 1]
            hotbar_targets = actions[:, 2]
            pan_targets = actions[:, 3]

            loss = (
                    nn.CrossEntropyLoss()(movement_logits, movement_targets) +
                    nn.CrossEntropyLoss()(item_logits, item_targets) +
                    nn.CrossEntropyLoss()(hotbar_logits, hotbar_targets) +
                    nn.CrossEntropyLoss()(pan_logits, pan_targets)
            )

            self.optimizer.zero_grad()
            loss.backward()
            self.optimizer.step()

    def test_accuracy(self, state, actions, batch_size=2048):
        self.ac.eval()

        total_correct = 0
        total_samples = 0

        total_move_correct = 0
        total_item_correct = 0
        total_hotbar_correct = 0
        total_pan_correct = 0

        with torch.no_grad():
            for i in range(0, len(state), batch_size):
                batch_obs = {k: v[i:i+batch_size] for k, v in state.items()}
                batch_actions = actions[i:i + batch_size]

                logits = self.ac.policy(batch_obs)

                pred_move = logits['movement'].argmax(dim=1)
                pred_item = logits['item_use'].argmax(dim=1)
                pred_hotbar = logits['hotbar'].argmax(dim=1)
                pred_pan = logits['pan_camera'].argmax(dim=1)

                true_move = batch_actions[:, 0]
                true_item = batch_actions[:, 1]
                true_hotbar = batch_actions[:, 2]
                true_pan = batch_actions[:, 3]

                total_move_correct += (pred_move == true_move).sum().item()
                total_item_correct += (pred_item == true_item).sum().item()
                total_hotbar_correct += (pred_hotbar == true_hotbar).sum().item()
                total_pan_correct += (pred_pan == true_pan).sum().item()

                all_correct = (
                        (pred_move == true_move)
                        & (pred_item == true_item)
                        & (pred_hotbar == true_hotbar)
                        & (pred_pan == true_pan)
                )
                total_correct += all_correct.sum().item()
                total_samples += len(batch_actions)

        move_acc = total_move_correct / total_samples
        item_acc = total_item_correct / total_samples
        hotbar_acc = total_hotbar_correct / total_samples
        pan_acc = total_pan_correct / total_samples

        total_acc = total_correct / total_samples

        print(f"Acc Movement   : {move_acc:.3f}")
        print(f"Acc Item Use   : {item_acc:.3f}")
        print(f"Acc Hotbar     : {hotbar_acc:.3f}")
        print(f"Acc Pan Camera : {pan_acc:.3f}")
        print(f"Acc ALL ACTIONS: {total_acc:.3f}")

        self.ac.train()

        return total_acc


    def train_policy(self, obs, act, old_log_probs, summed_old_log_probs, advantages, returns):
        rollout_size = obs["Blocks"].shape[0]
        for j in range(self.max_policy_train_iters):
            print(f'Policy iteration {j}')

            kl_arr = []
            for i in range(0, rollout_size, batch_size):
                self.optimizer.zero_grad()

                batch_obs = {
                    'Inventory': obs['Inventory'][i:i+batch_size],
                    'Blocks': obs['Blocks'][i:i+batch_size],
                    'Entities': obs['Entities'][i:i+batch_size],
                    'AgentInfo': obs['AgentInfo'][i:i+batch_size],
                    'PrevActions' : obs['PrevActions'][i:i+batch_size],
                }

                batch_act = {
                    'movement'  : act['movement'][i:i+batch_size],
                    'item_use'  : act['item_use'][i:i+batch_size],
                    'hotbar'    : act['hotbar'][i:i+batch_size],
                    'pan_cam'   : act['pan_cam'][i:i+batch_size],
                }

                batch_summed_old_log_probs = summed_old_log_probs[i:i+batch_size].detach()
                batch_old_log_probs = {
                    "old_movement_lp"   : old_log_probs['old_movement_lp'][i:i+batch_size],
                    "old_item_use_lp"   : old_log_probs['old_item_use_lp'][i:i+batch_size],
                    "old_hotbar_lp"     : old_log_probs['old_hotbar_lp'][i:i+batch_size],
                    "old_pan_cam_lp"    : old_log_probs['old_pan_cam_lp'][i:i+batch_size]
                }

                batch_advantages = advantages[i:i+batch_size].detach()

                logits_dict, value = self.ac(batch_obs)


                movement_dist = Categorical(logits=logits_dict['movement'])
                item_use_dist = Categorical(logits=logits_dict['item_use'])
                hotbar_dist = Categorical(logits=logits_dict['hotbar'])
                pan_cam_dist =  Categorical(logits=logits_dict['pan_camera'])

                movement_act = batch_act['movement']
                item_use_act = batch_act['item_use']
                hotbar_act = batch_act['hotbar']
                pan_cam_act = batch_act['pan_cam']

                movement_log_prob = movement_dist.log_prob(movement_act).squeeze()
                item_use_log_prob = item_use_dist.log_prob(item_use_act).squeeze()
                hotbar_log_prob = hotbar_dist.log_prob(hotbar_act).squeeze()
                pan_cam_log_prob = pan_cam_dist.log_prob(pan_cam_act).squeeze()


                new_log_probs = (
                        movement_log_prob +
                        item_use_log_prob +
                        hotbar_log_prob  +
                        pan_cam_log_prob
                )

                approx_kl = 0.5 * ((new_log_probs - batch_summed_old_log_probs) ** 2).mean()


                # ignore for now, may change later so easier to change
                entropy_bonus = (
                    movement_dist.entropy().mean() +
                    item_use_dist.entropy().mean() +
                    hotbar_dist.entropy().mean() +
                    pan_cam_dist.entropy().mean()
                    ) * 0.07

                policy_ratio = torch.exp(new_log_probs - batch_summed_old_log_probs)
                clipped_ratio = policy_ratio.clamp(1 - self.ppo_clip_val, 1 + self.ppo_clip_val)

                clipped_loss = clipped_ratio * batch_advantages
                full_loss = policy_ratio * batch_advantages

                policy_loss = -torch.min(clipped_loss, full_loss).mean()


                batch_returns = returns[i:i + batch_size]


                value_loss = ((batch_returns - value) ** 2).mean()

                loss = policy_loss + 0.25 * value_loss - entropy_bonus

                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.ac.parameters(), max_norm=2.0)
                self.optimizer.step()
                kl_arr.append(approx_kl.detach().cpu().item())
                if abs(approx_kl) > self.target_kl_div:
                    print(f"Early stop on policy update: KL={approx_kl:.4f}")
                    return

            if CNNDebugger.DEBUG_KL and len(kl_arr) > 0:
                print(f'Average kl across batches {np.mean(kl_arr)}')
