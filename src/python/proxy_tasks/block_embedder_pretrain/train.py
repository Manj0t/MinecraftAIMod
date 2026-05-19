import torch
import json
import os

from proxy_tasks.block_embedder_pretrain.decoder import BlockPropertyDecoder
from proxy_tasks.block_embedder_pretrain.trainer import BlockEmbeddingTrainer
from models.Embedders import BlockEmbedder
from config import DEVICE, EMBEDDING_DIM

PROPERTY_NAMES = ["is_solid", "is_dangerous", "is_liquid", "is_climbable", "is_passable", "is_interactable"]

def main(data_path: str, save_path: str, max_epoch: int = 2000):
    with open(data_path) as f:
        registry = json.load(f)

    num_blocks = registry["num_blocks"]
    num_properties = len(PROPERTY_NAMES)
    properties = torch.zeros(num_blocks, num_properties)

    for _, props in registry["blocks"].items():
        block_id = props["block_id"]
        properties[block_id] = torch.tensor([
            props["is_solid"],
            props["is_dangerous"],
            props["is_liquid"],
            props["is_climbable"],
            props["is_passable"],
            props["is_interactable"],
        ], dtype=torch.float32)

    block_ids = torch.arange(num_blocks).to(DEVICE)
    properties = properties.to(DEVICE)

    block_embedder = BlockEmbedder(num_blocks=num_blocks).to(DEVICE)
    block_decoder = BlockPropertyDecoder(EMBEDDING_DIM, num_properties).to(DEVICE)
    embedding_trainer = BlockEmbeddingTrainer(block_embedder, block_decoder)

    for epoch in range(max_epoch):
        loss = embedding_trainer.train_step(block_ids, properties)

        if epoch % 10 == 0:
            results = embedding_trainer.test(block_ids, properties)
            print(f"Epoch {epoch} | Loss: {results['loss']:.4f} | Acc: {results['accuracy']:.4f}")
            for i, name in enumerate(PROPERTY_NAMES):
                print(f"  {name}: {results['per_property'][i]:.4f}")

    torch.save({
        "embedder_state_dict": block_embedder.state_dict(),
        "decoder_state_dict": block_decoder.state_dict(),
        "optimizer_state_dict": embedding_trainer.optim.state_dict(),
    }, save_path)

if __name__ == "__main__":
    os.makedirs("checkpoints/proxy", exist_ok=True)
    main("block_properties.json", "checkpoints/proxy/block_embedder.pth")