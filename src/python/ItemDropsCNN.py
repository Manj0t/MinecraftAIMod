import torch
from torch import nn

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')

class ItemDropsCNN(nn.Module):
    def __init__(self, in_channels):
        super().__init__()

        self.cnn = nn.Sequential(
            # Passed in: (batch_size, 32, 17, 9, 17)
            nn.Conv3d(in_channels, 64, kernel_size=3, padding=1),   # (batch_size, 32, 17, 9, 17)
            nn.ReLU(),
            nn.Conv3d(64, 64, kernel_size=3, padding=1),            # (batch_size, 32, 17, 9, 17)
            nn.ReLU(),
            # nn.Conv3d(64, 64, kernel_size=3, padding=1),  # (batch_size, 32, 17, 9, 17)
            # nn.ReLU(),
            nn.MaxPool3d(kernel_size=2, stride=2),                  # (batch_size, 64, 8, 4, 8)

            nn.Conv3d(64, 128, kernel_size=3, padding=1),           # (batch_size, 128, 8, 4, 8)
            nn.ReLU(),
            nn.Conv3d(128, 128, kernel_size=3, padding=1),  # (batch_size, 128, 8, 4, 8)
            nn.ReLU(),
            # nn.Conv3d(128, 128, kernel_size=3, padding=1),  # (batch_size, 128, 8, 4, 8)
            # nn.ReLU(),
            nn.MaxPool3d(kernel_size=2, stride=2),                  # (batch_size, 128, 4, 2, 4)
        )

        self.proj1 = nn.Conv3d(in_channels, 64, kernel_size=1)
        self.proj2 = nn.Conv3d(64, 128, kernel_size=1)

    # def forward(self, x):
    #     x = self.cnn(x)
    #
    #     return x.flatten(start_dim=1) # (batch_size, 4096)

    def forward(self, x):
        inpt = x
        #### Block 1 ####
        x = self.cnn[0](x)  # conv1
        self.feat1 = x.detach().cpu()

        x = self.cnn[1](x)  # relu1

        skip_con = self.proj1(inpt)

        x = self.cnn[2](x)  # conv2
        self.feat2 = x.detach().cpu()

        x = x + skip_con

        x = self.cnn[3](x)  # relu2
        x = self.cnn[4](x)  # pool1
        self.feat3 = x.detach().cpu()

        ##### BLOCK 2 ####
        inpt = x
        x = self.cnn[5](x)  # conv3
        self.feat4 = x.detach().cpu()

        x = self.cnn[6](x)  # relu3

        skip_con = self.proj2(inpt)

        x = self.cnn[7](x)  # conv4
        self.feat5 = x.detach().cpu()

        x = x + skip_con

        x = self.cnn[8](x)  # relu4
        x = self.cnn[9](x)  # pool2
        self.feat6 = x.detach().cpu()

        return x.flatten(start_dim=1)

    # def forward(self, x):
    #     #### Block 1 ####
    #     x = self.cnn[0](x)  # conv1
    #     x = self.cnn[1](x)  # relu1
    #
    #     skip_con = x
    #
    #     x = self.cnn[2](x)  # conv2
    #     x = self.cnn[3](x)  # relu2
    #     x = self.cnn[4](x)  # conv3
    #     x = x + skip_con
    #     x = self.cnn[5](x)  # relu
    #     x = self.cnn[6](x)  # pool1
    #
    #     ##### BLOCK 2 ####
    #     x = self.cnn[7](x)  # conv4
    #     x = self.cnn[8](x)  # relu
    #
    #     skip_con = x
    #
    #     x = self.cnn[9](x)  # conv5
    #     x = self.cnn[10](x) # relu
    #     x = self.cnn[11](x) # conv6
    #     x = x + skip_con
    #     x = self.cnn[12](x)  # relu
    #     x = self.cnn[13](x)  # pool2
    #
    #     return x.flatten(start_dim=1)
