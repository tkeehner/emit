import json, threading, time, subprocess, sys, os, pytest
from http.server import HTTPServer, BaseHTTPRequestHandler
from msms import keystore, client as CL, bot, cli
from msms.sim_phone import SimPhone
from test_e2e_sim import _wait

class Hook:
    def __init__(self, codes):
        self.codes = list(codes); self.got = []
        hook = self
        class H(BaseHTTPRequestHandler):
            def log_message(self, *a): pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                hook.got.append((dict(self.headers), body))
                code = hook.codes.pop(0) if hook.codes else 200
                self.send_response(code); self.end_headers(); self.wfile.write(b'{"ok":true}')
        self.srv = HTTPServer(("127.0.0.1", 0), H); threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_port}/hook"

@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("MSMS_HOME", str(tmp_path))
    phone = SimPhone(); store = keystore.Store(); phone.pairing = True
    CL.pair(store, "127.0.0.1", phone.port, ask_code=lambda: _wait(phone))
    c = CL.PhoneClient(store, manual_host=f"127.0.0.1:{phone.port}", use_mdns=False)
    kf = tmp_path / "key.txt"; kf.write_text("sekret-123\n")
    yield phone, store, c, kf
    c.close(); phone.close()

def until(f, t=5):
    end = time.time() + t
    while time.time() < end:
        if f(): return True
        time.sleep(0.02)
    return False

def test_forward_with_bearer_and_ack(env):
    phone, store, c, kf = env; h = Hook([200])
    bot.set_config(store, h.url, str(kf))
    a = bot.BotAgent(c, store, keepalive=0.2).start()
    assert until(c.connected)
    d = phone.bot_command("@bot make fun of Devin")
    assert until(lambda: h.got)
    hdr, body = h.got[0]
    assert hdr["Authorization"] == "Bearer sekret-123"
    assert body["source"] == "matrix-sms" and body["type"] == "bot_command" and body["from_self"] is True
    assert body["instruction"] == "@bot make fun of Devin" and body["thread_id"] == 1 and body["participants"] and body["context"]
    assert until(lambda: d["cmd_id"] in phone.bot_acks)
    assert a.recent()[0]["status"] == "delivered"

def test_custom_header_and_retry(env):
    phone, store, c, kf = env; h = Hook([500, 503, 200])
    bot.set_config(store, h.url, str(kf), header="X-Api-Key", scheme="none")
    a = bot.BotAgent(c, store, keepalive=0.2, backoff=[0.05, 0.05, 0.05]).start(); assert until(c.connected)
    d = phone.bot_command("@bot hi")
    assert until(lambda: d["cmd_id"] in phone.bot_acks)
    assert len(h.got) == 3 and h.got[-1][0]["X-Api-Key"] == "sekret-123" and "Authorization" not in h.got[-1][0]
    assert a.recent()[0]["attempts"] == 3

def test_auth_failure_stops(env):
    phone, store, c, kf = env; h = Hook([401])
    bot.set_config(store, h.url, str(kf))
    a = bot.BotAgent(c, store, keepalive=0.2, backoff=[0.05] * 3).start(); assert until(c.connected)
    phone.bot_command("@bot hi")
    assert until(lambda: a.recent() and a.recent()[0]["status"] == "failed")
    assert len(h.got) == 1 and not phone.bot_acks

def test_queued_while_offline_then_flushed(env):
    phone, store, c, kf = env; h = Hook([200])
    bot.set_config(store, h.url, str(kf))
    d = phone.bot_command("@bot later")      # no PC session yet -> queued on phone
    assert phone.bot_queue
    bot.BotAgent(c, store, keepalive=0.2).start()
    assert until(lambda: d["cmd_id"] in phone.bot_acks)
    assert h.got[0][1]["instruction"] == "@bot later"

def test_not_from_self_ignored_and_duplicates(env):
    phone, store, c, kf = env; h = Hook([])
    bot.set_config(store, h.url, str(kf))
    a = bot.BotAgent(c, store, keepalive=0.2).start(); assert until(c.connected)
    phone.bot_command("@bot from someone else", from_self=False)
    d = phone.bot_command("@bot once"); phone._push("bot_command", d)
    assert until(lambda: d["cmd_id"] in phone.bot_acks); time.sleep(0.3)
    assert [b["instruction"] for _, b in h.got] == ["@bot once"]

def test_config_requires_https(env):
    phone, store, c, kf = env
    with pytest.raises(ValueError): bot.set_config(store, "http://example.com/x", str(kf))
    st = bot.set_config(store, "https://example.com/x", str(kf))
    assert st["key"].startswith("set") and "sekret" not in json.dumps(store.data)

def test_cli_draft_json(env, capsys):
    phone, store, c, kf = env
    rc = cli.main(["--host", f"127.0.0.1:{phone.port}", "--json", "draft", "--thread", "1", "--text", "hey"])
    out = json.loads(capsys.readouterr().out); assert rc == 0 and out["draft_id"] == 1 and out["status"] == "pending"
    rc = cli.main(["--host", f"127.0.0.1:{phone.port}", "--json", "request-send", str(out["draft_id"])])
    out2 = json.loads(capsys.readouterr().out); assert out2["status"] == "awaiting" and not phone.sent

def test_single_instance_lock():
    a = bot.take_lock(); assert a
    assert bot.take_lock() is None
    a.close()


def test_keepalive_polls_bot_pending_even_when_already_connected():
    import threading, time as _t
    from msms import bot as _bot
    calls = []
    class C:
        event_listeners = []
        def connected(self): return True
        def ensure(self): pass
        def call(self, m, p, timeout=None): calls.append(m)
    a = _bot.BotAgent.__new__(_bot.BotAgent)
    a.c, a.keepalive, a.stop = C(), 0.01, False
    threading.Thread(target=a._keep, daemon=True).start()
    _t.sleep(0.1); a.stop = True
    assert calls.count("bot_pending") >= 2

def test_prime_chat_forward_and_ack(env):
    phone, store, c, kf = env; h = Hook([200])
    bot.set_config(store, h.url, str(kf))
    a = bot.BotAgent(c, store, keepalive=0.2, backoff=[0.05]).start()
    assert until(c.connected)
    phone._push("prime_chat", {"msg_id": "m1", "text": "hi Prime", "ts": 1, "from_self": True})
    phone._push("prime_chat", {"msg_id": "m2", "text": "spoof", "ts": 1, "from_self": False})
    assert until(lambda: getattr(phone, "prime_acks", []) == ["m1"])
    assert len(h.got) == 1 and h.got[0][1]["kind"] == "prime_chat" and h.got[0][1]["text"] == "hi Prime" and h.got[0][1]["from_self"] is True
    a.stop = True

def test_prime_reply_queue_then_deliver(env):
    phone, store, c, kf = env
    it = bot.prime_queue("Prime chat is connected.")
    c.ensure(); done, left, err = bot.prime_drain(c)
    assert (done, left) == (1, 0) and phone.prime_replies[0]["reply_id"] == it["reply_id"]
    assert bot.prime_drain(c) == (0, 0, None)
