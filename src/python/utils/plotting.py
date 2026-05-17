import matplotlib.pyplot as plt
import numpy as np

import os

def plot_rewards(ep_rewards, window=20, path='graph'):
    plt.figure(figsize=(10, 5))

    rewards_moving_avg = np.convolve(ep_rewards, np.ones(window) / window, mode="valid")

    plt.plot(ep_rewards, label="Episode Reward", alpha=0.4)
    plt.plot(range(window - 1, len(ep_rewards)), rewards_moving_avg, label=f"Moving Avg (window={window})", linewidth=2)

    plt.xlabel("Episode")
    plt.ylabel("Reward")
    plt.title("Training Progress")
    plt.legend()
    plt.grid(True)
    os.makedirs("../graphs", exist_ok=True)
    plt.savefig(f'graphs/{path}.png',dpi=300, bbox_inches='tight')

    plt.close()