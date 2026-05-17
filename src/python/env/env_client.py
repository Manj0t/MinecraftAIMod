import socket
import torch

import struct
from env import state_pb2

DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')

class EnvClient:
    def __init__(self, num_envs: int, connections: list[socket.socket]):
        self.num_envs = num_envs
        self.connections = connections
        self.prev_actions = torch.zeros(self.num_envs, 11, device=DEVICE)

    def get_state(self) -> torch.Tensor:
        obs = {
            'Inventory': [],
            'Blocks': [],
            'Entities': [],
            'NearbyItemDrops': [],
            'AgentInfo': [],
            'PrevActions': [],
            'ContainerType': [],
            'Container': [],
            'ContainerMask': []
        }

        for conn in self.connections:
            size_bytes = conn.recv(4)
            if not size_bytes:
                print("Java closed connection, exiting loop.")
                return

            size = struct.unpack(">i", size_bytes)[0]
            buffer = bytearray()
            while len(buffer) < size:
                chunk = conn.recv(size - len(buffer))
                if not chunk: break
                buffer.extend(chunk)

            if len(buffer) != size:
                print("Didn't receive exactly ", len(buffer), "bytes")
                print("Shutting down...")

            state = state_pb2.State()
            state.ParseFromString(buffer)

            agentInfo = [
                value for value in state.agentInfo
            ]

            agentInventory = [
                [value for value in row.values] for row in state.inventory.rows
            ]

            nearbyEntities = [
                [value for value in row.values] for row in state.nearbyEntities.rows
            ]

            nearbyBlocks = [
                [
                    [value for value in row.values] for row in matrix.rows
                ] for matrix in state.nearbyBlocks.matrix
            ]

            nearbyItemDrops = [
                [
                    [
                        [value for value in row.values] for row in matrix.rows
                    ] for matrix in matrix3D.matrix
                ] for matrix3D in state.nearbyItemDrops.matrix3D
            ]

            openContainer = state.containerType

            container = [
                [value for value in row.values] for row in state.container.rows
            ]

            container_mask = [
                value for value in state.containerMask
            ]

            agentInfo_t = torch.tensor(agentInfo, dtype=torch.float32).to(DEVICE)
            agentInventory_t = torch.tensor(agentInventory, dtype=torch.float32).to(DEVICE)
            nearbyEntities_t = torch.tensor(nearbyEntities, dtype=torch.float32).to(DEVICE)
            nearbyBlocks_t = torch.tensor(nearbyBlocks, dtype=torch.int64).to(DEVICE)
            nearbyItemDrops_t = torch.tensor(nearbyItemDrops, dtype=torch.float32).to(DEVICE)
            openContainer_t = torch.tensor(openContainer, dtype=torch.long, device=DEVICE)
            conatiner_t = torch.tensor(container, dtype=torch.float32).to(DEVICE)
            conatinerMask_t = torch.tensor(container_mask, dtype=torch.float32).to(DEVICE)

            obs['Inventory'].append(agentInventory_t)
            obs['Blocks'].append(nearbyBlocks_t)
            obs['Entities'].append(nearbyEntities_t)
            obs['NearbyItemDrops'].append(nearbyItemDrops_t)
            obs['AgentInfo'].append(agentInfo_t)
            obs['ContainerType'].append(openContainer_t)
            obs['Container'].append(conatiner_t)
            obs['ContainerMask'].append(conatinerMask_t)

        for key in ['Inventory', 'Entities', 'AgentInfo', 'Blocks', 'NearbyItemDrops', 'ContainerType', 'Container',
                    'ContainerMask']:
            obs[key] = torch.stack(obs[key], dim=0).to(DEVICE)
        # obs['AgentInfo'] = torch.stack(obs['AgentInfo'], dim=0).to(DEVICE)
        # obs['Blocks'] = torch.stack(obs['Blocks'], dim=0).to(DEVICE)
        obs['PrevActions'] = self.prev_actions

        return obs


    def take_step(self, actions: list[list[int]], max_steps: int, itr: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        self.prev_actions = torch.tensor(actions, dtype=torch.float32, device=DEVICE)

        rewards, dones = [], []

        # send actions
        for env_idx, conn in enumerate(self.connections):
            out_action = state_pb2.Action()
            out_action.actions.extend(actions[env_idx])
            out = out_action.SerializeToString()
            conn.sendall(struct.pack(">i", len(out)))
            conn.sendall(out)

        # recv reward/done
        for env_idx, conn in enumerate(self.connections):
            reward = struct.unpack(">f", conn.recv(4))[0]
            done = struct.unpack(">i", conn.recv(4))[0]
            rewards.append(float(reward))
            dones.append(int(done))

            if done:
                self.prev_actions[env_idx].zero_()  # reset ONLY that env row

        # send continue/stop
        cont_flag = 0 if itr == max_steps - 1 else 1
        for conn in self.connections:
            conn.sendall(struct.pack(">i", cont_flag))

        # get next obs once
        next_obs = None if cont_flag == 0 else self.get_state()
        return next_obs, rewards, dones


    def take_step_test(self, actions: list[list[int]], max_steps: int, itr: int):
        self.prev_actions = torch.tensor(actions, dtype=torch.float32, device=DEVICE)  # (num_envs,4)

        rewards, dones = [], []

        # send actions
        for env_idx, conn in enumerate(self.connections):
            out_action = state_pb2.Action()
            out_action.actions.extend(actions)
            out = out_action.SerializeToString()
            conn.sendall(struct.pack(">i", len(out)))
            conn.sendall(out)

        # recv reward/done
        for env_idx, conn in enumerate(self.connections):
            reward = struct.unpack(">f", conn.recv(4))[0]
            done = struct.unpack(">i", conn.recv(4))[0]
            rewards.append(float(reward))
            dones.append(int(done))

            if done:
                self.prev_actions[env_idx].zero_()  # reset ONLY that env row

        # send continue/stop
        cont_flag = 0 if itr == max_steps - 1 else 1
        for conn in self.connections:
            conn.sendall(struct.pack(">i", cont_flag))

        # get next obs once
        next_obs = None if cont_flag == 0 else self.get_state()
        return next_obs, rewards, dones


    def is_action_noOp(self, action: list[float]) -> bool:
        if len(action) != 3 and action == [0.0, 4.0, 2.0, 2.0, self.prev_actions[0][4], 4.0, 0.0, 0.0, 0.0, 0.0, 0.0]:
            return True
        if action == [-1.0, -1.0, 0.0]:
            return True

        return False