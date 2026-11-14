# Upstream for GatewayEndToEndTest: echoes each request as JSON, streams /sse, fakes a user service, and echoes
# WebSocket text messages (a minimal RFC 6455 server: handshake, then each text frame comes back as JSON with the
# handshake's headers, so the test can see what the gateway forwarded).
import base64
import hashlib
import json
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

lock = threading.Lock()
users = {}
stats = {"lookups": 0, "creates": 0}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode() if length else ""

    def echo(self):
        headers = {}
        for name, value in self.headers.items():
            key = name.lower()
            headers[key] = headers[key] + ", " + value if key in headers else value
        self.send_json(200, {"method": self.command, "path": self.path, "headers": headers, "body": self.read_body()})

    def sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i in range(3):
            if i:
                time.sleep(0.3)
            data = f"data: event {i}\n\n".encode()
            self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
            self.wfile.flush()
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def read_frame(self):
        head = self.rfile.read(2)
        if len(head) < 2:
            return None, b""
        opcode, length = head[0] & 0x0F, head[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self.rfile.read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self.rfile.read(8))[0]
        mask = self.rfile.read(4) if head[1] & 0x80 else b"\0\0\0\0"
        payload = self.rfile.read(length)
        return opcode, bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    def send_frame(self, opcode, payload):
        n = len(payload)
        if n < 126:
            head = struct.pack(">BB", 0x80 | opcode, n)
        elif n < 65536:
            head = struct.pack(">BBH", 0x80 | opcode, 126, n)
        else:
            head = struct.pack(">BBQ", 0x80 | opcode, 127, n)
        self.wfile.write(head + payload)
        self.wfile.flush()

    def websocket(self):
        digest = hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode())
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", base64.b64encode(digest.digest()).decode())
        self.end_headers()
        self.close_connection = True
        headers = {name.lower(): value for name, value in self.headers.items()}
        while True:
            opcode, payload = self.read_frame()
            if opcode is None or opcode == 0x8:
                if opcode == 0x8:
                    self.send_frame(0x8, payload[:2])
                return
            if opcode == 0x9:
                self.send_frame(0xA, payload)
            elif opcode == 0x1:
                reply = {"echo": payload.decode(), "path": self.path, "headers": headers}
                self.send_frame(0x1, json.dumps(reply).encode())

    def do_GET(self):
        if (self.headers.get("Upgrade") or "").lower() == "websocket":
            return self.websocket()
        path = self.path.split("?")[0]
        if path == "/sse":
            return self.sse()
        if path == "/_stats":
            with lock:
                return self.send_json(200, dict(stats))
        if path.startswith("/users/by-email/"):
            email = unquote(path[len("/users/by-email/"):])
            with lock:
                stats["lookups"] += 1
                user = users.get(email)
            return self.send_json(200, user) if user else self.send_json(404, {"error": "no such user"})
        self.echo()

    def do_POST(self):
        if self.path == "/users":
            email = json.loads(self.read_body() or "{}").get("email", "")
            with lock:
                stats["creates"] += 1
                user = users.setdefault(email, {"id": f"u-{len(users) + 1}", "email": email})
            return self.send_json(201, user)
        self.echo()

    do_PUT = do_DELETE = do_PATCH = echo


ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
