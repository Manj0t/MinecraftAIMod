import torch
import json
import matplotlib.pyplot as plt
from sklearn.manifold import TSNE

# Load checkpoint and registry
checkpoint = torch.load("checkpoints/proxy/block_embedder.pth")
weights = checkpoint["embedder_state_dict"]["block_embedding.weight"].cpu().numpy()

with open("block_properties.json") as f:
    registry = json.load(f)

# Build id -> name mapping
id_to_name = {}
id_to_props = {}
for name, props in registry["blocks"].items():
    short_name = name.split(":")[1]  # "minecraft:stone" -> "stone"
    id_to_name[props["block_id"]] = short_name
    id_to_props[props["block_id"]] = props

# Project to 2D
tsne = TSNE(n_components=2, perplexity=30, random_state=42)
projected = tsne.fit_transform(weights)

# Plot colored by each property
prop_names = ["is_solid", "is_dangerous", "is_liquid", "is_climbable", "is_passable", "is_interactable"]
prop_colors = {
    "is_solid": "#6B8F71",
    "is_dangerous": "#C45B5B",
    "is_liquid": "#5B8DC4",
    "is_climbable": "#C49F5B",
    "is_passable": "#8B7EC4",
    "is_interactable": "#5BC4A8",
}

fig, axes = plt.subplots(2, 3, figsize=(24, 16))
fig.suptitle("Block Embedding Space (t-SNE)", fontsize=18, fontweight="bold")

for ax, prop in zip(axes.flat, prop_names):
    for i in range(len(projected)):
        if i not in id_to_props:
            continue
        has_prop = id_to_props[i][prop]
        color = prop_colors[prop] if has_prop else "#555555"
        alpha = 0.9 if has_prop else 0.3
        ax.scatter(projected[i, 0], projected[i, 1], c=color, s=20, alpha=alpha)

        # Label blocks that have this property
        if has_prop and i in id_to_name:
            ax.annotate(
                id_to_name[i],
                (projected[i, 0], projected[i, 1]),
                fontsize=5,
                alpha=0.8,
                ha="center",
                va="bottom",
                xytext=(0, 3),
                textcoords="offset points",
            )

    ax.set_title(prop, fontsize=14, fontweight="bold")
    ax.set_xticks([])
    ax.set_yticks([])

plt.tight_layout()
plt.savefig("embedding_visualization.png", dpi=200, bbox_inches="tight")
plt.show()
print("Saved to embedding_visualization.png")