import socket, ssl, struct, time, os, pytest
from relay_testlib import start_relay
from msms import transport as T

TOK = "t" * 32; ROOM = "ab" * 32

def conn(port, fp, role, tok=TOK, room=ROOM):
    s = T.tls_pinned("127.0.0.1", port, fp, 5); s.settimeout(5)
    s.sendall(f"MSMS-RELAY/1 {tok} {room} {role}\n".encode()); return s

def line(s): return T._line(s)

def pair(port, fp, room=ROOM):
    ph = conn(port, fp, "phone", room=room); assert line(ph) == b"OK wait\n"
    pc = conn(port, fp, "pc", room=room); assert line(pc) == b"OK paired\n"; assert line(ph) == b"OK paired\n"
    ph.sendall(b"GO\n"); pc.sendall(b"GO\n"); return ph, pc

def frame(b): return struct.pack(">I", len(b)) + b

def readn(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b)); assert c; b += c
    return b

def test_room_pairing_and_splice(relay, cert):
    ph, pc = pair(relay["port"], cert[2])
    pc.sendall(frame(b"hello")); assert readn(ph, 9) == frame(b"hello")
    ph.sendall(frame(b"x" * 70000)); assert readn(pc, 70004)[4:] == b"x" * 70000

def test_rooms_isolated(relay, cert):
    ph = conn(relay["port"], cert[2], "phone"); assert line(ph) == b"OK wait\n"
    pc = conn(relay["port"], cert[2], "pc", room="cd" * 32); assert line(pc) == b"OK wait\n"

def test_token_reject(relay, cert):
    s = conn(relay["port"], cert[2], "pc", tok="wrong" * 8); assert line(s) == b"ERR auth\n"

def test_bad_fingerprint(relay, cert):
    with pytest.raises(T.RelayError): T.tls_pinned("127.0.0.1", relay["port"], "00" * 32, 5)

def test_size_cap(relay, cert):
    ph, pc = pair(relay["port"], cert[2])
    pc.sendall(struct.pack(">I", 64 * 1024 * 1024))
    assert ph.recv(10) == b""  # relay dropped the room

def test_pc_waits_then_nopeer(cert):
    r = start_relay(cert, pc_wait=0.5)
    s = conn(r["port"], cert[2], "pc"); assert line(s) == b"OK wait\n"; assert line(s) == b"ERR nopeer\n"

def test_waiting_ping_and_idle_timeout(cert):
    r = start_relay(cert, wait_idle=0.6)
    s = conn(r["port"], cert[2], "phone"); assert line(s) == b"OK wait\n"
    s.sendall(b"PING\n"); assert line(s) == b"PONG\n"
    assert line(s) == b"ERR idle\n"

def test_splice_idle_timeout(cert):
    r = start_relay(cert, splice_idle=0.5)
    ph, pc = pair(r["port"], cert[2]); time.sleep(1.5)
    assert ph.recv(10) == b""

def test_max_rooms(cert):
    r = start_relay(cert, max_rooms=1)
    a = conn(r["port"], cert[2], "phone"); assert line(a) == b"OK wait\n"
    b = conn(r["port"], cert[2], "phone", room="cd" * 32); assert line(b) == b"ERR full\n"

def test_health_reveals_nothing(relay, cert):
    s = T.tls_pinned("127.0.0.1", relay["port"], cert[2], 5); s.sendall(b"GET /healthz HTTP/1.0\n")
    body = s.recv(500); assert body.startswith(b"HTTP/1.1 200 OK") and body.endswith(b"\r\n\r\nok\n")

def test_ip_rate_limit(cert):
    r = start_relay(cert, ip_rate=3)
    for _ in range(3): T.tls_pinned("127.0.0.1", r["port"], cert[2], 5).close()
    with pytest.raises((OSError, ssl.SSLError)): conn(r["port"], cert[2], "pc").recv(1) or (_ for _ in ()).throw(OSError())

def test_bad_auth_ban(cert):
    r = start_relay(cert, bad_auth_limit=2)
    for _ in range(2): assert line(conn(r["port"], cert[2], "pc", tok="x" * 32)) == b"ERR auth\n"
    with pytest.raises((OSError, ssl.SSLError)): conn(r["port"], cert[2], "pc").recv(1) or (_ for _ in ()).throw(OSError())

def test_garbage_proto(relay, cert):
    s = T.tls_pinned("127.0.0.1", relay["port"], cert[2], 5); s.settimeout(5); s.sendall(b"hello there\n")
    assert line(s) == b"ERR proto\n"

def test_dump_mode(cert, tmp_path):
    r = start_relay(cert, dump_dir=str(tmp_path))
    ph, pc = pair(r["port"], cert[2]); pc.sendall(frame(b"abc")); readn(ph, 7)
    ph.close(); pc.close(); time.sleep(0.3)
    data = b"".join(p.read_bytes() for p in tmp_path.iterdir()); assert data == b">" + frame(b"abc")

def test_tls12_refused(relay):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    with socket.create_connection(("127.0.0.1", relay["port"]), 5) as raw, pytest.raises(ssl.SSLError):
        ctx.wrap_socket(raw).do_handshake()

def test_tls13_negotiated(relay, cert):
    s = T.tls_pinned("127.0.0.1", relay["port"], cert[2], 5); assert s.version() == "TLSv1.3"; s.close()

def test_log_ip_prefix():
    import msms_relay as R
    assert R.ip_prefix("203.0.113.109") == "203.0.113.0/24"
    assert R.ip_prefix("::ffff:203.0.113.109") == "203.0.113.0/24"
    assert R.ip_prefix("2600:1700:abcd:1234::5") == "2600:1700:abcd::/48"
    assert R.ip_prefix("garbage") == "?"
