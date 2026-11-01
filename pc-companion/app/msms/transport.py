"""Transports deliver a raw byte stream to the phone. Crypto sits ABOVE this layer, so a relay only ever
sees ciphertext frames. Add new transports by subclassing Transport."""
import hashlib, hmac, socket, ssl

DEFAULT_PORT = 47613

class Transport:
    name = "abstract"
    def connect(self, timeout=5.0) -> socket.socket:
        raise NotImplementedError
    def describe(self) -> str: return self.name

class LanTransport(Transport):
    name = "lan"
    def __init__(self, host, port=DEFAULT_PORT):
        self.host, self.port = host, int(port)
    def connect(self, timeout=5.0):
        s = socket.create_connection((self.host, self.port), timeout=timeout)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        s.settimeout(None)
        return s
    def describe(self): return f"lan://{self.host}:{self.port}"

class RelayError(OSError): pass

def room_id(static_shared: bytes) -> str:
    """Rendezvous id: HMAC-SHA256(key = DH(s_pc, s_ph), "msms-room-v1"), hex. Unlinkable to numbers; only paired devices can compute it."""
    return hmac.new(static_shared, b"msms-room-v1", hashlib.sha256).hexdigest()

def norm_fp(fp: str) -> str:
    return fp.replace(":", "").replace(" ", "").lower()

def tls_pinned(host, port, fingerprint, timeout):
    """TLS to the relay, trusting exactly one self-signed cert by its SHA-256 fingerprint (no CA, no hostname)."""
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    raw = socket.create_connection((host, int(port)), timeout=timeout)
    s = ctx.wrap_socket(raw, server_hostname=None)
    got = hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()
    if not hmac.compare_digest(got, norm_fp(fingerprint)):
        s.close(); raise RelayError(f"relay certificate fingerprint mismatch (got {got[:16]}..)")
    return s

def _line(s, limit=64):
    b = bytearray()
    while not b.endswith(b"\n"):
        c = s.recv(1)
        if not c: raise RelayError("relay closed the connection")
        b += c
        if len(b) > limit: raise RelayError("relay protocol error")
    return bytes(b)

class RelayTransport(Transport):
    """TLS to your relay server (pinned cert), join the room derived from the pairing secret, wait for the phone,
    then return the spliced stream. The relay only ever sees the same ciphertext frames as the LAN. See docs/RELAY.md."""
    name = "relay"
    def __init__(self, host, port, fingerprint, token, room, role="pc", wait=20.0):
        self.host, self.port, self.fp, self.token, self.room, self.role, self.wait = host, int(port), fingerprint, token, room, role, wait
    def connect(self, timeout=5.0):
        s = tls_pinned(self.host, self.port, self.fp, timeout)
        s.sendall(f"MSMS-RELAY/1 {self.token} {self.room} {self.role}\n".encode())
        s.settimeout(self.wait + 5)
        while True:
            l = _line(s)
            if l == b"OK paired\n": break
            if l in (b"OK wait\n", b"PONG\n"): continue
            s.close()
            why = l.decode(errors="replace").strip()
            raise RelayError({"ERR nopeer": "phone is not connected to the relay", "ERR auth": "relay token rejected"}.get(why, "relay: " + why))
        s.sendall(b"GO\n"); s.settimeout(None)
        return s
    def describe(self): return f"relay://{self.host}:{self.port}"

def relay_for(store, device, wait=20.0):
    r = store.data.get("relay") or {}
    if not r.get("enabled", True) or not (r.get("host") and r.get("token") and r.get("fingerprint")): return None
    from . import crypto as C
    room = room_id(C.dh(store.secret_key(), C.b64d(device["pub"])))
    return RelayTransport(r["host"], r.get("port", 443), r["fingerprint"], r["token"], room, wait=wait)

def candidates(device, discovered=None, manual=None, relay=None):
    """Ordered list of transports to try: manual IP, fresh mDNS result, last known IP, then the relay (always last)."""
    out, seen = [], set()
    for h in [manual, (discovered or {}).get(device.get("phone_id")), device.get("host")]:
        if not h: continue
        host, _, port = (h if isinstance(h, str) else f"{h[0]}:{h[1]}").partition(":")
        key = (host, port or DEFAULT_PORT)
        if key in seen: continue
        seen.add(key); out.append(LanTransport(host, port or device.get("port") or DEFAULT_PORT))
    if relay: out.append(relay)
    return out
