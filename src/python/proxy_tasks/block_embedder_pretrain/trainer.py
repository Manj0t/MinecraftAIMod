import torch
import torch.nn as nn
import torch.optim as optim
from itertools import chain

from proxy_tasks.block_embedder_pretrain.decoder import BlockPropertyDecoder
from models.Embedders import BlockEmbedder


class BlockEmbeddingTrainer():
    def __init__(self, block_embedder: BlockEmbedder, decoder: BlockPropertyDecoder, lr: float = 3e-4):
        self.block_embedder = block_embedder
        self.decoder = decoder

        self.optim = optim.Adam(chain(block_embedder.parameters(), decoder.parameters()), lr=lr)

        self.loss_fn = nn.BCEWithLogitsLoss()

    def train_step(self, block_id: int, block_features: torch.Tensor) -> torch.Tensor:
        block_embedding = self.block_embedder.block_embedding(block_id)
        output_features = self.decoder(block_embedding)

        loss = self.loss_fn(output_features, block_features)
        self.optim.zero_grad()
        loss.backward()
        self.optim.step()

        return loss.item()

    @torch.no_grad()
    def test(self, block_ids: torch.Tensor, targets: torch.Tensor) -> dict:
        self.block_embedder.eval()
        self.decoder.eval()

        block_embedding = self.block_embedder.block_embedding(block_ids)
        predictions = self.decoder(block_embedding)

        loss = self.loss_fn(predictions, targets).item()

        probs = torch.sigmoid(predictions)
        pred_labels = (probs > 0.5).float()
        accuracy = (pred_labels == targets).float().mean().item()

        # Per-property accuracy
        per_property = (pred_labels == targets).float().mean(dim=0)

        self.block_embedder.train()
        self.decoder.train()

        return {
            "loss": loss,
            "accuracy": accuracy,
            "per_property": per_property,
        }