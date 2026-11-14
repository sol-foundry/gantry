# A custom authorizer for a Gantry gateway. The gateway sends it the headers of every request on a route that uses
# it; a 2xx answer lets the request through, anything else is returned to the caller as it is.
import json
import os
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))


def authorize(method, uri, authorization):
    """Decide here. Return the identity headers to pass on to the app, or None to refuse the request."""
    if authorization == "Bearer dev-token":
        return {"x-user-id": "dev-user"}
    return None


class Handler(BaseHTTPRequestHandler):
    def handle_request(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        identity = authorize(
            self.headers.get("X-Forwarded-Method", ""),
            self.headers.get("X-Forwarded-Uri", ""),
            self.headers.get("Authorization", ""),
        )
        if identity is not None:
            self.send_response(200)
            for name, value in identity.items():
                self.send_header(name, value)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = json.dumps({"error": "unauthorized"}).encode()
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = handle_request


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    server = ThreadingHTTPServer(("", PORT), Handler)
    print(f"authorizer listening on :{PORT}")
    server.serve_forever()
