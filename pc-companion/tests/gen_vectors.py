"""Writes tests/vectors/crypto_vectors.json: RFC known-answer tests + interop vectors from msms/crypto.py.
The same file is copied to the Android unit-test resources; both implementations must reproduce it."""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app"))
from msms import crypto as C, transport as T
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
h = bytes.fromhex
pt = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
key = bytes(range(0x80, 0xa0)); nonce = h("070000004041424344454647"); aad = h("50515253c0c1c2c3c4c5c6c7")
ct = ChaCha20Poly1305(key).encrypt(nonce, pt, aad)
assert ct[-16:].hex() == "1ae10b594f09e26a7e902ecbd0600691" and ct.hex().startswith("d31a8d34648e60db7b86afbc53ef7ec2")
x = {"alice_sk": "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
     "alice_pk": "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
     "bob_sk": "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
     "bob_pk": "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
     "shared": "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"}
assert C.public_key(h(x["alice_sk"])).hex() == x["alice_pk"] and C.dh(h(x["alice_sk"]), h(x["bob_pk"])).hex() == x["shared"]
hk = {"ikm": "0b" * 22, "salt": "000102030405060708090a0b0c", "info": "f0f1f2f3f4f5f6f7f8f9", "len": 42,
      "okm": "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"}
assert C.hkdf(h(hk["ikm"]), h(hk["salt"]), h(hk["info"]), 42).hex() == hk["okm"]
hm = {"key": "0b" * 20, "data": "4869205468657265",  # RFC 4231 case 1
      "mac": "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"}
assert C.hmac256(h(hm["key"]), h(hm["data"])).hex() == hm["mac"]
# interop: deterministic run of the whole link derivation
sk_pc = bytes(range(32)); sk_ph = bytes(range(32, 64)); na = b"\x11" * 16; nb = b"\x22" * 16
pc, ph = C.public_key(sk_pc), C.public_key(sk_ph); ss = C.dh(sk_pc, ph)
hello, hello_ok = b'{"t":"hello","v":1}', b'{"t":"hello_ok","v":1}'
th = C.transcript(hello, hello_ok); e1, e2 = b"\x07" * 32, b"\x09" * 32
k1, k2 = C.session_keys(C.dh(e1, C.public_key(e2)), ss, th)
s1 = C.AeadStream(k1); f0 = s1.seal(b"frame-0"); f1 = s1.seal(b"frame-1")
s2 = C.AeadStream(k2); g0 = s2.seal(b'{"t":"auth_ok"}')
inter = {"sk_pc": sk_pc.hex(), "sk_ph": sk_ph.hex(), "na": na.hex(), "nb": nb.hex(), "pub_pc": pc.hex(), "pub_ph": ph.hex(),
         "static_shared": ss.hex(), "commit": C.pair_commit(ph, pc, nb).hex(), "sas": C.pair_sas(pc, ph, na, nb),
         "confirm_pc_ok": C.hmac256(C.pair_confirm_key(ss, pc, ph, na, nb), b"pc-ok").hex(),
         "confirm_phone_ok": C.hmac256(C.pair_confirm_key(ss, pc, ph, na, nb), b"phone-ok").hex(),
         "hello": hello.decode(), "hello_ok": hello_ok.decode(), "transcript": th.hex(), "e_pc": e1.hex(), "e_ph": e2.hex(),
         "k_pc2ph": k1.hex(), "k_ph2pc": k2.hex(), "pc_frame0": f0.hex(), "pc_frame1": f1.hex(), "ph_frame0": g0.hex(),
         "room_id": T.room_id(ss)}
out = {"x25519_rfc7748": x, "chacha20poly1305_rfc8439": {"key": key.hex(), "nonce": nonce.hex(), "aad": aad.hex(), "pt": pt.hex(), "ct_tag": ct.hex()},
       "hkdf_rfc5869_case1": hk, "hmac_sha256_rfc4231_case1": hm, "interop": inter}
p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors", "crypto_vectors.json")
json.dump(out, open(p, "w"), indent=1); print("wrote", p)
