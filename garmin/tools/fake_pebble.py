#!/usr/bin/env python3
"""Stand-in for the Eversense Bridge on 127.0.0.1:17590, for the simulator.

usage: fake_pebble.py SGV DIRECTION AGE_MINUTES [PORT]

Serves the bridge's /pebble with one reading taken AGE_MINUTES ago.
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SGV, DIRECTION, AGE_MIN = sys.argv[1], sys.argv[2], float(sys.argv[3])
PORT = int(sys.argv[4]) if len(sys.argv) > 4 else 17590
TAKEN = int((time.time() - AGE_MIN * 60) * 1000)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] != "/pebble":
            self.send_error(404)
            return
        body = json.dumps({
            "status": [{"now": int(time.time() * 1000)}],
            "bgs": [{"sgv": SGV, "trend": 4, "direction": DIRECTION, "datetime": TAKEN, "bgdelta": 0}],
            "cals": [],
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print("served /pebble", flush=True)


HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
