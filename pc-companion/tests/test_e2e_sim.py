import json, os, time, io, pytest
from msms import keystore, client as CL, tools, mcp_server, transport as T
from msms.sim_phone import SimPhone

@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("MSMS_HOME", str(tmp_path))
    phone = SimPhone(); store = keystore.Store()
    phone.pairing = True
    CL.pair(store, "127.0.0.1", phone.port, ask_code=lambda: _wait(phone))
    c = CL.PhoneClient(store, manual_host=f"127.0.0.1:{phone.port}", use_mdns=False)
    yield phone, store, c
    c.close(); phone.close()

def _wait(phone):
    for _ in range(200):
        if phone.last_code: return phone.last_code
        time.sleep(0.01)

def mcp(c, *msgs):
    inp = io.StringIO("".join(json.dumps(m) + "\n" for m in msgs)); out = io.StringIO()
    mcp_server.serve_stdio(c, inp, out)
    return [json.loads(l) for l in out.getvalue().splitlines()]

def test_pairing_persists(env):
    phone, store, c = env
    assert store.device()["phone_id"] == phone.phone_id
    assert keystore.Store().device()["pub"] == store.device()["pub"]  # reload from disk
    assert not phone.pairing

def test_pair_refused_when_not_in_pairing_mode(env, tmp_path):
    phone, store, c = env
    with pytest.raises(Exception): CL.pair(keystore.Store(str(tmp_path / "other.json")), "127.0.0.1", phone.port, ask_code=lambda: "1")

def test_read_paths(env):
    phone, store, c = env
    th = tools.run(c, "list_threads", {})["threads"]
    assert th[0]["thread_id"] == 2 and "date_local" in th[0]
    r = tools.run(c, "read_thread", {"thread_id": 1, "limit": 1})
    assert r["has_more"] and len(r["messages"]) == 1
    r2 = tools.run(c, "read_thread", {"thread_id": 1, "before_ms": r["oldest_date"]})
    assert r2["messages"][0]["body"] == "Dinner sunday?"
    assert tools.run(c, "search_messages", {"query": "DINNER"})["results"][0]["thread_id"] == 1
    assert tools.run(c, "get_contact", {"query": "mo"})["contacts"][0]["name"] == "Mom"
    assert tools.run(c, "get_status", {})["connected"]

def test_mcp_full_flow_requires_phone_approval(env):
    phone, store, c = env
    res = mcp(c, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
              {"jsonrpc": "2.0", "method": "notifications/initialized"},
              {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
              {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "get_media", "arguments": {"part_id": 99}}},
              {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "create_draft", "arguments": {"thread_id": 1, "body": "6pm works"}}})
    assert res[0]["result"]["serverInfo"]["name"] == "matrix-sms" and len(res) == 4
    names = {t["name"] for t in res[1]["result"]["tools"]}
    assert "request_send" in names and not any("approve" in n for n in names)
    assert res[2]["result"]["content"][0]["type"] == "image"
    draft = res[3]["result"]["structuredContent"]["draft"]
    assert draft["status"] == "pending" and not phone.sent
    r = mcp(c, {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "request_send", "arguments": {"draft_id": draft["draft_id"]}}})
    assert r[0]["result"]["structuredContent"]["draft"]["status"] == "awaiting"
    assert not phone.sent  # still nothing sent
    phone.approve_on_phone(draft["draft_id"])
    assert phone.sent[0]["body"] == "6pm works"
    ds = tools.run(c, "list_drafts", {"status": "sent"})["drafts"]
    assert ds[0]["draft_id"] == draft["draft_id"]

def test_tool_errors_are_reported(env):
    phone, store, c = env
    r = mcp(c, {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "read_thread", "arguments": {"thread_id": 404}}})
    assert r[0]["result"]["isError"]

def test_push_events(env):
    phone, store, c = env
    c.ensure(); time.sleep(0.1)
    phone.incoming(1, "+15551230001", "u up?")
    ev = c.events.get(timeout=2)
    assert ev["ev"] == "messages_changed" and ev["d"]["thread_id"] == 1

def test_revoked_pc_cannot_read(env):
    phone, store, c = env
    tools.run(c, "list_threads", {})
    phone.revoke(store.pc_id); c.close()
    with pytest.raises(CL.PhoneError): tools.run(c, "list_threads", {})

def test_pc_ui_approval_respects_phone_toggle(env):
    phone, store, c = env
    from msms import web
    import threading, urllib.request
    srv = web.make_server(c, port=0); port = srv.server_address[1]
    # rebuild with the right port so the Host check passes
    srv.server_close(); srv = web.make_server(c, port=port)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}"
    page = urllib.request.urlopen(base + "/").read().decode()
    token = page.split('const TOKEN="')[1].split('"')[0]
    def post(path, body, tok=token):
        req = urllib.request.Request(base + path, json.dumps(body).encode(), {"Content-Type": "application/json", "X-MSMS-UI": tok})
        try: return 200, json.loads(urllib.request.urlopen(req).read())
        except urllib.error.HTTPError as e: return e.code, json.loads(e.read())
    code, d = post("/api/draft", {"thread_id": 1, "body": "from ui"}); assert code == 200
    did = d["draft"]["draft_id"]
    assert post("/api/approve", {"draft_id": did}, tok="wrong")[0] == 403
    phone.approvals_from_pc = False
    code, r = post("/api/approve", {"draft_id": did}); assert code == 400 and "turned off" in r["error"] and not phone.sent
    phone.approvals_from_pc = True
    code, r = post("/api/approve", {"draft_id": did}); assert code == 200 and phone.sent[-1]["body"] == "from ui"
    # MCP over HTTP works and has no approve tool
    code, r = post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert code == 200 and len(r["result"]["tools"]) == 10
    srv.shutdown()

def test_verify_selftest(env):
    phone, store, c = env
    from msms import verify
    rep = verify.run(c, store)
    assert rep["ok"], rep
    names = " ".join(x["check"] for x in rep["checks"])
    assert "forged PC key rejected" in names and "tampered frame rejected" in names
    assert rep["nonce_counters"]["after"][0] == rep["nonce_counters"]["before"][0] + 3
