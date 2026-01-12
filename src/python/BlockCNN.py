import torch
from torch import nn

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')

class BlockCNN(nn.Module):
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

        self.conv3 = nn.Sequential(
            nn.Conv3d(256, 512, kernel_size=3, padding=1),
            nn.BatchNorm3d(512),
            nn.ReLU(),
            nn.Conv3d(512, 512, kernel_size=3, padding=1),
            nn.BatchNorm3d(512),
        )

        self.pool = nn.MaxPool3d(kernel_size=2, stride=2)

        self.proj1 = nn.Conv3d(in_channels, 128, kernel_size=1)
        self.proj2 = nn.Conv3d(128, 256, kernel_size=1)
        self.proj3 = nn.Conv3d(256, 512, kernel_size=1)

    def forward(self, x):
        identity = x
        x = self.conv1(x)
        x = x + self.proj1(identity)
        x = nn.ReLU()(x)
        x = self.pool(x)

        identity = x
        x = self.conv2(x)
        x = x + self.proj2(identity)
        x = nn.ReLU()(x)
        x = self.pool(x)

        identity = x
        x = self.conv3(x)
        x = x + self.proj3(identity)
        x = nn.ReLU()(x)
        x = self.pool(x)

        return x.flatten(start_dim=1)
