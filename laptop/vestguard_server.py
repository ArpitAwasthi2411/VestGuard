#!/usr/bin/env python3
"""
VestGuard laptop server
=======================
  * Hosts the dashboard at        http://localhost:8000
  * Receives ESP32 data over UDP  (port 4210) and relays it live to the browser
  * Sends dashboard commands back to the ESP32 (port 4211)
  * Saves every sample to logs/ as a raw CSV backup (even if the browser crashes)
  * Hosts the caregiver phone app at  http://<laptop-ip>:8000/care

Run:
    python vestguard_server.py               # default
    python vestguard_server.py --port 8080   # different web port
    python vestguard_server.py --esp 10.42.0.57   # if auto-discovery is blocked
    python vestguard_server.py --no-log
"""
import argparse
import asyncio
import datetime as dt
import pathlib
import socket
import subprocess
import sys
import time

try:
    from aiohttp import web, WSMsgType
except ImportError:
    sys.exit("Missing dependency. Run:  pip install aiohttp")

from care import Care

HERE = pathlib.Path(__file__).resolve().parent
DASHBOARD = HERE / "vestguard_dashboard.html"
DATA_PORT = 4210          # ESP32 -> laptop
CMD_PORT = 4211           # laptop -> ESP32
BROADCAST = "255.255.255.255"

CSV_HEADER = ("host_time,seq,t_ms,s1_ax,s1_ay,s1_az,s1_gx,s1_gy,s1_gz,"
              "s2_ax,s2_ay,s2_az,s2_gx,s2_gy,s2_gz\n")


class Hub:
    def __init__(self, args):
        self.args = args
        self.esp = args.esp            # ESP32 IP (learned from its packets)
        self.fixed = bool(args.esp)
        self.last_rx = 0.0
        self.silent_warned = False
        self.browsers = set()
        self.transport = None
        self.d_lines = 0
        self.d_lines_window = 0
        self.packets = 0
        self.log = None
        if not args.no_log:
            (HERE / "logs").mkdir(exist_ok=True)
            name = HERE / "logs" / f"vestguard_raw_{dt.datetime.now():%Y%m%d_%H%M%S}.csv"
            self.log = open(name, "w", buffering=1 << 16)
            self.log.write(CSV_HEADER)
            self.log_path = name

    # ---------- ESP32 side ----------
    def on_udp(self, data: bytes, addr):
        ip = addr[0]
        if self.fixed and ip != self.esp:
            return
        new = ip != self.esp or not self.last_rx
        self.esp = ip
        self.last_rx = time.time()
        self.packets += 1
        if self.silent_warned:
            self.silent_warned = False
            self.to_browsers("# ESP32 is back")
        if new:
            print(f"\n  ✓ ESP32 found at {ip} — streaming\n", flush=True)
            self.send_esp("?")              # lets the ESP32 lock on to this laptop
        text = data.decode("utf-8", "replace")
        now = f"{time.time():.3f}"
        for line in text.splitlines():
            if line.startswith("D,"):
                self.d_lines += 1
                self.d_lines_window += 1
                if self.log:
                    self.log.write(now + line[1:] + "\n")
                p = line.split(",")
                if len(p) >= 15:
                    try:
                        self.care.on_sample(int(p[2]), [float(x) for x in p[3:15]])
                    except ValueError:
                        pass
            elif line.startswith("H,"):
                self.care.on_heartbeat(line.split(","))
            elif line.startswith("I,"):
                self.care.on_info(line.split(","))
        self.to_browsers(text)

    def send_esp(self, cmd: str):
        if not self.transport:
            return
        target = (self.esp, CMD_PORT) if self.esp else (BROADCAST, CMD_PORT)
        try:
            self.transport.sendto((cmd.strip() + "\n").encode(), target)
        except OSError as e:
            print(f"  ! could not send '{cmd}' to ESP32: {e}")

    # ---------- browser side ----------
    def to_browsers(self, text: str):
        for ws in list(self.browsers):
            if ws.closed:
                self.browsers.discard(ws)
                continue
            asyncio.ensure_future(self._send(ws, text))

    async def _send(self, ws, text):
        try:
            await ws.send_str(text)
        except Exception:
            self.browsers.discard(ws)


class UdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, hub):
        self.hub = hub

    def connection_made(self, transport):
        self.hub.transport = transport

    def datagram_received(self, data, addr):
        self.hub.on_udp(data, addr)

    def error_received(self, exc):
        pass


async def keepalive(hub: Hub):
    """Ping the ESP32 so it keeps sending to us; announce ourselves while it's missing."""
    last_status = 0.0
    while True:
        await asyncio.sleep(2)
        age = time.time() - hub.last_rx if hub.last_rx else None
        if hub.esp and age is not None and age < 8:
            hub.send_esp("P")
        else:
            hub.send_esp("HELLO")             # broadcast until an ESP32 shows up
        if age is not None and age > 5 and not hub.silent_warned:
            hub.silent_warned = True
            hub.to_browsers("# ESP32 silent for 5 s — check its power / WiFi")
            print("  ! ESP32 silent for 5 s", flush=True)
        if hub.log:
            hub.log.flush()
        now = time.time()
        if now - last_status >= 10:
            rate = hub.d_lines_window / (now - last_status) if last_status else 0
            hub.d_lines_window = 0
            last_status = now
            esp = f"ESP32 {hub.esp}" if hub.esp and age is not None and age < 5 else "waiting for ESP32…"
            print(f"  [{dt.datetime.now():%H:%M:%S}] {esp} | {rate:5.1f} samples/s | "
                  f"browsers: {len([b for b in hub.browsers if not b.closed])} | total {hub.d_lines}", flush=True)


async def index(request):
    if not DASHBOARD.exists():
        return web.Response(status=500, text=f"Missing {DASHBOARD.name} next to the server script.")
    return web.FileResponse(DASHBOARD, headers={"Cache-Control": "no-cache"})


async def care_index(request):
    return web.FileResponse(HERE / "care" / "index.html", headers={"Cache-Control": "no-cache"})


async def care_ws(request):
    care: Care = request.app["hub"].care
    ws = web.WebSocketResponse(heartbeat=10)
    await ws.prepare(request)
    print(f"  + caregiver app connected ({request.remote})", flush=True)
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                await care.handle(ws, msg.data)
    finally:
        care.disconnect(ws)
        print(f"  - caregiver app disconnected ({request.remote})", flush=True)
    return ws


async def ws_handler(request):
    hub: Hub = request.app["hub"]
    ws = web.WebSocketResponse(heartbeat=10)
    await ws.prepare(request)
    hub.browsers.add(ws)
    print(f"  + dashboard connected ({request.remote})", flush=True)
    live = hub.last_rx and time.time() - hub.last_rx < 5
    await ws.send_str("# laptop server: " + (f"ESP32 at {hub.esp}" if live else
                      "waiting for ESP32 on UDP 4210 — power it and check its WiFi name/password"))
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                for line in msg.data.splitlines():
                    if line.strip():
                        hub.send_esp(line)
    finally:
        hub.browsers.discard(ws)
        print(f"  - dashboard disconnected ({request.remote})", flush=True)
    return ws


def local_ips():
    ips = set()
    try:
        out = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=2).stdout
        ips.update(x for x in out.split() if ":" not in x)
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    ips.discard("127.0.0.1")
    return sorted(ips)


async def main():
    ap = argparse.ArgumentParser(description="VestGuard laptop server")
    ap.add_argument("--port", type=int, default=8000, help="web port for the dashboard (default 8000)")
    ap.add_argument("--esp", default="", help="ESP32 IP, only if auto-discovery doesn't work")
    ap.add_argument("--no-log", action="store_true", help="don't write raw CSV backups to logs/")
    args = ap.parse_args()

    hub = Hub(args)
    hub.care = Care(hub, HERE / "data")

    # UDP socket: receives ESP32 data, sends commands (broadcast allowed for discovery)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("0.0.0.0", DATA_PORT))
    except OSError as e:
        sys.exit(f"Can't open UDP port {DATA_PORT} ({e}). Is another copy of the server running?")
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(lambda: UdpProtocol(hub), sock=sock)

    app = web.Application()
    app["hub"] = hub
    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/care", care_index)
    app.router.add_get("/care/", care_index)
    app.router.add_get("/care-ws", care_ws)
    app.router.add_static("/care/", HERE / "care", show_index=False)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    try:
        await web.TCPSite(runner, "0.0.0.0", args.port).start()
    except OSError as e:
        sys.exit(f"Can't open web port {args.port} ({e}). Try:  python vestguard_server.py --port 8080")

    print("\n  VestGuard laptop server")
    print("  ───────────────────────")
    print(f"  Dashboard : http://localhost:{args.port}")
    for ip in local_ips():
        print(f"              http://{ip}:{args.port}   (other devices on the same WiFi)")
    print("  Caregiver app (open on phones on the same WiFi):")
    for ip in local_ips() or ["<laptop-ip>"]:
        print(f"              http://{ip}:{args.port}/care")
    print(f"  ESP32 data: UDP {DATA_PORT}  ·  commands: UDP {CMD_PORT}")
    if hub.log:
        print(f"  Raw backup: {hub.log_path.relative_to(HERE)}")
    print("  Waiting for the ESP32…  (Ctrl+C to stop)\n", flush=True)

    asyncio.ensure_future(keepalive(hub))
    asyncio.ensure_future(hub.care.push_loop())
    try:
        await asyncio.Future()
    finally:
        if hub.log:
            hub.log.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n  stopped.")
