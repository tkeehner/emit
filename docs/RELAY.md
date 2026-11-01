# AWS relay (phase 2, built)

The relay is used only when the phone isn't reachable on the LAN. It sees TLS-terminated bytes that are still the
end-to-end ChaCha20-Poly1305 frames of docs/PROTOCOL.md: it holds no keys and can't read or forge anything.

## Server (`matrix-sms-relay/`)
Python 3 stdlib, TLS :443 with a self-signed cert (clients pin SHA-256), shared token, systemd hardened unit. Wire format:
`MSMS-RELAY/1 <token> <room> <phone|pc>\n` -> `OK wait\n` (PING/PONG while waiting) -> `OK paired\n` -> both send `GO\n` ->
length-prefixed frames spliced verbatim (cap 32 MiB). `GET /healthz` -> `ok`. Errors: `ERR auth|proto|full|nopeer|idle`.

## Rendezvous
room = hex(HMAC-SHA256(key = DH(s_pc, s_ph), "msms-room-v1")). Only the two paired devices can compute it; it can't be linked to a number.

## PC
`RelayTransport` (msms/transport.py) is tried only after every LAN candidate (manual, mDNS, last IP) fails.
`msms relay set --host H --port 443 --fingerprint <sha256> --token-file client.json|token.txt`, `msms relay status`
(health check, never prints the token), `msms relay on|off|clear`, and `msms relay push` (sends host/port/fingerprint/token to
the phone inside the encrypted session; refused unless connected over the LAN; the phone also refuses it over the relay).

## Phone
`RelayConnector`: ConnectivityManager default-network callback + RelayPolicy. No location permission: "home" is an unmetered
Wi-Fi network whose fingerprint (gateway + subnet + DNS servers, hashed) saw a LAN PC session within 14 days. Connect when
relay enabled + configured + a PC is paired + network up + no LAN session + not home. One TLS socket per paired PC waits in
its room, PING every 4 min (relay idles out at 5.5 min), exponential backoff 2 s..5 min with jitter, no wakelocks. The spliced
socket goes to the same `LinkService.handle()` as LAN sockets (pairing is refused over the relay).

## Updates over the link
`msms update push <apk>` / UI button: update_begin {sha256,size} -> offset (resume); update_chunk {sha256,offset,data_b64}
(1 MiB) -> offset; update_finish -> phone checks SHA-256, package name, signing cert == installed signer, versionCode higher.
The phone shows a badge on the UPD button; nothing installs until the user taps [ install ] and confirms the system prompt.
