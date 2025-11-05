import torch
import torch.nn as nn

class MinecraftTransformer(nn.Module):
    def __init__(self, embedding_dim=64, num_heads=4, num_layers=2, dropout=0.1):
        super().__init__()

        assert embedding_dim % num_heads == 0, \
            f"embedding_dim ({embedding_dim}) must be divisible by num_heads ({num_heads})"

        # (batch, n, m)
        encoder_layer = nn.TransformerEncoderLayer(d_model=embedding_dim, nhead=num_heads, dim_feedforward=embedding_dim * 4, dropout=dropout, batch_first=True)

        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=num_layers)

    def forward(self, x):
        print(f'x: {x.shape}')
        return torch.flatten(self.encoder(x), start_dim=1)
