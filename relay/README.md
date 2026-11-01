# msms-relay

Ciphertext-only rendezvous for Emit (formerly Matrix SMS) (phone <-> PC) when they are not on the same Wi-Fi. Python 3 stdlib only.

- TLS on :443 with a self-signed cert generated at install; clients pin its SHA-256 fingerprint (no domain, no CA).
- Shared relay token (stops open-relay abuse; not what protects messages: that's the end-to-end layer, docs/PROTOCOL.md).
- Rooms: room id = HMAC-SHA256(DH(s_pc, s_ph), "msms-room-v1"), so the relay can't link a room to a phone number.
- Limits: frame cap 32 MiB, 4 MB/s per room (64 MB burst), 30 conns/min/IP, 5 bad tokens -> 10 min IP ban, 64 rooms, 256 conns,
  waiting idle 330 s (phone PINGs every 4 min), spliced idle 15 min, PC waits 20 s for the phone.
- Nothing about payloads is ever logged. `GET /healthz` over TLS answers `ok` and nothing else.
- systemd: unprivileged `msms-relay` user, only CAP_NET_BIND_SERVICE, ProtectSystem=strict, NoNewPrivileges, syscall filter, etc.

## Install / upgrade (on the server)
    tar xzf msms-relay.tar.gz && cd msms-relay && sudo bash install.sh --public-host <your-server>

## Debug: prove the server only sees ciphertext (OFF by default)
    sudo systemctl edit msms-relay      # add:
    [Service]
    ExecStart=
    ExecStart=/usr/bin/python3 -I /opt/msms-relay/msms_relay.py --config /etc/msms-relay/relay.json --cert /etc/msms-relay/cert.pem --key /etc/msms-relay/key.pem --dump-dir /var/lib/msms-relay/dump
    sudo systemctl restart msms-relay
    # send a text containing a marker through the relay, then:
    sudo python3 tools/capture_check.py <(sudo cat /var/lib/msms-relay/dump/*.bin) --marker "MARKER-123" --number 5551234567
    sudo systemctl revert msms-relay && sudo systemctl restart msms-relay && sudo rm -rf /var/lib/msms-relay/dump
Dump records are `>` (PC->phone) or `<` (phone->PC) followed by the frame exactly as forwarded.

## Tests
    python -m pytest tests     (needs pytest; uses ../pc-companion/app for the client helpers)
