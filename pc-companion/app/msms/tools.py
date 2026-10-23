"""The single tool catalog used by MCP, the CLI and the web UI. There is deliberately NO tool that sends or
approves a message: request_send only asks the user, who must tap Approve on the phone (or in the PC UI)."""
import datetime

def _iso(ms):
    try: return datetime.datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")
    except Exception: return None

def _annotate(o):
    """Add human-readable local times next to every epoch-ms 'date'/'created' field."""
    if isinstance(o, list): return [_annotate(x) for x in o]
    if isinstance(o, dict):
        out = {k: _annotate(v) for k, v in o.items()}
        for k in ("date", "created"):
            if isinstance(o.get(k), int) and o[k] > 0: out[k + "_local"] = _iso(o[k])
        return out
    return o

def _int(a, k, default=None, lo=None, hi=None):
    v = a.get(k, default)
    if v is None: raise ValueError(f"'{k}' is required")
    v = int(v)
    if lo is not None: v = max(lo, v)
    if hi is not None: v = min(hi, v)
    return v

TOOLS = [
 {"name": "get_status",
  "description": "Connection status: whether the PC is paired and connected to the user's phone, by which transport (lan/relay), the phone app version, and how many drafts are awaiting the user's approval. Call this first if other tools fail.",
  "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
  "readOnly": True},
 {"name": "list_threads",
  "description": "List SMS/MMS conversations on the user's phone, newest activity first. Each thread has thread_id (use it with read_thread/create_draft), display name, participant addresses, last-message snippet, date (epoch ms + local ISO), unread flag, and group flag.",
  "inputSchema": {"type": "object", "properties": {
      "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 30},
      "offset": {"type": "integer", "minimum": 0, "default": 0},
      "unread_only": {"type": "boolean", "default": False}}, "additionalProperties": False},
  "readOnly": True},
 {"name": "read_thread",
  "description": "Read messages in one conversation, oldest-first within the page. Returns the newest page by default; to page back, pass before_ms = the oldest_date from the previous result. Media appears as media[] entries with part_id for get_media. outgoing=true means the user sent it.",
  "inputSchema": {"type": "object", "properties": {
      "thread_id": {"type": "integer", "description": "From list_threads or search_messages."},
      "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 30},
      "before_ms": {"type": "integer", "description": "Only messages older than this epoch-ms timestamp."}},
      "required": ["thread_id"], "additionalProperties": False},
  "readOnly": True},
 {"name": "search_messages",
  "description": "Case-insensitive substring search over message text across all conversations. Returns matching messages with thread_id, date and sender.",
  "inputSchema": {"type": "object", "properties": {
      "query": {"type": "string", "minLength": 1},
      "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 30}},
      "required": ["query"], "additionalProperties": False},
  "readOnly": True},
 {"name": "get_contact",
  "description": "Look up contacts on the user's phone by name or number fragment. Returns name/number pairs.",
  "inputSchema": {"type": "object", "properties": {"query": {"type": "string", "minLength": 1}},
      "required": ["query"], "additionalProperties": False},
  "readOnly": True},
 {"name": "get_media",
  "description": "Fetch one MMS attachment (picture/video/audio) by part_id from read_thread. Images are returned as image content; other types as base64 with their mime type.",
  "inputSchema": {"type": "object", "properties": {"part_id": {"type": "integer"}},
      "required": ["part_id"], "additionalProperties": False},
  "readOnly": True},
 {"name": "create_draft",
  "description": "Create a reply DRAFT in an existing conversation. This does NOT send anything. The draft shows up in that chat on the user's phone (and in the PC UI) where the user can approve or reject it. Recipients are always the thread's participants; you cannot choose other recipients.",
  "inputSchema": {"type": "object", "properties": {
      "thread_id": {"type": "integer"},
      "body": {"type": "string", "minLength": 1, "maxLength": 2000, "description": "Plain text message."}},
      "required": ["thread_id", "body"], "additionalProperties": False},
  "readOnly": False},
 {"name": "list_drafts",
  "description": "List drafts and their status: pending (created, not yet submitted), awaiting (the user has been asked to approve), sent, rejected, cancelled, failed.",
  "inputSchema": {"type": "object", "properties": {
      "thread_id": {"type": "integer"},
      "status": {"type": "string", "enum": ["all", "pending", "awaiting", "sent", "rejected", "cancelled", "failed"], "default": "all"}},
      "additionalProperties": False},
  "readOnly": True},
 {"name": "request_send",
  "description": "Ask the user to approve sending a draft. REQUIRES HUMAN APPROVAL: this only posts an Approve/Deny notification on the user's phone and an entry in the PC approval queue. Nothing is sent unless the user approves. Returns immediately with status 'awaiting'; poll list_drafts to see whether it was sent or rejected. Never call this unless the user asked you to send something.",
  "inputSchema": {"type": "object", "properties": {"draft_id": {"type": "integer"}},
      "required": ["draft_id"], "additionalProperties": False},
  "readOnly": False},
 {"name": "cancel_draft",
  "description": "Withdraw a pending or awaiting draft so it can no longer be approved.",
  "inputSchema": {"type": "object", "properties": {"draft_id": {"type": "integer"}},
      "required": ["draft_id"], "additionalProperties": False},
  "readOnly": False},
]
BY_NAME = {t["name"]: t for t in TOOLS}

def run(client, name, a):
    """Execute a tool; returns a JSON-able dict (get_media returns {'mime','data_b64',...})."""
    a = a or {}
    if name not in BY_NAME: raise ValueError(f"unknown tool {name}")
    if name == "get_status":
        out = {"paired": bool(client.store.devices()), "pc_id": client.store.pc_id, "pc_name": client.store.name}
        d = client.store.device()
        if d: out["phone"] = {"phone_id": d["phone_id"], "name": d.get("name"), "last_host": d.get("host")}
        try:
            out["phone_status"] = client.call("status"); out["connected"] = True
            out["transport"] = client.via.describe() if client.via else None
        except Exception as e:
            out["connected"] = False; out["error"] = str(e)
        return out
    if name == "list_threads":
        r = client.call("list_threads", {"limit": _int(a, "limit", 30, 1, 200), "offset": _int(a, "offset", 0, 0)})
        if a.get("unread_only"): r["threads"] = [t for t in r["threads"] if t.get("unread")]
        return _annotate(r)
    if name == "read_thread":
        p = {"thread_id": _int(a, "thread_id"), "limit": _int(a, "limit", 30, 1, 200)}
        if a.get("before_ms"): p["before_ms"] = int(a["before_ms"])
        return _annotate(client.call("read_thread", p))
    if name == "search_messages":
        q = str(a.get("query", "")).strip()
        if not q: raise ValueError("'query' is required")
        return _annotate(client.call("search", {"query": q, "limit": _int(a, "limit", 30, 1, 200)}))
    if name == "get_contact":
        return client.call("contacts", {"query": str(a.get("query", ""))})
    if name == "get_media":
        return client.call("get_media", {"part_id": _int(a, "part_id")}, timeout=60)
    if name == "create_draft":
        body = str(a.get("body", "")).strip()
        if not body: raise ValueError("'body' is required")
        return _annotate(client.call("create_draft", {"thread_id": _int(a, "thread_id"), "body": body[:2000]}))
    if name == "list_drafts":
        p = {"status": a.get("status") or "all"}
        if a.get("thread_id") is not None: p["thread_id"] = int(a["thread_id"])
        return _annotate(client.call("list_drafts", p))
    if name == "request_send":
        r = client.call("request_send", {"draft_id": _int(a, "draft_id")})
        r["note"] = "Approval requested on the user's phone. Nothing is sent until the user approves."
        return _annotate(r)
    if name == "cancel_draft":
        return _annotate(client.call("cancel_draft", {"draft_id": _int(a, "draft_id")}))
