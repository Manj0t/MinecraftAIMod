import torch
from torch import nn
from models.Embedders import ItemEmbedder, BlockEmbedder, EntityEmbedder
from models.BlockEncoder import BlockEncoder
from models.ItemDropsCNN import ItemDropsCNN
from config import DEVICE, EMBEDDING_DIM

class ActorCriticNetwork(nn.Module):
    def __init__(self, agent_info_dim, num_items, num_blocks, num_entities, num_envs, hidden_size=256):
        super().__init__()

        self.item_embedder = ItemEmbedder(num_items)
        self.block_embedder = BlockEmbedder(num_blocks)
        self.entity_embedder = EntityEmbedder(num_entities)

        self.embed_dim = EMBEDDING_DIM
        self.hidden_size = hidden_size

        #  * ( (5 * 2) + 1) * ( (3 * 2) + 1) * ( (5 * 2) + 1 )
        # self.block_transformer = MinecraftTransformer(self.embed_dim)
        self.block_encoder = BlockEncoder(self.embed_dim)
        self.nearby_item_drops_cnn = ItemDropsCNN(self.embed_dim)

        obs_space_size = ((agent_info_dim + 1)
                          + 41 * self.embed_dim     # inventory
                          + 256                    # Nearby Blocks
                          + 256                    # Nearby Item Drops
                          + 10 * self.embed_dim     # Nearby Entities
                          + self.embed_dim
                          + 11)                      # +10 for prviouse actions
        # obs_space_size = (agent_info_dim + 0) + 4096 + 4

        # Pre-encoder: compress raw obs before GRU
        self.pre_encoder = nn.Sequential(
            nn.Linear(obs_space_size, 512),
            nn.ReLU(),
            nn.Linear(512, hidden_size),
            nn.ReLU(),
        )

        # GRU replaces the deep shared MLP
        self.gru = nn.GRU(input_size=hidden_size, hidden_size=hidden_size, batch_first=True)

        # Post-GRU layer before heads
        self.post_gru = nn.Sequential(
            nn.Linear(hidden_size, hidden_size),
            nn.ReLU(),
        )

        # Hidden states per env
        self.num_envs = num_envs
        self.h_states = torch.zeros(num_envs, 1, hidden_size, device=DEVICE)

        # Policy heads
        self.inv_action_type = nn.Linear(hidden_size, 4)    # No inventory action 0, swap items 1, drop item 2, craft item 3
        self.movement_policy = nn.Linear(hidden_size, 5)    # 0 forward, 1 backward, 2 forward jump, 3 jump, 4 none
        self.move_side_policy = nn.Linear(hidden_size, 3)   # left, right, none
        self.item_use_policy = nn.Linear(hidden_size, 3)    # Left click (attack)0 , right click (use item)1 , neither2
        self.hotbar_policy = nn.Linear(hidden_size, 9)      # Active hotbar slot
        self.pan_camera = nn.Linear(hidden_size, 5)         # Pan camera up 0, down 1, left 2, right 3

        # self.swap_flag = nn.Linear(128, 1)          # 0 no swap, 1 swap. If 0 ignores from and to slot
        self.from_slot = nn.Linear(hidden_size, 41)         # 41 possible slots to pick from
        self.to_slot = nn.Linear(hidden_size, 41)           # 41 possible slots to pick from

        # self.drop_flag = nn.Linear(128, 1)          # 0 no drop, 1 do drop. If 0 ignores drop_slot and drop_all
        self.drop_slot = nn.Linear(hidden_size, 41)         # 41 possible slots to pick to drop
        self.drop_all = nn.Linear(hidden_size, 1)           # 0 drop one, 1 drop all

        # self.craft_flag = nn.Linear(128, 1)         # 0 no craft, 1 do craft. If 0 ignores craft_item_id
        self.craft_item_id = nn.Linear(hidden_size, num_items)  # Many items to choose from to craft. Item crafting will hopefully be learned

        self.value_layer = nn.Sequential(
            nn.Linear(hidden_size, 128),
            nn.ReLU(),
            nn.Linear(128, 1),
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
        looking_at_info = look_slice[:, 1:].long()

        look_embeds = torch.zeros(agent_info.size(0), self.embed_dim, device=DEVICE)

        block_mask = (look_type == 1)
        entity_mask = (look_type == 2)

        if block_mask.any():
            block_ids = looking_at_info[block_mask, 0]
            look_embeds[block_mask] = self.block_embedder.block_embedding(block_ids)

        if entity_mask.any():
            look_embeds[entity_mask] = self.entity_embedder(looking_at_info[entity_mask])

        if len(item_embedding.shape) == 2:
            item_embedding = item_embedding.unsqueeze(0)
        if len(block_embedding.shape) == 3:
            block_embedding = block_embedding.unsqueeze(0)
        if len(entity_embedding.shape) == 2:
            entity_embedding = entity_embedding.unsqueeze(0)
        if len(nearby_items.shape) == 4:
            nearby_items = nearby_items.unsqueeze(0)

        item_x = torch.flatten(item_embedding, start_dim=1)
        block_x = self.block_encoder(block_embedding)
        entity_x = torch.flatten(entity_embedding, start_dim=1)

        nearby_item_drop_embedding = self.item_embedder(nearby_items)
        nearby_item_drop_embedding = nearby_item_drop_embedding.permute(0, 4, 1, 2, 3)
        item_drops_x = self.nearby_item_drops_cnn(nearby_item_drop_embedding)

        return torch.cat([agent_info, item_x, block_x, entity_x, item_drops_x, look_embeds, prevActions], dim=-1)


    def get_policy_logits(self, x: torch.Tensor):
        inv_act_logits = self.inv_action_type(x)

        movement_policy_logits = self.movement_policy(x)
        side_movement_policy_logits = self.move_side_policy(x)
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
            'side_movement' : side_movement_policy_logits,
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

    def value(self, obs, num_envs=1):
        _, value = self.forward(obs, num_envs)
        return value


    def policy(self, obs, num_envs=1):
        policy_logits, _ = self.forward(obs, num_envs)
        return policy_logits


    def forward(self, obs, num_envs=1):
        n_obs = self.obs_preprocessing(obs)
        if torch.isnan(n_obs).any() or torch.isinf(n_obs).any():
            print(" NaN or Inf detected in observation BEFORE network")

        x = self.pre_encoder(n_obs)

        # GRU step
        h_prev = self.h_states[0:num_envs].detach()

        gru_out, h_new = self.gru(x.unsqueeze(1), h_prev.transpose(0, 1))
        self.h_states[0:num_envs] = h_new.transpose(0, 1)

        x = gru_out.squeeze(1)
        x = self.post_gru(x)

        policy_logits = self.get_policy_logits(x)
        value = self.value_layer(x)

        return policy_logits, value

    def forward_train(self, obs, h_prev):
        """For PPO updates — uses stored hidden states from rollout."""
        n_obs = self.obs_preprocessing(obs)
        x = self.pre_encoder(n_obs)

        # h_prev shape: [batch, 1, hidden_size] -> [1, batch, hidden_size]
        gru_out, _ = self.gru(x.unsqueeze(1), h_prev.transpose(0, 1))
        x = gru_out.squeeze(1)
        x = self.post_gru(x)

        return self.get_policy_logits(x), self.value_layer(x)

    def reset_h_states(self):
        self.h_states = torch.zeros(self.num_envs, 1, self.hidden_size, device=DEVICE)

    def debug_tensor(self, name, t):
        if torch.isnan(t).any() or torch.isinf(t).any():
            print(f" NaN or Inf detected in {name}")
            print("   min:", torch.nanmin(t))
            print("   max:", torch.nanmax(t))
            print("   values:", t)
            raise ValueError(f"Invalid values in {name}")




# nn.Sequential(
#             # Gradual compression instead of brutal 15x drop
#             nn.Linear(obs_space_size, 2048),  # ← ADD THIS (only 7.5x compression)
#             nn.ReLU(),
#             nn.Dropout(0.1),
#
#             nn.Linear(2048, 1024),  # ← Now only 2x compression
#             nn.ReLU(),
#             nn.Dropout(0.1),
#
#             nn.Linear(1024, 512),
#             nn.ReLU(),
#             nn.Dropout(0.1),
#
#             nn.Linear(512, 512),
#             nn.ReLU(),
#
#             nn.Linear(512, 256),
#             nn.ReLU(),
#         )