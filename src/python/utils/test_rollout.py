import torch
from env.env_client import EnvClient
from models.ActorCritic import ActorCriticNetwork
from config import DEVICE

def test_rollout(env_client: EnvClient, model: ActorCriticNetwork, max_steps: int = 2048):
    env_client.prev_actions = torch.zeros(env_client.num_envs, 11, dtype=torch.float32).to(DEVICE)
    obs = env_client.get_state()  # Should return a tensor for obs

    for i in range(max_steps):
        if i % 500 == 0:
            print(i)
        with torch.no_grad():
            logits_dict, value = model(obs, env_client.num_envs)

        inv_act = torch.argmax(logits_dict['inv_act'])

        movement_act = torch.argmax(logits_dict['movement'])
        item_use_act = torch.argmax(logits_dict['item_use'])
        hotbar_act = torch.argmax(logits_dict['hotbar'])
        pan_cam_act = torch.argmax(logits_dict['pan_camera'])

        from_slot_act = torch.argmax(logits_dict['from_slot'])
        to_slot_act = torch.argmax(logits_dict['to_slot'])

        drop_slot_act = torch.argmax(logits_dict['drop_slot'])
        drop_all_flag_act = torch.argmax(logits_dict['drop_all_flag'])

        craft_item_id_act = torch.argmax(logits_dict['craft_item_id'])
        side_movement_act = torch.argmax(logits_dict['side_movement'])

        action = [inv_act, movement_act, side_movement_act, item_use_act, hotbar_act, pan_cam_act, from_slot_act, to_slot_act, drop_slot_act, drop_all_flag_act, craft_item_id_act]
        next_obs, reward, done = env_client.take_step_test(action, max_steps, i)

        obs = next_obs