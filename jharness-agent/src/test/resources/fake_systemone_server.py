"""A fake System One server for CI's native reviewer check (spec 038).

Usage: python3 -I fake_systemone_server.py PORT_FILE NOUL
Writes the port it listens on to PORT_FILE and answers every POST /v1/systemone
with the given noul for the question "fits".
"""
import http.server
import json
import sys

NOUL = float(sys.argv[2])


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path != "/v1/systemone" or "fits" not in body.get("questions", {}):
            self.send_response(422)
            self.end_headers()
            return
        out = json.dumps({"model": body.get("model"), "answers": {"fits": {"type": "noul", "noul": NOUL}}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
with open(sys.argv[1], "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
