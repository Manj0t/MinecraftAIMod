import os
import torch

from models.ActorCritic import ActorCriticNetwork
from training.PPOTrainer import PPOTrainer


def load_checkpoint(world_model: ActorCriticNetwork, ppo: PPOTrainer, load_path: str, device, ep_rewards=None):
    """
    Load a checkpoint with shape-aware partial weight loading.
    Handles mismatched layer shapes by copying overlapping slices.

    Returns (iteration, best_reward) from checkpoint, or (0, -inf) if none found.
    """
    if not os.path.exists(load_path):
        print("- No checkpoint found, training from scratch")
        return 0, float('-inf')

    print(f"<(-_-)> Loading checkpoint: {load_path}")
    checkpoint = torch.load(load_path, map_location=device, weights_only=False)

    # --- world_model: partial load with shape matching ---
    model_dict = world_model.state_dict()
    pretrained_dict = checkpoint["world_model"]
    filtered_dict = {}

    for key, params in pretrained_dict.items():
        if key not in model_dict:
            continue
        if params.shape == model_dict[key].shape:
            filtered_dict[key] = params
        else:
            with torch.no_grad():
                new = model_dict[key].clone()
                slices = tuple(
                    slice(0, min(o, n))
                    for o, n in zip(params.shape, new.shape)
                )
                new[slices] = params[slices]
                filtered_dict[key] = new

    skipped = set(pretrained_dict.keys()) - set(filtered_dict.keys())
    print("-> Loaded:", list(filtered_dict.keys()))
    if skipped:
        print("- Skipped:", skipped)

    model_dict.update(filtered_dict)
    world_model.load_state_dict(model_dict, strict=False)

    # --- optimizer ---
    if "optimizer_state_dict" in checkpoint:
        try:
            ppo.optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
            print("> Loaded optimizer state!")
        except Exception:
            print("/| Optimizer state incompatible — resetting optimizer.")
            ppo.optimizer = torch.optim.Adam(
                ppo.optimizer.param_groups[0]["params"]
            )

    best_reward = checkpoint.get("best_reward", float('-inf'))
    if ep_rewards is not None:
        ep_rewards.append(best_reward)

    print(f"> Loaded model! Best reward: {best_reward}")
    return 0, best_reward