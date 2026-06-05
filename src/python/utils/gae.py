import numpy as np

# SET num_env
def compute_gaes(rewards, values, dones, num_envs, gamma=0.99, lam=0.95):
    rewards = np.asarray(rewards, dtype=np.float32)
    values  = np.asarray(values, dtype=np.float32)
    dones   = np.asarray(dones, dtype=np.float32)

    # If single env, make it (E,T)
    if rewards.ndim == 1:
        rewards = rewards[None, :]
        values  = values[None, :]
        dones   = dones[None, :]

    # stored as (E,T) convert to (T,E)
    if rewards.shape[0] == num_envs:
        rewards = rewards.T
        values  = values.T
        dones   = dones.T

    T, E = rewards.shape
    advantages = np.zeros((T, E), dtype=np.float32)
    returns    = np.zeros((T, E), dtype=np.float32)

    for e in range(E):
        gae = 0.0
        for t in reversed(range(T)):
            next_value = values[t+1, e] if t < T-1 else 0.0
            delta = rewards[t, e] + gamma * next_value * (1 - dones[t, e]) - values[t, e]
            gae = delta + gamma * lam * (1 - dones[t, e]) * gae
            advantages[t, e] = gae
        returns[:, e] = advantages[:, e] + values[:, e]

    return advantages.T, returns.T