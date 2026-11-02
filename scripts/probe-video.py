#!/usr/bin/env python3
"""Probe an anonymous Seasonvar episode without printing or storing media URLs."""
import argparse
import base64
import http.client
import importlib.util
import json
import re
import socket
import sys
from pathlib import Path
from urllib.parse import urljoin, urlsplit

spec = importlib.util.spec_from_file_location("seasonvar_fetch", Path(__file__).with_name("fetch-seasonvar.py"))
fetcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetcher)


def probe(args):
    page = args.url
    parsed = fetcher.validate_url(page)
    if not re.fullmatch(r"/serial-[0-9]+[^/]*\.html", parsed.path):
        raise ValueError("Pass an exact series page")
    body, _, ip, _ = fetcher.fetch(page, args.strategy, args.timeout)
    html = body.decode("utf-8")
    match = re.search(r"\bvar\s+pl\s*=\s*\{([^;]+)\}", html)
    paths = {}
    if match:
        for row in re.finditer(r"[\"']?([0-9]+)[\"']?\s*:\s*([\"'])(.*?)\2", match.group(1)):
            paths[row.group(1)] = row.group(3)
    for row in re.finditer(r"\bpl\[\s*[\"']?([0-9]+)[\"']?\s*\]\s*=\s*([\"'])(.*?)\2", html):
        paths[row.group(1)] = row.group(3)
    playlist = urljoin(page, paths[args.translation].replace("\\/", "/").replace("\\u0026", "&"))
    body, _, _, _ = fetcher.fetch(playlist, args.strategy, args.timeout, override_ip=ip)
    data = json.loads(body)
    leaves = []

    def flatten(rows, depth=0):
        if depth > 8:
            raise ValueError("Playlist nesting")
        for row in rows:
            folder = row.get("folder", row.get("playlist"))
            if isinstance(folder, list):
                flatten(folder, depth + 1)
            else:
                leaves.append(row)

    flatten(data)
    matches = [row for row in leaves if str(row.get("id")) == args.episode]
    if len(matches) != 1:
        raise ValueError("Episode absent or ambiguous")
    value = matches[0]["file"]
    if value.startswith("#2"):
        value = base64.b64decode(value[2:].replace("//b2xvbG8=", "", 1)).decode("utf-8")
    elif value.startswith("#"):
        raise ValueError("Unsupported encoding")
    media = re.search(r"(?:https?:)?//[^,\s\[\]]+", value).group()
    if media.startswith("//"):
        media = "https:" + media
    if media.startswith("http:"):
        media = "https:" + media[5:]
    target = urlsplit(media)
    if target.scheme != "https" or not target.hostname or target.username or target.password:
        raise ValueError("Invalid media URL")
    path = (target.path or "/") + ("?" + target.query if target.query else "")
    addresses = fetcher.quad9(target.hostname, min(args.timeout, 8))
    result = None
    for address in addresses:
        try:
            with socket.create_connection((address, 443), timeout=args.timeout) as sock:
                stream = fetcher.TLSStream(sock, target.hostname, args.strategy, args.timeout)
                request = (f"GET {path} HTTP/1.1\r\nHost: {target.hostname}\r\n"
                           f"Referer: {page}\r\nRange: bytes=0-1023\r\n"
                           "User-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n")
                stream.sendall(request.encode("ascii"))
                with http.client.HTTPResponse(stream) as response:
                    response.begin()
                    sample = response.read(1024)
                    result = response.status, response.getheader("Content-Type", ""), b"ftyp" in sample[:32]
                break
        except (OSError, http.client.HTTPException):
            continue
    if result is None:
        raise ValueError("Media connection failed")
    status, kind, mp4 = result
    print(f"Playlist episodes: {len(leaves)}; HTTP: {status}; media type: {kind}; MP4 header: {mp4}")
    if status not in (200, 206) or not mp4:
        raise ValueError("No supported MP4 response")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--translation", default="0")
    parser.add_argument("--episode", default="1")
    parser.add_argument("--strategy", choices=["default", "fragment", "direct"], default="default")
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()
    try:
        probe(args)
    except Exception as error:
        print("Video probe failed: " + type(error).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
