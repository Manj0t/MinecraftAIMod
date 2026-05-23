import torch
from torch import nn
from models.Embedders import ItemEmbedder, EntityEmbedder

import torch.nn.functional as F

class ContainerModel(nn.Module):
    def __init__(self, agent_info_dim, num_items, num_entities):
        super().__init__()

        self.item_embedder = ItemEmbedder(num_items)
        self.entity_embedder = EntityEmbedder(num_entities)

        self.embed_dim = 32

        obs_space_size = ((agent_info_dim + 1)
                          + 41 * self.embed_dim     # inventory
                          + 54 * self.embed_dim     # container
                          + 10 * self.embed_dim     # Nearby Entities
                          + 4                       # Container type
                          + 54                      # Contianer mask
                          )

        self.shared_layers = nn.Sequential(
            nn.Linear(obs_space_size, 256),
            nn.ReLU(),
            nn.Linear(256, 256),
            nn.ReLU(),
            nn.Linear(256, 128),
            nn.ReLU(),
        )

        self.close_container = nn.Linear(128, 1) # Don't Close 0, Close 1

        self.container_slot = nn.Linear(128, 54) # Maybe select the largest container for output dim?
        self.inventory_slot = nn.Linear(128, 36) # Possible slots from inventory to choose from

        self.value_layer = nn.Sequential(
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1)
        )


    def obs_preprocessing(self, obs):
        inventory_embedding = self.item_embedder(obs['Inventory'])
        container_embedding = self.item_embedder(obs['Container'])
        entity_embedding = self.entity_embedder(obs['Entities'])
        agent_info = obs['AgentInfo']
        container_type = obs['ContainerType'].long()
        container_mask = obs['ContainerMask']

        if len(agent_info.shape) == 1:
            agent_info = agent_info.unsqueeze(0)
        elif container_type.dim() == 2 and container_type.size(-1) == 1:
            container_type = container_type.squeeze(-1)  # [B,1] -> [B]
        if len(container_mask.shape) == 1:
            container_mask = container_mask.unsqueeze(0)

        agent_info = agent_info[:, :-9]
        container_type_one_hot = F.one_hot(container_type, 4).float() # None, Chest, Double chest, furnace
        container_mask = container_mask.float()

        if len(inventory_embedding.shape) == 2:
            inventory_embedding = inventory_embedding.unsqueeze(0)
        if len(entity_embedding.shape) == 2:
            entity_embedding = entity_embedding.unsqueeze(0)
        if len(container_embedding.shape) == 2:
            container_embedding = container_embedding.unsqueeze(0)

        inventory_x = torch.flatten(inventory_embedding, start_dim=1)
        entity_x = torch.flatten(entity_embedding, start_dim=1)
        container_x = torch.flatten(container_embedding, start_dim=1)

        return torch.cat([
            agent_info,
            inventory_x,
            container_x,
            entity_x,
            container_type_one_hot,
            container_mask
        ], dim=-1)


    def get_policy_logits(self, x):
        close_container = self.close_container(x)
        container_slot = self.container_slot(x)
        inventory_slot = self.inventory_slot(x)

        policy_logits = {
            'close_container' : close_container,
            'container_slot' : container_slot,
            'inventory_slot' : inventory_slot,
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
        if torch.isnan(n_obs).any() or torch.isinf(n_obs).any():
            print("❌ NaN or Inf detected in observation BEFORE network")

        x = self.shared_layers(n_obs)

        policy_logits = self.get_policy_logits(x)
        value = self.value_layer(x)

        return policy_logits, value