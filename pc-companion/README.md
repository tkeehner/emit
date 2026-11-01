# Emit (formerly Matrix SMS) — PC companion (phase 1: LAN)

This is a lean Windows companion for the Emit Android app (formerly Matrix SMS; internal names like `msms`, `MatrixSMS-PC` and `_matrixsms._tcp` are kept on purpose for compatibility). It is built for AI use and has three faces, all on one tool catalog:
- **MCP server** (stdio, plus streamable HTTP at `http://127.0.0.1:8765/mcp` while the UI is running)
- **CLI** `msms …` (plain text, or `--json`)
- **Local web UI** `http://127.0.0.1:8765/`: threads, conversation, search, draft composer and approval queue, in black and #00FF41 green

It talks to the phone directly over home Wi-Fi (found by mDNS `_matrixsms._tcp`, with a manual IP fallback), end-to-end encrypted with X25519 + ChaCha20-Poly1305 (see `docs/PROTOCOL.md`). Away from home it falls back to the AWS relay (`docs/RELAY.md`).

**Nothing is ever sent without the user approving it.** AI tools can read and create drafts. `request_send` only posts an APPROVE/DENY notification on the phone, and that APPROVE needs the phone unlocked. Approval is also possible from the PC web UI's queue, unless the phone setting "PC app may approve sends" is turned off. No MCP or CLI command can approve or send.

## Install (Windows)
1. Unzip `dist/MatrixSMS-PC-win64.zip` to, for example, `C:\Users\<you>\Apps\MatrixSMS-PC\`. It bundles Python 3.12, so nothing else needs installing.
2. Phone: install `matrix-sms-0.1.18-debug.apk` (`adb install -r …`).

## Pair (once)
1. Phone: Emit → tap **[⇄ PC]** at the bottom of the inbox → enable **link enabled** → **[ PAIR NEW PC ]**. The screen shows the phone's IP.
2. PC (PowerShell in the unzipped folder): `.\msms.bat pair --host <phone-ip>`. Leave out `--host` to use mDNS. Windows Firewall may ask once; allow it on Private networks.
3. The phone shows a 6-digit code. Type it on the PC. If the PC accepts it, tap **ALLOW** on the phone. If the PC says the code doesn't match, tap **deny**.
4. Check with `.\msms.bat status` and `.\msms.bat threads`.

## Use
- UI: double-click `MatrixSMS.bat`. It opens the browser at http://127.0.0.1:8765/.
- CLI: `msms threads`, `msms read <thread_id>`, `msms search "dinner"`, `msms contact jake`, `msms media <part_id> -o pic.jpg`, `msms draft <thread_id> "text"`, `msms drafts`, `msms request-send <draft_id>`, `msms cancel <id>`, `msms watch`, `msms tools` (prints the JSON schemas).
- If the phone isn't found automatically, add `--host 192.168.x.y` to any command.

## MCP config
Cursor (`%USERPROFILE%\.cursor\mcp.json`) and Claude Desktop (`%APPDATA%\Claude\claude_desktop_config.json`):
```json
{ "mcpServers": { "matrix-sms": {
    "command": "C:\\Users\\<you>\\Apps\\MatrixSMS-PC\\msms-mcp.bat",
    "args": [] } } }
```
HTTP clients can use `{ "url": "http://127.0.0.1:8765/mcp" }` while `MatrixSMS.bat` is running.
The tools are get_status, list_threads, read_thread, search_messages, get_contact, get_media, create_draft, list_drafts, request_send (human approval) and cancel_draft.

## Files
- Identity and paired phones are in `%APPDATA%\MatrixSMS-PC\identity.json`. The secret key is DPAPI-protected for the current Windows user. Set `MSMS_HOME` to use a different location.
- Messages are not cached on the PC; everything is read live from the phone.

## Dev / tests (Linux or Windows)
```
python -m venv .venv && .venv/bin/pip install cryptography zeroconf pytest jsonschema
.venv/bin/python -m pytest tests     # crypto, pairing, session, MCP schemas, e2e vs simulated phone
python -m msms sim                   # simulated phone on :47613 for trying the UI/MCP with no phone
```
`build_win.sh` rebuilds the Windows zip.

## Battery
The phone service is one idle `accept()` thread plus an NSD advert. It uses no wakelock, does no polling, and runs only while "link enabled" is on. A low-priority notification shows that it's running.

## Away from home (AWS relay)
    msms relay set --host your-server --port 443 --fingerprint <sha256 from install.sh> --token-file C:\path\client.json
    msms relay status          # health check; never prints the token
    msms relay push            # on home Wi-Fi: sends the relay settings to the phone over the encrypted link
LAN is always tried first; the relay only carries the same end-to-end encrypted frames.

## Phone app updates
    msms update push C:\Users\<you>\Documents\MatrixSMS\matrix-sms-0.1.21-debug.apk
or the [ phone update ] button in the UI. The phone checks SHA-256, signer and version, then waits for you to tap [ install ].

## Crypto self-test
    msms verify                # live handshake + forged-key and tampered-frame checks against the phone


## @bot commands (0.2.1)

On the phone, a message YOU type that starts with `@bot` is sent normally to the chat AND forwarded to this PC as a
`bot_command` (tag under the bubble: `[sent to Prime]`, or `[queued - PC offline]` with `[ cancel ]`). Messages other
people send containing @bot are never commands. Toggle: phone > `⇄ PC` > `@bot commands`.

The PC forwards each command to a webhook:

    msms bot set --url https://example/hook --key-file C:\path\key.txt            # Authorization: Bearer <key>
    msms bot set --url https://example/hook --key-file key.txt --header X-Api-Key --scheme none   # X-Api-Key: <key>
    msms bot status | msms bot test | msms bot log | msms bot clear

Body: `{source:"matrix-sms", type:"bot_command", from_self:true, cmd_id, thread_id, participants:[{number,name}],
instruction, context:[{sender,text,time,outgoing}], ts}`. The key is stored DPAPI-wrapped in %APPDATA%\MatrixSMS-PC\identity.json.
Retries: 2s, 5s, 15s, 30s, 1m, 2m, 5m; 400/401/403/404/422 stop immediately (fix config, see `msms bot log`).
After a 2xx the PC acks to the phone.

Something must hold the phone link: the UI (`MatrixSMS.bat`) does while open; for headless use run `msms-agent.bat`
(hidden `msms agent`). Only one process per PC forwards (lock on 127.0.0.1:47614).

Replying: `msms --json draft --thread <id> --text "..."` prints `draft_id`; then `msms --json request-send <draft_id>`
asks for approval on the phone. Nothing sends without your tap.
