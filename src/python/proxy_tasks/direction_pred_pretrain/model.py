import torch
from torch import nn
from models.Embedders import BlockEmbedder
from models.BlockEncoder import BlockEncoder
from config import DEVICE, EMBEDDING_DIM

class DirectionClassifier(nn.Module):
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
        self.moveable_spaces = nn.Linear(hidden_size, 4)   # forward, backward, left, right

