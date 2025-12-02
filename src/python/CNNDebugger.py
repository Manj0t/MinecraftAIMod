# cnn_debugger.py
import torch
import matplotlib.pyplot as plt
import os
import math
import threading
import sys

SAVE_DIR = "cnn_debug"   # folder where images are saved
os.makedirs(SAVE_DIR, exist_ok=True)

DEVICE = torch.device('cuda:0' if torch.cuda.is_available() else 'cpu')


DEBUG_CNN = False
SAVE_STATE = False
DEBUG_KL = False

def start_debugging():
    threading.Thread(target=debug_input_listener, daemon=True).start()

def debug_input_listener():
    global DEBUG_CNN
    global SAVE_STATE
    global DEBUG_KL
    print("-> CNN Debugger listening... Enter 'd' anytime to dump feature maps.")
    while True:
        user_input = sys.stdin.readline().strip()
        if user_input.lower() == "d":
            DEBUG_CNN = True
        elif user_input.lower() == "s":
            SAVE_STATE = True
        elif user_input.lower() == "k":
            DEBUG_KL = not DEBUG_KL
            print(f'DEBUG_KL is now {DEBUG_KL}')

def save_slice(tensor, title, filename, depth_axis=0, slice_index=None, cmap="viridis"):

    t = tensor.detach().cpu()

    if t.dim() == 4:
        # choose center slice by default
        if slice_index is None:
            slice_index = t.shape[depth_axis] // 2

        if depth_axis == 0:
            t2 = t[slice_index]
        elif depth_axis == 1:
            t2 = t[:, slice_index, :]
        else:
            t2 = t[:, :, slice_index]
    elif t.dim() == 3:
        if slice_index is None:
            slice_index = t.shape[depth_axis] // 2

        if depth_axis == 0:
            t2 = t.sum(dim=0)
        elif depth_axis == 1:
            t2 = t.sum(dim=1)
        else:
            t2 = t.sum(dim=2)
    else:
        raise ValueError("Tensor must be 3D or 4D")

    plt.figure(figsize=(5, 5))
    plt.imshow(t2.numpy(), cmap=cmap)
    plt.title(title)
    plt.colorbar()
    plt.savefig(os.path.join(SAVE_DIR, filename), dpi=150)
    plt.close()


def save_feature_maps(feature_tensor, name):

    feat = feature_tensor[0]  # remove batch
    C = feat.shape[0]

    cols = 8
    rows = math.ceil(C / cols)

    plt.figure(figsize=(15, 3 * rows))
    for i in range(C):
        mid = feat[i].shape[0] // 2
        plt.subplot(rows, cols, i + 1)
        plt.imshow(feat[i, mid].detach().cpu().numpy(), cmap="inferno")
        plt.title(f"C{i}")
        plt.axis("off")

    out_path = os.path.join(SAVE_DIR, f"{name}.png")
    plt.suptitle(name)
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"Saved {name} → {out_path}")


def debug_cnn(model, block_ids_tensor, ppo, ep_rewards, i, curr_best):
    global DEBUG_CNN
    global SAVE_STATE

    if SAVE_STATE:
        save_path = f"models/Saved_State_{curr_best:.2f}.pth"
        torch.save({
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": ppo.optimizer.state_dict(),
            "rewards": ep_rewards,
            "best_reward": curr_best,
            "iter": i,
        }, save_path)
        SAVE_STATE = False
        print("Saved model!")

    if not DEBUG_CNN:
        return

    DEBUG_CNN = False

    print("\n=== CNN DEBUGGING ===")
    print("Saving images to:", SAVE_DIR)

    save_slice(block_ids_tensor, "Raw Blocks (Top)", "raw_top.png", depth_axis=1)
    save_slice(block_ids_tensor, "Raw Blocks (X slice)", "raw_x.png", depth_axis=0)
    save_slice(block_ids_tensor, "Raw Blocks (Z slice)", "raw_z.png", depth_axis=2)

    embeds = model.block_embedder(block_ids_tensor.to(DEVICE))
    save_feature_maps(embeds, "embedding_maps")

    with torch.no_grad():
        _ = model.block_cnn(embeds)

    layers = [

        ("conv1", model.block_cnn.feat1),
        ("conv2", model.block_cnn.feat2),
        ("pool1", model.block_cnn.feat3),
        ("conv3", model.block_cnn.feat4),
        ("conv4", model.block_cnn.feat5),
        ("pool2", model.block_cnn.feat6),
    ]

    for name, feat in layers:
        save_feature_maps(feat, f"{name}_maps")

    print("Debugging complete.")
