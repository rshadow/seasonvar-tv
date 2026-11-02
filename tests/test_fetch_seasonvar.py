import importlib.util
import io
import ssl
import struct
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("seasonvar_fetch", ROOT / "scripts/fetch-seasonvar.py")
fetcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetcher)


def client_hello():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    incoming, outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
    tls = context.wrap_bio(incoming, outgoing, server_hostname="seasonvar.ru")
    try:
        tls.do_handshake()
    except ssl.SSLWantReadError:
        pass
    return outgoing.read()


def records(data):
    result = []
    while data:
        size = int.from_bytes(data[3:5], "big")
        result.append((data[:3], data[5:5 + size]))
        data = data[5 + size:]
    return result


class FragmentTests(unittest.TestCase):
    def test_handshake_bytes_and_following_record_are_preserved(self):
        original = client_hello()
        tail = b"\x17\x03\x03\x00\x03abc"
        changed = fetcher.fragment_hello(original + tail, "seasonvar.ru")
        before, after = records(original + tail), records(changed)
        self.assertEqual(len(after), len(before) + 1)
        self.assertEqual(after[0][0], before[0][0])
        self.assertEqual(after[1][0], before[0][0])
        self.assertEqual(after[0][1] + after[1][1], before[0][1])
        self.assertEqual(after[2:], before[1:])

    def test_split_is_five_bytes_before_end_of_sni(self):
        original = client_hello()
        start, stop = fetcher.sni_bounds(original, "seasonvar.ru")
        changed = records(fetcher.fragment_hello(original, "seasonvar.ru"))
        self.assertEqual(len(changed[0][1]) + 5, stop - 5)
        self.assertEqual(changed[1][1][:5], original[stop - 5:stop])
        self.assertGreater(stop - 5, start)

    def test_hostname_in_random_field_is_not_used_as_sni(self):
        original = bytearray(client_hello())
        original[11:23] = b"seasonvar.ru"
        start, _ = fetcher.sni_bounds(original, "seasonvar.ru")
        self.assertGreater(start, 23)

    def test_wrong_sni_and_truncated_hello_are_rejected(self):
        for data, hostname in [
            (client_hello(), "example.org"),
            (client_hello()[:60], "seasonvar.ru"),
            (b"", "seasonvar.ru"),
        ]:
            with self.subTest(hostname=hostname, length=len(data)):
                with self.assertRaises(fetcher.FetchError):
                    fetcher.fragment_hello(data, hostname)

    def test_tls_eof_returns_zero_instead_of_spinning(self):
        stream = fetcher.TLSStream.__new__(fetcher.TLSStream)
        stream.tls = Mock()
        stream.tls.read.return_value = b""
        self.assertEqual(stream.readinto(bytearray(32)), 0)
        stream.tls.read.assert_called_once_with(32)
        stream.close()


class DnsTests(unittest.TestCase):
    def test_compressed_names_and_cname_before_address(self):
        question = b"\x09seasonvar\x02ru\0" + struct.pack("!HH", 1, 1)
        cname = b"\xc0\x0c" + struct.pack("!HHIH", 5, 1, 60, 2) + b"\xc0\x0c"
        address = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + b"\xc0\x00\x02\x01"
        packet = struct.pack("!6H", 42, 0x8180, 1, 2, 0, 0) + question + cname + address
        self.assertEqual(fetcher.dns_addresses(packet, 42), ["192.0.2.1"])
        with self.assertRaises(fetcher.FetchError):
            fetcher.dns_addresses(packet[:-1], 42)

    def test_wrong_transaction_id_and_nxdomain_are_rejected(self):
        for ident, flags in [(7, 0x8180), (42, 0x8183)]:
            with self.subTest(ident=ident, flags=flags):
                packet = struct.pack("!6H", ident, flags, 0, 0, 0, 0)
                with self.assertRaises(fetcher.FetchError):
                    fetcher.dns_addresses(packet, 42)


class OriginTests(unittest.TestCase):
    def test_http_external_origins_and_userinfo_are_rejected(self):
        for url in [
            "http://seasonvar.ru/",
            "https://example.org/",
            "https://user:pass@seasonvar.ru/",
            "https://seasonvar.ru:8443/",
            "https://seasonvar.ru/\r\nHost:example.org",
        ]:
            with self.subTest(url=url):
                with self.assertRaises(fetcher.FetchError):
                    fetcher.validate_url(url)

    @patch.object(fetcher, "quad9", return_value=["192.0.2.1"])
    @patch.object(fetcher, "request_page")
    def test_block_page_is_not_saved_as_homepage(self, request, dns):
        request.return_value = (200, "text/html", None, b"<title>Access blocked</title>")
        with self.assertRaises(fetcher.FetchError):
            fetcher.fetch("https://seasonvar.ru/", "default", 1)

    def test_ajax_wire_request_uses_post_form_and_get_stays_unchanged(self):
        for ajax, mode in ((False, None), (True, "new"), (True, "pop"), (True, "newest")):
            with self.subTest(ajax=ajax, mode=mode), patch.object(fetcher.socket, "create_connection"), \
                    patch.object(fetcher, "TLSStream") as tls:
                stream = tls.return_value
                stream.makefile.return_value = io.BytesIO(
                    b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n")
                url = "https://seasonvar.ru/ajax.php?mode=" + mode if ajax else "https://seasonvar.ru/"
                fetcher.request_page(url, "192.0.2.1", "default", 1, ajax=ajax)
                wire = stream.sendall.call_args.args[0]
                headers, body = wire.split(b"\r\n\r\n", 1)
                if ajax:
                    self.assertTrue(headers.startswith(("POST /ajax.php?mode=" + mode + " HTTP/1.1\r\n").encode()))
                    self.assertIn(b"Content-Type: application/x-www-form-urlencoded", headers)
                    self.assertIn(b"X-Requested-With: XMLHttpRequest", headers)
                    self.assertIn(b"Referer: https://seasonvar.ru/", headers)
                    self.assertIn(b"Content-Length: " + str(len(body)).encode(), headers)
                    self.assertEqual(body, b"ganre=&country=&block=0&main=1")
                else:
                    self.assertTrue(headers.startswith(b"GET / HTTP/1.1\r\n"))
                    self.assertEqual(body, b"")

    @patch.object(fetcher, "quad9", return_value=["192.0.2.1"])
    @patch.object(fetcher, "request_page")
    def test_ajax_redirect_is_not_replaced_with_a_homepage_get(self, request, dns):
        request.return_value = (302, "text/html", "/", b"")
        with self.assertRaises(fetcher.FetchError):
            fetcher.fetch("https://seasonvar.ru/ajax.php?mode=new", "default", 1, ajax=True)
        request.assert_called_once()

    @patch.object(fetcher, "quad9", return_value=["192.0.2.1"])
    @patch.object(fetcher, "request_page")
    def test_external_redirect_is_not_followed(self, request, dns):
        request.return_value = (302, "text/html", "https://example.org/", b"")
        with self.assertRaises(fetcher.FetchError):
            fetcher.fetch("https://seasonvar.ru/", "default", 1)
        request.assert_called_once()


if __name__ == "__main__":
    unittest.main()
