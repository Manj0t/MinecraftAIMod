import torch
import torch.nn as nn
import torch.optim as optim
from proxy_tasks.direction_pred_pretrain.model import DirectionClassifier

class DirectionTrainer():
    def __init__(self, model: DirectionClassifier, lr: float = 3e-4):
        self.model = model

        self.optim = optim.Adam(self.model.parameters(), lr=lr)

        self.loss_fn = nn.BCEWithLogitsLoss()

    def train(self, x, y):
        pred = self.model(x)

        loss = self.loss_fn(pred, y)

        self.optim.zero_grad()
        loss.backward()
        self.optim.step()

        return loss.item()

    def test(self, x, targets):
        self.model.eval()

        predictions = self.model(x)

        loss = self.loss_fn(predictions, targets)

        probs = torch.sigmoid(predictions)
        pred_labels = (probs > 0.5).float()
        accuracy = (pred_labels == targets).float().mean().item()

        # Per-property accuracy
        per_property = (pred_labels == targets).float().mean(dim=0)

        self.model.train()

        return {
            "loss": loss,
            "accuracy": accuracy,
            "per_property": per_property,
        }