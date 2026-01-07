import socket
import sys

def check_connection(host, port):
    print(f"Resolving {host}...")
    try:
        ip = socket.gethostbyname(host)
        print(f"Resolved {host} to {ip}")
    except Exception as e:
        print(f"Failed to resolve {host}: {e}")
        return

    print(f"Connecting to {host}:{port} ({ip})...")
    try:
        sock = socket.create_connection((host, port), timeout=5)
        print("Successfully connected!")
        sock.close()
    except Exception as e:
        print(f"Failed to connect: {e}")

if __name__ == "__main__":
    check_connection('kafka', 29092)
