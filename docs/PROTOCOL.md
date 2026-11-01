# Emit (formerly Matrix SMS) link protocol v1

Transport: any reliable byte stream (LAN TCP to phone port 47613, or the AWS relay splice, docs/RELAY.md). Frames: 4-byte big-endian length + payload (max 32 MiB).

## Keys
- Each device has a long-term X25519 identity key. Phone: wrapped with an Android Keystore AES-GCM key. PC: DPAPI (CurrentUser).
- Pairing stores the peer's public key. Revoking on the phone deletes the PC's key, and the next request fails with `revoked`.

## Pairing (numeric comparison with commitment, plaintext JSON frames)
1. PC → `pair1 {pc_id, name, pub}`
2. Phone → `pair2 {phone_id, name, pub, commit = SHA256("msms-commit-v1"‖pub_ph‖pub_pc‖Nb)}`
3. PC → `pair3 {na}` (16 random bytes)
4. Phone → `pair4 {nb}`. The PC checks the commitment.
5. Both compute SAS = SHA256("msms-sas-v1"‖pub_pc‖pub_ph‖Na‖Nb)[0..4] (big-endian) mod 10^6. The phone shows it and the user types it on the PC, which rejects a mismatch.
6. PC → `pair5 {mac = HMAC(ck,"pc-ok")}`, with ck = HKDF(DH(static), salt=SHA256("msms-pair-v1"‖pub_pc‖pub_ph‖Na‖Nb), "msms-pair-confirm").
7. The phone shows ALLOW / DENY. Allow sends `pair6 {mac = HMAC(ck,"phone-ok")}`.

Because of the commitment, a man-in-the-middle cannot choose keys that make both legs show the same code (1 in 10^6 chance). The phone only accepts pairing while the user has "PAIR NEW PC" open: 3 minutes, and it closes after 3 failures.

## Session
1. PC → `hello {v, pc_id, e (ephemeral pub), n}`
2. Phone → `hello_ok {v, phone_id, e, n}`, or `err` for an unknown PC.
3. th = SHA256("msms-hs-v1"‖len‖hello‖len‖hello_ok), using the exact bytes that were sent.
4. keys = HKDF-SHA256(ikm = DH(e_pc,e_ph)‖DH(s_pc,s_ph), salt = th, info = "msms-session-v1", 64): [0:32] for PC→phone, [32:64] for phone→PC.
5. Every later frame is ChaCha20-Poly1305 with nonce = 0x00000000‖counter_be64, and a separate strictly increasing counter in each direction. A replayed, reordered or altered frame breaks the connection.
6. The PC sends `{"t":"auth","th":b64(th)}`; the phone checks th (constant time) and answers `{"t":"auth_ok"}` (key confirmation both ways). Both sides reject `v` != 1 (no downgrade). Counters never wrap (2^63 cap). The static-static DH is what authenticates both sides; the ephemeral DH gives forward secrecy.

## RPC (inside the channel)
The request is `{"id":n,"m":method,"p":{...}}`. The reply is `{"id":n,"r":result}` or `{"id":n,"e":{"code","msg"}}`. Push messages are `{"ev":"messages_changed"|"draft","d":{...}}`.

| method | params | notes |
|---|---|---|
| status | – | phone_id, app_version, approvals_from_pc, drafts_awaiting |
| list_threads | limit, offset | newest first |
| read_thread | thread_id, limit, before_ms | page, has_more, oldest_date |
| search | query, limit | SMS bodies and MMS text |
| contacts | query | name and number |
| get_media | part_id | base64, 20 MB cap |
| create_draft | thread_id, body | recipients always come from the thread |
| list_drafts | thread_id?, status? | statuses: pending, awaiting, sent, rejected, cancelled, failed |
| request_send | draft_id | posts the phone notification (APPROVE needs the phone unlocked) |
| cancel_draft | draft_id | |
| approve_send | draft_id | **PC UI only** (not available in MCP or the CLI); refused unless the phone toggle "PC app may approve sends" is on |
| relay_config | host, port, fingerprint, token, enabled | LAN session only |
| update_begin / update_chunk / update_finish | sha256, size / offset, data_b64 / – | APK push; install needs a tap on the phone |
