#!/usr/bin/env python3
"""Emit (formerly Matrix SMS) relay: TLS on :443, token-gated rooms, splices one phone and one PC that present the same room id.
Pure Python 3 stdlib (asyncio + ssl). Never logs or stores payloads. Protocol (after TLS):
  client -> "MSMS-RELAY/1 <token> <room:64 hex> <phone|pc>\\n"
  relay  -> "OK wait\\n"   (until the peer arrives; client may send "PING\\n", relay answers "PONG\\n")
  relay  -> "OK paired\\n"; each client then sends "GO\\n" (ends control mode), after which the relay forwards
            length-prefixed frames (4-byte BE length) verbatim in both directions, enforcing a size cap.
  relay  -> "ERR <reason>\\n" and close on any failure.
  "GET /healthz" -> "HTTP/1.1 200 OK ... ok" (reveals nothing else).
"""
import argparse, asyncio, hmac, json, os, ssl, struct, sys, time

MAGIC = b"MSMS-RELAY/1"
MAX_FRAME = 32 * 1024 * 1024 + 1024

class Cfg:
    token = b""; max_rooms = 64; max_conns = 256; wait_idle = 330.0; splice_idle = 900.0; pc_wait = 20.0
    hello_timeout = 10.0; ip_rate = 30; ip_window = 60.0; bad_auth_limit = 5; bad_auth_ban = 600.0
    room_rate = 4 * 1024 * 1024; room_burst = 64 * 1024 * 1024; dump_dir = None

def ip_prefix(ip):
    """Coarse network prefix for logs (IPv4 /24, IPv6 /48) so the journal never holds a full address."""
    import ipaddress
    try:
        a = ipaddress.ip_address(str(ip).split("%")[0])
        if a.version == 6 and a.ipv4_mapped: a = a.ipv4_mapped
        return str(ipaddress.ip_network(f"{a}/{24 if a.version == 4 else 48}", strict=False))
    except ValueError: return "?"

def log(event, ip, role="-", room="-", **kw):
    """Connection metadata only (time, peer network prefix, role, room prefix, reason). Never full IPs, contents or tokens."""
    ip = ip_prefix(ip)
    extra = "".join(f" {k}={v}" for k, v in kw.items())
    print(f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} {event} ip={ip} role={role} room={room[:8] if room != '-' else '-'}{extra}",
          file=sys.stderr, flush=True)

class Bucket:
    def __init__(self, rate, cap): self.rate, self.cap, self.tokens, self.t = rate, cap, cap, time.monotonic()
    async def take(self, n):
        while True:
            now = time.monotonic(); self.tokens = min(self.cap, self.tokens + (now - self.t) * self.rate); self.t = now
            if self.tokens >= n: self.tokens -= n; return
            await asyncio.sleep(min(1.0, (n - self.tokens) / self.rate))

class Peer:
    def __init__(self, r, w, role):
        self.r, self.w, self.role = r, w, role; loop = asyncio.get_running_loop()
        self.paired = loop.create_future(); self.go = loop.create_future(); self.splice_done = loop.create_future()

class Relay:
    def __init__(self, cfg: Cfg):
        self.cfg = cfg; self.rooms = {}; self.conns = 0; self.ip_hits = {}; self.bad = {}
        self.stats = {"paired": 0, "rejected": 0}

    def _ip_ok(self, ip):
        now = time.monotonic()
        if self.bad.get(ip, (0, 0))[1] > now: return False
        hits = [t for t in self.ip_hits.get(ip, []) if now - t < self.cfg.ip_window]; hits.append(now)
        self.ip_hits[ip] = hits
        if len(self.ip_hits) > 10000: self.ip_hits = {k: v for k, v in self.ip_hits.items() if v and now - v[-1] < self.cfg.ip_window}
        return len(hits) <= self.cfg.ip_rate

    def _bad_auth(self, ip):
        n, _ = self.bad.get(ip, (0, 0)); n += 1
        self.bad[ip] = (n, time.monotonic() + self.cfg.bad_auth_ban if n >= self.cfg.bad_auth_limit else 0)

    async def handle(self, r, w):
        ip = (w.get_extra_info("peername") or ("?",))[0]
        if self.conns >= self.cfg.max_conns or not self._ip_ok(ip): w.close(); return
        self.conns += 1; t0 = time.monotonic(); info = {"role": "-", "room": "-", "why": "closed"}
        log("connect", ip, conns=self.conns)
        try: await self._handle(r, w, ip, info)
        except (asyncio.TimeoutError, ConnectionError, asyncio.IncompleteReadError, ssl.SSLError, OSError, ValueError) as e:
            info["why"] = type(e).__name__
        finally:
            self.conns -= 1
            log("disconnect", ip, info["role"], info["room"], why=info["why"], secs=round(time.monotonic() - t0, 1))
            try: w.close()
            except Exception: pass

    async def _err(self, w, why):
        self.stats["rejected"] += 1
        try: w.write(b"ERR " + why.encode() + b"\n"); await w.drain()
        except Exception: pass

    async def _handle(self, r, w, ip, info=None):
        info = info if info is not None else {}
        line = await asyncio.wait_for(r.readuntil(b"\n"), self.cfg.hello_timeout)
        if len(line) > 300: return
        if line.startswith(b"GET /healthz "):
            info["why"] = "healthz"; w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 3\r\nConnection: close\r\n\r\nok\n"); await w.drain(); return
        parts = line.strip().split(b" ")
        if len(parts) != 4 or parts[0] != MAGIC: return await self._err(w, "proto")
        _, tok, room, role = parts
        if not hmac.compare_digest(tok, self.cfg.token):
            info["why"] = "auth"; self._bad_auth(ip); await asyncio.sleep(1.0); return await self._err(w, "auth")
        try: room = room.decode(); bytes.fromhex(room); role = role.decode()
        except ValueError: return await self._err(w, "proto")
        if len(room) != 64 or role not in ("phone", "pc"): return await self._err(w, "proto")
        info["role"], info["room"] = role, room
        slot = self.rooms.get(room)
        log("join", ip, role, room, peer_waiting=bool(slot and slot.get("pc" if role == "phone" else "phone")))
        if slot is None:
            if len(self.rooms) >= self.cfg.max_rooms: return await self._err(w, "full")
            slot = self.rooms[room] = {}
        old = slot.get(role)
        if old and not old.paired.done(): old.paired.set_result(None); log("replace", ip, role, room)  # newer connection replaces a stale waiter
        me = Peer(r, w, role); slot[role] = me
        other = slot.get("pc" if role == "phone" else "phone")
        try:
            if other and not other.paired.done():
                other.paired.set_result(me); me.paired.set_result(other)
                del self.rooms[room]
            else:
                w.write(b"OK wait\n"); await w.drain()
                await self._wait(me, self.cfg.pc_wait if role == "pc" else self.cfg.wait_idle)
            peer = me.paired.result() if me.paired.done() else None
            if peer is None:
                if not me.splice_done.done(): me.splice_done.set_result(None)
                info["why"] = "nopeer" if role == "pc" else "idle"
                return await self._err(w, "nopeer" if role == "pc" else "idle")
            log("paired", ip, role, room); info["why"] = "session-end"
            w.write(b"OK paired\n"); await w.drain()
            # Drain control lines until GO.
            while True:
                l = await asyncio.wait_for(r.readuntil(b"\n"), self.cfg.hello_timeout)
                if l == b"GO\n": break
                if l != b"PING\n": return
            me.go.set_result(True)
            if role == "phone":  # the phone side drives the splice once both sent GO
                await asyncio.wait_for(asyncio.shield(peer.go), self.cfg.hello_timeout)
                self.stats["paired"] += 1
                await self._splice(me, peer, room)
            else:
                await peer.splice_done
        finally:
            if not me.splice_done.done() and role == "pc": me.splice_done.set_result(None)
            if role == "phone" and not me.splice_done.done(): me.splice_done.set_result(None)
            s = self.rooms.get(room)
            if s and s.get(role) is me:
                del s[role]
                if not s: self.rooms.pop(room, None)

    async def _wait(self, me, idle):
        while not me.paired.done():
            rd = asyncio.ensure_future(me.r.readuntil(b"\n"))
            done, _ = await asyncio.wait({rd, me.paired}, timeout=idle, return_when=asyncio.FIRST_COMPLETED)
            if rd in done:
                if rd.result() != b"PING\n":
                    if not me.paired.done(): me.paired.set_result(None)
                    return
                me.w.write(b"PONG\n"); await me.w.drain(); continue
            rd.cancel()
            try: await rd
            except BaseException: pass
            if not done and not me.paired.done(): me.paired.set_result(None)  # idle timeout

    async def _splice(self, a, b, room):
        loop = asyncio.get_running_loop()
        bucket = Bucket(self.cfg.room_rate, self.cfg.room_burst)
        dump = None  # debug dump: each record = b'>' (pc->phone) or b'<' (phone->pc) + the frame exactly as forwarded
        if self.cfg.dump_dir:
            os.makedirs(self.cfg.dump_dir, exist_ok=True)
            dump = open(os.path.join(self.cfg.dump_dir, f"{int(time.time())}-{room[:8]}.bin"), "ab")
        last = [time.monotonic()]
        async def pump(src, dst, tag):
            while True:
                hdr = await src.r.readexactly(4)
                (n,) = struct.unpack(">I", hdr)
                if n > MAX_FRAME: raise ValueError("frame too large")
                body = await src.r.readexactly(n)
                await bucket.take(n + 4); last[0] = time.monotonic()
                if dump: dump.write(tag + hdr + body); dump.flush()
                dst.w.write(hdr + body); await dst.w.drain()
        async def idle():
            while True:
                await asyncio.sleep(min(30, self.cfg.splice_idle))
                if time.monotonic() - last[0] > self.cfg.splice_idle: return
        tasks = [asyncio.ensure_future(x) for x in (pump(a, b, b"<"), pump(b, a, b">"), idle())]
        try: await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for t in tasks: t.cancel()
            for t in tasks:
                try: await t
                except BaseException: pass
            if dump: dump.close()
            for p in (a, b):
                try: p.w.close()
                except Exception: pass
            if not a.splice_done.done(): a.splice_done.set_result(None)
            if not b.splice_done.done(): b.splice_done.set_result(None)

def load_cfg(path, args):
    c = Cfg()
    if path and os.path.exists(path):
        d = json.load(open(path))
        for k, v in d.items():
            if hasattr(Cfg, k) and k != "token": setattr(c, k, v)
        if d.get("token"): c.token = d["token"].encode()
    if args.token_file: c.token = open(args.token_file).read().strip().encode()
    if args.dump_dir: c.dump_dir = args.dump_dir
    if not c.token or len(c.token) < 16: sys.exit("relay token missing or too short")
    return c

async def serve(cfg, host, port, certfile=None, keyfile=None, ready=None):
    ctx = None
    if certfile:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        ctx.load_cert_chain(certfile, keyfile)
    relay = Relay(cfg)
    srv = await asyncio.start_server(relay.handle, host or None, port, ssl=ctx, limit=512, ssl_handshake_timeout=10 if ctx else None)
    if ready: ready(srv, relay)
    async with srv: await srv.serve_forever()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="/etc/msms-relay/relay.json")
    ap.add_argument("--host", default="", help="bind address (default: all IPv4 + IPv6)"); ap.add_argument("--port", type=int, default=443)
    ap.add_argument("--cert", default="/etc/msms-relay/cert.pem"); ap.add_argument("--key", default="/etc/msms-relay/key.pem")
    ap.add_argument("--token-file"); ap.add_argument("--dump-dir", help="DEBUG ONLY: write forwarded bytes here (off by default)")
    a = ap.parse_args(); cfg = load_cfg(a.config, a)
    if cfg.dump_dir: print("WARNING: dump mode on, forwarded bytes are written to", cfg.dump_dir, flush=True)
    print(f"msms-relay listening on {a.host or '*'}:{a.port}", flush=True)
    asyncio.run(serve(cfg, a.host, a.port, a.cert, a.key))

if __name__ == "__main__": main()
