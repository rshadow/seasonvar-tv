#!/usr/bin/env python3
"""Run real video through an emulator-only loopback proxy with synthetic credentials."""
import argparse
import base64
import select
import socket
import socketserver
import subprocess
import sys
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--page", required=True)
parser.add_argument("--season-page", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--auth", choices=("true", "false"), default="true")
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("Use an emulator")


def exact(stream, length):
    value = b""
    while len(value) < length:
        chunk = stream.recv(length - len(value))
        if not chunk:
            raise EOFError()
        value += chunk
    return value


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        client = self.request
        client.settimeout(15)
        try:
            header = b""
            while not header.endswith(b"\r\n\r\n"):
                header += exact(client, 1)
                if len(header) > 16384:
                    return
            lines = header.split(b"\r\n")
            method, authority, version = lines[0].decode("ascii").split(" ")
            if method != "CONNECT" or version != "HTTP/1.1":
                return
            authorization = next((line.split(b":", 1)[1].strip() for line in lines
                                  if line.lower().startswith(b"proxy-authorization:")), None)
            expected = b"Basic " + base64.b64encode(b"fixture-user:fixture-password")
            if args.auth == "true" and authorization != expected:
                client.sendall(b'HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="fixture"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                return
            if args.auth == "false" and authorization is not None:
                return
            host, port = authority.rsplit(":", 1)
            if int(port) != 443 or not any(host == suffix or host.endswith("." + suffix)
                                          for suffix in ("seasonvar.ru", "svstat.net", "11cdn.org")):
                return
            with socket.create_connection((host, int(port)), timeout=15) as remote:
                client.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
                self.server.connections += 1
                first_tls = True
                while True:
                    ready, _, _ = select.select([client, remote], [], [], 60)
                    if not ready:
                        return
                    for source in ready:
                        data = source.recv(65536)
                        if not data:
                            return
                        if source is client and first_tls:
                            first_tls = False
                            # Локальный прокси выходит через ту же блокируемую сеть и восстанавливает DPI-стратегию первого пакета.
                            remote.sendall(data[:1])
                            remote.send(b"a", socket.MSG_OOB)
                            time.sleep(0.01)
                            remote.sendall(data[1:])
                        else:
                            (remote if source is client else client).sendall(data)
        except (OSError, EOFError, ValueError):
            return


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True


with Server(("127.0.0.1", 0), Handler) as server:
    server.connections = 0
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        completed = subprocess.run([sys.executable, "scripts/probe-player.py", "--device", args.device,
                                    "--runner", args.runner, "--page", args.page, "--season-page", args.season_page,
                                    "--output", args.output, "--proxy-port", str(server.server_address[1]), "--proxy-auth", args.auth],
                                   check=False)
        if completed.returncode or not server.connections:
            raise SystemExit(1)
        print("proxy_video_verified=true")
    finally:
        server.shutdown()
        thread.join(timeout=5)
