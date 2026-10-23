"""High-level connection to the paired phone: connect (manual IP > mDNS > last IP), RPC calls, push events."""
import itertools, queue, socket, threading, time
from . import crypto as C, protocol as P, transport as T, discovery

class PhoneError(Exception):
    def __init__(self, code, msg): super().__init__(f"{code}: {msg}"); self.code = code

class PhoneClient:
    def __init__(self, store, manual_host=None, transports=None, use_mdns=True):
        self.store, self.manual_host, self._transports, self.use_mdns = store, manual_host, transports, use_mdns
        self.ch = None; self.via = None; self._lock = threading.Lock()
        self._ids = itertools.count(1); self._pending = {}; self._plock = threading.Lock()
        self.events = queue.Queue(maxsize=1000); self.event_listeners = []
        self.last_error = None

    # ---- connection ----
    def device(self):
        d = self.store.device()
        if not d: raise PhoneError("not_paired", "No phone paired. Run: msms pair --host <phone-ip>")
        return d

    def connected(self): return self.ch is not None

    def ensure(self):
        with self._lock:
            if self.ch: return self.ch
            dev = self.device(); errs = []
            tlist = self._transports or T.candidates(dev, manual=self.manual_host)
            if not tlist and self.use_mdns:
                tlist = T.candidates(dev, discovered=discovery.browse(2.5), manual=self.manual_host)
            tried_mdns = not tlist
            for attempt in range(2):
                for t in tlist:
                    try:
                        sock = t.connect(timeout=4)
                        ch = P.session_initiator(sock, self.store.pc_id, self.store.secret_key(), C.b64d(dev["pub"]), dev["phone_id"])
                        self.ch, self.via = ch, t
                        if isinstance(t, T.LanTransport): self.store.update_host(dev["phone_id"], t.host, t.port)
                        threading.Thread(target=self._reader, args=(ch,), daemon=True).start()
                        self.last_error = None
                        return ch
                    except (OSError, P.ProtocolError, ValueError) as e:
                        errs.append(f"{t.describe()}: {e}")
                if tried_mdns or not self.use_mdns or self._transports: break
                tried_mdns = True
                tlist = [t for t in T.candidates(dev, discovered=discovery.browse(2.5)) if t.describe() not in {x.describe() for x in tlist}]
            relay = None if self._transports else T.relay_for(self.store, dev)
            if relay:
                try:
                    sock = relay.connect(timeout=8)
                    ch = P.session_initiator(sock, self.store.pc_id, self.store.secret_key(), C.b64d(dev["pub"]), dev["phone_id"])
                    self.ch, self.via = ch, relay
                    threading.Thread(target=self._reader, args=(ch,), daemon=True).start()
                    self.last_error = None
                    return ch
                except (OSError, P.ProtocolError, ValueError) as e:
                    errs.append(f"{relay.describe()}: {e}")
            self.last_error = "; ".join(errs) or "no address known"
            raise PhoneError("unreachable", "Phone not reachable (" + self.last_error +
                             "). Is Link PC enabled on the phone? On the same Wi-Fi try --host <phone-ip>; away from home check `msms relay status`.")

    def close(self):
        ch, self.ch = self.ch, None
        if ch: ch.close()

    def _reader(self, ch):
        try:
            while True:
                m = ch.recv()
                if "ev" in m:
                    for fn in list(self.event_listeners):
                        try: fn(m)
                        except Exception: pass
                    try: self.events.put_nowait(m)
                    except queue.Full: pass
                    continue
                with self._plock: slot = self._pending.pop(m.get("id"), None)
                if slot: slot.put(m)
        except Exception as e:
            self.last_error = f"connection lost: {e}"
        finally:
            if self.ch is ch: self.ch = None
            ch.close()
            with self._plock:
                for slot in self._pending.values(): slot.put({"e": {"code": "disconnected", "msg": "connection lost"}})
                self._pending.clear()

    def call(self, method, params=None, timeout=20):
        for attempt in range(2):
            ch = self.ensure()
            rid = next(self._ids); slot = queue.Queue(1)
            with self._plock: self._pending[rid] = slot
            try:
                ch.send({"id": rid, "m": method, "p": params or {}})
            except OSError:
                self.close(); continue
            try: m = slot.get(timeout=timeout)
            except queue.Empty:
                with self._plock: self._pending.pop(rid, None)
                raise PhoneError("timeout", f"{method} timed out")
            if "e" in m:
                if m["e"].get("code") == "disconnected" and attempt == 0: continue
                raise PhoneError(m["e"].get("code", "error"), m["e"].get("msg", ""))
            return m.get("r")
        raise PhoneError("disconnected", "connection lost")

def pair(store, host, port=T.DEFAULT_PORT, ask_code=None):
    sock = T.LanTransport(host, port).connect(timeout=6)
    sock.settimeout(180)
    try:
        dev = P.pair_initiator(sock, store.pc_id, store.name, store.secret_key(), ask_code or (lambda: input("Code shown on phone: ")))
    finally:
        sock.close()
    dev.update(host=host, port=int(port), paired_at=int(time.time()))
    store.add_device(dev)
    return dev
