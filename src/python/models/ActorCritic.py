import torch
from torch import nn
from models.Embedders import BlockEmbedder
from models.BlockEncoder import BlockEncoder
from config import DEVICE, EMBEDDING_DIM


class ActorCriticNetwork(nn.Module):
    """
    Phase 1: Navigation only.
    Obs  = 8 agent‑info scalars + 256‑dim block encoder = 264
    Heads = movement (5) + side_movement (3) + pan_camera (5)
    """

    # agent_info_dim should be 8 for phase 1
    def __init__(self, agent_info_dim, num_items, num_blocks, num_entities, num_envs, hidden_size=256):
        super().__init__()

        self.block_embedder = BlockEmbedder(num_blocks)
        self.block_encoder = BlockEncoder(EMBEDDING_DIM)

        self.embed_dim = EMBEDDING_DIM
        self.hidden_size = hidden_size

        # 8 agent scalars + 256 block encoder
        obs_space_size = 8 + 256 # using only 8 values from agent_info_dim for phase 1

        # Pre-encoder
        self.pre_encoder = nn.Sequential(
            nn.Linear(obs_space_size, 512),
            nn.ReLU(),
            nn.Linear(512, hidden_size),
            nn.ReLU(),
        )

        # but keeping it so the plumbing stays intact for later phases)
        self.gru = nn.GRU(input_size=hidden_size, hidden_size=hidden_size, batch_first=True)

        self.post_gru = nn.Sequential(
            nn.Linear(hidden_size, hidden_size),
            nn.ReLU(),
        )

        # Hidden states per env
        self.num_envs = num_envs
        self.h_states = torch.zeros(num_envs, 1, hidden_size, device=DEVICE)

        # == Policy heads (navigation only) ==
        self.movement_policy = nn.Linear(hidden_size, 5)   # forward, backward, forward+jump, jump, none
        self.move_side_policy = nn.Linear(hidden_size, 3)  # left, right, none
        self.pan_camera = nn.Linear(hidden_size, 5)        # up, down, left, right, none

        # == Value head ==
        self.value_layer = nn.Sequential(
            nn.Linear(hidden_size, 128),
            nn.ReLU(),
            nn.Linear(128, 1),
        )

    # ------------------------------------------------------------------ #
    #  Observation preprocessing                                         #
    # ------------------------------------------------------------------ #

    # Indices into the full 31-value Java agent info array:
    # [3] normYaw, [4] normPitch, [5] vx, [6] vy, [7] vz,
    # [9] colliding, [14] onGround, [15] isFalling
    NAV_INDICES = [3, 4, 5, 6, 7, 9, 14, 15]
    def obs_preprocessing(self, obs):
        agent_info = obs['AgentInfo']                       # (B, 8) or (8,)

        if agent_info.dim() == 1:
            agent_info = agent_info.unsqueeze(0)

        # Slice out only the 8 nav-relevant values
        agent_info = agent_info[:, self.NAV_INDICES]

        block_embedding = self.block_embedder(obs['Blocks'])
        if block_embedding.dim() == 3:
            block_embedding = block_embedding.unsqueeze(0)  # (1, C, D, H, W)

        block_x = self.block_encoder(block_embedding)       # (B, 256)

        return torch.cat([agent_info, block_x], dim=-1)     # (B, 264)

    # ------------------------------------------------------------------ #
    #  Policy logits                                                     #
    # ------------------------------------------------------------------ #
    def get_policy_logits(self, x: torch.Tensor):
        return {
            'movement':      self.movement_policy(x),
            'side_movement': self.move_side_policy(x),
            'pan_camera':    self.pan_camera(x),
        }

    # ------------------------------------------------------------------ #
    #  Forward passes                                                    #
    # ------------------------------------------------------------------ #
    def value(self, obs, num_envs=1):
        _, v = self.forward(obs, num_envs)
        return v

    def policy(self, obs, num_envs=1):
        p, _ = self.forward(obs, num_envs)
        return p

    def forward(self, obs, num_envs=1):
        n_obs = self.obs_preprocessing(obs)

        x = self.pre_encoder(n_obs)

        h_prev = self.h_states[:num_envs].detach()
        gru_out, h_new = self.gru(x.unsqueeze(1), h_prev.transpose(0, 1))
        self.h_states[:num_envs] = h_new.transpose(0, 1)

        x = gru_out.squeeze(1)
        x = self.post_gru(x)

        return self.get_policy_logits(x), self.value_layer(x)

    def forward_train(self, obs):
        """
        Used strictly during PPO training - processes a sequence window block.
        Expects:
          - obs['Blocks']: [num_envs, window_size, ...]
          - obs['AgentInfo']: [num_envs, window_size, 31]
          - h_prev_window: [1, num_envs, hidden_size] (Hidden state starting this chunk)
        """
        num_envs = obs['Blocks'].shape[0]
        window_size = obs['Blocks'].shape[1]

        # 1. Temporarily flatten Env and Time dimensions to pass through 2D block encoders
        flat_obs = {
            'Blocks': obs['Blocks'].reshape(-1, *obs['Blocks'].shape[2:]),
            'AgentInfo': obs['AgentInfo'].reshape(-1, *obs['AgentInfo'].shape[2:])
        }

        n_obs = self.obs_preprocessing(flat_obs) # Shape: [num_envs * window_size, 264]
        x = self.pre_encoder(n_obs)              # Shape: [num_envs * window_size, hidden_size]

        # 2. Reshape into explicit 3D sequence format for the GRU
        # Shape: [num_envs, window_size, hidden_size]
        x_sequence = x.view(num_envs, window_size, self.hidden_size)

        # 3. Process the entire sequence chunk window through the GRU at once
        # GRU input: [Batch=num_envs, Seq=window_size, Features=hidden_size]
        # h_next captures the updated state to pass to the next window chunk
        h_prev = self.h_states[:num_envs].detach()

        gru_out, h_next = self.gru(x_sequence, h_prev.transpose(0, 1))

        self.h_states = h_next.transpose(0, 1).detach()

        # 4. Flatten back to 2D to calculate policy head choices
        x_flat = gru_out.reshape(-1, self.hidden_size)
        x_out = self.post_gru(x_flat)

        logits = self.get_policy_logits(x_out)
        values = self.value_layer(x_out)

        # 5. Reshape outputs back into 2D grid blocks to match your PPO buffer format
        logits_reshaped = {
            'movement':      logits['movement'].view(num_envs, window_size, -1),
            'side_movement': logits['side_movement'].view(num_envs, window_size, -1),
            'pan_camera':    logits['pan_camera'].view(num_envs, window_size, -1)
        }
        values_reshaped = values.view(num_envs, window_size)

        return logits_reshaped, values_reshaped


    def reset_h_states(self):
        self.h_states = torch.zeros(self.num_envs, 1, self.hidden_size, device=DEVICE)