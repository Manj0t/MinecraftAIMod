import socket
import struct
import sys

from config import HOST, START_PORT

def setup_env() -> tuple[int, bool, list[socket.socket], list[tuple[int, int, int, int]]]:
    try:
        num_envs = int(sys.argv[1])
    except IndexError:
        print("Number of environments not specified")
        exit(1)

    if len(sys.argv) > 2:
        test = True
    else:
        test = False

    #  -- Establish Connections --
    PORTS = [START_PORT + i for i in range(num_envs)]

    env_sockets = []
    headers = []

    for port in PORTS:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.connect((HOST, port))
        print(f"Connected to env on port {port}")
        env_sockets.append(sock)

        expected = 4 * 4  # 16 bytes
        buffer = b""
        while len(buffer) < expected:
            chunk = sock.recv(expected - len(buffer))
            if not chunk:
                raise ConnectionError("Socket closed before receiving full header")
            buffer += chunk

        agent_info_dim, num_items, num_blocks, num_entities = struct.unpack(">4i", buffer)
        headers.append((agent_info_dim, num_items, num_blocks, num_entities))

    return num_envs, test, env_sockets, headers