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
        #  * ( (5 * 2) + 1) * ( (3 * 2) + 1) * ( (5 * 2) + 1 )
        self.block_transformer = MinecraftTransformer(embed_dim)
        self.entity_transformer = MinecraftTransformer(embed_dim)

        obs_space_size = agent_info_dim + 41 * embed_dim + embed_dim* ( (5 * 2) + 1) * ( (3 * 2) + 1) * ( (5 * 2) + 1 ) + 10 * embed_dim

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
        print(obs['Inventory'].shape)
        item_embedding = self.item_embedder(obs['Inventory'])
        block_embedding = self.block_embedder(obs['Blocks'])
        entity_embedding = self.entity_embedder(obs['Entities'])
        agent_info = obs['AgentInfo']

        print(f'item_embeding shape {item_embedding.shape}')
        print(f'block_embedding shape {block_embedding.shape}')
        print(f'entity_embedding shape {entity_embedding.shape}')
        print(f'agent_info shape {agent_info.shape}')

        if len(item_embedding.shape) == 2:
            item_embedding = item_embedding.unsqueeze(0)
        if len(block_embedding.shape) == 2:
            block_embedding = block_embedding.unsqueeze(0)
        if len(entity_embedding.shape) == 2:
            entity_embedding = entity_embedding.unsqueeze(0)

        if len(agent_info.shape) == 1:
            agent_info = agent_info.unsqueeze(0)

        print(f'agent_info shape after {agent_info.shape}')
        print(f'item_embedding shape after {item_embedding.shape}')
        print(f'block_embedding shape after {block_embedding.shape}')
        print(f'entity_embedding shape after {entity_embedding.shape}')

        item_x = torch.flatten(item_embedding, start_dim=1)
        block_x = self.block_transformer(block_embedding)
        entity_x = torch.flatten(entity_embedding, start_dim=1)

        # print(item_x.shape)
        # print(block_x.shape)
        # print(entity_x.shape)
        # print(agent_info.shape)

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
        n_obs = self.obs_preprocessing(obs)
        x = self.shared_layers(n_obs)
        return self.value_layer(x)


    def policy(self, obs):
        n_obs = self.obs_preprocessing(obs)
        x = self.shared_layers(n_obs)

        policy_logits = self.get_policy_logits(x)

        return policy_logits


    def forward(self, obs):
        n_obs = self.obs_preprocessing(obs)
        # print(n_obs.shape)
        # print(n_obs)
        x = self.shared_layers(n_obs)

        policy_logits = self.get_policy_logits(x)
        value = self.value_layer(x)

        return policy_logits, value