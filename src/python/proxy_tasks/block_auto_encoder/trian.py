import torch
import json
from models.Embedders import BlockEmbedder
from models.BlockEncoder import BlockEncoder
from proxy_tasks.block_auto_encoder.decoder import BlockDecoder
from proxy_tasks.block_auto_encoder.trainer import TrainAutoEncoder
from config import DEVICE, EMBEDDING_DIM

PROPERTY_NAMES = ["is_solid", "is_dangerous", "is_liquid", "is_climbable", "is_passable", "is_interactable"]

def main(num_epochs: int = 50, batch_size: int = 32):
    # Load block property lookup table
    with open("data/block_properties.json") as f:
        registry = json.load(f)

    num_blocks = registry["num_blocks"]
    num_properties = len(PROPERTY_NAMES)
    property_table = torch.zeros(num_blocks, num_properties)

    for _, props in registry["blocks"].items():
        bid = props["block_id"]
        property_table[bid] = torch.tensor([
            props["is_solid"], props["is_dangerous"], props["is_liquid"],
            props["is_climbable"], props["is_passable"], props["is_interactable"]
        ], dtype=torch.float32)

    property_table = property_table.to(DEVICE)

    # Load block grids
    with open("data/block_grids.json") as f:
        raw_grids = json.load(f)

    block_grids = [torch.tensor(g, dtype=torch.long) for g in raw_grids]
    print(f"Loaded {len(block_grids)} voxel grids")

    # Load pretrained embedder and freeze
    block_embedder = BlockEmbedder(num_blocks=num_blocks).to(DEVICE)
    checkpoint = torch.load("checkpoints/proxy/block_embedder.pth", map_location=DEVICE)
    block_embedder.load_state_dict(checkpoint["embedder_state_dict"])
    block_embedder.eval()
    for p in block_embedder.parameters():
        p.requires_grad = False

    # Build encoder + decoder
    encoder = BlockEncoder(EMBEDDING_DIM).to(DEVICE)
    decoder = BlockDecoder(256, num_properties).to(DEVICE)

    trainer = TrainAutoEncoder(block_embedder, encoder, decoder)

    # Training loop
    for epoch in range(num_epochs):
        indices = torch.randperm(len(block_grids))
        total_loss = 0
        num_batches = 0

        for i in range(0, len(block_grids), batch_size):
            batch_idx = indices[i:i+batch_size]
            batch = torch.stack([block_grids[j] for j in batch_idx]).to(DEVICE)
            # batch: (B, 17, 9, 17)

            targets = property_table[batch]
            targets = targets.permute(0, 4, 1, 2, 3)

            loss = trainer.train_step(batch, targets)
            total_loss += loss
            num_batches += 1

        avg_loss = total_loss / num_batches

        if epoch % 5 == 0:
            # Test on last batch
            results = trainer.test(batch, targets)
            print(f"Epoch {epoch} | Loss: {results['loss']:.4f} | Acc: {results['accuracy']:.4f}")

    # Save
    torch.save({
        "encoder_state_dict": encoder.state_dict(),
        "decoder_state_dict": decoder.state_dict(),
        "optimizer_state_dict": trainer.optim.state_dict(),
    }, "checkpoints/proxy/block_encoder.pth")
    print("Saved block encoder checkpoint")

if __name__ == "__main__":
    main()