#!/usr/bin/env python3
"""Local mock subscription server used to verify the header parsing chain."""
import base64
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

BODY = "#profile-title: 测试订阅\n".encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/redirect"):
            self.send_response(302)
            self.send_header("Location", "/sub")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path.startswith("/noheader"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(BODY)))
            self.end_headers()
            self.wfile.write(BODY)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("profile-title", "base64:" + base64.b64encode("测试订阅".encode("utf-8")).decode("ascii"))
        self.send_header("subscription-userinfo",
                         "upload=1073741824; download=2147483648; total=107374182400; expire=1800000000")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
