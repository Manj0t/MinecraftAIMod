import torch
import torch.optim as optim
import torch.nn as nn
from itertools import chain

from models.BlockEncoder import BlockEncoder
from models.Embedders import BlockEmbedder
from proxy_tasks.block_auto_encoder.decoder import BlockDecoder


class TrainAutoEncoder():
    def __init__(self, embedder: BlockEmbedder, encoder: BlockEncoder, decoder: BlockDecoder, lr: float = 3e-4):
        self.embedder = embedder
        self.encoder = encoder
        self.decoder = decoder

        self.optim = optim.Adam(
            chain(encoder.parameters(), decoder.parameters()), lr=lr
        )

        self.loss_fn = nn.BCEWithLogitsLoss()

    def train_step(self, block_ids: torch.Tensor, targets: torch.Tensor) -> float:
        with torch.no_grad():
            embedded = self.embedder(block_ids)

        encoded = self.encoder(embedded)
        decoded = self.decoder(encoded)

        loss = self.loss_fn(decoded, targets)
        self.optim.zero_grad()
        loss.backward()
        self.optim.step()

        return loss.item()

    @torch.no_grad()
    def test(self, block_ids: torch.Tensor, targets: torch.Tensor) -> dict:
        self.encoder.eval()
        self.decoder.eval()

        embedded = self.embedder(block_ids)
        encoded = self.encoder(embedded)
        decoded = self.decoder(encoded)

        loss = self.loss_fn(decoded, targets).item()

        probs = torch.sigmoid(decoded)
        preds = (probs > 0.5).float()
        accuracy = (preds == targets).float().mean().item()

        self.encoder.train()
        self.decoder.train()

        return {"loss": loss, "accuracy": accuracy}