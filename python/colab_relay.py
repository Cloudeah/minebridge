"""Authenticated, single-job relay for interactive Ollama inference in Colab."""
from __future__ import annotations

import json
import os
import secrets
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def load_relay_config(filename):
    path = Path(filename)
    if not path.exists():
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump({"token": secrets.token_urlsafe(32)}, output)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value.get("token"), str) or len(value["token"]) < 32:
        raise ValueError("Relay configuration requires a token of at least 32 characters")
    return value


class JobBroker:
    def __init__(self):
        self.condition = threading.Condition()
        self.worker_ready = threading.Event()
        self.pending = None
        self.result = None
        self.completed = None
        self.closed = False

    def infer(self, payload, timeout=180):
        with self.condition:
            if self.pending is not None:
                raise RuntimeError("Another model request is pending")
            if self.closed:
                raise RuntimeError("Relay closed")
            self.pending = {"job_id": str(uuid.uuid4()), "payload": payload}
            self.result = None
            self.condition.notify_all()
            try:
                ready = self.condition.wait_for(
                    lambda: self.result is not None or self.closed, timeout
                )
                if not ready:
                    raise TimeoutError("No answer from Colab; run the notebook decision cell")
                if self.closed:
                    raise RuntimeError("Relay closed")
                result = self.result
                self.completed = (self.pending["job_id"], result)
                if "error" in result:
                    raise RuntimeError("Colab: " + str(result["error"])[:500])
                return result["decision"]
            finally:
                self.pending = self.result = None
                self.condition.notify_all()

    def next_job(self, timeout=10):
        self.worker_ready.set()
        with self.condition:
            self.condition.wait_for(
                lambda: self.closed or self.pending is not None and self.result is None,
                timeout,
            )
            return self.pending if not self.closed and self.result is None else None

    def deliver(self, value):
        if not isinstance(value, dict) or not isinstance(value.get("job_id"), str):
            raise ValueError("Expected a job_id and decision or error")
        if ("decision" in value) == ("error" in value):
            raise ValueError("Expected exactly one of decision/error")
        result = {key: value[key] for key in ("decision", "error") if key in value}
        with self.condition:
            if self.completed and value["job_id"] == self.completed[0]:
                return self.completed[1] == result
            if self.pending is None or value["job_id"] != self.pending["job_id"]:
                return False
            if self.result is not None:
                return self.result == result
            self.result = result
            self.condition.notify_all()
            return True

    def close(self):
        with self.condition:
            self.closed = True
            self.worker_ready.set()  # Release startup waiters when the controller is interrupted.
            self.condition.notify_all()


class RelayServer:
    def __init__(self, token, port=8766):
        self.broker = JobBroker()
        broker = self.broker

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # Neither headers nor authentication are logged.

            def reply(self, status, value=None):
                data = b"" if value is None else json.dumps(value, allow_nan=False).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def authenticated(self):
                received = self.headers.get("Authorization", "")
                if not secrets.compare_digest(received.encode(), ("Bearer " + token).encode()):
                    self.reply(401, {"error": "invalid_token"})
                    return False
                return True

            def do_GET(self):
                if not self.authenticated():
                    return
                if self.path == "/health":
                    self.reply(200, {"status": "ready", "protocol": "minebridge-colab-1"})
                elif self.path == "/job":
                    job = broker.next_job()
                    self.reply(200 if job else 204, job)
                else:
                    self.reply(404, {"error": "unknown_path"})

            def do_POST(self):
                if not self.authenticated():
                    return
                if self.path != "/result":
                    self.reply(404, {"error": "unknown_path"})
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 65536:
                        self.reply(413, {"error": "body_limit"})
                        return
                    self.connection.settimeout(10)
                    value = json.loads(self.rfile.read(size))
                    accepted = broker.deliver(value)
                    self.reply(200 if accepted else 409, {"accepted": accepted})
                except (ValueError, UnicodeError, TimeoutError, RecursionError):
                    self.reply(400, {"error": "invalid_result"})

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.broker.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
