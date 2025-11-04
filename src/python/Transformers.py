import torch
import torch.nn as nn

class MinecraftTransformer(nn.Module):
    def __init__(self, embedding_dim=66, num_heads=4, num_layers=2, dropout=0.1):
        super().__init__()

        encoder_layer = nn.TransformerEncoderLayer(d_model=embedding_dim, nhead=num_heads, dim_feedforward=embedding_dim * 4, dropout=dropout, batch_first=True)

        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=num_layers)

    def forward(self, x):
        return self.encoder(x).mean(dim=1)
