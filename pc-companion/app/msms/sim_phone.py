"""A simulated phone speaking the real protocol (same crypto/handshake as the Android LinkService).
Used by the end-to-end tests and for trying the PC app without a phone:  python -m msms sim"""
import base64, os, secrets, socket, threading, time
from . import crypto as C, protocol as P

class SimPhone:
    def __init__(self, port=0, pairing_code_cb=None, auto_allow=True, approvals_from_pc=True):
        self.sk, self.pub = C.gen_keypair(); self.phone_id = secrets.token_hex(8); self.name = "SimPhone"
        self.pcs = {}; self.pairing = False; self.code_cb = pairing_code_cb; self.auto_allow = auto_allow
        self.approvals_from_pc = approvals_from_pc
        self.last_code = None; self.sessions = []; self.sent = []
        now = int(time.time() * 1000)
        self.threads = {
            1: {"addresses": ["+15551230001"], "names": ["Mom"], "messages": [
                {"id": 1, "mms": False, "from": "+15551230001", "body": "Dinner sunday?", "date": now - 3600_000, "outgoing": False},
                {"id": 2, "mms": False, "from": "", "body": "yes! what time", "date": now - 3500_000, "outgoing": True}]},
            2: {"addresses": ["+15551230002", "+15551230003"], "names": ["Jake", "Ana"], "messages": [
                {"id": 7, "mms": True, "from": "+15551230002", "body": "look at this", "date": now - 60_000, "outgoing": False,
                 "media": [{"part_id": 99, "mime": "image/png"}]}]},
        }
        self.media = {99: ("image/png", base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="))}
        self.drafts = {}; self._next_draft = 1; self.relay = None; self.updates = None; self.bot_queue = []; self.bot_acks = []
        self.verify_apk = lambda path: {"version_name": "sim", "version_code": 999}
        self.srv = socket.socket(); self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", port)); self.srv.listen(8); self.port = self.srv.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    # --- phone-side "human" actions ---
    def approve_on_phone(self, draft_id): return self._approve(draft_id)
    def deny_on_phone(self, draft_id):
        d = self.drafts[draft_id]; d["status"] = "rejected"; self._push("draft", d)
    def incoming(self, thread_id, frm, body):
        t = self.threads[thread_id]
        t["messages"].append({"id": 1000 + len(self.sent) + len(t["messages"]), "mms": False, "from": frm, "body": body,
                              "date": int(time.time() * 1000), "outgoing": False})
        self._push("messages_changed", {"thread_id": thread_id, "snippet": body})
    def revoke(self, pc_id): self.pcs.pop(pc_id, None)

    def start_relay(self, ping_every=240.0):
        """Mirror of the Android RelayConnector: one outbound TLS connection per paired PC, waits in the room, hands the
        spliced stream to the same session handler as the LAN listener, reconnects with backoff."""
        from . import transport as T
        self.relay_stop = False
        def loop(pc_id):
            backoff = 1.0
            while not self.relay_stop and pc_id in self.pcs and self.relay and self.relay.get("enabled", True):
                r = self.relay
                try:
                    s = T.tls_pinned(r["host"], r["port"], r["fingerprint"], 5)
                    s.sendall(f"MSMS-RELAY/1 {r['token']} {T.room_id(C.dh(self.sk, self.pcs[pc_id]))} phone\n".encode())
                    s.settimeout(ping_every)
                    while True:
                        try: l = T._line(s)
                        except socket.timeout: s.sendall(b"PING\n"); continue
                        if l == b"OK paired\n": break
                        if l not in (b"OK wait\n", b"PONG\n"): raise OSError(l.decode().strip())
                    s.sendall(b"GO\n"); s.settimeout(None); backoff = 1.0
                    self.relay_sessions += 1
                    self._conn(s)
                except Exception as e:
                    self.relay_error = str(e); time.sleep(backoff); backoff = min(backoff * 2, 60)
        self.relay_sessions = 0; self.relay_error = None
        for pid in list(self.pcs): threading.Thread(target=loop, args=(pid,), daemon=True).start()

    def _approve(self, draft_id):
        d = self.drafts[draft_id]
        if d["status"] not in ("pending", "awaiting"): raise ValueError("draft not approvable")
        d["status"] = "sent"; self.sent.append(dict(d))
        self.threads[d["thread_id"]]["messages"].append({"id": 5000 + len(self.sent), "mms": False, "from": "", "body": d["body"],
                                                         "date": int(time.time() * 1000), "outgoing": True})
        self._push("draft", d); return d

    def _push(self, ev, d):
        for ch in list(self.sessions):
            try: ch.send({"ev": ev, "d": d})
            except OSError: self.sessions.remove(ch)

    def _accept(self):
        while True:
            try: s, _ = self.srv.accept()
            except OSError: return
            threading.Thread(target=self._conn, args=(s,), daemon=True).start()

    def _conn(self, s):
        try:
            first = P.read_frame(s); import json; m = json.loads(first)
            class Replay:  # put the first frame back
                def __init__(self, sock, data): self.s, self.buf = sock, len(data).to_bytes(4, "big") + data
                def recv(self, n):
                    if self.buf: out, self.buf = self.buf[:n], self.buf[n:]; return out
                    return self.s.recv(n)
                def sendall(self, b): return self.s.sendall(b)
                def close(self): self.s.close()
            rs = Replay(s, first)
            if m.get("t") == "pair1":
                if not self.pairing: P.write_frame(s, P.jdump({"t": "err", "msg": "phone is not in pairing mode"})); return
                def show(code):
                    self.last_code = code
                    if self.code_cb: self.code_cb(code)
                info = P.pair_responder(rs, self.phone_id, self.name, self.sk, show, lambda n: self.auto_allow)
                self.pcs[info["pc_id"]] = C.b64d(info["pub"]); self.pairing = False
                return
            ch, pc_id = P.session_responder(rs, self.phone_id, self.sk, lambda pid: self.pcs.get(pid))
            self.sessions.append(ch)
            while True:
                req = ch.recv()
                if pc_id not in self.pcs: ch.send({"id": req.get("id"), "e": {"code": "revoked", "msg": "PC was unlinked"}}); return
                try: ch.send({"id": req["id"], "r": self._rpc(req["m"], req.get("p") or {})})
                except KeyError as e: ch.send({"id": req["id"], "e": {"code": "not_found", "msg": str(e)}})
                except ValueError as e: ch.send({"id": req["id"], "e": {"code": "bad_request", "msg": str(e)}})
                except PermissionError as e: ch.send({"id": req["id"], "e": {"code": "forbidden", "msg": str(e)}})
        except (ConnectionError, OSError, P.ProtocolError, Exception):
            pass
        finally:
            s.close()

    def _thread_view(self, tid, t):
        last = t["messages"][-1]
        return {"thread_id": tid, "name": ", ".join(t["names"]), "addresses": t["addresses"], "names": t["names"],
                "snippet": last["body"], "date": last["date"], "unread": not last["outgoing"], "count": len(t["messages"]),
                "group": len(t["addresses"]) > 1}

    def _rpc(self, m, p):
        if m == "status":
            return {"phone_id": self.phone_id, "name": self.name, "app_version": "sim", "approvals_from_pc": self.approvals_from_pc,
                    "drafts_awaiting": sum(d["status"] == "awaiting" for d in self.drafts.values())}
        if m == "list_threads":
            ts = sorted((self._thread_view(k, v) for k, v in self.threads.items()), key=lambda x: -x["date"])
            off, lim = int(p.get("offset", 0)), int(p.get("limit", 30))
            return {"threads": ts[off:off + lim], "total": len(ts)}
        if m == "read_thread":
            t = self.threads[int(p["thread_id"])]; before = int(p.get("before_ms") or 2**62); lim = int(p.get("limit", 30))
            msgs = [dict(x, key=("mms:" if x["mms"] else "sms:") + str(x["id"]), media=x.get("media", [])) for x in t["messages"] if x["date"] < before]
            page = msgs[-lim:]
            return {"thread_id": int(p["thread_id"]), "messages": page, "has_more": len(msgs) > lim, "oldest_date": page[0]["date"] if page else 0}
        if m == "search":
            q = str(p.get("query", "")).lower()
            if not q: raise ValueError("query required")
            return {"results": [dict(x, thread_id=k) for k, t in self.threads.items() for x in t["messages"] if q in x["body"].lower()]}
        if m == "contacts":
            q = str(p.get("query", "")).lower()
            return {"contacts": [{"name": n, "number": a} for t in self.threads.values() for n, a in zip(t["names"], t["addresses"]) if q in n.lower() or q in a]}
        if m == "get_media":
            mime, data = self.media[int(p["part_id"])]
            return {"part_id": int(p["part_id"]), "mime": mime, "size": len(data), "data_b64": C.b64e(data)}
        if m == "create_draft":
            tid = int(p["thread_id"]); body = str(p.get("body", "")).strip()
            if tid not in self.threads: raise KeyError(f"thread {tid}")
            if not body: raise ValueError("body required")
            d = {"draft_id": self._next_draft, "thread_id": tid, "to": self.threads[tid]["addresses"], "body": body,
                 "created": int(time.time() * 1000), "status": "pending"}
            self.drafts[d["draft_id"]] = d; self._next_draft += 1; self._push("draft", d); return {"draft": d}
        if m == "list_drafts":
            ds = [d for d in self.drafts.values() if ("thread_id" not in p or d["thread_id"] == int(p["thread_id"]))
                  and (p.get("status") in (None, "", "all") or d["status"] == p["status"])]
            return {"drafts": ds}
        if m == "request_send":
            d = self.drafts[int(p["draft_id"])]
            if d["status"] != "pending": raise ValueError("draft is " + d["status"])
            d["status"] = "awaiting"; self._push("draft", d); return {"draft": d}
        if m == "cancel_draft":
            d = self.drafts[int(p["draft_id"])]
            if d["status"] in ("pending", "awaiting"): d["status"] = "cancelled"
            return {"draft": d}
        if m == "approve_send":
            if not self.approvals_from_pc: raise PermissionError("PC approvals are turned off on the phone")
            if self.drafts[int(p["draft_id"])]["status"] != "awaiting": raise ValueError("request_send first")
            return {"draft": self._approve(int(p["draft_id"]))}
        if m in ("update_begin", "update_chunk", "update_finish"):
            if self.updates is None:
                import tempfile
                from .update import Receiver
                self.updates = Receiver(tempfile.mkdtemp(prefix="simupd"), self.verify_apk)
            return getattr(self.updates, m.split("_")[1])(p)
        if m == "relay_config":
            fp = str(p.get("fingerprint", "")).lower()
            if len(fp) != 64 or not p.get("host") or len(str(p.get("token", ""))) < 16: raise ValueError("bad relay config")
            self.relay = {"host": str(p["host"]), "port": int(p.get("port", 443)), "fingerprint": fp, "token": str(p["token"]),
                          "enabled": bool(p.get("enabled", True))}
            return {"ok": True, "enabled": self.relay["enabled"], "host": self.relay["host"]}
        if m == "prime_reply":
            self.prime_replies = getattr(self, "prime_replies", [])
            if not any(r["reply_id"] == p["reply_id"] for r in self.prime_replies): self.prime_replies.append(p)
            return {"ok": True}
        if m == "prime_ack":
            self.prime_acks = getattr(self, "prime_acks", []); self.prime_acks.append(p["msg_id"]); return {"ok": True}
        if m == "bot_ack":
            self.bot_acks.append(p["cmd_id"]); return {"ok": True}
        if m == "bot_pending":
            for b in self.bot_queue: self._push("bot_command", b)
            self.bot_queue = []; return {"queued": 0}
        raise ValueError("unknown method " + m)

    def bot_command(self, instruction, thread_id=1, from_self=True):
        """Simulate the user sending '@bot ...' from the phone composer (queued if no PC session)."""
        import uuid
        t = self.threads[thread_id]
        d = {"cmd_id": str(uuid.uuid4()), "thread_id": thread_id, "from_self": from_self, "instruction": instruction, "ts": int(time.time() * 1000),
             "participants": [{"number": a, "name": n} for a, n in zip(t["addresses"], t["names"])],
             "context": [{"sender": "Me" if x["outgoing"] else x["from"], "text": x["body"], "time": x["date"]} for x in t["messages"][-10:]]}
        if self.sessions: self._push("bot_command", d)
        else: self.bot_queue.append(d)
        return d

    def stop_lan(self):
        try: self.srv.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        self.srv.close()
    def close(self): self.relay_stop = True; self.stop_lan()
