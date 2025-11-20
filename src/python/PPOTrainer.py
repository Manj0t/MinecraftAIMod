import torch
from torch import nn
from torch import optim
from torch.distributions import Categorical, Bernoulli

batch_size = 256


class PPOTrainer():
    def __init__(self, actor_critic, ppo_clip_val=0.2, target_kl_div=0.1, max_policy_train_iters=10, value_train_iters=10, policy_lr=3e-4):
        self.ac = actor_critic
        self.ppo_clip_val = ppo_clip_val
        self.target_kl_div = target_kl_div
        self.max_policy_train_iters = max_policy_train_iters
        self.value_train_iters = value_train_iters

        shared_params = list(self.ac.shared_layers.parameters())
        value_params = list(self.ac.value_layer.parameters())
        policy_params = list(self.ac.movement_policy.parameters()) + \
                list(self.ac.jump_policy.parameters()) + \
                list(self.ac.item_use_policy.parameters()) + \
                list(self.ac.hotbar_policy.parameters()) + \
                list(self.ac.pan_camera.parameters())

        # self.value_optimizer = optim.Adam(value_params, lr=value_lr)
        # self.policy_optimizer = optim.Adam(policy_params, lr=policy_lr)

        self.optimizer = optim.Adam(shared_params + value_params + policy_params, lr=policy_lr)


    def train_policy(self, obs, act, old_log_probs, advantages, returns):
        rollout_size = obs["Blocks"].shape[0]
        # torch.cuda.empty_cache()
        # torch.cuda.reset_peak_memory_stats()
        for j in range(self.max_policy_train_iters):
            print(f'Policy iteration {j}')


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
                    'jump'      : act['jump'][i:i+batch_size],
                    'item_use'  : act['item_use'][i:i+batch_size],
                    'hotbar'    : act['hotbar'][i:i+batch_size],
                    'pan_cam'   : act['pan_cam'][i:i+batch_size],
                }

                batch_old_log_probs = old_log_probs[i:i+batch_size].detach()
                batch_advantages = advantages[i:i+batch_size].detach()

                logits_dict, value = self.ac(batch_obs)


                movement_dist = Categorical(logits=logits_dict['movement'])
                jump_dist = Bernoulli(logits=logits_dict['jump'].squeeze(-1))
                item_use_dist = Categorical(logits=logits_dict['item_use'])
                hotbar_dist = Categorical(logits=logits_dict['hotbar'])
                pan_cam_dist =  Categorical(logits=logits_dict['pan_camera'])

                movement_act = batch_act['movement']
                jump_act = batch_act['jump']
                item_use_act = batch_act['item_use']
                hotbar_act = batch_act['hotbar']
                pan_cam_act = batch_act['pan_cam']

                # print(f' movement shape : {movement_act.shape}')
                # print(f' jump shape : {jump_act.shape}')
                # print(f' item use shape : {item_use_act.shape}')
                # print(f' hotbar shape : {hotbar_act.shape}')
                jump_act = jump_act.squeeze(-1).float()

                movement_log_prob = movement_dist.log_prob(movement_act).squeeze()
                jump_log_prob = jump_dist.log_prob(jump_act).squeeze()
                item_use_log_prob = item_use_dist.log_prob(item_use_act).squeeze()
                hotbar_log_prob = hotbar_dist.log_prob(hotbar_act).squeeze()
                pan_cam_log_prob = pan_cam_dist.log_prob(pan_cam_act).squeeze()


                new_log_probs = (
                        movement_log_prob +
                        jump_log_prob +
                        item_use_log_prob +
                        hotbar_log_prob  +
                        pan_cam_log_prob
                )
                # ignore for now, may change later so easier to change
                entropy_bonus = (
                    movement_dist.entropy().mean() +
                    jump_dist.entropy().mean() +
                    item_use_dist.entropy().mean() +
                    hotbar_dist.entropy().mean() +
                    pan_cam_dist.entropy().mean()
                    ) * 0.01

                policy_ratio = torch.exp(new_log_probs - batch_old_log_probs)
                clipped_ratio = policy_ratio.clamp(1 - self.ppo_clip_val, 1 + self.ppo_clip_val)

                clipped_loss = clipped_ratio * batch_advantages
                full_loss = policy_ratio * batch_advantages

                policy_loss = -torch.min(clipped_loss, full_loss).mean()


                batch_returns = returns[i:i + batch_size]


                value_loss = ((batch_returns - value) ** 2).mean()

                loss = policy_loss + 0.25 * value_loss - entropy_bonus

                loss.backward()
                self.optimizer.step()

                approx_kl = (batch_old_log_probs - new_log_probs).mean().item()

                if abs(approx_kl) > self.target_kl_div:
                    print(f"Early stop on policy update: KL={approx_kl:.4f}")
                    return


    # def train_value(self, obs, returns):
        # rollout_size = obs["Blocks"].shape[0]
        # torch.cuda.empty_cache()
        # torch.cuda.reset_peak_memory_stats()
        # for j in range(self.value_train_iters):
        #     if j % 10 == 0:
        #         print(f'Value iteration {j}')
        #
        #
        #     for i in range(0, rollout_size, batch_size):
        #         self.value_optimizer.zero_grad()
        #
        #         batch_obs = {
        #             'Inventory': obs['Inventory'][i:i + batch_size],
        #             'Blocks': obs['Blocks'][i:i + batch_size],
        #             'Entities': obs['Entities'][i:i + batch_size],
        #             'AgentInfo': obs['AgentInfo'][i:i + batch_size],
        #         }
        #         batch_returns = returns[i:i+batch_size]
        #
        #         value = self.ac.value(batch_obs)
        #         value_loss = ((batch_returns - value) ** 2).mean()
        #
        #         value_loss.backward()
        #         self.value_optimizer.step()
