import json, os, random, socket, struct, threading, io, pytest
from msms import crypto as C, protocol as P, transport as T
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.exceptions import InvalidTag
V = json.load(open(os.path.join(os.path.dirname(__file__), "vectors", "crypto_vectors.json"))); h = bytes.fromhex

def test_rfc7748_x25519():
    x = V["x25519_rfc7748"]
    assert C.public_key(h(x["alice_sk"])).hex() == x["alice_pk"] and C.public_key(h(x["bob_sk"])).hex() == x["bob_pk"]
    assert C.dh(h(x["alice_sk"]), h(x["bob_pk"])).hex() == x["shared"] == C.dh(h(x["bob_sk"]), h(x["alice_pk"])).hex()

def test_rfc8439_aead():
    a = V["chacha20poly1305_rfc8439"]
    assert ChaCha20Poly1305(h(a["key"])).encrypt(h(a["nonce"]), h(a["pt"]), h(a["aad"])).hex() == a["ct_tag"]
    assert a["ct_tag"].endswith("1ae10b594f09e26a7e902ecbd0600691")

def test_rfc5869_hkdf_and_hmac():
    k = V["hkdf_rfc5869_case1"]; m = V["hmac_sha256_rfc4231_case1"]
    assert C.hkdf(h(k["ikm"]), h(k["salt"]), h(k["info"]), k["len"]).hex() == k["okm"]
    assert C.hmac256(h(m["key"]), h(m["data"])).hex() == m["mac"]

def test_interop_vectors():
    i = V["interop"]; pc, ph = h(i["pub_pc"]), h(i["pub_ph"])
    assert C.public_key(h(i["sk_pc"])) == pc and C.dh(h(i["sk_ph"]), pc).hex() == i["static_shared"]
    assert C.pair_sas(pc, ph, h(i["na"]), h(i["nb"])) == i["sas"]
    th = C.transcript(i["hello"].encode(), i["hello_ok"].encode()); assert th.hex() == i["transcript"]
    k1, k2 = C.session_keys(C.dh(h(i["e_pc"]), C.public_key(h(i["e_ph"]))), h(i["static_shared"]), th)
    assert (k1.hex(), k2.hex()) == (i["k_pc2ph"], i["k_ph2pc"]) and k1 != k2
    r = C.AeadStream(k1); assert r.open(h(i["pc_frame0"])) == b"frame-0" and r.open(h(i["pc_frame1"])) == b"frame-1"
    assert T.room_id(h(i["static_shared"])) == i["room_id"]

K = b"\x05" * 32

def test_every_bit_flip_rejected():
    ct = C.AeadStream(K).seal(b"secret message 123")
    for byte in range(len(ct)):
        for bit in range(8):
            bad = bytearray(ct); bad[byte] ^= 1 << bit
            with pytest.raises(InvalidTag): C.AeadStream(K).open(bytes(bad))

def test_replay_reorder_truncate_wrongkey():
    tx = C.AeadStream(K); a, b = tx.seal(b"a"), tx.seal(b"b")
    rx = C.AeadStream(K); rx.open(a)
    with pytest.raises(InvalidTag): rx.open(a)                      # replay
    with pytest.raises(InvalidTag): C.AeadStream(K).open(b)          # reorder (b first)
    for n in range(len(a)):
        with pytest.raises(InvalidTag): C.AeadStream(K).open(a[:n])  # truncation
    with pytest.raises(InvalidTag): C.AeadStream(b"\x06" * 32).open(a)  # wrong key
    with pytest.raises(InvalidTag): C.AeadStream(K).open(a + b"\0")     # extension

def test_nonce_uniqueness_and_direction_separation():
    s = C.AeadStream(K); seen = set()
    for _ in range(5000): seen.add(s._nonce()); s.seal(b"")
    assert len(seen) == 5000
    s.counter = C.AeadStream.MAX
    with pytest.raises(ValueError): s.seal(b"x")  # no wrap-around
    # each direction has its own key, so identical counters never reuse a (key, nonce) pair
    i = V["interop"]; assert i["k_pc2ph"] != i["k_ph2pc"]

def _pipe():
    a, b = socket.socketpair(); return a, b

def _session(tamper=None, phone_version=None, pc_pub_seen=None):
    sk_pc, sk_ph = os.urandom(32), os.urandom(32)
    a, b = _pipe(); res = {}
    def phone():
        try: res["ph"] = P.session_responder(b, "ph", sk_ph, lambda pid: pc_pub_seen or C.public_key(sk_pc))
        except Exception as e: res["ph_err"] = e
    t = threading.Thread(target=phone); t.start()
    class Mitm:  # optional tamper on the phone->pc direction
        def __init__(s, sock): s.s = sock
        def sendall(s, d): s.s.sendall(d)
        def recv(s, n):
            d = s.s.recv(n)
            return tamper(d) if tamper else d
        def gettimeout(s): return s.s.gettimeout()
        def settimeout(s, v): s.s.settimeout(v)
        def close(s): s.s.close()
    try: res["pc"] = P.session_initiator(Mitm(a), "pc", sk_pc, C.public_key(sk_ph), "ph")
    except Exception as e: res["pc_err"] = e
    a.close(); t.join(5); b.close(); return res

def test_handshake_ok():
    r = _session(); assert "pc" in r and "ph" in r

def test_handshake_tampered_hello_ok_breaks_key_confirmation():
    flipped = [False]
    def tamper(d):
        if not flipped[0] and b'"n":"' in d:
            i = d.index(b'"n":"') + 6; d = d[:i] + (b"A" if d[i:i+1] != b"A" else b"B") + d[i+1:]; flipped[0] = True
        return d
    r = _session(tamper); assert "pc_err" in r or "ph_err" in r

def test_wrong_static_key_fails():
    r = _session(pc_pub_seen=C.public_key(os.urandom(32))); assert "pc_err" in r and "ph_err" in r

def test_version_downgrade_rejected():
    a, b = _pipe(); sk_ph = os.urandom(32); err = {}
    def phone():
        try: P.session_responder(b, "ph", sk_ph, lambda pid: C.public_key(os.urandom(32)))
        except Exception as e: err["e"] = e
    t = threading.Thread(target=phone); t.start()
    P.write_frame(a, P.jdump({"t": "hello", "v": 0, "pc_id": "x", "e": C.b64e(os.urandom(32)), "n": "AA=="}))
    assert P.read_json(a)[1]["msg"] == "unsupported version"; t.join(5); assert "bad version" in str(err["e"])

def test_frame_parser_fuzz():
    rnd = random.Random(1234)
    for _ in range(3000):
        blob = bytes(rnd.getrandbits(8) for _ in range(rnd.randint(0, 40)))
        if rnd.random() < 0.3: blob = struct.pack(">I", rnd.choice([0, 1, 5, 2**31, P.MAX_FRAME + 1, 2**32 - 1])) + blob
        a, b = _pipe(); a.sendall(blob); a.close()
        try:
            while True: P.read_frame(b)
        except (ConnectionError, P.ProtocolError): pass
        finally: b.close()
    # random ciphertext frames never decrypt
    for _ in range(500):
        with pytest.raises(InvalidTag): C.AeadStream(K).open(os.urandom(rnd.randint(16, 64)))
