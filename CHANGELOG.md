# Changelog

## 0.1.50
- New Holiday theme: candle-lit night, drifting spooky side icons, and a stable shaded holiday character for every contact (`AvatarStyle.HOLIDAY`). Turned on manually in settings > theme.
- Two-way theme sync with the Beacon launcher. Each app defines a `signature|knownSigner` permission that only the other app's signing cert may hold, and theme changes go out as explicit broadcasts.
## 0.1.49
- New ODST theme: rainy night city, amber and steel comms panels with callsign headers, hex callsign badges, VISR outlines, armor-plate keyboard, and a squelch on send.
## 0.1.48
- The assistant can stage a text to a number with no existing thread (`thread_for`). The number is validated (no USSD/MMI characters), and the draft still waits for your APPROVE tap.
## 0.1.47
- Contact names in the inbox, chat header and message labels use each chat's chosen color.
## 0.1.46
- New Neon City theme: neon yellow and cyan, angled panels, Oxanium font (OFL), and a per-contact glyph badge derived from an on-device random secret. New glitch and key-glow switches. Matrix stays the default.
## 0.1.45
- Fix: tapping inside the message box moves the cursor there.

## 0.1.44 (first public release)
- Full-width, word-wrapping message box that grows to 5 lines; buttons moved to a row below with a character counter.
## 0.1.43
- Voice-to-text: mic button in every chat (including the assistant chat); text lands in the box, never auto-sends.
## Earlier (0.1.0 – 0.1.42)
- SMS/MMS client with terminal-style UI, custom keyboard and dictionary, scheduling, bulk select, link previews.
- Encrypted PC link (X25519 + ChaCha20-Poly1305, SAS pairing), approval-gated drafts, signed over-the-link app updates.
- Optional TLS 1.3 relay with pinned self-signed certificate for use away from home Wi-Fi.

Public history starts here; earlier private history is not included.
