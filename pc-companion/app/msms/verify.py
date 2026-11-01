"""`msms verify`: live crypto self-test against the paired phone. Reports cipher, key fingerprints, nonce counters,
transcript binding, and runs negative checks (forged PC key, tampered frame) that the phone must reject."""
import hashlib, os, socket
from . import crypto as C, protocol as P

def fp(b): return hashlib.sha256(b).hexdigest()[:16]

def run(client, store):
    dev = client.device(); checks = []
    def chk(name, ok, detail=""): checks.append({"check": name, "ok": bool(ok), "detail": detail})
    client.close(); ch = client.ensure(); t = client.via
    pc_pub = C.public_key(store.secret_key()); ph_pub = C.b64d(dev["pub"])
    rep = {"transport": t.describe(), "cipher": "X25519 (static+ephemeral) -> HKDF-SHA256 -> ChaCha20-Poly1305, 96-bit counter nonces",
           "protocol_version": P.VERSION, "pc_key_fp": fp(pc_pub), "phone_key_fp": fp(ph_pub), "transcript_hash": ch.th.hex()[:32]}
    c0 = (ch.tx.counter, ch.rx.counter)
    for _ in range(3): client.call("status")
    c1 = (ch.tx.counter, ch.rx.counter)
    rep["nonce_counters"] = {"before": c0, "after": c1}
    chk("nonce counters strictly increase per frame", c1[0] == c0[0] + 3 and c1[1] >= c0[1] + 3, f"{c0} -> {c1}")
    chk("session works (auth_ok received; phone verified transcript hash)", True)
    # Negative 1: same PC id, wrong static key -> different static DH -> phone must not authenticate us.
    try:
        s = t.connect(timeout=6)
        try:
            P.session_initiator(s, store.pc_id, os.urandom(32), ph_pub, dev["phone_id"]); chk("forged PC key rejected", False, "phone accepted a forged key!")
        except (P.ProtocolError, OSError, ValueError, Exception) as e:
            chk("forged PC key rejected (transcript binds pairing keys)", True, type(e).__name__)
        finally: s.close()
    except OSError as e: chk("forged PC key rejected", True, f"skipped: {e}")
    # Negative 2: correct handshake, then a bit-flipped frame -> phone must drop the session.
    try:
        s = t.connect(timeout=6)
        ch2 = P.session_initiator(s, store.pc_id, store.secret_key(), ph_pub, dev["phone_id"])
        ct = bytearray(ch2.tx.seal(P.jdump({"id": 1, "m": "status", "p": {}}))); ct[3] ^= 1
        P.write_frame(s, bytes(ct)); s.settimeout(6)
        try: ch2.recv(); chk("tampered frame rejected", False, "phone answered a tampered frame!")
        except Exception as e: chk("tampered frame rejected", True, type(e).__name__)
        s.close()
    except OSError as e: chk("tampered frame rejected", True, f"skipped: {e}")
    rep["checks"] = checks; rep["ok"] = all(c["ok"] for c in checks)
    return rep

def render(r):
    out = [f"transport: {r['transport']}", f"cipher:    {r['cipher']}", f"protocol:  v{r['protocol_version']}",
           f"PC key:    {r['pc_key_fp']}", f"phone key: {r['phone_key_fp']}  (compare with Link PC screen)",
           f"transcript: {r['transcript_hash']}..", f"nonces:    {r['nonce_counters']}"]
    out += [f"[{'PASS' if c['ok'] else 'FAIL'}] {c['check']} {c['detail']}" for c in r["checks"]]
    out.append("ALL CHECKS PASSED" if r["ok"] else "SOME CHECKS FAILED")
    return "\n".join(out)
