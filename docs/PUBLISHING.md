# Publishing checklist
- When the GitHub repo is created, set its license field to **GPL-3.0** (SPDX `GPL-3.0-only` is what GitHub shows for key `gpl-3.0`; the project itself is GPL-3.0-or-later per README).
- Before the first push, rerun the identifier scan and gitleaks, and confirm git author name/email are neutral.
- Never commit keystore.properties, keystores, *.pem, client.json, relay.json, tokens, logs, or dumps.
