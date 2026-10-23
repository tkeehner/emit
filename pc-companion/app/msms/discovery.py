"""mDNS (_matrixsms._tcp) discovery. Optional: falls back to manual IP if zeroconf is missing."""
import time
SERVICE = "_matrixsms._tcp.local."

def browse(seconds=3.0):
    """Returns {phone_id: "ip:port"} for phones advertising on the LAN, plus key '*' entries for unnamed ones."""
    try:
        from zeroconf import Zeroconf, ServiceBrowser
    except ImportError:
        return {}
    found = {}
    class L:
        def add_service(self, zc, t, name):
            info = zc.get_service_info(t, name, 1500)
            if not info: return
            addrs = info.parsed_addresses()
            ip4 = [a for a in addrs if ":" not in a] or addrs
            if not ip4: return
            props = {k.decode(): (v or b"").decode() for k, v in (info.properties or {}).items()}
            found[props.get("id") or name] = f"{ip4[0]}:{info.port}"
        def update_service(self, *a): pass
        def remove_service(self, *a): pass
    zc = Zeroconf()
    try:
        ServiceBrowser(zc, SERVICE, L()); time.sleep(seconds)
    finally:
        zc.close()
    return found
