import socket, webbrowser
from msms import cli, web, client as CL

def test_second_ui_opens_browser_and_exits(tmp_path, monkeypatch):
    monkeypatch.setenv("MSMS_HOME", str(tmp_path))
    ls = socket.socket(); ls.bind(("127.0.0.1", 0)); ls.listen(); port = ls.getsockname()[1]
    opened = []
    monkeypatch.setattr(webbrowser, "open", lambda u, *a, **k: opened.append(u))
    def boom(*a, **k): raise AssertionError("second instance started")
    monkeypatch.setattr(CL, "PhoneClient", boom); monkeypatch.setattr(web, "serve", boom)
    assert cli.main(["ui", "--port", str(port)]) == 0
    assert opened == [f"http://127.0.0.1:{port}/"]
    ls.close()

def test_ui_running_false_when_free():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    assert web.ui_running(port) is False
    web._ui_sock.close(); web._ui_sock = None
