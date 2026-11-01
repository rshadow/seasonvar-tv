#!/usr/bin/env python3
"""Получение публичных страниц Seasonvar через TLS-фрагментацию и TCP OOB."""

import argparse
import base64
import gzip
import http.client
import io
import ipaddress
import os
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote, urljoin, urlsplit

MAX_BODY = 8 * 1024 * 1024
HOSTS = {"seasonvar.ru", "www.seasonvar.ru"}


class FetchError(Exception):
    pass


def sni_bounds(hello, hostname):
    end = 5 + int.from_bytes(hello[3:5], "big")
    if len(hello) < 44 or hello[0] != 22 or hello[5] != 1 or end > len(hello):
        raise FetchError("Expected a complete TLS ClientHello record")
    try:
        pos = 43
        pos += 1 + hello[pos]
        pos += 2 + int.from_bytes(hello[pos:pos + 2], "big")
        pos += 1 + hello[pos]
        extensions_end = pos + 2 + int.from_bytes(hello[pos:pos + 2], "big")
        pos += 2
        if extensions_end > end:
            raise ValueError()
        while pos + 4 <= extensions_end:
            kind, size = struct.unpack_from("!HH", hello, pos)
            pos += 4
            if pos + size > extensions_end:
                raise ValueError()
            if kind == 0:
                names_end = pos + 2 + int.from_bytes(hello[pos:pos + 2], "big")
                cursor = pos + 2
                if names_end != pos + size:
                    raise ValueError()
                while cursor + 3 <= names_end:
                    name_type = hello[cursor]
                    length = int.from_bytes(hello[cursor + 1:cursor + 3], "big")
                    start = cursor + 3
                    stop = start + length
                    if stop > names_end:
                        raise ValueError()
                    if name_type == 0 and hello[start:stop] == hostname.encode("idna"):
                        return start, stop
                    cursor = stop
            pos += size
    except (IndexError, ValueError, struct.error) as exc:
        raise FetchError("Malformed ClientHello") from exc
    raise FetchError("Expected hostname was not found in the SNI extension")


def fragment_hello(hello, hostname):
    start, stop = sni_bounds(hello, hostname)
    cut = stop - 5
    end = 5 + int.from_bytes(hello[3:5], "big")
    if not start < cut < stop:
        raise FetchError("Hostname is too short for the selected strategy")
    header = hello[:3]
    first, second = hello[5:cut], hello[cut:end]
    return (
        header + len(first).to_bytes(2, "big") + first
        + header + len(second).to_bytes(2, "big") + second + hello[end:]
    )


class TLSStream(io.RawIOBase):
    def __init__(self, sock, hostname, strategy, timeout):
        super().__init__()
        self.sock = sock
        self.deadline = time.monotonic() + timeout
        self.incoming = ssl.MemoryBIO()
        self.outgoing = ssl.MemoryBIO()
        context = ssl.create_default_context()
        context.set_alpn_protocols(["http/1.1"])
        self.tls = context.wrap_bio(self.incoming, self.outgoing, server_hostname=hostname)
        first = True
        while True:
            try:
                self.tls.do_handshake()
                self.flush_tls()
                break
            except ssl.SSLWantReadError:
                pending = self.outgoing.read()
                if first and pending:
                    if strategy != "direct":
                        pending = fragment_hello(pending, hostname)
                    if strategy == "default":
                        self.send_wire(pending[:1])
                        self.set_timeout()
                        if self.sock.send(b"a", socket.MSG_OOB) != 1:
                            raise FetchError("Could not send the TCP OOB byte")
                        time.sleep(0.01)
                        pending = pending[1:]
                    first = False
                self.send_wire(pending)
                self.receive_tls()

    def set_timeout(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Request deadline exceeded")
        self.sock.settimeout(remaining)

    def send_wire(self, data):
        if data:
            self.set_timeout()
            self.sock.sendall(data)

    def flush_tls(self):
        self.send_wire(self.outgoing.read())

    def receive_tls(self):
        self.set_timeout()
        data = self.sock.recv(65536)
        if data:
            self.incoming.write(data)
        else:
            self.incoming.write_eof()

    def sendall(self, data):
        offset = 0
        while offset < len(data):
            try:
                offset += self.tls.write(data[offset:])
            except ssl.SSLWantReadError:
                self.flush_tls()
                self.receive_tls()
            self.flush_tls()

    def readable(self):
        return True

    def readinto(self, buffer):
        while True:
            try:
                data = self.tls.read(len(buffer))
                buffer[:len(data)] = data
                return len(data)
            except ssl.SSLZeroReturnError:
                return 0
            except ssl.SSLWantReadError:
                self.flush_tls()
                self.receive_tls()

    def makefile(self, mode, *args, **kwargs):
        if mode != "rb":
            raise ValueError("Only binary reads are supported")
        return io.BufferedReader(self)

    def close(self):
        if getattr(self, "sock", None) is not None:
            self.sock.close()
        super().close()


def skip_dns_name(data, pos):
    while True:
        if pos >= len(data):
            raise FetchError("Truncated DNS name")
        size = data[pos]
        pos += 1
        if size == 0:
            return pos
        if size & 0xC0 == 0xC0:
            if pos >= len(data):
                raise FetchError("Truncated DNS pointer")
            return pos + 1
        if size > 63 or pos + size > len(data):
            raise FetchError("Invalid DNS label")
        pos += size


def dns_addresses(data, query_id):
    if len(data) < 12:
        raise FetchError("Truncated DNS response")
    ident, flags, questions, answers, _, _ = struct.unpack_from("!6H", data)
    if ident != query_id or not flags & 0x8000 or flags & 0x0200 or flags & 15:
        raise FetchError("Quad9 returned an unsuccessful DNS response")
    pos = 12
    result = []
    try:
        for _ in range(questions):
            pos = skip_dns_name(data, pos) + 4
        for _ in range(answers):
            pos = skip_dns_name(data, pos)
            kind, cls, _, length = struct.unpack_from("!HHIH", data, pos)
            pos += 10
            if pos + length > len(data):
                raise FetchError("Truncated DNS record")
            if kind == 1 and cls == 1 and length == 4:
                address = socket.inet_ntoa(data[pos:pos + 4])
                if address not in result:
                    result.append(address)
            pos += length
    except (IndexError, struct.error) as exc:
        raise FetchError("Truncated DNS response") from exc
    if not result:
        raise FetchError("Quad9 returned no IPv4 addresses")
    return result


def quad9(hostname, timeout):
    ident = int.from_bytes(os.urandom(2), "big")
    labels = hostname.encode("idna").split(b".")
    query = struct.pack("!6H", ident, 0x0100, 1, 0, 0, 0)
    query += b"".join(bytes([len(label)]) + label for label in labels) + b"\0"
    query += struct.pack("!HH", 1, 1)
    encoded = base64.urlsafe_b64encode(query).rstrip(b"=").decode()
    command = [
        "curl", "--fail", "--silent", "--show-error", "--max-time", str(timeout),
        "--resolve", "dns.quad9.net:443:9.9.9.9",
        "--header", "Accept: application/dns-message",
        "https://dns.quad9.net/dns-query?dns=" + encoded,
    ]
    try:
        result = subprocess.run(command, capture_output=True, check=True, timeout=timeout + 2)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise FetchError("Quad9 DoH failed; curl with HTTP/2 support is required") from exc
    data = result.stdout
    if len(data) > 65535:
        raise FetchError("Quad9 returned an oversized DNS response")
    return dns_addresses(data, ident)


def validate_url(url):
    parsed = urlsplit(url)
    if (
        parsed.scheme != "https" or parsed.hostname not in HOSTS
        or parsed.port not in (None, 443) or parsed.username or parsed.password
        or any(ord(char) < 32 or ord(char) == 127 for char in url)
    ):
        raise FetchError("Use a public HTTPS URL on seasonvar.ru or www.seasonvar.ru")
    return parsed


def request_page(url, ip, strategy, timeout, source_ip=None, updates=False):
    parsed = validate_url(url)
    target = quote(parsed.path or "/", safe="/%:@!$&()*+,;=-._~")
    if parsed.query:
        target += "?" + quote(parsed.query, safe="%=&+/:?@!$()*,-._~;")
    body = b"ganre=&country=&block=0&main=1" if updates else b""
    ajax_headers = (
        "Content-Type: application/x-www-form-urlencoded\r\n"
        "X-Requested-With: XMLHttpRequest\r\n"
        "Referer: https://seasonvar.ru/\r\n"
        f"Content-Length: {len(body)}\r\n"
    ) if updates else ""
    method = "POST" if updates else "GET"
    request = (
        f"{method} {target} HTTP/1.1\r\nHost: {parsed.hostname}\r\n"
        "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36\r\n"
        "Accept: text/html,application/json;q=0.9,*/*;q=0.8\r\n"
        "Accept-Language: ru-RU,ru;q=0.9,en;q=0.8\r\n"
        f"Accept-Encoding: identity\r\n{ajax_headers}Connection: close\r\n\r\n"
    )
    source = (source_ip, 0) if source_ip else None
    with socket.create_connection((ip, 443), timeout=timeout, source_address=source) as sock:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        stream = TLSStream(sock, parsed.hostname, strategy, timeout)
        stream.sendall(request.encode("ascii") + body)
        with http.client.HTTPResponse(stream) as response:
            response.begin()
            status = response.status
            content_type = response.getheader("Content-Type", "")
            location = response.getheader("Location")
            encoding = response.getheader("Content-Encoding", "").lower()
            body = response.read(MAX_BODY + 1)
        if len(body) > MAX_BODY:
            raise FetchError("Response exceeds 8 MiB")
        if encoding == "gzip":
            with gzip.GzipFile(fileobj=io.BytesIO(body)) as compressed:
                body = compressed.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise FetchError("Decoded response exceeds 8 MiB")
        elif encoding not in ("", "identity"):
            raise FetchError("Unexpected content encoding")
        return status, content_type, location, body


class PageSummary(HTMLParser):
    def __init__(self):
        super().__init__()
        self.news_blocks = 0

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "div" and "news" in values.get("class", "").split():
            self.news_blocks += 1


def fetch(url, strategy, timeout, source_ip=None, override_ip=None, updates=False):
    addresses = {}
    for _ in range(6):
        parsed = validate_url(url)
        host = parsed.hostname
        if host not in addresses:
            addresses[host] = [override_ip] if override_ip else quad9(host, min(timeout, 8))
        last_error = None
        for ip in addresses[host]:
            try:
                result = request_page(url, ip, strategy, timeout, source_ip, updates)
                break
            except (OSError, ssl.SSLError, FetchError, http.client.HTTPException) as exc:
                last_error = exc
                print(f"{host} via {ip}: {type(exc).__name__}: {exc}", file=sys.stderr)
        else:
            raise FetchError("All destination addresses failed") from last_error
        status, content_type, location, body = result
        if status in (301, 302, 303, 307, 308):
            if updates:
                raise FetchError("Unexpected AJAX redirect")
            if not location:
                raise FetchError("Redirect has no Location")
            url = urljoin(url, location)
            continue
        if status != 200:
            raise FetchError(f"Seasonvar returned HTTP {status}")
        summary = PageSummary()
        if "html" in content_type:
            summary.feed(body.decode("utf-8", errors="replace"))
        if parsed.path in ("", "/") and summary.news_blocks == 0:
            raise FetchError("The homepage contains no news blocks; it may be a block page")
        return body, content_type, ip, summary.news_blocks
    raise FetchError("Too many redirects")


def save_output(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(body)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="https://seasonvar.ru/")
    parser.add_argument("--output", type=Path, default=Path("build/site/index.html"))
    parser.add_argument("--strategy", choices=("default", "tlsrec", "direct"), default="default")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--source-ip")
    parser.add_argument("--updates", action="store_true", help="POST the public updates AJAX form")
    parser.add_argument("--ip", help="Override the destination IPv4 for diagnostics")
    args = parser.parse_args()
    try:
        if not 0 < args.timeout <= 60:
            raise FetchError("Timeout must be between 0 and 60 seconds")
        for address in (args.ip, args.source_ip):
            if address:
                ipaddress.IPv4Address(address)
        body, content_type, ip, count = fetch(
            "https://seasonvar.ru/ajax.php?mode=new" if args.updates else args.url,
            args.strategy, args.timeout, args.source_ip, args.ip, args.updates
        )
        save_output(args.output, body)
        print(
            f"HTTP 200; {content_type}; {len(body)} bytes; news blocks: {count}; "
            f"strategy: {args.strategy}; IP: {ip}; saved: {args.output}"
        )
    except (FetchError, OSError, ValueError, ssl.SSLError, http.client.HTTPException) as exc:
        print(f"Fetch failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
