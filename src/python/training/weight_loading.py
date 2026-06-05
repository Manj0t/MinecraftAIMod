import os
import torch

from models.ActorCritic import ActorCriticNetwork
from training.PPOTrainer import PPOTrainer


def load_checkpoint(world_model: ActorCriticNetwork, ppo: PPOTrainer, load_path: str, device, proxy_paths: dict[str: str] | None = None, ep_rewards=None, load_optimizer=True):
    if not os.path.exists(load_path):
        print("- No checkpoint found, training from scratch")
        if proxy_paths is not None:
            for type, proxy_path in proxy_paths.items():
                checkpoint = torch.load(proxy_path, map_location=device, weights_only=False)
                if type == "block_embedder":
                    world_model.block_embedder.load_state_dict(checkpoint["embedder_state_dict"])
                    for param in world_model.block_embedder.parameters():
                        param.requires_grad = False
                elif type == "block_encoder":
                    world_model.block_encoder.load_state_dict(checkpoint["encoder_state_dict"])
                print(f'Loaded proxy {proxy_path}')
        return 0, float('-inf')

    print(f"<(-_-)> Loading checkpoint: {load_path}")
    checkpoint = torch.load(load_path, map_location=device, weights_only=False)

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
    print("-> Loaded:", len(list(filtered_dict.keys())))
    if skipped:
        print("- Skipped:", len(skipped))

    model_dict.update(filtered_dict)
    world_model.load_state_dict(model_dict, strict=False)

    if load_optimizer and "optimizer_state_dict" in checkpoint:
        try:
            ppo.optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
            print("> Loaded optimizer state")
        except Exception:
            print("> Optimizer state incompatible — using fresh optimizer")
    else:
        print("> Using fresh optimizer with correct LR groups")

    best_reward = checkpoint.get("best_reward", float('-inf'))
    iteration = checkpoint.get("iter", 0) + 1  # +1 so we start on the NEXT iteration

    # Restore reward history for continuous plotting
    if ep_rewards is not None and "rewards" in checkpoint:
        saved_rewards = checkpoint["rewards"]
        ep_rewards.extend(saved_rewards)
        print(f"> Restored {len(saved_rewards)} reward entries")

    if proxy_paths is not None:
        for type, proxy_path in proxy_paths.items():
            checkpoint = torch.load(proxy_path, map_location=device, weights_only=False)
            if type == "block_embedder":
                world_model.block_embedder.load_state_dict(checkpoint["embedder_state_dict"])
                for param in world_model.block_embedder.parameters():
                    param.requires_grad = False
            elif type == "block_encoder":
                world_model.block_encoder.load_state_dict(checkpoint["encoder_state_dict"])

    print(f"> Loaded model! Iteration: {iteration}, Best reward: {best_reward}")
    return iteration, best_reward