import torch
from torch import nn
from Embedders import ItemEmbedder, BlockEmbedder, EntityEmbedder
from Transformers import MinecraftTransformer
from BlockCNN import BlockCNN
from ItemDropsCNN import ItemDropsCNN

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')

class ActorCriticNetwork(nn.Module):
    def __init__(self, agent_info_dim, num_items, num_blocks, num_entities):
        super().__init__()

        self.item_embedder = ItemEmbedder(num_items)
        self.block_embedder = BlockEmbedder(num_blocks)
        self.entity_embedder = EntityEmbedder(num_entities)

        self.embed_dim = 32

        #  * ( (5 * 2) + 1) * ( (3 * 2) + 1) * ( (5 * 2) + 1 )
        # self.block_transformer = MinecraftTransformer(self.embed_dim)
        self.block_cnn = BlockCNN(self.embed_dim)
        self.nearby_item_drops_cnn = ItemDropsCNN(self.embed_dim)

        obs_space_size = ((agent_info_dim + 0)
                          + 41 * self.embed_dim     # inventory
                          + 4096                    # Nearby Blocks
                          + 2048                    # Nearby Item Drops
                          + 10 * self.embed_dim     # Nearby Entities
                          + 10)                      # +10 for prviouse actions
        # obs_space_size = (agent_info_dim + 0) + 4096 + 4

        self.shared_layers = nn.Sequential(
            nn.Linear(obs_space_size, 256),
            nn.ReLU(),
            nn.Linear(256, 256),
            nn.ReLU(),
            nn.Linear(256, 128),
            nn.ReLU(),
        )

        self.inv_action_type = nn.Linear(128, 4)    # No inventory action 0, swap items 1, drop item 2, craft item 3
                                                    #    0   ,   1     ,  2  ,  3   ,       4     ,  5  ,  6
        self.movement_policy = nn.Linear(128, 7)    # forward, backward, left, right, forward jump, jump, none
        self.item_use_policy = nn.Linear(128, 3)    # Left click (attack)0 , right click (use item)1 , neither2
        self.hotbar_policy = nn.Linear(128, 9)      # Active hotbar slot
        self.pan_camera = nn.Linear(128, 5)         # Pan camera up 0, down 1, left 2, right 3

        # self.swap_flag = nn.Linear(128, 1)          # 0 no swap, 1 swap. If 0 ignores from and to slot
        self.from_slot = nn.Linear(128, 41)         # 41 possible slots to pick from
        self.to_slot = nn.Linear(128, 41)           # 41 possible slots to pick from

        # self.drop_flag = nn.Linear(128, 1)          # 0 no drop, 1 do drop. If 0 ignores drop_slot and drop_all
        self.drop_slot = nn.Linear(128, 41)         # 41 possible slots to pick to drop
        self.drop_all = nn.Linear(128, 1)           # 0 drop one, 1 drop all

        # self.craft_flag = nn.Linear(128, 1)         # 0 no craft, 1 do craft. If 0 ignores craft_item_id
        self.craft_item_id = nn.Linear(128, num_items)  # Many items to choose from to craft. Item crafting will hopefully be learned

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
        nearby_items = obs['NearbyItemDrops']

        if len(agent_info.shape) == 1:
            agent_info = agent_info.unsqueeze(0)
        if len(prevActions.shape) == 1:
            prevActions = prevActions.unsqueeze(0)

        look_slice = agent_info[:, -9:]
        agent_info = agent_info[:, :-9]

        look_type = look_slice[:, 0].long()
        looking_at_info = look_slice[:, 1:]


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
        if len(nearby_items.shape) == 4: # Maybe? Little confused. Just trying to follow my previous patterns
            nearby_items = nearby_items.unsqueeze(0)

        item_x = torch.flatten(item_embedding, start_dim=1)
        block_x = self.block_cnn(block_embedding)
        entity_x = torch.flatten(entity_embedding, start_dim=1)

        nearby_item_drop_embedding = self.item_embedder(nearby_items)
        nearby_item_drop_embedding = nearby_item_drop_embedding.permute(0, 4, 1, 2, 3)
        item_drops_x = self.nearby_item_drops_cnn(nearby_item_drop_embedding)

        # print("agent_info:", agent_info.shape)
        # print("item_x:", item_x.shape)
        # print("block_x:", block_x.shape)
        # print("entity_x:", entity_x.shape)
        # print("item_drops_x:", item_drops_x.shape)
        # print("prevActions:", prevActions.shape)

        return torch.cat([agent_info, item_x, block_x, entity_x, item_drops_x, prevActions], dim=-1)
        # return torch.cat([agent_info, block_x, prevActions], dim=-1)



    def get_policy_logits(self, x):
        inv_act_logits = self.inv_action_type(x)

        movement_policy_logits = self.movement_policy(x)
        item_use_policy_logits = self.item_use_policy(x)
        hotbar_policy_logits = self.hotbar_policy(x)
        pan_camera_logits = self.pan_camera(x)

        from_slot_logits = self.from_slot(x)
        to_slot_logits = self.to_slot(x)

        drop_slot_logits = self.drop_slot(x)
        drop_all_flag_logits = self.drop_all(x)

        craft_item_id_logits = self.craft_item_id(x)

        policy_logits = {
            'inv_act' : inv_act_logits,
            'movement': movement_policy_logits,
            'item_use': item_use_policy_logits,
            'hotbar': hotbar_policy_logits,
            'pan_camera' : pan_camera_logits,
            'from_slot': from_slot_logits,
            'to_slot': to_slot_logits,
            'drop_slot': drop_slot_logits,
            'drop_all_flag': drop_all_flag_logits,
            'craft_item_id': craft_item_id_logits
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