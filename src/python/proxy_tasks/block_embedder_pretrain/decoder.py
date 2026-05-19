import torch
import torch.nn as nn

class BlockPropertyDecoder(nn.Module):
    def __init__(self, input_dim: int, output_dim: int):
        super().__init__()

        self.head = nn.Linear(input_dim, output_dim)

    def forward(self, block_embedding: torch.Tensor) -> torch.Tensor:
        return self.head(block_embedding)
