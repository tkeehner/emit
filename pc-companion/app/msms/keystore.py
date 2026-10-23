"""PC identity + paired phones. The X25519 secret key is wrapped with Windows DPAPI (CurrentUser scope);
on non-Windows (tests/dev) it is stored base64 in a 0600 file and marked 'plain'."""
import json, os, secrets, socket, sys, ctypes
from . import crypto as C

def config_dir():
    if os.environ.get("MSMS_HOME"): d = os.environ["MSMS_HOME"]
    elif sys.platform == "win32": d = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "MatrixSMS-PC")
    else: d = os.path.join(os.path.expanduser("~"), ".config", "matrixsms-pc")
    os.makedirs(d, exist_ok=True); return d

if sys.platform == "win32":
    from ctypes import wintypes
    class _BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]
    def _blob(b):
        buf = ctypes.create_string_buffer(b, len(b)); return _BLOB(len(b), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char))), buf
    def _dpapi(data: bytes, protect: bool) -> bytes:
        crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
        inb, _keep = _blob(data); ent, _k2 = _blob(b"matrixsms-pc-v1"); out = _BLOB()
        fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
        ok = (fn(ctypes.byref(inb), "msms", ctypes.byref(ent), None, None, 0x1, ctypes.byref(out)) if protect
              else fn(ctypes.byref(inb), None, ctypes.byref(ent), None, None, 0x1, ctypes.byref(out)))
        if not ok: raise OSError("DPAPI failed")
        try: return ctypes.string_at(out.pbData, out.cbData)
        finally: kernel32.LocalFree(out.pbData)
    def protect(b): return "dpapi", C.b64e(_dpapi(b, True))
    def unprotect(kind, s):
        if kind != "dpapi": raise ValueError("unexpected key protection " + kind)
        return _dpapi(C.b64d(s), False)
else:
    def protect(b): return "plain", C.b64e(b)
    def unprotect(kind, s): return C.b64d(s)

class Store:
    def __init__(self, path=None):
        self.path = path or os.path.join(config_dir(), "identity.json")
        if os.path.exists(self.path):
            with open(self.path) as f: self.data = json.load(f)
        else:
            sk, _ = C.gen_keypair(); kind, blob = protect(sk)
            self.data = {"pc_id": secrets.token_hex(8), "name": socket.gethostname()[:40], "key_kind": kind, "key": blob, "devices": []}
            self.save()
        self._sk = None
    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f: json.dump(self.data, f, indent=1)
        try: os.chmod(tmp, 0o600)
        except OSError: pass
        os.replace(tmp, self.path)
    @property
    def pc_id(self): return self.data["pc_id"]
    @property
    def name(self): return self.data["name"]
    def secret_key(self):
        if self._sk is None: self._sk = unprotect(self.data["key_kind"], self.data["key"])
        return self._sk
    def devices(self): return list(self.data["devices"])
    def device(self, phone_id=None):
        ds = self.data["devices"]
        if not ds: return None
        if phone_id is None: return ds[0]
        return next((d for d in ds if d["phone_id"] == phone_id), None)
    def add_device(self, dev):
        self.data["devices"] = [d for d in self.data["devices"] if d["phone_id"] != dev["phone_id"]] + [dev]; self.save()
    def remove_device(self, phone_id):
        self.data["devices"] = [d for d in self.data["devices"] if d["phone_id"] != phone_id]; self.save()
    def update_host(self, phone_id, host, port):
        for d in self.data["devices"]:
            if d["phone_id"] == phone_id and (d.get("host"), d.get("port")) != (host, port):
                d["host"], d["port"] = host, port; self.save()
