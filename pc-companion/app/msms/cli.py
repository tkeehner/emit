"""msms - plain-text / JSON CLI. Every command accepts --json for machine-readable output."""
import argparse, json, sys, time
from . import tools, keystore, client as CL, discovery, transport as T, __version__

def _out(args, obj, text):
    if args.json: print(json.dumps(obj, ensure_ascii=False, indent=1))
    else: print(text(obj))

def main(argv=None):
    ap = argparse.ArgumentParser(prog="msms", description="Emit PC companion")
    ap.add_argument("--host", help="phone IP[:port] (skip mDNS)")
    ap.add_argument("--json", action="store_true", help="JSON output")
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("pair", help="pair with the phone (open Link PC > PAIR on phone first)"); p.add_argument("--code")
    sp.add_parser("discover", help="list phones advertising on the LAN")
    sp.add_parser("devices", help="paired phones"); p = sp.add_parser("unpair"); p.add_argument("phone_id")
    sp.add_parser("status")
    p = sp.add_parser("threads"); p.add_argument("-n", type=int, default=20); p.add_argument("--unread", action="store_true")
    p = sp.add_parser("read"); p.add_argument("thread_id", type=int); p.add_argument("-n", type=int, default=30); p.add_argument("--before", type=int)
    p = sp.add_parser("search"); p.add_argument("query"); p.add_argument("-n", type=int, default=30)
    p = sp.add_parser("contact"); p.add_argument("query")
    p = sp.add_parser("media"); p.add_argument("part_id", type=int); p.add_argument("-o", "--out", required=True)
    p = sp.add_parser("draft", help="create a draft (never sends)"); p.add_argument("thread_id", type=int, nargs="?"); p.add_argument("body", nargs="?")
    p.add_argument("--thread", type=int, dest="thread_opt"); p.add_argument("--text", dest="text_opt")
    p = sp.add_parser("drafts"); p.add_argument("--status", default="all")
    p = sp.add_parser("request-send", help="ask for approval on the phone"); p.add_argument("draft_id", type=int)
    p = sp.add_parser("cancel"); p.add_argument("draft_id", type=int)
    sp.add_parser("tools", help="print the tool catalog (JSON schemas)")
    sp.add_parser("mcp", help="run the MCP server on stdio")
    p = sp.add_parser("ui", help="run the local web UI (+ MCP at /mcp)"); p.add_argument("--port", type=int, default=8765); p.add_argument("--no-browser", action="store_true")
    p = sp.add_parser("watch", help="print push events"); 
    p = sp.add_parser("sim", help="run a simulated phone for testing"); p.add_argument("--port", type=int, default=T.DEFAULT_PORT)
    sp.add_parser("version"); sp.add_parser("help", help="show this command list")
    p = sp.add_parser("relay", help="AWS relay settings (used only when the phone isn't reachable on the LAN)")
    rs = p.add_subparsers(dest="rcmd", required=True)
    q = rs.add_parser("set"); q.add_argument("--host", dest="rhost", required=True); q.add_argument("--port", type=int, default=443)
    q.add_argument("--fingerprint", required=True); q.add_argument("--token-file", required=True)
    rs.add_parser("status"); rs.add_parser("off"); rs.add_parser("on"); rs.add_parser("clear")
    q = rs.add_parser("push", help="send the relay config to the phone over the encrypted LAN link"); q.add_argument("--disable", action="store_true")
    p = sp.add_parser("update", help="phone app updates over the link"); us = p.add_subparsers(dest="ucmd", required=True)
    q = us.add_parser("push", help="send an APK to the phone (the user taps install on the phone)"); q.add_argument("apk")
    sp.add_parser("verify", help="live crypto self-test with the phone")
    sp.add_parser("agent", help="background: keep the phone link open and forward @bot commands to the webhook")
    p = sp.add_parser("prime-reply", help="deliver a reply into the phone's Prime chat (queued if the phone is offline)"); p.add_argument("text", nargs="+")
    sp.add_parser("prime-outbox", help="show queued/delivered Prime chat replies")
    p = sp.add_parser("bot", help="@bot webhook settings"); bs = p.add_subparsers(dest="bcmd", required=True)
    q = bs.add_parser("set"); q.add_argument("--url", required=True); q.add_argument("--key-file")
    q.add_argument("--header", help="auth header name (default Authorization)"); q.add_argument("--scheme", help="value prefix (default Bearer; 'none' = raw key)")
    bs.add_parser("status"); bs.add_parser("test", help="POST a sample bot_command to the webhook"); bs.add_parser("log"); bs.add_parser("clear")
    argv = list(sys.argv[1:] if argv is None else argv)
    front = []  # allow --host/--json anywhere on the line
    for flag, takes in ((("--host", True),) if argv[:1] != ["relay"] else ()) + (("--json", False),):
        while flag in argv:
            i = argv.index(flag); front += argv[i:i + 1 + takes]; del argv[i:i + 1 + takes]
    args = ap.parse_args(front + argv)

    if args.cmd == "version": print(__version__); return 0
    if args.cmd == "help": ap.print_help(); return 0
    if args.cmd == "sim":
        from .sim_phone import SimPhone
        s = SimPhone(port=args.port, pairing_code_cb=lambda c: print("SIM PHONE PAIRING CODE:", c, flush=True))
        s.pairing = True; print(f"sim phone on 127.0.0.1:{s.port} (pairing open). Approve drafts by typing: approve <id>", flush=True)
        if not sys.stdin or not sys.stdin.isatty():
            while True: time.sleep(3600)
        for line in sys.stdin:
            w = line.split()
            if w[:1] == ["approve"]: print(s.approve_on_phone(int(w[1])))
            elif w[:1] == ["deny"]: s.deny_on_phone(int(w[1]))
            elif w[:1] == ["pair"]: s.pairing = True; print("pairing open")
        return 0
    if args.cmd == "tools": print(json.dumps(tools.TOOLS, indent=1)); return 0
    if args.cmd == "discover":
        r = discovery.browse(3.0); _out(args, r, lambda r: "\n".join(f"{k}  {v}" for k, v in r.items()) or "none found"); return 0

    store = keystore.Store()
    if args.cmd == "devices":
        _out(args, store.devices(), lambda ds: "\n".join(f"{d['phone_id']}  {d.get('name')}  last={d.get('host')}:{d.get('port')}" for d in ds) or "no paired phones"); return 0
    if args.cmd == "relay" and args.rcmd != "push":
        return _relay_local(args, store)
    if args.cmd == "bot":
        from . import bot
        if args.bcmd == "set":
            try: _out(args, bot.set_config(store, args.url, args.key_file, args.header, args.scheme), lambda d: "bot webhook saved: " + json.dumps(d))
            except (ValueError, OSError) as e: print("error:", e, file=sys.stderr); return 2
            return 0
        if args.bcmd == "status": _out(args, bot.status(store), lambda d: "\n".join(f"{k}: {v}" for k, v in d.items())); return 0
        if args.bcmd == "clear": store.data.pop("bot", None); store.save(); print("bot webhook removed"); return 0
        if args.bcmd == "log":
            import os
            try: items = json.load(open(os.path.join(keystore.config_dir(), "bot_log.json")))
            except (OSError, ValueError): items = []
            _out(args, items[-20:], lambda l: "\n".join(f"{time.strftime('%m-%d %H:%M', time.localtime(i['received']/1000))}  {i['status']:<9} {i['instruction'][:70]}" + (f"  ({i['last_error']})" if i.get('last_error') else "") for i in l) or "no bot commands yet"); return 0
        if args.bcmd == "test":
            body = bot.build_body({"cmd_id": "test-" + str(int(time.time())), "thread_id": 0, "participants": [{"number": "+15550000000", "name": "Test"}],
                                   "instruction": "@bot this is a test from msms bot test (ignore)", "context": [], "ts": int(time.time() * 1000)})
            body["test"] = True
            try: code, txt = bot.post(store, body)
            except Exception as e: print("error:", e, file=sys.stderr); return 1
            _out(args, {"http": code, "response": txt}, lambda d: f"HTTP {d['http']}: {d['response'][:300]}"); return 0 if 200 <= code < 300 else 1
    if args.cmd == "prime-outbox":
        from . import bot
        _out(args, bot._outbox_load()[-20:], lambda l: "\n".join(f"{time.strftime('%m-%d %H:%M', time.localtime(i['ts']/1000))}  {i['status']:<9} {i['text'][:70]}" + (f"  ({i['last_error']})" if i.get('last_error') and i['status'] == 'queued' else "") for i in l) or "no Prime replies yet"); return 0
    if args.cmd == "prime-reply":
        from . import bot
        try: item = bot.prime_queue(" ".join(args.text))
        except ValueError as e: print("error:", e, file=sys.stderr); return 2
        c = CL.PhoneClient(store, manual_host=args.host)
        try:
            try: c.ensure(); done, left, err = bot.prime_drain(c)
            except Exception as e: done, left, err = 0, None, str(e)[:200]
        finally: c.close()
        st = next((i for i in bot._outbox_load() if i["reply_id"] == item["reply_id"]), item)
        res = {"reply_id": item["reply_id"], "status": st["status"], "accepted": True}
        if st["status"] != "delivered": res["note"] = "phone not reachable or not updated yet; queued, the Emit agent/UI retries every ~20 s"; res["error"] = err
        _out(args, res, lambda r: ("delivered to the phone's Prime chat" if r["status"] == "delivered" else f"queued ({r.get('error')}); will retry automatically") + f" [id {r['reply_id'][:8]}]")
        return 0
    if args.cmd == "unpair": store.remove_device(args.phone_id); print("removed (also revoke this PC on the phone)"); return 0
    if args.cmd == "pair":
        host = args.host
        if not host:
            found = discovery.browse(3.0)
            if not found: print("No phone found via mDNS. Use: msms pair --host <phone-ip shown on the Link PC screen>"); return 2
            host = next(iter(found.values())); print("found phone at", host)
        h, _, port = host.partition(":")
        print("Type the 6-digit code shown on the phone, then tap ALLOW on the phone.")
        dev = CL.pair(store, h, port or T.DEFAULT_PORT, (lambda: args.code) if args.code else None)
        print(f"Paired with {dev['name']} ({dev['phone_id']})."); return 0

    if args.cmd == "ui":
        from . import web
        if web.ui_running(args.port):
            url = f"http://127.0.0.1:{args.port}/"
            print(f"Emit is already running; opening {url}", flush=True)
            if not args.no_browser:
                import webbrowser; webbrowser.open(url)
            return 0
    c = CL.PhoneClient(store, manual_host=args.host)
    try:
        if args.cmd == "mcp":
            from . import mcp_server; mcp_server.serve_stdio(c); return 0
        if args.cmd == "ui":
            from . import web; web.serve(c, args.port, not args.no_browser, store=store); return 0
        if args.cmd == "agent":
            from . import bot
            lk = bot.take_lock()
            if not lk: print("another Emit agent/UI already handles @bot on this PC"); return 0
            bot.BotAgent(c, store).start(); print("msms agent: holding phone link, forwarding @bot commands (Ctrl+C to stop)", flush=True)
            while True: time.sleep(3600)
        if args.cmd == "watch":
            c.ensure(); print("watching (Ctrl+C to stop)", flush=True)
            while True: print(json.dumps(c.events.get()), flush=True)
        run = lambda n, a: tools.run(c, n, a)
        if args.cmd == "relay":  # push
            r = store.data.get("relay")
            if not r: print("set the relay first: msms relay set ..."); return 2
            c.ensure()
            if not isinstance(c.via, T.LanTransport): print("push only over the home Wi-Fi link (connected via " + c.via.describe() + ")"); return 2
            res = c.call("relay_config", {"host": r["host"], "port": r["port"], "fingerprint": r["fingerprint"], "token": r["token"],
                                          "enabled": not args.disable})
            _out(args, res, lambda r: "phone relay config updated: " + json.dumps(r)); return 0
        if args.cmd == "update":
            from . import update
            last = [-1]
            def prog(o, n):
                pct = o * 100 // n
                if pct // 10 != last[0] // 10 and not args.json: print(f"  {pct}%", flush=True)
                last[0] = pct
            r = update.push(c, args.apk, prog)
            _out(args, r, lambda r: f"sent {r['size']} bytes (sha256 {r['sha256'][:16]}..) via {c.via.describe()}; phone accepted "
                 f"{r.get('version_name')} ({r.get('version_code')}). Tap the update button on the phone, then [ install ]."); return 0
        if args.cmd == "verify":
            from . import verify; rep = verify.run(c, store)
            _out(args, rep, verify.render); return 0 if rep["ok"] else 1
        if args.cmd == "status":
            _out(args, run("get_status", {}), lambda r: json.dumps(r, indent=1))
        elif args.cmd == "threads":
            _out(args, run("list_threads", {"limit": args.n, "unread_only": args.unread}), lambda r: "\n".join(
                f"{t['thread_id']:>6}  {'*' if t['unread'] else ' '} {t['name'][:28]:<28}  {t.get('date_local','')[:16]}  {t['snippet'][:60]!r}" for t in r["threads"]))
        elif args.cmd == "read":
            a = {"thread_id": args.thread_id, "limit": args.n}
            if args.before: a["before_ms"] = args.before
            _out(args, run("read_thread", a), lambda r: "\n".join(
                f"[{m.get('date_local','')[:16]}] {'me' if m['outgoing'] else (m.get('from_name') or m['from'])}: {m['body']}" +
                "".join(f" <media part_id={x['part_id']} {x['mime']}>" for x in m.get("media", [])) for m in r["messages"]) +
                (f"\n(older: --before {r['oldest_date']})" if r.get("has_more") else ""))
        elif args.cmd == "search":
            _out(args, run("search_messages", {"query": args.query, "limit": args.n}), lambda r: "\n".join(
                f"thread {m['thread_id']} [{m.get('date_local','')[:16]}] {'me' if m['outgoing'] else m['from']}: {m['body'][:100]}" for m in r["results"]))
        elif args.cmd == "contact":
            _out(args, run("get_contact", {"query": args.query}), lambda r: "\n".join(f"{x['name']}  {x['number']}" for x in r["contacts"]))
        elif args.cmd == "media":
            r = run("get_media", {"part_id": args.part_id})
            from .crypto import b64d
            with open(args.out, "wb") as f: f.write(b64d(r["data_b64"]))
            print(f"saved {r['size']} bytes ({r['mime']}) to {args.out}")
        elif args.cmd in ("draft", "request-send", "cancel", "drafts"):
            if args.cmd == "draft":
                args.thread_id = args.thread_opt if args.thread_opt is not None else args.thread_id
                args.body = args.text_opt if args.text_opt is not None else args.body
                if args.thread_id is None or not args.body: print("usage: msms draft --thread <id> --text <text>", file=sys.stderr); return 2
            name, a = {"draft": ("create_draft", {"thread_id": getattr(args, "thread_id", 0), "body": getattr(args, "body", "")}),
                       "request-send": ("request_send", {"draft_id": getattr(args, "draft_id", 0)}),
                       "cancel": ("cancel_draft", {"draft_id": getattr(args, "draft_id", 0)}),
                       "drafts": ("list_drafts", {"status": getattr(args, "status", "all")})}[args.cmd]
            r = run(name, a)
            if isinstance(r, dict) and isinstance(r.get("draft"), dict): r = dict(r, draft_id=r["draft"].get("draft_id"), status=r["draft"].get("status"))
            _out(args, r, lambda r: (f"draft {r['draft_id']} {r['status']}" if "draft_id" in r else json.dumps(r, indent=1, ensure_ascii=False)))
        return 0
    except CL.PhoneError as e:
        print(f"error: {e}", file=sys.stderr); return 1
    except KeyboardInterrupt:
        return 130
    finally:
        c.close()

def _relay_local(args, store):
    import os
    r = store.data.get("relay")
    if args.rcmd == "set":
        raw = open(os.path.expanduser(args.token_file), "rb").read()
        tok = (raw.decode("utf-16") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8-sig")).strip()  # PowerShell '>' writes UTF-16
        if tok.startswith("{"): tok = json.loads(tok)["token"]  # accept client.json directly
        fp = T.norm_fp(args.fingerprint)
        if len(fp) != 64 or any(ch not in "0123456789abcdef" for ch in fp): print("fingerprint must be the 64-hex SHA-256"); return 2
        store.data["relay"] = {"host": args.rhost, "port": args.port, "fingerprint": fp, "token": tok, "enabled": True}; store.save()
        print("relay saved. Next: on home Wi-Fi run `msms relay push` so the phone gets it."); return 0
    if args.rcmd == "clear": store.data.pop("relay", None); store.save(); print("relay removed"); return 0
    if not r: print("relay: not configured"); return 0 if args.rcmd == "status" else 2
    if args.rcmd in ("on", "off"): r["enabled"] = args.rcmd == "on"; store.save(); print("relay", args.rcmd); return 0
    st = {"host": r["host"], "port": r["port"], "fingerprint": r["fingerprint"], "enabled": r.get("enabled", True), "token": "set"}
    try:
        s = T.tls_pinned(r["host"], r["port"], r["fingerprint"], 6); s.sendall(b"GET /healthz HTTP/1.0\n")
        st["health"] = "ok" if b"200 OK" in s.recv(200) else "bad"; s.close()
    except Exception as e: st["health"] = f"unreachable: {e}"
    _out(args, st, lambda d: "\n".join(f"{k}: {v}" for k, v in d.items())); return 0
