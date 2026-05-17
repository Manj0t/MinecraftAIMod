import torch

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
HOST = '127.0.0.1'
START_PORT = 5000
