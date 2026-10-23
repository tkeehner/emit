"""@bot commands: the phone pushes a `bot_command` event (only for the user's own composer text starting with @bot);
this PC forwards it to a configured webhook and acks it back to the phone.

Config lives in identity.json under "bot": {url, header, scheme, key_kind, key}. The key is wrapped with DPAPI on
Windows (same as the PC identity key). Auth header defaults to `Authorization: Bearer <key>`; `--header X-Api-Key
--scheme none` sends `X-Api-Key: <key>` instead.

Only ONE process per PC runs the agent (lock = 127.0.0.1:47614), so the UI and `msms agent` never double-post.
"""
import json, os, socket, threading, time, urllib.request, urllib.error
from . import keystore, client as CL

LOCK_PORT = 47614
BACKOFF = [2, 5, 15, 30, 60, 120, 300]   # seconds; 7 retries after the first try (~9 min total)

def _read_key_file(path):
    raw = open(os.path.expanduser(path), "rb").read()
    k = (raw.decode("utf-16") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8-sig")).strip()
    if not k or any(c.isspace() for c in k): raise ValueError("key file must contain one key with no spaces")
    return k

def set_config(store, url, key_file=None, header=None, scheme=None):
    if not (url.startswith("https://") or url.startswith("http://127.0.0.1") or url.startswith("http://localhost")):
        raise ValueError("webhook URL must be https:// (http only for localhost)")
    cfg = dict(store.data.get("bot") or {})
    cfg["url"] = url
    if header is not None: cfg["header"] = header
    if scheme is not None: cfg["scheme"] = "" if scheme.lower() in ("none", "") else scheme
    cfg.setdefault("header", "Authorization"); cfg.setdefault("scheme", "Bearer")
    if key_file:
        kind, blob = keystore.protect(_read_key_file(key_file).encode()); cfg["key_kind"], cfg["key"] = kind, blob
    store.data["bot"] = cfg; store.save(); return status(store)

def status(store):
    c = store.data.get("bot")
    if not c: return {"configured": False}
    return {"configured": True, "url": c["url"], "header": c.get("header", "Authorization"), "scheme": c.get("scheme", "Bearer") or "none",
            "key": "set (" + c.get("key_kind", "?") + ")" if c.get("key") else "not set"}

def auth_headers(cfg, key):
    if not key: return {}
    sch = cfg.get("scheme", "Bearer")
    return {cfg.get("header", "Authorization"): (sch + " " + key) if sch else key}

def build_body(d):
    return {"source": "matrix-sms", "type": "bot_command", "from_self": True, "cmd_id": d.get("cmd_id"),
            "thread_id": d.get("thread_id"), "participants": d.get("participants", []), "instruction": d.get("instruction", ""),
            "context": d.get("context", []), "ts": d.get("ts")}

def build_prime_body(d):
    return {"source": "matrix-sms", "type": "prime_chat", "kind": "prime_chat", "from_self": True, "msg_id": d.get("msg_id"),
            "text": d.get("text", ""), "ts": d.get("ts"),
            "reply_with": "msms.bat prime-reply \"<text>\" (delivers into the Prime chat pinned at the top of Emit)"}

# ---- Prime chat replies (PC -> phone). Queued in prime_outbox.json until the phone accepts them. ----
_outbox_lock = threading.Lock()
def _outbox_path(): return os.path.join(keystore.config_dir(), "prime_outbox.json")
def _outbox_load():
    try:
        with open(_outbox_path()) as f: return json.load(f)
    except (OSError, ValueError): return []
def _outbox_save(items):
    tmp = _outbox_path() + ".tmp"
    with open(tmp, "w") as f: json.dump(items[-200:], f, indent=1)
    os.replace(tmp, _outbox_path())

def prime_queue(text):
    import uuid
    text = (text or "").strip()
    if not text: raise ValueError("reply text required")
    item = {"reply_id": str(uuid.uuid4()), "text": text[:4000], "ts": int(time.time() * 1000), "status": "queued", "last_error": None}
    with _outbox_lock: items = _outbox_load(); items.append(item); _outbox_save(items)
    return item

def prime_drain(client, timeout=10):
    """Try to deliver every queued reply; returns (delivered, still_queued, last_error). Never raises."""
    with _outbox_lock: items = _outbox_load()
    queued = [i for i in items if i.get("status") == "queued"]
    if not queued: return 0, 0, None
    done, err = 0, None
    for it in queued:
        try:
            client.call("prime_reply", {"reply_id": it["reply_id"], "text": it["text"], "ts": it["ts"]}, timeout=timeout)
            it["status"] = "delivered"; it["delivered"] = int(time.time() * 1000); it["last_error"] = None; done += 1
        except Exception as e:
            err = str(e)[:200]; it["last_error"] = err
            break   # phone offline / old app: keep order, try again later
    with _outbox_lock:
        cur = _outbox_load(); by = {i["reply_id"]: i for i in queued}
        cur = [by.get(i["reply_id"], i) for i in cur]; _outbox_save(cur)
    return done, sum(1 for i in cur if i.get("status") == "queued"), err

def post(store, body, timeout=15):
    cfg = store.data.get("bot")
    if not cfg: raise RuntimeError("bot webhook not configured (msms bot set --url ... --key-file ...)")
    key = keystore.unprotect(cfg["key_kind"], cfg["key"]).decode() if cfg.get("key") else None
    h = {"Content-Type": "application/json", "User-Agent": "MatrixSMS-PC"}; h.update(auth_headers(cfg, key))
    req = urllib.request.Request(cfg["url"], data=json.dumps(body).encode(), headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read(500).decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read(500).decode("utf-8", "replace")

def take_lock():
    s = socket.socket()
    try: s.bind(("127.0.0.1", LOCK_PORT)); s.listen(1); return s
    except OSError: s.close(); return None

class BotAgent:
    """Keeps a link session open (reconnects every `keepalive` s), receives bot_command events, posts them with retry."""
    def __init__(self, client, store, keepalive=20.0, backoff=BACKOFF, poster=None):
        self.c, self.store, self.keepalive, self.backoff = client, store, keepalive, backoff
        self.poster = poster or (lambda body: post(store, body))
        self.log_path = os.path.join(keystore.config_dir(), "bot_log.json")
        self.items = self._load(); self.lock = threading.Lock(); self.stop = False
        client.event_listeners.append(self._on_event)

    def _load(self):
        try:
            with open(self.log_path) as f: return json.load(f)
        except (OSError, ValueError): return []
    def _save(self):
        with self.lock:
            tmp = self.log_path + ".tmp"
            with open(tmp, "w") as f: json.dump(self.items[-100:], f, indent=1)
            os.replace(tmp, self.log_path)
    def recent(self, n=20): return list(reversed(self.items[-n:]))

    def _on_event(self, m):
        if m.get("ev") == "prime_chat":
            d = m.get("d") or {}
            if d.get("from_self") is not True or not d.get("msg_id"): return
            return self._on_prime(d)
        if m.get("ev") != "bot_command": return
        d = m.get("d") or {}
        if d.get("from_self") is not True: return   # defence in depth: only the user's own commands
        cid = d.get("cmd_id")
        with self.lock:
            if any(i["cmd_id"] == cid for i in self.items):   # re-sent after reconnect: just re-ack
                done = next(i for i in self.items if i["cmd_id"] == cid)["status"] == "delivered"
            else:
                done = None
                self.items.append({"cmd_id": cid, "thread_id": d.get("thread_id"), "instruction": d.get("instruction", ""),
                                   "participants": [p.get("name") or p.get("number") for p in d.get("participants", [])],
                                   "received": int(time.time() * 1000), "status": "pending", "attempts": 0, "last_error": None, "body": build_body(d)})
        if done: threading.Thread(target=self._ack, args=(cid,), daemon=True).start()
        elif done is None: self._save(); threading.Thread(target=self._deliver, args=(cid,), daemon=True).start()

    def _on_prime(self, d):
        # Prime chat messages share the @bot log/retry machinery, keyed by "prime:<msg_id>"; acked with prime_ack.
        cid = "prime:" + str(d["msg_id"])
        with self.lock:
            ex = next((i for i in self.items if i["cmd_id"] == cid), None)
            if ex is None:
                self.items.append({"cmd_id": cid, "kind": "prime_chat", "thread_id": None, "instruction": "[Prime chat] " + d.get("text", ""),
                                   "participants": [], "received": int(time.time() * 1000), "status": "pending", "attempts": 0,
                                   "last_error": None, "body": build_prime_body(d)})
        if ex is not None:
            if ex["status"] == "delivered": threading.Thread(target=self._ack, args=(cid,), daemon=True).start()
            return
        self._save(); threading.Thread(target=self._deliver, args=(cid,), daemon=True).start()

    def _item(self, cid): return next(i for i in self.items if i["cmd_id"] == cid)
    def _deliver(self, cid):
        it = self._item(cid)
        for n in range(len(self.backoff) + 1):
            if self.stop: return
            it["attempts"] = n + 1
            try:
                code, txt = self.poster(it["body"])
                if 200 <= code < 300: it["status"] = "delivered"; it["last_error"] = None; it["http"] = code; self._save(); self._ack(cid); return
                it["last_error"] = f"HTTP {code}: {txt[:200]}"
                if code in (400, 401, 403, 404, 422): it["status"] = "failed"; self._save(); return   # won't fix itself
            except Exception as e: it["last_error"] = str(e)[:200]
            it["status"] = "retrying"; self._save()
            if n < len(self.backoff): time.sleep(self.backoff[n])
        it["status"] = "failed"; self._save()

    def _ack(self, cid):
        try:
            if cid.startswith("prime:"): self.c.call("prime_ack", {"msg_id": cid[6:]}, timeout=10)
            else: self.c.call("bot_ack", {"cmd_id": cid}, timeout=10)
        except Exception: pass

    def _keep(self):
        while not self.stop:
            try:
                self.c.ensure()
                # Ask the phone to flush queued commands on EVERY tick, not only after a fresh connect: a long-lived
                # session never reconnects, so a command stuck in "queued" on the phone would otherwise never move.
                self.c.call("bot_pending", {}, timeout=10)
            except Exception: pass
            try: prime_drain(self.c)   # queued `msms prime-reply` texts (phone was offline / not updated yet)
            except Exception: pass
            time.sleep(self.keepalive)

    def start(self): threading.Thread(target=self._keep, daemon=True).start(); return self
