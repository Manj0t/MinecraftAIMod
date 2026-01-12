import torch
import torch.nn as nn


class ItemEmbedder(nn.Module):
    def __init__(self, num_items):
        super().__init__()
        self.id_embedding = nn.Embedding(num_items, 64)

        self.scalar_mlp = nn.Sequential(
            nn.Linear(7, 64),
            nn.ReLU(),
            nn.Linear(64, 64),
            nn.ReLU(),
            nn.Linear(64, 32),
        )

        self.final = nn.Sequential(
            nn.Linear(64 + 32 + 2, 128),
            nn.ReLU(),
            nn.LayerNorm(128),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 64),  # Final output
        )

    def forward(self, item_info):
        # print(item_info)
        id = item_info[..., 0].long()
        scalar = item_info[..., 1:8].float()
        count_and_durability = item_info[..., 8:].float()

        id_embedding = self.id_embedding(id)
        scalar_vec = self.scalar_mlp(scalar)

        combined = torch.cat((id_embedding, scalar_vec, count_and_durability), dim=-1)

        return self.final(combined)


class BlockEmbedder(nn.Module):
    # [Block id, x, y, z]

    def __init__(self, num_blocks):
        super().__init__()
        self.block_embedding = nn.Embedding(num_blocks, 64)

    def forward(self, block_info, look_emb=False):
        # block_ids: (D, H, W) OR (batch, D, H, W)

        if block_info.dim() == 3:
            block_info = block_info.unsqueeze(0)

        # (B, D, H, W, C)
        block_embedding = self.block_embedding(block_info)

        # (B, C, D, H, W)
        if not look_emb:
            block_embedding = block_embedding.permute(0, 4, 1, 2, 3)

        return block_embedding


class EntityEmbedder(nn.Module):
    def __init__(self, num_entities):
        super().__init__()
        self.entity_embedding = nn.Embedding(num_entities, 64)

        self.scalar_mlp = nn.Sequential(
            nn.Linear(4, 64),
            nn.ReLU(),
            nn.Linear(64, 64),
            nn.ReLU(),
            nn.Linear(64, 32),
        )

        self.final = nn.Sequential(
            nn.Linear(64 + 32 + 3, 128),
            nn.ReLU(),
            nn.LayerNorm(128),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 64),
        )

    def forward(self, entity_info):
        id = entity_info[..., 0].long()
        scalar = entity_info[..., 1:5].float()
        entity_pos = entity_info[..., 5:].float()

        entity_embedding = self.entity_embedding(id)
        scalar_vec = self.scalar_mlp(scalar)
        combined = torch.cat((entity_embedding, scalar_vec, entity_pos), dim=-1)

        return self.final(combined)
