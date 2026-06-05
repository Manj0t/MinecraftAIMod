import torch
from torch import nn, optim
from torch.distributions import Categorical
import numpy as np
from utils import debug

BATCH_SIZE = 128


class PPOTrainer:
    def __init__(self, actor_critic, ppo_clip_val=0.2, target_kl_div=0.015, max_policy_train_iters=10, policy_lr=3e-4, entropy_coeff=0.01):

        self.ac = actor_critic
        self.ppo_clip_val = ppo_clip_val
        self.target_kl_div = target_kl_div
        self.max_policy_train_iters = max_policy_train_iters
        self.entropy_coeff = entropy_coeff

        encoder_params = list(self.ac.block_encoder.parameters())
        shared_params = (
            list(self.ac.pre_encoder.parameters()) +
            list(self.ac.gru.parameters()) +
            list(self.ac.post_gru.parameters())
        )
        value_params = list(self.ac.value_layer.parameters())
        policy_params = (
            list(self.ac.movement_policy.parameters()) +
            list(self.ac.move_side_policy.parameters()) +
            list(self.ac.pan_camera.parameters())
        )

        self.optimizer = optim.Adam([
            {"params": encoder_params, "lr": policy_lr * 0.1},  # fine-tune CNN slowly
            {"params": shared_params + value_params + policy_params, "lr": policy_lr},
        ])

    def train_policy(self, obs, act, summed_old_log_probs, advantages, returns):
        env_count = obs["Blocks"].shape[0]
        rollout_size = obs["Blocks"].shape[1]

        for j in range(self.max_policy_train_iters):
            print(f"Policy iteration {j}")
            kl_arr = []
            for i in range(0, rollout_size, BATCH_SIZE):
                self.optimizer.zero_grad()

                batch_obs = {
                    "Blocks":    obs["Blocks"][:, i:i+BATCH_SIZE],
                    "AgentInfo": obs["AgentInfo"][:, i:i+BATCH_SIZE],
                }

                batch_move = act["movement"][:, i:i+BATCH_SIZE]
                batch_side = act["side_movement"][:, i:i+BATCH_SIZE]
                batch_cam  = act["pan_cam"][:, i:i+BATCH_SIZE]

                batch_old_lp = summed_old_log_probs[:, i:i+BATCH_SIZE].detach()
                batch_adv    = advantages[:, i:i+BATCH_SIZE].detach()
                batch_ret    = returns[:, i:i+BATCH_SIZE]

                # == Forward ==
                # logits: [num_envs, BATCH_SIZE, actions], value: [num_envs, BATCH_SIZE]
                logits_dict, value = self.ac.forward_train(batch_obs)

                move_dist = Categorical(logits=logits_dict["movement"])
                side_dist = Categorical(logits=logits_dict["side_movement"])
                cam_dist  = Categorical(logits=logits_dict["pan_camera"])

                # == New log probs ==
                new_log_probs = (
                    move_dist.log_prob(batch_move)
                    + side_dist.log_prob(batch_side)
                    + cam_dist.log_prob(batch_cam)
                )

                # == PPO clipped objective ==
                ratio = torch.exp(new_log_probs - batch_old_lp)
                clipped = ratio.clamp(1 - self.ppo_clip_val, 1 + self.ppo_clip_val)
                policy_loss = -torch.min(ratio * batch_adv, clipped * batch_adv).mean()

                # == Value loss ==
                value_loss = ((batch_ret - value) ** 2).mean()

                # == Entropy bonus ==
                entropy = (
                    move_dist.entropy()
                    + side_dist.entropy()
                    + cam_dist.entropy()
                ).mean() * self.entropy_coeff

                # == Combined loss ==
                loss = policy_loss + 0.25 * value_loss - entropy

                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.ac.parameters(), max_norm=2.0)
                self.optimizer.step()

                approx_kl = 0.5 * ((batch_old_lp - new_log_probs) ** 2).mean()
                kl_arr.append(approx_kl.detach().cpu().item())

                if approx_kl > self.target_kl_div:
                    print(f"Early stop on policy update: KL={approx_kl:.4f}")
                    return

            self.ac.reset_h_states()

            if debug.DEBUG_KL and len(kl_arr) > 0:
                print(f"Average KL across batches: {np.mean(kl_arr):.6f}")