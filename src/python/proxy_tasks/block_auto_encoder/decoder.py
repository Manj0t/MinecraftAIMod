import torch
import torch.nn as nn

class BlockDecoder(nn.Module):
    def __init__(self, latent_shape: tuple[int, int, int, int], num_properties: int):
        # properties may comprise of is_solid, is liquid,
        super().__init__()

        C, D, H, W = latent_shape

        self.unflatten = nn.Unflatten(1, (C, D, H, W))

        self.conv = nn.Sequential(
            nn.ConvTranspose3d(C, 128, kernel_size=2, stride=2),
            nn.BatchNorm3d(128),
            nn.ReLU(),
            nn.ConvTranspose3d(128, 64, kernel_size=2, stride=2),
            nn.BatchNorm3d(64),
            nn.ReLU(),
        )

        self.head = nn.Conv3d(64, num_properties, kernel_size=1, stride=1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.unflatten(x)

        x = self.conv(x)

        return self.head(x)