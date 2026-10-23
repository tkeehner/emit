#!/bin/bash
# Matrix SMS relay installer for Amazon Linux 2023 (idempotent). Run as root:  sudo bash install.sh --public-host <your-server-ip-or-hostname>
# Generates a self-signed cert + relay token once; re-running upgrades the code and keeps the existing cert/token.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PUBLIC_HOST=""; PORT=443
while [ $# -gt 0 ]; do case "$1" in --public-host) PUBLIC_HOST="$2"; shift 2;; --port) PORT="$2"; shift 2;; *) echo "unknown arg $1"; exit 2;; esac; done
[ -n "$PUBLIC_HOST" ] || { echo "usage: sudo bash install.sh --public-host <your-server-ip-or-hostname> [--port 443]"; exit 2; }
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
command -v python3 >/dev/null || dnf -y install python3
command -v openssl >/dev/null || dnf -y install openssl
python3 -c 'import sys; assert sys.version_info >= (3, 8)' || { echo "python3 >= 3.8 required"; exit 1; }

id msms-relay >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /nonexistent --shell /sbin/nologin msms-relay
install -d -m 0755 -o root -g root /opt/msms-relay
install -m 0644 -o root -g root "$HERE/msms_relay.py" /opt/msms-relay/msms_relay.py
install -d -m 0750 -o root -g msms-relay /etc/msms-relay

C=/etc/msms-relay
if [ ! -s $C/cert.pem ] || [ ! -s $C/key.pem ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj "/CN=msms-relay" \
    -keyout $C/key.pem -out $C/cert.pem 2>/dev/null
  echo "generated new self-signed certificate"
fi
chown root:msms-relay $C/key.pem $C/cert.pem; chmod 0640 $C/key.pem; chmod 0644 $C/cert.pem
if [ ! -s $C/token ]; then (umask 077; openssl rand -hex 32 > $C/token); echo "generated new relay token"; fi
chown root:root $C/token; chmod 0600 $C/token
TOKEN="$(cat $C/token)"
FP="$(openssl x509 -in $C/cert.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')"

# relay.json: read by the service (token inside; root:msms-relay 0640). Keep any local tuning on upgrade.
python3 - "$C/relay.json" "$TOKEN" <<'PY'
import json, os, sys
p, tok = sys.argv[1], sys.argv[2]
d = json.load(open(p)) if os.path.exists(p) else {}
d.update(token=tok); d.setdefault("max_rooms", 64); d.setdefault("max_conns", 256)
open(p, "w").write(json.dumps(d, indent=1) + "\n")
PY
chown root:msms-relay $C/relay.json; chmod 0640 $C/relay.json
# client.json: what the PC needs (root-only). Copy it with: sudo cat /etc/msms-relay/client.json
(umask 077; printf '{"host": "%s", "port": %s, "fingerprint": "%s", "token": "%s"}\n' "$PUBLIC_HOST" "$PORT" "$FP" "$TOKEN" > $C/client.json)
chown root:root $C/client.json; chmod 0600 $C/client.json

if [ "$PORT" != 443 ]; then sed -i "s#--config /etc#--port $PORT --config /etc#" "$HERE/msms-relay.service"; fi
install -m 0644 "$HERE/msms-relay.service" /etc/systemd/system/msms-relay.service
systemctl daemon-reload
systemctl enable msms-relay >/dev/null 2>&1
systemctl restart msms-relay
sleep 1
systemctl is-active --quiet msms-relay && STATE=running || STATE="NOT running (journalctl -u msms-relay)"
echo "----------------------------------------------"
echo "msms-relay: $STATE on :$PORT"
echo "certificate SHA-256 fingerprint: $FP"
echo "token + fingerprint for the PC: /etc/msms-relay/client.json (root only)"
echo "----------------------------------------------"
