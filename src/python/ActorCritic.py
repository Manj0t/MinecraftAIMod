import torch
from torch import nn
from Embedders import ItemEmbedder, BlockEmbedder, EntityEmbedder
from Transformers import MinecraftTransformer
from BlockCNN import BlockCNN

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')

class ActorCriticNetwork(nn.Module):
    def __init__(self, agent_info_dim, num_items, num_blocks, num_entities):
        super().__init__()

        self.item_embedder = ItemEmbedder(num_items)
        self.block_embedder = BlockEmbedder(num_blocks)
        self.entity_embedder = EntityEmbedder(num_entities)

        self.embed_dim = 32

        # coreRadius = 5
        # verticalRadius = 3

        #  * ( (5 * 2) + 1) * ( (3 * 2) + 1) * ( (5 * 2) + 1 )
        self.block_transformer = MinecraftTransformer(self.embed_dim)
        self.block_cnn = BlockCNN(self.embed_dim)

        obs_space_size = (agent_info_dim + 0) + 41 * self.embed_dim + 4096 + 10 * self.embed_dim + 4 # +5 for prviouse actions

        self.shared_layers = nn.Sequential(
            nn.Linear(obs_space_size, 256),
            nn.ReLU(),
            nn.Linear(256, 256),
            nn.ReLU(),
            nn.Linear(256, 128),
            nn.ReLU(),
        )
                                                    #    0   ,   1     ,  2  ,  3   ,       4     ,  5  ,  6
        self.movement_policy = nn.Linear(128, 7)    # forward, backward, left, right, forward jump, jump, none
        # self.jump_policy = nn.Linear(128, 1)        # Do or don't jump
        self.item_use_policy = nn.Linear(128, 3)    # Left click (attack), right click (use item), neither
        self.hotbar_policy = nn.Linear(128, 9)      # Active hotbar slot
        self.pan_camera = nn.Linear(128, 5)         # Pan camera up, down, left, right

        self.value_layer = nn.Sequential(
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1)
        )


    def obs_preprocessing(self, obs):
        item_embedding = self.item_embedder(obs['Inventory'])
        block_embedding = self.block_embedder(obs['Blocks'])
        entity_embedding = self.entity_embedder(obs['Entities'])
        agent_info = obs['AgentInfo']
        prevActions = obs['PrevActions']
        if len(agent_info.shape) == 1:
            agent_info = agent_info.unsqueeze(0)
        if len(prevActions.shape) == 1:
            prevActions = prevActions.unsqueeze(0)

        look_slice = agent_info[:, -9:]
        agent_info = agent_info[:, :-9]

        # look_type = look_slice[:, 0].long()
        # looking_at_info = look_slice[:, 1:]
        #
        #
        # look_embeds = []
        # for b in range(agent_info.size(0)):
        #     type = int(look_type[b].item())
        #     if type == 0:
        #         look_embeds.append(torch.zeros(self.embed_dim, device=DEVICE))
        #     elif type == 1:
        #         look_embeds.append(self.block_embedder(looking_at_info[b:b+1, 0]).squeeze(0))
        #     else:
        #         look_embeds.append(self.entity_embedder(looking_at_info[b:b+1]).squeeze(0))
        #
        # look_embeds = torch.stack(look_embeds, dim=0)

        if len(item_embedding.shape) == 2:
            item_embedding = item_embedding.unsqueeze(0)
        if len(block_embedding.shape) == 3:
            block_embedding = block_embedding.unsqueeze(0)
        if len(entity_embedding.shape) == 2:
            entity_embedding = entity_embedding.unsqueeze(0)


        item_x = torch.flatten(item_embedding, start_dim=1)
        block_x = self.block_cnn(block_embedding)
        entity_x = torch.flatten(entity_embedding, start_dim=1)


        return torch.cat([agent_info, item_x, block_x, entity_x, prevActions], dim=-1)


    def get_policy_logits(self, x):
        movement_policy_logits = self.movement_policy(x)
        # jump_policy_logits = self.jump_policy(x)
        item_use_policy_logits = self.item_use_policy(x)
        hotbar_policy_logits = self.hotbar_policy(x)
        pan_camera_logits = self.pan_camera(x)

        policy_logits = {
            'movement': movement_policy_logits,
            # 'jump': jump_policy_logits,
            'item_use': item_use_policy_logits,
            'hotbar': hotbar_policy_logits,
            'pan_camera' : pan_camera_logits
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

    def debug_tensor(self, name, t):
        if torch.isnan(t).any() or torch.isinf(t).any():
            print(f"❌ NaN or Inf detected in {name}")
            print("   min:", torch.nanmin(t))
            print("   max:", torch.nanmax(t))
            print("   values:", t)
            raise ValueError(f"Invalid values in {name}")