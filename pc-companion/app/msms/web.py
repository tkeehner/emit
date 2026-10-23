"""Local web UI on 127.0.0.1 + streamable-HTTP MCP endpoint at /mcp. Approve/deny endpoints exist ONLY here,
gated by a per-run token embedded in the page, a Host check (anti DNS-rebinding) and an Origin check."""
import json, os, secrets, threading, time, webbrowser
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from . import tools, mcp_server, crypto as C

HERE = os.path.dirname(__file__)

class EventLog:
    def __init__(self): self.items = []; self.seq = 0; self.cv = threading.Condition()
    def add(self, ev):
        with self.cv:
            self.seq += 1; self.items.append((self.seq, ev)); self.items = self.items[-200:]; self.cv.notify_all()
    def since(self, s, wait=25):
        with self.cv:
            if not any(q > s for q, _ in self.items): self.cv.wait(wait)
            return self.seq, [e for q, e in self.items if q > s]

def make_server(client, port=8765, host="127.0.0.1", agent=None):
    token = secrets.token_urlsafe(24); events = EventLog()
    client.event_listeners.append(events.add)
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    with open(os.path.join(HERE, "ui.html"), encoding="utf-8") as f: page = f.read().replace("__TOKEN__", token)

    class H(BaseHTTPRequestHandler):
        server_version = "MatrixSMS-PC"
        def log_message(self, *a): pass
        def _send(self, code, obj=None, ctype="application/json", raw=None):
            body = raw if raw is not None else json.dumps(obj).encode()
            self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store"); self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'")
            self.end_headers(); self.wfile.write(body)
        def _guard(self, post):
            if self.headers.get("Host") not in allowed_hosts: self._send(403, {"error": "bad host"}); return False
            if post:
                o = self.headers.get("Origin")
                if o and urlparse(o).netloc not in allowed_hosts: self._send(403, {"error": "bad origin"}); return False
            return True
        def _tool(self, name, args):
            try: self._send(200, tools.run(client, name, args))
            except Exception as e: self._send(400, {"error": str(e)})
        def do_GET(self):
            if not self._guard(False): return
            u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
            if u.path == "/": return self._send(200, raw=page.encode(), ctype="text/html; charset=utf-8")
            if u.path == "/api/status": return self._tool("get_status", {})
            if u.path == "/api/threads": return self._tool("list_threads", {"limit": int(q.get("limit", 100))})
            if u.path == "/api/thread":
                a = {"thread_id": int(q["id"]), "limit": int(q.get("limit", 40))}
                if q.get("before"): a["before_ms"] = int(q["before"])
                return self._tool("read_thread", a)
            if u.path == "/api/search": return self._tool("search_messages", {"query": q.get("q", "")})
            if u.path == "/api/drafts": return self._tool("list_drafts", {"status": q.get("status", "all")})
            if u.path == "/api/media":
                try:
                    r = tools.run(client, "get_media", {"part_id": int(q["part"])})
                    return self._send(200, raw=C.b64d(r["data_b64"]), ctype=r["mime"])
                except Exception as e: return self._send(404, {"error": str(e)})
            if u.path == "/api/bot":
                from . import bot
                return self._send(200, {"config": bot.status(agent.store) if agent else {"configured": False},
                                        "active": agent is not None, "items": [{k: v for k, v in i.items() if k != "body"} for i in (agent.recent(20) if agent else [])]})
            if u.path == "/api/events":
                seq, evs = events.since(int(q.get("since", 0)), 20); return self._send(200, {"seq": seq, "events": evs})
            self._send(404, {"error": "not found"})
        def do_POST(self):
            if not self._guard(True): return
            n = int(self.headers.get("Content-Length") or 0)
            if urlparse(self.path).path == "/api/update":  # human-only (UI token), raw APK body
                if self.headers.get("X-MSMS-UI") != token: return self._send(403, {"error": "UI token required"})
                from . import update
                if not 0 < n <= update.MAX_APK: return self._send(400, {"error": "bad size"})
                import tempfile, os
                fd, tmp = tempfile.mkstemp(suffix=".apk")
                try:
                    with os.fdopen(fd, "wb") as f:
                        left = n
                        while left: b = self.rfile.read(min(left, 1 << 20)); f.write(b); left -= len(b) if b else left
                    return self._send(200, update.push(client, tmp))
                except Exception as e: return self._send(400, {"error": str(e)})
                finally: os.remove(tmp)
            try: body = json.loads(self.rfile.read(n) or b"{}")
            except ValueError: return self._send(400, {"error": "bad json"})
            p = urlparse(self.path).path
            if p == "/mcp":
                r = mcp_server.handle(client, body) if isinstance(body, dict) else [x for x in (mcp_server.handle(client, m) for m in body) if x]
                if r is None or r == []: self.send_response(202); self.end_headers(); return
                return self._send(200, r)
            if self.headers.get("X-MSMS-UI") != token: return self._send(403, {"error": "UI token required"})
            if p == "/api/draft": return self._tool("create_draft", body)
            if p == "/api/request_send": return self._tool("request_send", body)
            if p == "/api/cancel": return self._tool("cancel_draft", body)
            if p == "/api/approve":
                # Human-only path: not exposed through MCP or CLI. Phone also enforces its 'PC may approve' toggle.
                try:
                    d = int(body["draft_id"])
                    st = client.call("list_drafts", {"status": "all"})
                    dr = next((x for x in st["drafts"] if x["draft_id"] == d), None)
                    if dr and dr["status"] == "pending": client.call("request_send", {"draft_id": d})
                    return self._send(200, client.call("approve_send", {"draft_id": d}))
                except Exception as e: return self._send(400, {"error": str(e)})
            self._send(404, {"error": "not found"})
    ThreadingHTTPServer.allow_reuse_address = (os.name != 'nt')  # Windows reuse allows a 2nd bind
    srv = ThreadingHTTPServer((host, port), H); srv.daemon_threads = True
    return srv

_ui_sock = None
def ui_running(port=8765):
    """True if another UI already owns port (TCP listener answers or our exclusive lock socket is held)."""
    import socket
    global _ui_sock
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5): return True
    except OSError: pass
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    try: s.bind(("127.0.0.1", 10000 + (port + 8765) % 55000))   # instance lock
    except OSError: s.close(); return True
    _ui_sock = s; return False

def serve(client, port=8765, open_browser=True, store=None):
    agent = None
    if store is not None:
        from . import bot
        global _bot_lock
        _bot_lock = bot.take_lock()   # one @bot forwarder per PC
        if _bot_lock: agent = bot.BotAgent(client, store).start()
        else: print("(@bot forwarding handled by another msms agent process)", flush=True)
    srv = make_server(client, port, agent=agent)
    url = f"http://127.0.0.1:{port}/"
    print(f"Emit PC UI: {url}   (MCP over HTTP: {url}mcp)  Ctrl+C to quit", flush=True)
    if open_browser: threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try: srv.serve_forever()
    except KeyboardInterrupt: pass
