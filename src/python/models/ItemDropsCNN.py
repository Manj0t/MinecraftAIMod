import torch
from torch import nn


class ItemDropsCNN(nn.Module):
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

        self.proj1 = nn.Conv3d(in_channels, 128, kernel_size=1)
        self.proj2 = nn.Conv3d(128, 256, kernel_size=1)

        self.pool = nn.MaxPool3d(kernel_size=2, stride=2)
        self.adaptive_pool = nn.AdaptiveAvgPool3d((1, 1, 1))

    def forward(self, x):
        identity = x
        x = self.conv1(x)
        x = x + self.proj1(identity)
        x = nn.ReLU()(x)
        x = self.pool(x)  # (B, 128, 8, 3, 8)

        identity = x
        x = self.conv2(x)
        x = x + self.proj2(identity)
        x = nn.ReLU()(x)
        x = self.pool(x)  # (B, 256, 4, 1, 4)

        x = self.adaptive_pool(x)  # (B, 256, 1, 1, 1)

        return x.flatten(start_dim=1)  # (B, 256)