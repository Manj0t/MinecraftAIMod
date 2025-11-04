import torch
import torch.nn as nn

class ItemEmbedder(nn.Module):
    # each item is represented as [item_id, isArmor, isFood, isTool, isWeapon, utility1, utility2, count, durability]
    # Input (batch_size, 41, 9)

    def __init__(self, num_items):
        super().__init__()
        self.id_embedding = nn.Embedding(num_items, 64)

        self.scalar_mlp = nn.Sequential(
            nn.Linear(6, 32),
            nn.ReLU(),
            nn.Linear(32, 16),
        )

        # self.fuse = nn.Linear(64 + 16, 64)
        #
        # self.output = nn.Linear(64 + 2, 64)

        self.final = nn.Sequential(
            nn.Linear(64 + 16 + 2, 64),
            nn.ReLU(),
            nn.LayerNorm(64),
        )

    def forward(self, item_info):
        id = item_info[..., 0].long()
        scalar = item_info[..., 1:7].float()
        count_and_durability = item_info[..., 7:].float()

        id_embedding = self.id_embedding(id)
        scalar_vec = self.scalar_mlp(scalar)

        # old comment (batch_size, 41, 66)
        combined = torch.cat((id_embedding, scalar_vec, count_and_durability), dim=-1)

        return self.final(combined)


class BlockEmbedder(nn.Module):
    # [Block id, x, y, z]
    # Input: (batch_size, idk, 4)

    def __init__(self, num_blocks):
        super().__init__()
        self.block_embedding = nn.Embedding(num_blocks, 64)

        self.final = nn.Sequential(
            nn.Linear(64 + 3, 64),
            nn.ReLU(),
            nn.LayerNorm(64),
        )
    def forward(self, block_info):
        block_id = block_info[..., 0].long()
        block_position = block_info[..., 1:].float()

        block_embedding = self.block_embedding(block_id)

        # (batch_size, idk, 67)
        combined = torch.cat((block_embedding, block_position), dim=-1)

        return self.final(combined)


class EntityEmbedder(nn.Module):
    # [entity id, isMonster, isAngerable, isPassive, isUnknown, x, y, z]
    # Input: (batch_size, 10, 8)
    def __init__(self, num_entities):
        super().__init__()
        self.entity_embedding = nn.Embedding(num_entities, 64)

        self.scalar_mlp = nn.Sequential(
            nn.Linear(4, 32),
            nn.ReLU(),
            nn.Linear(32, 16),
        )

        self.final = nn.Sequential(
            nn.Linear(64 + 16 + 3, 64),
            nn.ReLU(),
            nn.LayerNorm(64),
        )

    def forward(self, entity_info):
        id = entity_info[..., 0].long()
        scalar = entity_info[..., 1:5].float()
        entity_pos = entity_info[..., 5:].float()

        entity_embedding = self.entity_embedding(id)
        scalar_vec = self.scalar_mlp(scalar)
        combined = torch.cat((entity_embedding, scalar_vec, entity_pos), dim=-1)

        # (batch_size, 10, 67)

        return self.final(combined)
