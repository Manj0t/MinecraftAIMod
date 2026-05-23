import torch
from torch import nn

from config import DEVICE

class BlockEncoder(nn.Module):
    def __init__(self, in_channels):
        super().__init__()

        self.conv1 = nn.Sequential(
            nn.Conv3d(in_channels, 128, kernel_size=3, padding=1),
            nn.BatchNorm3d(128),
            nn.ReLU(),
            nn.Conv3d(128, 128, kernel_size=3, padding=1),
            nn.BatchNorm3d(128),
        )

        self.conv2 = nn.Sequential(
            nn.Conv3d(128, 256, kernel_size=3, padding=1),
            nn.BatchNorm3d(256),
            nn.ReLU(),
            nn.Conv3d(256, 256, kernel_size=3, padding=1),
            nn.BatchNorm3d(256),
        )

        self.pool = nn.MaxPool3d(kernel_size=2, stride=2)
        self.adaptive_pool = nn.AdaptiveAvgPool3d((1, 1, 1))

        self.proj1 = nn.Conv3d(in_channels, 128, kernel_size=1)
        self.proj2 = nn.Conv3d(128, 256, kernel_size=1)

        self.relu = nn.ReLU()

    def forward(self, x):
        # Block 1: (B, 64, 11, 7, 11) -> (B, 128, 5, 3, 5)
        identity = x
        x = self.conv1(x)
        x = x + self.proj1(identity)
        x = self.relu(x)
        x = self.pool(x)

        # Block 2: (B, 128, 5, 3, 5) -> (B, 256, 2, 1, 2)
        identity = x
        x = self.conv2(x)
        x = x + self.proj2(identity)
        x = self.relu(x)
        x = self.pool(x)

        # Collapse spatial dims: (B, 256, 2, 1, 2) -> (B, 256, 1, 1, 1)
        x = self.adaptive_pool(x)

        return x.flatten(start_dim=1) # (B, 256)
