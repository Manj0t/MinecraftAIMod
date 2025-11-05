import torch
from torch import nn
from torch import optim
from torch.distributions import Categorical, Bernoulli

class PPOTrainer():
    def __init__(self, actor_critic, ppo_clip_val=0.2, target_kl_div=0.01, max_policy_train_iters=80, value_train_iters=80, policy_lr=3e-4, value_lr=1e-3,):
        self.ac = actor_critic
        self.ppo_clip_val = ppo_clip_val
        self.target_kl_div = target_kl_div
        self.max_policy_train_iters = max_policy_train_iters
        self.value_train_iters = value_train_iters

        shared_params = list(self.ac.shared_layers.parameters())
        value_params = shared_params + list(self.ac.value_layer.parameters())
        policy_params = shared_params + \
                list(self.ac.movement_policy.parameters()) + \
                list(self.ac.jump_policy.parameters()) + \
                list(self.ac.item_use_policy.parameters()) + \
                list(self.ac.hotbar_policy.parameters())

        self.value_optimizer = optim.Adam(value_params, lr=value_lr)
        self.policy_optimizer = optim.Adam(policy_params, lr=policy_lr)


    def train_policy(self, obs, act, old_log_probs, advantages):
        for _ in range(self.max_policy_train_iters):
            self.policy_optimizer.zero_grad()

            logits_dict = self.ac.policy(obs)

            movement_dist = Categorical(logits=logits_dict['movement'])
            jump_dist = Bernoulli(logits=logits_dict['jump'].squeeze(-1))
            item_use_dist = Categorical(logits=logits_dict['item_use'])
            hotbar_dist = Categorical(logits=logits_dict['hotbar'])

            movement_act = act['movement']
            jump_act = act['jump']
            item_use_act = act['item_use']
            hotbar_act = act['hotbar']

            print(f' movement shape : {movement_act.shape}')
            print(f' jump shape : {jump_act.shape}')
            print(f' item use shape : {item_use_act.shape}')
            print(f' hotbar shape : {hotbar_act.shape}')

            movement_log_prob = movement_dist.log_prob(movement_act)
            jump_log_prob = jump_dist.log_prob(jump_act)
            item_use_log_prob = item_use_dist.log_prob(item_use_act)
            hotbar_log_prob = hotbar_dist.log_prob(hotbar_act)

            new_log_probs = movement_log_prob + jump_log_prob + item_use_log_prob + hotbar_log_prob

            # ignore for now, may change later so easier to change
            entropy_bonus = (
                movement_dist.entropy() +
                jump_dist.entropy() +
                item_use_dist.entropy() +
                hotbar_dist.entropy()
                ).mean() * 0.01

            policy_ratio = torch.exp(new_log_probs - old_log_probs)
            clipped_ratio = policy_ratio.clamp(1 - self.ppo_clip_val, 1 + self.ppo_clip_val)

            clipped_loss = clipped_ratio * advantages
            full_loss = policy_ratio * advantages

            policy_loss = -torch.min(clipped_loss, full_loss).mean() - entropy_bonus

            policy_loss.backward()
            self.policy_optimizer.step()

            approx_kl = (old_log_probs - new_log_probs).mean().abs()

            if approx_kl > self.target_kl_div:
                print(f"Early stop on policy update: KL={approx_kl:.4f}")
                break


    def train_value(self, obs, returns):

        for _ in range(self.value_train_iters):
            self.value_optimizer.zero_grad()

            value = self.ac.value(obs)
            value_loss = ((returns - value) ** 2).mean()

            value_loss.backward()
            self.value_optimizer.step()
