"""PC <-> simulated phone through a real local relay instance (TLS, pinned cert, token), plus relay push and capture check."""
import os, sys, time, subprocess, pytest
RELAY = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "relay")
sys.path.insert(0, RELAY); sys.path.insert(0, os.path.join(RELAY, "tests"))
from relay_testlib import start_relay, cert  # noqa: F401  (session fixture)
from msms import keystore, client as CL, tools, transport as T
from msms.sim_phone import SimPhone

@pytest.fixture
def setup(tmp_path, monkeypatch, cert):
    monkeypatch.setenv("MSMS_HOME", str(tmp_path))
    dump = tmp_path / "dump"
    relay = start_relay(cert, dump_dir=str(dump))
    phone = SimPhone(); phone.pairing = True; store = keystore.Store()
    CL.pair(store, "127.0.0.1", phone.port, ask_code=lambda: _wait(phone))
    tok = tmp_path / "client.json"; tok.write_text('{"token": "%s"}' % ("t" * 32))
    from msms import cli
    assert cli.main(["relay", "set", "--host", "127.0.0.1", "--port", str(relay["port"]), "--fingerprint", cert[2], "--token-file", str(tok)]) == 0
    lan = CL.PhoneClient(keystore.Store(), manual_host=f"127.0.0.1:{phone.port}", use_mdns=False)
    yield phone, relay, lan, dump
    lan.close(); phone.relay_stop = True; phone.close()

def _wait(phone):
    for _ in range(300):
        if phone.last_code: return phone.last_code
        time.sleep(0.01)

def test_push_then_relay_fallback(setup):
    phone, relay, lan, dump = setup
    from msms import cli
    assert cli.main(["--host", f"127.0.0.1:{phone.port}", "relay", "push"]) == 0
    assert phone.relay["token"] == "t" * 32
    phone.start_relay(); time.sleep(0.5)
    phone.stop_lan()  # phone "leaves home": LAN listener gone
    c = CL.PhoneClient(keystore.Store(), use_mdns=False)
    th = tools.run(c, "list_threads", {})["threads"]
    assert c.via.name == "relay" and th[0]["thread_id"] == 2
    d = tools.run(c, "create_draft", {"thread_id": 1, "body": "MARKER-7731 via relay"})["draft"]
    assert d["status"] == "pending" and not phone.sent
    c.close(); time.sleep(0.3)
    # relay dump only contains ciphertext
    sys.path.insert(0, os.path.join(RELAY, "tools")); import capture_check
    files = list(dump.iterdir()); assert files
    rep = capture_check.check(b"".join(f.read_bytes() for f in files), [b"MARKER-7731", b"Dinner sunday", b"+15551230001", b"15551230001"])
    assert rep["ok"], rep
    # control: the same check flags plaintext
    assert not capture_check.check(b"xx MARKER-7731 yy", [b"MARKER-7731"])["ok"]

def test_relay_reconnects_after_session(setup):
    phone, relay, lan, dump = setup
    tools.run(lan, "list_threads", {})
    lan.call("relay_config", {"host": "127.0.0.1", "port": relay["port"], "fingerprint": T.norm_fp(lan.store.data["relay"]["fingerprint"]), "token": "t" * 32})
    phone.start_relay(); time.sleep(0.4); phone.stop_lan()
    for _ in range(2):
        c = CL.PhoneClient(keystore.Store(), use_mdns=False)
        st = tools.run(c, "get_status", {}); assert st["connected"], (_, st.get("error"), phone.relay_error); c.close(); time.sleep(1.5)
    assert phone.relay_sessions >= 2

def test_relay_config_push_refused_over_relay(setup):
    """`msms relay push` (relay settings) is home-Wi-Fi only, matching the phone's LinkRpc 'forbidden' guard.
    APK updates are a different path and DO work over the relay (see test_update_push_over_relay)."""
    phone, relay, lan, dump = setup
    lan.call("relay_config", {"host": "127.0.0.1", "port": relay["port"], "fingerprint": lan.store.data["relay"]["fingerprint"], "token": "t" * 32})
    phone.start_relay(); time.sleep(0.4); phone.stop_lan()
    from msms import cli
    assert cli.main(["relay", "push"]) == 2

def test_update_push_over_relay(setup, tmp_path):
    phone, relay, lan, dump = setup
    lan.call("relay_config", {"host": "127.0.0.1", "port": relay["port"], "fingerprint": lan.store.data["relay"]["fingerprint"], "token": "t" * 32})
    phone.start_relay(); time.sleep(0.4); phone.stop_lan()
    from msms import update
    p = tmp_path / "x.apk"; p.write_bytes(b"PK\x03\x04" + os.urandom(600_000))
    c = CL.PhoneClient(keystore.Store(), use_mdns=False)
    r = update.push(c, str(p))
    assert c.via.name == "relay" and r["ready"]
    assert open(phone.updates.ready["path"], "rb").read() == p.read_bytes()
    c.close()

def test_relay_status(setup, capsys):
    from msms import cli
    assert cli.main(["relay", "status"]) == 0
    out = capsys.readouterr().out
    assert "health: ok" in out and "t" * 32 not in out
