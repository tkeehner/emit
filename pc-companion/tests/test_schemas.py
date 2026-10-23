from msms import tools, mcp_server

def test_tool_schemas_wellformed():
    names = [t["name"] for t in tools.TOOLS]
    assert len(names) == len(set(names))
    for want in ["list_threads", "read_thread", "search_messages", "get_contact", "get_media", "create_draft", "list_drafts", "request_send", "get_status"]:
        assert want in names
    for t in tools.TOOLS:
        s = t["inputSchema"]; assert s["type"] == "object" and len(t["description"]) > 40
        for r in s.get("required", []): assert r in s["properties"]
        for p in s["properties"].values(): assert p["type"] in ("integer", "string", "boolean")

def test_no_send_or_approve_tool():
    for t in tools.TOOLS:
        assert t["name"] not in ("send", "send_message", "approve_send")
    assert "HUMAN APPROVAL" in tools.BY_NAME["request_send"]["description"]

def test_jsonschema_valid():
    import jsonschema
    for t in mcp_server.tool_list():
        jsonschema.Draft202012Validator.check_schema(t["inputSchema"])
