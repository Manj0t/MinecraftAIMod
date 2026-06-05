import torch
from proxy_tasks.direction_pred_pretrain.model import DirectionClassifier


def main(num_epochs: int = 50, batch_size: int = 32):
    data = torch.load("data/proxy_walkable_data.pt")

    

    proxyWalkData.append({
        "obs": {
            "Blocks": blocks_np,
            "AgentInfo": agent_info_np
        },
        "is_walkable_target": is_walkable_targets  # The true y values [Forward, Backward, Left, Right]
    })