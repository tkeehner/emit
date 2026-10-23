"""Minimal MCP server (JSON-RPC 2.0). stdio transport (newline-delimited) and a single-POST HTTP handler used by web.py."""
import json, sys, traceback
from . import tools, __version__

PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"]
INSTRUCTIONS = ("Tools for the user's text messages on his Android phone (Emit). Read freely. You may create drafts, "
                "but you can never send: request_send only asks the user to approve on his phone. Times are epoch ms plus *_local ISO strings.")

def tool_list():
    out = []
    for t in tools.TOOLS:
        out.append({"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"],
                    "annotations": {"readOnlyHint": t["readOnly"], "destructiveHint": False, "openWorldHint": not t["readOnly"],
                                    "idempotentHint": t["readOnly"]}})
    return out

def call_tool(client, name, args):
    try:
        r = tools.run(client, name, args)
    except Exception as e:
        return {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
    if name == "get_media" and isinstance(r, dict) and "data_b64" in r:
        meta = {k: v for k, v in r.items() if k != "data_b64"}
        if str(r.get("mime", "")).startswith("image/"):
            return {"content": [{"type": "image", "data": r["data_b64"], "mimeType": r["mime"]},
                                {"type": "text", "text": json.dumps(meta)}]}
        return {"content": [{"type": "text", "text": json.dumps(r)}]}
    return {"content": [{"type": "text", "text": json.dumps(r, ensure_ascii=False, indent=1)}], "structuredContent": r}

def handle(client, msg):
    """Returns a response dict, or None for notifications."""
    mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None: return None
    def ok(r): return {"jsonrpc": "2.0", "id": mid, "result": r}
    if method == "initialize":
        v = params.get("protocolVersion")
        return ok({"protocolVersion": v if v in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
                   "capabilities": {"tools": {"listChanged": False}},
                   "serverInfo": {"name": "matrix-sms", "version": __version__}, "instructions": INSTRUCTIONS})
    if method == "ping": return ok({})
    if method == "tools/list": return ok({"tools": tool_list()})
    if method == "tools/call": return ok(call_tool(client, params.get("name"), params.get("arguments") or {}))
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}}

def serve_stdio(client, inp=None, out=None):
    inp = inp or sys.stdin; out = out or sys.stdout
    for line in inp:
        line = line.strip()
        if not line: continue
        try: msg = json.loads(line)
        except ValueError:
            out.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}) + "\n"); out.flush(); continue
        msgs = msg if isinstance(msg, list) else [msg]
        resps = []
        for m in msgs:
            try: r = handle(client, m)
            except Exception as e:
                traceback.print_exc(file=sys.stderr)
                r = {"jsonrpc": "2.0", "id": m.get("id"), "error": {"code": -32603, "message": str(e)}}
            if r: resps.append(r)
        if resps:
            out.write(json.dumps(resps if isinstance(msg, list) else resps[0]) + "\n"); out.flush()
