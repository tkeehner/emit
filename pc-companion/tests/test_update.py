import os, time, pytest, hashlib
from msms import update, keystore, client as CL
from msms.sim_phone import SimPhone

@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("MSMS_HOME", str(tmp_path))
    phone = SimPhone(); phone.pairing = True; store = keystore.Store()
    CL.pair(store, "127.0.0.1", phone.port, ask_code=lambda: _w(phone))
    c = CL.PhoneClient(store, manual_host=f"127.0.0.1:{phone.port}", use_mdns=False)
    yield phone, c, tmp_path
    c.close(); phone.close()

def _w(p):
    for _ in range(300):
        if p.last_code: return p.last_code
        time.sleep(.01)

def apk(tmp_path, n=2_500_000):
    p = tmp_path / "x.apk"; p.write_bytes(b"PK\x03\x04" + os.urandom(n)); return p

def test_push_chunked(env):
    phone, c, tmp = env; p = apk(tmp)
    r = update.push(c, str(p))
    assert r["ready"] and phone.updates.ready["sha256"] == hashlib.sha256(p.read_bytes()).hexdigest()
    assert open(phone.updates.ready["path"], "rb").read() == p.read_bytes()

def test_resume(env):
    phone, c, tmp = env; p = apk(tmp); sha = update.sha256_file(p); data = p.read_bytes()
    c.call("update_begin", {"sha256": sha, "size": len(data)})
    from msms.crypto import b64e
    c.call("update_chunk", {"sha256": sha, "offset": 0, "data_b64": b64e(data[:update.CHUNK])})
    assert c.call("update_begin", {"sha256": sha, "size": len(data)})["offset"] == update.CHUNK
    # out-of-order chunk is ignored and the phone reports where it is
    assert c.call("update_chunk", {"sha256": sha, "offset": 5, "data_b64": b64e(b"zz")})["offset"] == update.CHUNK
    assert update.push(c, str(p))["ready"]

def test_hash_mismatch(env):
    phone, c, tmp = env; data = b"PK\x03\x04" + b"a" * 100
    from msms.crypto import b64e
    c.call("update_begin", {"sha256": "0" * 64, "size": len(data)})
    c.call("update_chunk", {"sha256": "0" * 64, "offset": 0, "data_b64": b64e(data)})
    with pytest.raises(CL.PhoneError, match="sha256 mismatch"): c.call("update_finish", {"sha256": "0" * 64})
    assert phone.updates.ready is None

def test_overflow_and_signer_reject(env):
    phone, c, tmp = env
    from msms.crypto import b64e
    c.call("update_begin", {"sha256": "1" * 64, "size": 3})
    with pytest.raises(CL.PhoneError): c.call("update_chunk", {"sha256": "1" * 64, "offset": 0, "data_b64": b64e(b"abcd")})
    def bad(path): raise ValueError("signing certificate does not match the installed app")
    phone.verify_apk = bad; phone.updates = None
    with pytest.raises(CL.PhoneError, match="signing certificate"): update.push(c, str(apk(tmp, 1000)))

def test_not_an_apk(env, tmp_path):
    phone, c, tmp = env; p = tmp_path / "a.apk"; p.write_bytes(b"hello")
    with pytest.raises(ValueError): update.push(c, str(p))
