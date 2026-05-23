import torch
import torch.nn as nn
import torch.nn.functional as F

class BlockDecoder(nn.Module):
    def __init__(self, input_dim: int, num_properties: int):
        super().__init__()

        self.unflatten = nn.Sequential(
            nn.Linear(input_dim, 256 * 2 * 2 * 2),
            nn.ReLU(),
        )

        self.deconv1 = nn.Sequential(
            nn.ConvTranspose3d(256, 128, kernel_size=2, stride=2),
            nn.BatchNorm3d(128),
            nn.ReLU(),
        )

        self.deconv2 = nn.Sequential(
            nn.ConvTranspose3d(128, 64, kernel_size=2, stride=2),
            nn.BatchNorm3d(64),
            nn.ReLU(),
        )

        self.head = nn.Conv3d(64, num_properties, kernel_size=1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.unflatten(x)
        x = x.view(-1, 256, 2, 2, 2)
        x = self.deconv1(x)       # (B, 128, 4, 4, 4)
        x = self.deconv2(x)       # (B, 64, 8, 8, 8)
        x = F.interpolate(x, size=(17, 9, 17), mode='trilinear', align_corners=False)
        return self.head(x)       # (B, num_properties, 17, 9, 17)