"""Push a phone APK over the encrypted link (LAN or relay): chunked, resumable, SHA-256 checked.
The phone verifies hash, signing certificate and versionCode, then waits for the user to tap [ install ]."""
import hashlib, os
from . import crypto as C

CHUNK = 1024 * 1024
MAX_APK = 200 * 1024 * 1024

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def push(client, path, progress=None):
    size = os.path.getsize(path)
    if size <= 0 or size > MAX_APK: raise ValueError("APK size out of range")
    with open(path, "rb") as f:
        if f.read(4) != b"PK\x03\x04": raise ValueError("not an APK (zip) file")
    digest = sha256_file(path)
    off = int(client.call("update_begin", {"sha256": digest, "size": size, "name": os.path.basename(path)[:80]})["offset"])
    with open(path, "rb") as f:
        while off < size:
            f.seek(off); data = f.read(CHUNK)
            r = client.call("update_chunk", {"sha256": digest, "offset": off, "data_b64": C.b64e(data)}, timeout=60)
            off = int(r["offset"])
            if progress: progress(off, size)
    res = client.call("update_finish", {"sha256": digest}, timeout=60)
    res.update(sha256=digest, size=size); return res

class Receiver:
    """Phone-side state machine (mirrored in Kotlin UpdateReceiver); used by the simulator and tests."""
    def __init__(self, directory, verify):
        self.dir = directory; self.verify = verify; self.cur = None; self.ready = None
        os.makedirs(directory, exist_ok=True)
    def _path(self, sha): return os.path.join(self.dir, sha + ".part")
    def begin(self, p):
        sha = str(p["sha256"]).lower(); size = int(p["size"])
        if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha): raise ValueError("bad sha256")
        if not 0 < size <= MAX_APK: raise ValueError("bad size")
        if self.cur and self.cur[0] != sha:
            try: os.remove(self._path(self.cur[0]))
            except OSError: pass
        self.cur = (sha, size); path = self._path(sha)
        have = os.path.getsize(path) if os.path.exists(path) else 0
        if have > size: os.remove(path); have = 0
        return {"offset": have}
    def chunk(self, p):
        sha = str(p["sha256"]).lower()
        if not self.cur or self.cur[0] != sha: raise ValueError("update_begin first")
        path = self._path(sha); have = os.path.getsize(path) if os.path.exists(path) else 0
        off = int(p["offset"]); data = C.b64d(p["data_b64"])
        if off != have: return {"offset": have}  # resend from where we are
        if have + len(data) > self.cur[1] or len(data) > 2 * CHUNK: raise ValueError("chunk exceeds declared size")
        with open(path, "ab") as f: f.write(data)
        return {"offset": have + len(data)}
    def finish(self, p):
        sha = str(p["sha256"]).lower()
        if not self.cur or self.cur[0] != sha: raise ValueError("update_begin first")
        path = self._path(sha)
        if os.path.getsize(path) != self.cur[1]: raise ValueError("incomplete")
        from .update import sha256_file as h
        if h(path) != sha: os.remove(path); self.cur = None; raise ValueError("sha256 mismatch, discarded")
        info = self.verify(path)  # raises on signer / version problems
        self.ready = dict(info, sha256=sha, size=self.cur[1], path=path)
        return {"ready": True, **{k: v for k, v in info.items()}, "note": "waiting for the [ install ] tap on the phone"}
