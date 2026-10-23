import json, socket, threading, pytest
from msms import crypto as C, protocol as P

def pipe():
    a, b = socket.socketpair(); return a, b

def run_pair(code_override=None, tamper_commit=False, allow=True):
    a, b = pipe(); sk_pc, _ = C.gen_keypair(); sk_ph, _ = C.gen_keypair(); shown = {}; res = {}
    def phone():
        try:
            sock = b
            if tamper_commit:
                class T:
                    def recv(self, n): return b.recv(n)
                    def sendall(self, d):
                        if b'"pair2"' in d:
                            o = json.loads(d[4:]); o["commit"] = C.b64e(b"\0" * 32); d2 = P.jdump(o); d = len(d2).to_bytes(4, "big") + d2
                        b.sendall(d)
                sock = T()
            res["phone"] = P.pair_responder(sock, "ph1", "Phone", sk_ph, lambda c: shown.setdefault("code", c), lambda n: allow)
        except Exception as e: res["phone_err"] = e
    t = threading.Thread(target=phone); t.start()
    def ask():
        import time
        for _ in range(100):
            if "code" in shown: break
            time.sleep(0.01)
        return code_override or shown["code"]
    try: res["pc"] = P.pair_initiator(a, "pc1", "PC", sk_pc, ask)
    except Exception as e: res["pc_err"] = e
    a.close(); t.join(2); b.close()
    return res, sk_pc, sk_ph

def test_pair_ok():
    res, sk_pc, sk_ph = run_pair()
    assert C.b64d(res["pc"]["pub"]) == C.public_key(sk_ph)
    assert C.b64d(res["phone"]["pub"]) == C.public_key(sk_pc)

def test_pair_wrong_code():
    res, *_ = run_pair(code_override="000000x")
    assert "pc_err" in res and "phone" not in res

def test_pair_bad_commit_detected():
    res, *_ = run_pair(tamper_commit=True)
    assert "commitment" in str(res["pc_err"])

def test_pair_denied_on_phone():
    res, *_ = run_pair(allow=False)
    assert "pc_err" in res and "phone" not in res

def test_sas_format_and_determinism():
    s = C.pair_sas(b"a" * 32, b"b" * 32, b"c" * 16, b"d" * 16)
    assert len(s) == 6 and s.isdigit() and s == C.pair_sas(b"a" * 32, b"b" * 32, b"c" * 16, b"d" * 16)

def session(known=True):
    a, b = pipe(); sk_pc, pub_pc = C.gen_keypair(); sk_ph, pub_ph = C.gen_keypair(); out = {}
    def phone():
        try:
            ch, pid = P.session_responder(b, "ph1", sk_ph, lambda pid: pub_pc if known else None)
            out["pid"] = pid; m = ch.recv(); ch.send({"echo": m})
        except Exception as e: out["err"] = e
    t = threading.Thread(target=phone); t.start()
    try:
        ch = P.session_initiator(a, "pc1", sk_pc, pub_ph, "ph1"); ch.send({"hi": 1}); out["reply"] = ch.recv()
    except Exception as e: out["pc_err"] = e
    t.join(2); a.close(); b.close(); return out

def test_session_roundtrip():
    o = session(); assert o["reply"] == {"echo": {"hi": 1}} and o["pid"] == "pc1"

def test_session_unknown_pc_rejected():
    o = session(known=False); assert "not paired" in str(o["pc_err"])

def test_session_wrong_phone_key_fails():
    a, b = pipe(); sk_pc, pub_pc = C.gen_keypair(); sk_ph, _ = C.gen_keypair(); _, wrong = C.gen_keypair()
    t = threading.Thread(target=lambda: _swallow(lambda: P.session_responder(b, "ph1", sk_ph, lambda p: pub_pc), b)); t.start()
    with pytest.raises(Exception): P.session_initiator(a, "pc1", sk_pc, wrong)
    a.close(); b.close(); t.join(2)

def _swallow(f, sock=None):
    try: f()
    except Exception:
        if sock: sock.shutdown(2)

def test_aead_replay_and_tamper():
    k = b"k" * 32; tx = C.AeadStream(k); rx = C.AeadStream(k)
    c1 = tx.seal(b"one"); c2 = tx.seal(b"two")
    with pytest.raises(Exception): rx.open(c2)       # out of order
    rx2 = C.AeadStream(k); assert rx2.open(c1) == b"one"
    with pytest.raises(Exception): rx2.open(c1)      # replay
    bad = bytearray(c2); bad[0] ^= 1
    with pytest.raises(Exception): C.AeadStream(k).open(bytes(bad))
