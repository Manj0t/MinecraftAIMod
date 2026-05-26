import torch
from torch import nn
from torch import optim
from torch.distributions import Categorical, Bernoulli
import numpy as np
from utils import debug

batch_size = 128


class PPOTrainer():
    def __init__(self, actor_critic, cont_model, ppo_clip_val=0.2, target_kl_div=0.10, max_policy_train_iters=10, value_train_iters=10, policy_lr=3e-4):
        self.ac = actor_critic
        self.cont_model = cont_model
        self.ppo_clip_val = ppo_clip_val
        self.target_kl_div = target_kl_div
        self.max_policy_train_iters = max_policy_train_iters
        self.value_train_iters = value_train_iters

        shared_params = (
                list(self.ac.pre_encoder.parameters()) +
                list(self.ac.gru.parameters()) +
                list(self.ac.post_gru.parameters())
        )
        value_params = list(self.ac.value_layer.parameters())
        policy_params = (
                 list(self.ac.inv_action_type.parameters()) +
                 list(self.ac.movement_policy.parameters()) +
                 list(self.ac.move_side_policy.parameters()) +
                 list(self.ac.pan_camera.parameters()) +
                 list(self.ac.item_use_policy.parameters()) +
                 list(self.ac.hotbar_policy.parameters()) +
                 list(self.ac.from_slot.parameters()) +
                 list(self.ac.to_slot.parameters()) +
                 list(self.ac.drop_slot.parameters()) +
                 list(self.ac.drop_all.parameters()) +
                 list(self.ac.craft_item_id.parameters())
                )

        cont_shared_params = list(self.cont_model.shared_layers.parameters())
        cont_policy_params = (
            list(self.cont_model.close_container.parameters()) +
            list(self.cont_model.container_slot.parameters()) +
            list(self.cont_model.inventory_slot.parameters())
        )

        cont_value_params = list(self.cont_model.value_layer.parameters())

        self.optimizer = optim.Adam(shared_params + value_params + policy_params, lr=policy_lr)
        self.cont_optim = optim.Adam(cont_shared_params + cont_policy_params + cont_value_params, lr=policy_lr)

    def imitation_train_policy(self, obs, actions):

        for _ in range(self.max_policy_train_iters):
            logits_dict, _ = self.ac(obs)

            # logits
            inv_act_logits = logits_dict['inv_act']
            movement_logits = logits_dict["movement"]
            side_move_logits = logits_dict["side_movement"]
            item_logits = logits_dict["item_use"]
            hotbar_logits = logits_dict["hotbar"]
            pan_logits = logits_dict["pan_camera"]

            from_slot_logits = logits_dict["from_slot"]
            to_slot_logits = logits_dict["to_slot"]

            drop_slot_logits = logits_dict["drop_slot"]
            drop_all_logits = logits_dict["drop_all_flag"]

            craft_logits = logits_dict["craft_item_id"]

            # targets
            inv_act_t = actions[:, 0].long()

            movement_t = actions[:, 1].long()
            side_move_t = actions[:, 2].long()
            item_t = actions[:, 3].long()
            hotbar_t = actions[:, 4].long()
            pan_t = actions[:, 5].long()

            from_slot_t = actions[:, 6].long()
            to_slot_t = actions[:, 7].long()

            drop_slot_t = actions[:, 8].long()
            drop_all_t = actions[:, 9].float()

            craft_t = actions[:, 10].long()

            # masks by inv_act
            is_move = (inv_act_t == 0)
            is_swap = (inv_act_t == 1)
            is_drop = (inv_act_t == 2)
            is_craft = (inv_act_t == 3)

            loss = 0.0

            # inv action always trained
            loss += nn.CrossEntropyLoss()(inv_act_logits, inv_act_t)

            # move
            if is_move.any():
                loss += nn.CrossEntropyLoss()(movement_logits[is_move], movement_t[is_move])
                loss += nn.CrossEntropyLoss()(side_move_logits[is_move], side_move_t[is_move])
                loss += nn.CrossEntropyLoss()(item_logits[is_move], item_t[is_move])
                loss += nn.CrossEntropyLoss()(hotbar_logits[is_move], hotbar_t[is_move])
                loss += nn.CrossEntropyLoss()(pan_logits[is_move], pan_t[is_move])

            # swap
            if is_swap.any():
                loss += nn.CrossEntropyLoss()(from_slot_logits[is_swap], from_slot_t[is_swap])
                loss += nn.CrossEntropyLoss()(to_slot_logits[is_swap], to_slot_t[is_swap])

            # drop
            if is_drop.any():
                loss += nn.CrossEntropyLoss()(drop_slot_logits[is_drop], drop_slot_t[is_drop])
                loss += nn.BCEWithLogitsLoss()(drop_all_logits[is_drop].squeeze(-1), drop_all_t[is_drop])

            # craft
            if is_craft.any():
                loss += nn.CrossEntropyLoss()(craft_logits[is_craft], craft_t[is_craft])

            self.optimizer.zero_grad()
            loss.backward()
            self.optimizer.step()

    def imitation_train_policy_cont(self, obs, actions):

        for _ in range(self.max_policy_train_iters):
            logits_dict, _ = self.cont_model(obs)

            close_logits = logits_dict["close_container"].squeeze(-1)
            cont_logits = logits_dict["container_slot"]
            inv_logits = logits_dict["inventory_slot"]

            inv_slot_t = actions[:, 0].long()
            cont_slot_t = actions[:, 1].long()
            close_t = actions[:, 2].float()

            loss = (
                    nn.CrossEntropyLoss()(inv_logits, inv_slot_t) +
                    nn.CrossEntropyLoss()(cont_logits, cont_slot_t) +
                    nn.BCEWithLogitsLoss()(close_logits, close_t)
            )

            self.cont_optim.zero_grad()
            loss.backward()
            self.cont_optim.step()

    def test_accuracy(self, obs, actions, world_policy_bool, batch_size=1024):
        if world_policy_bool:
            self.ac.eval()

            total = 0
            correct_all = 0

            # per-head stats
            stats = {
                "inv_act": [0, 0],
                "move": [0, 0],
                "swap": [0, 0],
                "drop": [0, 0],
                "craft": [0, 0],
            }

            with (torch.no_grad()):
                for i in range(0, len(actions), batch_size):
                    batch_obs = {k: v[i:i + batch_size] for k, v in obs.items()}
                    batch_act = actions[i:i + batch_size]

                    logits = self.ac.policy(batch_obs)

                    inv_act_t = batch_act[:, 0].long()
                    inv_act_p = logits["inv_act"].argmax(dim=1)

                    total += len(batch_act)
                    stats["inv_act"][1] += len(batch_act)
                    stats["inv_act"][0] += (inv_act_p == inv_act_t).sum().item()

                    all_ok = (inv_act_p == inv_act_t)

                    # MOVE
                    mask = inv_act_t == 0
                    if mask.any():
                        move_ok = (
                                          logits["movement"][mask].argmax(1) == batch_act[mask, 1]
                                  ) & (
                                        logits["side_movement"][mask].argmax(1) == batch_act[mask, 2]
                                  ) & (
                                          logits["item_use"][mask].argmax(1) == batch_act[mask, 3]
                                  ) & (
                                          logits["hotbar"][mask].argmax(1) == batch_act[mask, 4]
                                  ) & (
                                          logits["pan_camera"][mask].argmax(1) == batch_act[mask, 5]
                                  )

                        stats["move"][1] += mask.sum().item()
                        stats["move"][0] += move_ok.sum().item()
                        all_ok[mask] &= move_ok

                    # SWAP
                    mask = inv_act_t == 1
                    if mask.any():
                        swap_ok = (
                                          logits["from_slot"][mask].argmax(1) == batch_act[mask, 6]
                                  ) & (
                                          logits["to_slot"][mask].argmax(1) == batch_act[mask, 7]
                                  )

                        stats["swap"][1] += mask.sum().item()
                        stats["swap"][0] += swap_ok.sum().item()
                        all_ok[mask] &= swap_ok

                    # DROP
                    mask = inv_act_t == 2
                    if mask.any():
                        drop_ok = (
                                          logits["drop_slot"][mask].argmax(1) == batch_act[mask, 8]
                                  ) & (
                                          (logits["drop_all_flag"][mask].squeeze(-1) > 0) == (batch_act[mask, 9] > 0.5)
                                  )

                        stats["drop"][1] += mask.sum().item()
                        stats["drop"][0] += drop_ok.sum().item()
                        all_ok[mask] &= drop_ok

                    # CRAFT
                    mask = inv_act_t == 3
                    if mask.any():
                        craft_ok = (
                                logits["craft_item_id"][mask].argmax(1) == batch_act[mask, 10]
                        )

                        stats["craft"][1] += mask.sum().item()
                        stats["craft"][0] += craft_ok.sum().item()
                        all_ok[mask] &= craft_ok

                    correct_all += all_ok.sum().item()

            print("=== WORLD POLICY ACCURACY ===")
            for k, (c, n) in stats.items():
                if n > 0:
                    print(f"{k:10s}: {c / n:.3f}")
            print(f"ALL ACTIONS: {correct_all / total:.3f}")

            self.ac.train()
            return correct_all / total
        else:
            if actions.size(1) != 3:
                raise ValueError(
                    f"Container policy expects action dim = 3, got {actions.shape}"
                )

        self.cont_model.eval()

        total = 0
        inv_correct = 0
        cont_correct = 0
        close_correct = 0
        all_correct = 0

        with torch.no_grad():
            for i in range(0, len(actions), batch_size):
                batch_obs = {k: v[i:i + batch_size] for k, v in obs.items()}
                batch_act = actions[i:i + batch_size]

                logits, _ = self.cont_model(batch_obs)

                inv_pred = logits["inventory_slot"].argmax(dim=1)
                cont_pred = logits["container_slot"].argmax(dim=1)
                close_pred = (logits["close_container"].squeeze(-1) > 0)

                inv_t = batch_act[:, 0].long()
                cont_t = batch_act[:, 1].long()
                close_t = batch_act[:, 2] > 0.5

                inv_ok = inv_pred == inv_t
                cont_ok = cont_pred == cont_t
                close_ok = close_pred == close_t

                all_ok = inv_ok & cont_ok & close_ok

                inv_correct += inv_ok.sum().item()
                cont_correct += cont_ok.sum().item()
                close_correct += close_ok.sum().item()
                all_correct += all_ok.sum().item()

                total += len(batch_act)

        print("=== CONTAINER POLICY ACCURACY ===")
        print(f"Inventory slot : {inv_correct / total:.3f}")
        print(f"Container slot : {cont_correct / total:.3f}")
        print(f"Close flag     : {close_correct / total:.3f}")
        print(f"ALL ACTIONS    : {all_correct / total:.3f}")

        self.cont_model.train()
        return all_correct / total


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
                    'NearbyItemDrops' : obs['NearbyItemDrops'][i:i+batch_size],
                    'AgentInfo': obs['AgentInfo'][i:i+batch_size],
                    'PrevActions' : obs['PrevActions'][i:i+batch_size],
                }

                batch_h = obs["HiddenStates"][i:i+batch_size]

                batch_act = {
                    'inv_act'   : act['inv_act'][i:i+batch_size],

                    'movement'  : act['movement'][i:i+batch_size],
                    'side_movement': act['side_movement'][i:i+batch_size],
                    'item_use'  : act['item_use'][i:i+batch_size],
                    'hotbar'    : act['hotbar'][i:i+batch_size],
                    'pan_cam'   : act['pan_cam'][i:i+batch_size],

                    'from_slot' : act['from_slot'][i:i+batch_size],
                    'to_slot'   : act['to_slot'][i:i+batch_size],

                    'drop_slot' : act['drop_slot'][i:i+batch_size],
                    'drop_all_flag' : act['drop_all_flag'][i:i+batch_size],

                    'craft_item_id' : act['craft_item_id'][i:i+batch_size],
                }

                batch_summed_old_log_probs = summed_old_log_probs[i:i+batch_size].detach()
                # batch_old_log_probs = {
                #     "old_movement_lp"   : old_log_probs['old_movement_lp'][i:i+batch_size],
                #     "old_item_use_lp"   : old_log_probs['old_item_use_lp'][i:i+batch_size],
                #     "old_hotbar_lp"     : old_log_probs['old_hotbar_lp'][i:i+batch_size],
                #     "old_pan_cam_lp"    : old_log_probs['old_pan_cam_lp'][i:i+batch_size]
                # }

                batch_advantages = advantages[i:i+batch_size].detach()

                logits_dict, value = self.ac.forward_train(batch_obs, batch_h)

                # ===== distributions =====
                inv_act_dist = Categorical(logits=logits_dict['inv_act'])

                movement_dist = Categorical(logits=logits_dict['movement'])
                side_move_dist = Categorical(logits=logits_dict['side_movement'])
                item_use_dist = Categorical(logits=logits_dict['item_use'])
                hotbar_dist = Categorical(logits=logits_dict['hotbar'])
                pan_cam_dist =  Categorical(logits=logits_dict['pan_camera'])

                from_slot_dist = Categorical(logits=logits_dict['from_slot'])
                to_slot_dist = Categorical(logits=logits_dict['to_slot'])

                drop_slot_dist = Categorical(logits=logits_dict['drop_slot'])
                drop_all_flag_dist = Bernoulli(logits=logits_dict['drop_all_flag'])

                craft_item_id_dist = Categorical(logits=logits_dict['craft_item_id'])

                # ===== actions =====
                inv_act = batch_act['inv_act']

                movement_act = batch_act['movement']
                side_move_act = batch_act['side_movement']
                item_use_act = batch_act['item_use']
                hotbar_act = batch_act['hotbar']
                pan_cam_act = batch_act['pan_cam']

                from_slot_act = batch_act['from_slot']
                to_slot_act = batch_act['to_slot']

                drop_slot_act = batch_act['drop_slot']
                drop_all_flag_act = batch_act['drop_all_flag'].float()

                craft_item_act = batch_act['craft_item_id']

                # ===== log-probs =====
                inv_lp = inv_act_dist.log_prob(inv_act)

                move_lp = (
                        movement_dist.log_prob(movement_act)
                        + side_move_dist.log_prob(side_move_act)
                        + item_use_dist.log_prob(item_use_act)
                        + hotbar_dist.log_prob(hotbar_act)
                        + pan_cam_dist.log_prob(pan_cam_act)
                )

                swap_lp = (
                        from_slot_dist.log_prob(from_slot_act)
                        + to_slot_dist.log_prob(to_slot_act)
                )

                drop_lp = (
                        drop_slot_dist.log_prob(drop_slot_act)
                        + drop_all_flag_dist.log_prob(drop_all_flag_act).squeeze(-1)
                )

                craft_lp = craft_item_id_dist.log_prob(craft_item_act)

                # ===== masks =====
                is_move = (inv_act == 0).float()
                is_swap = (inv_act == 1).float()
                is_drop = (inv_act == 2).float()
                is_craft = (inv_act == 3).float()

                # ===== final PPO log-prob =====
                new_log_probs = (
                        inv_lp
                        + is_move * move_lp
                        + is_swap * swap_lp
                        + is_drop * drop_lp
                        + is_craft * craft_lp
                )


                approx_kl = 0.5 * ((new_log_probs - batch_summed_old_log_probs) ** 2).mean()

                entropy_bonus = (
                        inv_act_dist.entropy()
                        + is_move * (
                                movement_dist.entropy()
                                + side_move_dist.entropy()
                                + item_use_dist.entropy()
                                + hotbar_dist.entropy()
                                + pan_cam_dist.entropy()
                        )
                        + is_swap * (
                                from_slot_dist.entropy()
                                + to_slot_dist.entropy()
                        )
                        + is_drop * (
                                drop_slot_dist.entropy()
                                + drop_all_flag_dist.entropy().squeeze(-1)
                        )
                        + is_craft * craft_item_id_dist.entropy()
                ).mean() * 0.15

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

            if debug.DEBUG_KL and len(kl_arr) > 0:
                print(f'Average kl across batches {np.mean(kl_arr)}')
