import torch
from torch import nn
from Embedders import ItemEmbedder, BlockEmbedder, EntityEmbedder
from Transformers import MinecraftTransformer

class ActorCriticNetwork(nn.Module):
    def __init__(self, agent_info_dim, num_items, num_blocks, num_entities):
        super().__init__()

        self.item_embedder = ItemEmbedder(num_items)
        self.block_embedder = BlockEmbedder(num_blocks)
        self.entity_embedder = EntityEmbedder(num_entities)

        embed_dim = 64
        self.item_transformer = MinecraftTransformer(embed_dim)
        self.block_transformer = MinecraftTransformer(embed_dim)
        self.entity_transformer = MinecraftTransformer(embed_dim)

        obs_space_size = agent_info_dim + embed_dim * 3

        self.shared_layers = nn.Sequential(
            nn.Linear(obs_space_size, 256),
            nn.ReLU(),
            nn.Linear(256, 256),
            nn.ReLU(),
            nn.Linear(256, 128),
            nn.ReLU(),
        )

        self.movement_policy = nn.Linear(128, 5)    # forward, backward, left, right, none
        self.jump_policy = nn.Linear(128, 1)        # Do or don't jump
        self.item_use_policy = nn.Linear(128, 3)    # Left click (attack), right click (use item), neither
        self.hotbar_policy = nn.Linear(128, 9)      # Active hotbar slot

        self.value_layer = nn.Sequential(
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1)
        )


    def obs_preprocessing(self, obs):
        item_embedding = self.item_embedder(obs['Inventory']).unsqueeze(0)
        block_embedding = self.block_embedder(obs['Blocks']).unsqueeze(0)
        entity_embedding = self.entity_embedder(obs['Entities']).unsqueeze(0)
        agent_info = obs['AgentInfo'].unsqueeze(0)

        item_x = self.item_transformer(item_embedding)
        block_x = self.block_transformer(block_embedding)
        entity_x = self.entity_transformer(entity_embedding)

        print(item_x.shape)
        print(block_x.shape)
        print(entity_x.shape)
        print(agent_info.shape)

        return torch.cat([agent_info, item_x, block_x, entity_x], dim=-1)


    def get_policy_logits(self, x):
        movement_policy_logits = self.movement_policy(x)
        jump_policy_logits = self.jump_policy(x)
        item_use_policy_logits = self.item_use_policy(x)
        hotbar_policy_logits = self.hotbar_policy(x)

        policy_logits = {
            'movement': movement_policy_logits,
            'jump': jump_policy_logits,
            'item_use': item_use_policy_logits,
            'hotbar': hotbar_policy_logits,
        }

        return policy_logits


    def value(self, obs):
        x = self.shared_layers(obs)
        return self.value_layer(x)


    def policy(self, x):
        x = self.obs_preprocessing(x)
        x = self.shared_layers(x)

        policy_logits = self.get_policy_logits(x)

        return policy_logits


    def forward(self, obs):
        n_obs = self.obs_preprocessing(obs)
        print(n_obs.shape)
        print(n_obs)
        x = self.shared_layers(n_obs)

        policy_logits = self.get_policy_logits(x)
        value = self.value_layer(x)

        return policy_logits, value