"""Finds the TV on the home network when its address has changed (the router hands it a new one
now and then), so the daily refresh and deploy.sh still reach it.

The TV is known by its serial number (adb's ro.serialno), kept in tv-id.json. Its last known
address is in tv-serial.txt. When that address doesn't answer as this TV, every address on the
Mac's /24 with adb's port open is tried (checked in parallel, about two seconds); the one that
answers with the TV's serial number is saved to tv-serial.txt. (Its MAC is no help: on Wi-Fi the
TV uses a randomised one, and the Mac's ARP table soon forgets it.)

  python3 -I find_tv.py      prints the TV's address (exit 1 when it can't be found: off?)
"""
import concurrent.futures
import json
import os
import socket
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # tv-assets
SERIAL_FILE = os.path.join(ROOT, 'tv-serial.txt')
ID_FILE = os.path.join(ROOT, 'tv-id.json')
PORT = 5555


def _adb(*args, timeout=8):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)


def _serial_of(addr):
    """The serial number of the Android device adb reaches at addr, or None."""
    try:
        _adb('connect', addr)
        r = _adb('-s', addr, 'shell', 'getprop', 'ro.serialno')
        return (r.stdout.strip() or None) if r.returncode == 0 else None
    except (subprocess.SubprocessError, OSError):
        return None


def _local_ip():
    """The Mac's own address on the home network (no packet is sent)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]


def _open(ip):
    try:
        with socket.create_connection((ip, PORT), timeout=0.5):
            return ip
    except OSError:
        return None


def _sweep():
    """Every address on the Mac's /24 that has adb's port open."""
    base = _local_ip().rsplit('.', 1)[0]
    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        return [ip for ip in ex.map(_open, (f'{base}.{i}' for i in range(1, 255))) if ip]


def saved_address():
    try:
        with open(SERIAL_FILE) as f:
            return f.read().strip() or None
    except OSError:
        return None


def _save(addr, serial):
    with open(SERIAL_FILE, 'w') as f:
        f.write(addr + '\n')
    with open(ID_FILE, 'w') as f:
        json.dump({'serial': serial}, f, indent=1)


def find_tv(log=print):
    """The TV's adb address (ip:5555), finding it again if it moved; None when it can't be reached."""
    try:
        with open(ID_FILE) as f:
            ident = json.load(f)
    except (OSError, ValueError):
        ident = {}
    saved = saved_address()
    serial = ident.get('serial')
    if saved:
        got = _serial_of(saved)
        if got and (serial is None or got == serial):
            if serial is None:
                _save(saved, got)  # first time: remember who the TV is
            return saved
    if serial is None:
        return None  # never seen: nothing to look for
    tried = {saved}
    try:
        candidates = [f'{ip}:{PORT}' for ip in _sweep()]
    except OSError:
        candidates = []  # no network
    for addr in candidates:
        if addr in tried:
            continue
        tried.add(addr)
        if _serial_of(addr) == serial:
            _save(addr, serial)
            log(f'the TV moved: now at {addr}')
            return addr
    return None


if __name__ == '__main__':
    addr = find_tv(log=lambda *a: print(*a, file=sys.stderr))
    if not addr:
        print('TV not found (off, or not on this network)', file=sys.stderr)
        sys.exit(1)
    print(addr)
