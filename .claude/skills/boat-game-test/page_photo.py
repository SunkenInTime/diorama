#!/usr/bin/env python3
"""Photographs a web map page in Chrome once the page says its pictures are in.

usage: page_photo.py <address> <out.png> [seconds]

The game tests call it (ClientWebMapTest.photograph). Chrome's own --screenshot with
--virtual-time-budget takes the picture when its made-up clock runs out, and that clock doesn't
wait for a picture being decoded: on a busy VM the map came out blank now and then. This runs
Chrome on the real clock, asks the page through the DevTools protocol whether every tile in view
has loaded (window.__fill) or it shows its "no map" line, and photographs it then; after
[seconds] (30) it photographs whatever is there. Exits 3 if the machine has no Chrome.
"""
import base64
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request

DONE = """(() => {
  const loaded = typeof map !== "undefined" && !!map;
  const fill = window.__fill ? window.__fill() : null;
  const empty = document.getElementById("empty");
  const none = !!empty && getComputedStyle(empty).display !== "none";
  return loaded && (none || (!!fill && fill.wanted > 0 && fill.loaded >= fill.wanted));
})()"""


class DevTools:
    """One page's DevTools connection: a WebSocket client small enough to need no library."""

    def __init__(self, url):
        rest = url.split('://', 1)[1]
        host, path = rest.split('/', 1)
        name, port = host.split(':')
        self.sock = socket.create_connection((name, int(port)), timeout=30)
        self.sock.sendall((f'GET /{path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                           'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
        self.buf = b''
        while b'\r\n\r\n' not in self.buf:
            self.buf += self.sock.recv(4096)
        self.buf = self.buf.split(b'\r\n\r\n', 1)[1]
        self.next = 0

    def need(self, n):
        while len(self.buf) < n:
            more = self.sock.recv(65536)
            if not more:
                raise OSError('Chrome closed the connection')
            self.buf += more

    def frame(self):
        self.need(2)
        last, size, start = self.buf[0] & 0x80, self.buf[1] & 0x7F, 2
        if size == 126:
            self.need(4)
            size, start = struct.unpack('>H', self.buf[2:4])[0], 4
        elif size == 127:
            self.need(10)
            size, start = struct.unpack('>Q', self.buf[2:10])[0], 10
        self.need(start + size)
        data, self.buf = self.buf[start:start + size], self.buf[start + size:]
        return last, data

    def call(self, method, **params):
        self.next += 1
        data = json.dumps({'id': self.next, 'method': method, 'params': params}).encode()
        mask = os.urandom(4)
        head = bytes([0x81]) + (bytes([0x80 | len(data)]) if len(data) < 126 else bytes([0x80 | 126]) + struct.pack('>H', len(data)))
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
        while True:
            message = b''
            while True:
                last, data = self.frame()
                message += data
                if last:
                    break
            answer = json.loads(message)
            if answer.get('id') == self.next:
                return answer.get('result', {})


def main():
    address, out = sys.argv[1], sys.argv[2]
    seconds = float(sys.argv[3]) if len(sys.argv) > 3 else 30
    chrome = next((path for path in map(shutil.which, ('chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable', '/snap/bin/chromium')) if path), None)
    if chrome is None:
        return 3
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    # A browser that has never seen the page, so nothing shown comes from an earlier visit.
    profile = tempfile.mkdtemp(prefix='page-photo-')
    browser = subprocess.Popen([chrome, '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--window-size=1280,800',
                                f'--user-data-dir={profile}', f'--remote-debugging-port={port}', address],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.time() + seconds
        page = None
        while page is None and time.time() < deadline + 30:
            try:
                tabs = json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/json', timeout=5))
                page = next((tab for tab in tabs if tab.get('type') == 'page'), None)
            except OSError:
                pass
            if page is None:
                time.sleep(0.2)
        if page is None:
            print('Chrome never opened the page', file=sys.stderr)
            return 1
        tools = DevTools(page['webSocketDebuggerUrl'])
        done = False
        while not done and time.time() < deadline:
            done = tools.call('Runtime.evaluate', expression=DONE, returnByValue=True).get('result', {}).get('value') is True
            if not done:
                time.sleep(0.25)
        # The page paints on the next frame after its last picture loads.
        time.sleep(0.5)
        with open(out, 'wb') as file:
            file.write(base64.b64decode(tools.call('Page.captureScreenshot')['data']))
        print('photographed once the page had its pictures' if done else f'photographed after {seconds:.0f} s, the page still loading')
        return 0
    finally:
        browser.terminate()
        try:
            browser.wait(10)
        except subprocess.TimeoutExpired:
            browser.kill()
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
