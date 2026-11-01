import asyncio, os, subprocess, sys, threading, time, pytest
ROOT = os.path.dirname(os.path.dirname(__file__)); sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(os.path.dirname(ROOT), "pc-companion", "app"))
import msms_relay as R

@pytest.fixture(scope="session")
def cert(tmp_path_factory):
    d = tmp_path_factory.mktemp("cert")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "2",
                    "-subj", "/CN=msms-relay", "-keyout", str(d / "k.pem"), "-out", str(d / "c.pem")], check=True, capture_output=True)
    fp = subprocess.run(["openssl", "x509", "-in", str(d / "c.pem"), "-noout", "-fingerprint", "-sha256"], capture_output=True, text=True).stdout
    return str(d / "c.pem"), str(d / "k.pem"), fp.split("=")[1].strip().replace(":", "").lower()

def start_relay(cert, **over):
    cfg = R.Cfg(); cfg.token = b"t" * 32
    for k, v in over.items(): setattr(cfg, k, v)
    box = {}; ev = threading.Event()
    def run():
        loop = asyncio.new_event_loop(); box["loop"] = loop
        def ready(srv, relay): box["port"] = srv.sockets[0].getsockname()[1]; box["relay"] = relay; ev.set()
        try: loop.run_until_complete(R.serve(cfg, "127.0.0.1", 0, cert[0], cert[1], ready))
        except Exception: pass
    threading.Thread(target=run, daemon=True).start(); ev.wait(5)
    return box

@pytest.fixture
def relay(cert):
    return start_relay(cert)
