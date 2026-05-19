from models.Embedders import BlockEmbedder
from models.BlockCNN import BlockCNN
from proxy_tasks.block_auto_encoder.decoder import BlockDecoder
from proxy_tasks.block_auto_encoder.trainer import TrainAutoEncoder

from config import DEVICE, EMBEDDING_DIM

def main(num_epochs: int = 100):
    block_embedder = BlockEmbedder(EMBEDDING_DIM)
    encoder = BlockCNN(EMBEDDING_DIM)

    # Get decoder dims
    decoder = BlockDecoder()

    auto_encoder = TrainAutoEncoder(block_embedder, encoder, decoder)

    # load data

    for epoch in range(num_epochs):
        auto_encoder.train_step()

        if epoch % 10 == 0:
            # test