import torch
import torch.optim as optim
import torch.nn as nn

from models.BlockCNN import BlockCNN
from models.Embedders import BlockEmbedder
from proxy_tasks.block_decoder.decoder import BlockDecoder


class TrainAutoEncoder():
    def __init__(self, embedder: BlockEmbedder, encoder : BlockCNN, decoder: BlockDecoder, lr: float = 1e-4):
        self.embedder = embedder
        self.encoder = encoder
        self.decoder = decoder

        self.optim = optim.Adam(self.encoder.parameters() + decoder.parameters(), lr=lr)

        self.loss_fn = nn.CrossEntropyLoss()

    def train_step(self, block_ids : torch.Tensor, property_labels: torch.Tensor) -> torch.Tensor:
        embedded = self.embedder(block_ids)
        encoded = self.encoder(embedded)
        decoded = self.decoder(encoded)

        loss = self.loss_fn(decoded, property_labels)

        self.optim.zero_grad()
        loss.backward()
        self.optim.step()

        return loss.item()
