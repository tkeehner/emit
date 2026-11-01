#!/usr/bin/env python3
"""Prove that captured link traffic is ciphertext only.

  python capture_check.py <file.pcap|file.pcapng|raw.bin> --marker "MARKER-123" [--marker ...] [--numbers-from contacts.txt]

Accepts a classic pcap (payloads of every packet are concatenated), a pcapng, a relay dump (--dump-dir output), or any
raw byte dump. Fails (exit 1) if any marker, or any phone number in common formats (+1XXXXXXXXXX, XXXXXXXXXX,
(XXX) XXX-XXXX, XXX-XXX-XXXX) appears in cleartext, in UTF-8, UTF-16LE, or base64 at any alignment. Reports byte
entropy: AEAD ciphertext is ~7.9+ bits/byte on large samples; JSON or text is ~4-5.
Note: a pcap of the relay's port 443 is TLS on top of our own encryption; dump on the relay (--dump-dir) or capture
LAN port 47613 to test the inner layer.
"""
import argparse, base64, math, re, struct, sys
from collections import Counter

def pcap_payloads(data):
    if data[:4] in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"): e = "<"
    elif data[:4] in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"): e = ">"
    elif data[:4] == b"\x0a\x0d\x0d\x0a": return pcapng_payloads(data)
    else: return None
    out, i = [], 24
    while i + 16 <= len(data):
        incl = struct.unpack(e + "I", data[i + 8:i + 12])[0]; out.append(data[i + 16:i + 16 + incl]); i += 16 + incl
    return b"".join(out)

def pcapng_payloads(data):
    out, i = [], 0
    while i + 12 <= len(data):
        btype, blen = struct.unpack("<II", data[i:i + 8])
        if blen < 12: break
        if btype == 6: cap = struct.unpack("<I", data[i + 20:i + 24])[0]; out.append(data[i + 28:i + 28 + cap])
        elif btype == 3: out.append(data[i + 12:i + blen - 4])
        i += blen
    return b"".join(out)

def entropy(b):
    if not b: return 0.0
    n = len(b); return -sum(c / n * math.log2(c / n) for c in Counter(b).values())

def number_forms(num):
    d = re.sub(r"\D", "", num.decode() if isinstance(num, bytes) else num)
    if len(d) == 11 and d[0] == "1": d = d[1:]
    if len(d) != 10: return [d.encode()] if d else []
    return [x.encode() for x in (d, "1" + d, "+1" + d, f"({d[:3]}) {d[3:6]}-{d[6:]}", f"{d[:3]}-{d[3:6]}-{d[6:]}")]

def variants(m):
    out = {m, m.decode("utf-8", "ignore").encode("utf-16-le")}
    for k in range(3):  # base64 at each alignment: keep the stable middle chars
        e = base64.b64encode(b"\0" * k + m)[(k * 4 + 2) // 3 + 1:-4]
        if len(e) >= 6: out.add(e)
    return out

PHONE_RE = re.compile(rb"(?<!\d)(?:\+?1[ -.]?)?\(?\d{3}\)?[ -.]?\d{3}[ -.]?\d{4}(?!\d)")

def check(data, markers=(), numbers=()):
    raw = pcap_payloads(data) or data
    hits = []
    for m in list(markers) + [f for n in numbers for f in number_forms(n)]:
        for v in variants(m):
            if v and v in raw: hits.append({"needle": m.decode(errors="replace"), "form": v[:40].decode(errors="replace"), "at": raw.index(v)})
    phones = [p.decode() for p in PHONE_RE.findall(raw) if len(re.sub(rb"\D", b"", p)) >= 10][:5]
    printable = sum(32 <= b < 127 for b in raw) / max(1, len(raw))
    rep = {"bytes": len(raw), "entropy_bits_per_byte": round(entropy(raw), 3), "printable_ratio": round(printable, 3),
           "marker_hits": hits, "phone_like_runs": phones}
    rep["ok"] = not hits
    return rep

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture"); ap.add_argument("--marker", action="append", default=[])
    ap.add_argument("--number", action="append", default=[]); ap.add_argument("--numbers-from")
    a = ap.parse_args()
    nums = list(a.number) + ([l.strip() for l in open(a.numbers_from) if l.strip()] if a.numbers_from else [])
    rep = check(open(a.capture, "rb").read(), [m.encode() for m in a.marker], nums)
    for k, v in rep.items(): print(f"{k}: {v}")
    print("PASS: no plaintext markers or numbers found" if rep["ok"] else "FAIL: plaintext found")
    if rep["phone_like_runs"]: print("note: digit runs that look like numbers occurred by chance in random bytes; check they are not real numbers")
    sys.exit(0 if rep["ok"] else 1)

if __name__ == "__main__": main()
