"""Crypto primitives shared with the phone (see docs/PROTOCOL.md). Must stay byte-identical
with the phone app LinkCrypto.kt."""
import base64, hashlib, hmac, os, struct
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization

def b64e(b: bytes) -> str: return base64.b64encode(b).decode()
def b64d(s: str) -> bytes: return base64.b64decode(s)

def public_key(sk: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(sk).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)

def gen_keypair():
    sk = os.urandom(32)
    return sk, public_key(sk)

def dh(sk: bytes, pk: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(sk).exchange(X25519PublicKey.from_public_bytes(pk))

def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts: h.update(p)
    return h.digest()

def hkdf(ikm: bytes, salt: bytes, info: bytes, n: int) -> bytes:
    return HKDF(hashes.SHA256(), n, salt, info).derive(ikm)

def hmac256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()

def lp(b: bytes) -> bytes:
    return struct.pack(">I", len(b)) + b

# --- pairing (numeric-comparison SAS with commitment) ---
def pair_commit(pub_ph, pub_pc, nb): return sha256(b"msms-commit-v1", pub_ph, pub_pc, nb)

def pair_sas(pub_pc, pub_ph, na, nb) -> str:
    v = int.from_bytes(sha256(b"msms-sas-v1", pub_pc, pub_ph, na, nb)[:4], "big") % 1_000_000
    return f"{v:06d}"

def pair_confirm_key(shared, pub_pc, pub_ph, na, nb):
    return hkdf(shared, sha256(b"msms-pair-v1", pub_pc, pub_ph, na, nb), b"msms-pair-confirm", 32)

# --- session ---
def transcript(hello_pc: bytes, hello_ph: bytes) -> bytes:
    return sha256(b"msms-hs-v1", lp(hello_pc), lp(hello_ph))

def session_keys(ee: bytes, ss: bytes, th: bytes):
    """Returns (key pc->phone, key phone->pc)."""
    okm = hkdf(ee + ss, th, b"msms-session-v1", 64)
    return okm[:32], okm[32:]

class AeadStream:
    """One direction of a channel. Nonce = 4 zero bytes || 8-byte BE counter; counter must be strictly sequential."""
    MAX = 2**63
    def __init__(self, key: bytes):
        self._a = ChaCha20Poly1305(key); self.counter = 0
    def _nonce(self): return b"\0\0\0\0" + struct.pack(">Q", self.counter)
    def seal(self, pt: bytes) -> bytes:
        if self.counter >= self.MAX: raise ValueError("nonce space exhausted")
        ct = self._a.encrypt(self._nonce(), pt, None); self.counter += 1; return ct
    def open(self, ct: bytes) -> bytes:
        pt = self._a.decrypt(self._nonce(), ct, None); self.counter += 1; return pt
