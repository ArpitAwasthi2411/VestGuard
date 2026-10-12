#!/usr/bin/env python3
"""VestGuard Health Desk - PC bridge (Python 3 standard library only).

Puts the health-centre dashboard (app/www/phc.html) on a PC. Browsers cannot hear UDP, so this
small server listens to the households' phones on the Wi-Fi (UDP 4212, the same lines family
phones already exchange) and streams them to the page; actions taken on the page (ASHA sent,
ambulance sent, ...) go back to that household's phones.

    python3 tools/phc_server.py            # then open the address it prints
    python3 tools/phc_server.py --port 8090

On the Wi-Fi: the PC must be on the same Wi-Fi / phone hotspot as the household phones.
Over the internet: turn on "Also receive alerts over the internet" in the dashboard settings and list
the families' codes; each family turns on "Alerts over the internet" in their app (relay: ntfy.sh).
On Windows, allow Python through the firewall (private networks) when asked.
"""
import argparse
import base64
import hashlib
import urllib.request
import json
import os
import queue
import random
import socket
import string
import sys
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

SYNC_PORT = 4212
WWW = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'www'))
DEVICE = 'phcpc' + ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))

state = {'name': 'Health centre', 'net': False, 'families': [], 'net_ok': False}
NTFY = os.environ.get('VG_NTFY', 'https://ntfy.sh/')
net_wake = threading.Event()
clients = []                 # one queue per open dashboard
clients_lock = threading.Lock()
recent = []                  # last lines, replayed to a dashboard that (re)connects
families = {}                # family code -> last heard (s)
peers = {}                   # sender ip -> last heard (s)


def topic(fam):
    """same topic name as the app (NetRelay.topic): vg + first 13 bytes of sha256, hex"""
    return 'vg' + hashlib.sha256(('vestguard-v1:' + fam.strip().upper()).encode()).hexdigest()[:26]


def net_publish(line):
    p = line.split(',', 3)
    if not state['net'] or len(p) < 4 or p[0] != 'CS' or not p[1]:
        return
    def go():
        try:
            req = urllib.request.Request(NTFY + topic(p[1]), data=line.encode('utf-8'), method='POST', headers={'Content-Type': 'text/plain; charset=utf-8'})
            urllib.request.urlopen(req, timeout=8).read()
        except Exception as e:
            print('internet publish failed:', e)
    threading.Thread(target=go, daemon=True).start()


def net_loop():
    """follow every enrolled household's internet topic; lines go to the dashboard like Wi-Fi lines"""
    last_time = 0
    while True:
        fams = sorted(set(state['families']) | set(families.keys())) if state['net'] else []
        if not fams:
            state['net_ok'] = False
            net_wake.wait(3); net_wake.clear()
            continue
        topics = ','.join(sorted(topic(f) for f in fams))
        since = str(last_time) if last_time and time.time() - last_time < 600 else '120s'
        try:
            with urllib.request.urlopen(f'{NTFY}{topics}/json?since={since}', timeout=100) as r:
                state['net_ok'] = True
                publish({'netOk': True})
                for raw in r:
                    if net_wake.is_set():           # family list changed: reconnect with the new topics
                        net_wake.clear()
                        break
                    try:
                        m = json.loads(raw.decode('utf-8'))
                    except ValueError:
                        continue
                    if m.get('event') != 'message':
                        continue
                    last_time = m.get('time', last_time)
                    line = str(m.get('message', '')).strip()
                    p = line.split(',', 3)
                    if len(p) == 4 and p[0] in ('CS', 'CG') and p[2] != DEVICE:
                        publish({'line': line, 'ts': int(time.time() * 1000)})
        except Exception as e:
            state['net_ok'] = False
            publish({'netOk': False})
            print('internet stream:', e)
            time.sleep(10)


def b64(obj):
    return base64.b64encode(json.dumps(obj, separators=(',', ':')).encode('utf-8')).decode('ascii')


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('10.255.255.255', 1))           # no packet is sent; picks the Wi-Fi interface
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return [ip for ip in ips if not ip.startswith('127.')]


def broadcast_targets():
    t = {'255.255.255.255'}
    for ip in local_ips():
        a = ip.split('.')
        t.add('.'.join(a[:3] + ['255']))         # phone hotspots are /24
    now = time.time()
    for ip, ts in list(peers.items()):
        if now - ts < 120:
            t.add(ip)
    return t


def publish(item):
    recent.append(item)
    del recent[:-200]
    with clients_lock:
        for q in clients:
            q.put(item)


def udp_loop(sock):
    mine = set(local_ips())
    while True:
        try:
            data, (ip, _) = sock.recvfrom(8192)
        except OSError:
            continue
        for line in data.decode('utf-8', 'replace').split('\n'):
            line = line.strip()
            p = line.split(',', 3)
            if len(p) < 4 or p[0] not in ('CG', 'CS') or p[2] == DEVICE or not p[1]:
                continue
            if ip not in mine:
                peers[ip] = time.time()
            families[p[1]] = time.time()
            publish({'line': line, 'ts': int(time.time() * 1000)})


def send_line(sock, line):
    b = (line + '\n').encode('utf-8')
    for t in broadcast_targets():
        try:
            sock.sendto(b, (t, SYNC_PORT))
        except OSError:
            pass


def presence_loop(sock):
    """Say hello to every household heard recently, so the family app shows the health centre as connected."""
    while True:
        now = time.time()
        body = b64({'name': state['name'], 'role': 'phc', 'kind': 'phc', 'vest': False})
        for fam, ts in list(families.items()):
            if now - ts > 60:
                families.pop(fam, None)
                continue
            send_line(sock, f'CG,{fam},{DEVICE},{body}')
        time.sleep(3)


class Handler(SimpleHTTPRequestHandler):
    sock = None

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WWW, **kw)

    def log_message(self, fmt, *args):
        pass

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def do_GET(self):
        if self.path in ('/', '/index.html'):
            self.send_response(302)
            self.send_header('Location', '/phc.html')
            self.end_headers()
            return
        if self.path.startswith('/events'):
            return self.events()
        return super().do_GET()

    def events(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()
        q = queue.Queue()
        with clients_lock:
            clients.append(q)
        try:
            self.wfile.write(('data: ' + json.dumps({'hello': 1, 'netOk': state['net_ok']}) + '\n\n').encode())
            for item in list(recent):
                self.wfile.write(('data: ' + json.dumps(item) + '\n\n').encode())
            self.wfile.flush()
            while True:
                try:
                    item = q.get(timeout=15)
                    self.wfile.write(('data: ' + json.dumps(item) + '\n\n').encode())
                except queue.Empty:
                    self.wfile.write(b': ping\n\n')
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with clients_lock:
                if q in clients:
                    clients.remove(q)

    def read_json(self):
        n = int(self.headers.get('Content-Length', '0') or 0)
        try:
            return json.loads(self.rfile.read(n) or b'{}')
        except ValueError:
            return {}

    def ok(self):
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        if self.path == '/send':
            m = self.read_json()
            fam = str(m.get('family', '')).strip().upper()
            body = m.get('body') or {}
            if fam and isinstance(body, dict):
                line = f'CS,{fam},{DEVICE},{b64(body)}'
                send_line(self.sock, line)
                net_publish(line)
            return self.ok()
        if self.path == '/config':
            m = self.read_json()
            if m.get('name'):
                state['name'] = str(m['name'])[:60]
            if 'net' in m:
                was = (state['net'], tuple(state['families']))
                state['net'] = bool(m['net'])
                state['families'] = [str(f).strip().upper() for f in (m.get('families') or []) if str(f).strip()][:30]
                if was != (state['net'], tuple(state['families'])):
                    net_wake.set()
            return self.ok()
        self.send_response(404)
        self.end_headers()


def main():
    ap = argparse.ArgumentParser(description='VestGuard Health Desk bridge')
    ap.add_argument('--port', type=int, default=8090)
    ap.add_argument('--www', help='folder that contains phc.html')
    args = ap.parse_args()
    global WWW
    here = os.path.dirname(os.path.abspath(__file__))
    for d in [args.www, os.path.join(here, '..', 'app', 'www'), here, os.getcwd()]:
        if d and os.path.exists(os.path.join(d, 'phc.html')):
            WWW = os.path.normpath(d)
            break
    else:
        sys.exit('phc.html not found. Put phc.html in the same folder as this script, or pass --www <folder>.')

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, 'SO_REUSEPORT'):
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except OSError:
            pass
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(('', SYNC_PORT))
    except OSError as e:
        sys.exit(f'Cannot listen on UDP {SYNC_PORT}: {e}')
    Handler.sock = sock
    threading.Thread(target=udp_loop, args=(sock,), daemon=True).start()
    threading.Thread(target=presence_loop, args=(sock,), daemon=True).start()
    threading.Thread(target=net_loop, daemon=True).start()

    httpd = ThreadingHTTPServer(('0.0.0.0', args.port), Handler)
    httpd.daemon_threads = True
    print('VestGuard Health Desk')
    print(f'  open  http://localhost:{args.port}/phc.html')
    for ip in local_ips():
        print(f'  or    http://{ip}:{args.port}/phc.html   (another PC / tablet on this Wi-Fi)')
    print(f'  listening for household phones on UDP {SYNC_PORT}. Ctrl+C to stop.')
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
