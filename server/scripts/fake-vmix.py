#!/usr/bin/env python3
"""fake-vmix.py — vMix Tally simulator (TCP port 8099)

ใช้ทดสอบ SWITCHY โดยไม่ต้องมี vMix จริง
ทำงานเหมือน vMix TCP API:
  - รับคำสั่ง "SUBSCRIBE TALLY"
  - ส่งบรรทัด "TALLY OK 0120..." ทุกครั้งที่สถานะเปลี่ยน

ตัวอย่าง:
  python fake-vmix.py                      # 8 inputs, สลับทุก 3 วินาที
  python fake-vmix.py --inputs 4 --cycle 2 # 4 inputs, สลับทุก 2 วินาที
  python fake-vmix.py --script "cam1:program,cam2:preview"   # ล็อกสถานะตายตัว
"""

from __future__ import annotations

import argparse
import socket
import threading
import time

STATE_CHAR = {"program": "1", "preview": "2", "safe": "0", "off": "0"}


class FakeVMix:
    def __init__(self, host: str, port: int, inputs: int, cycle: float,
                 script: list[tuple[str, str]] | None, quiet: bool = False) -> None:
        self.host = host
        self.port = port
        self.inputs = inputs
        self.cycle = cycle
        self.script = script
        self.quiet = quiet
        self.clients: list[socket.socket] = []
        self.lock = threading.Lock()
        self.states = ["0"] * inputs  # 0=off 1=program 2=preview

    def log(self, *a) -> None:
        if not self.quiet:
            print("[fake-vmix]", *a, flush=True)

    # ---- สถานะ ----
    def payload(self) -> str:
        return "".join(self.states)

    def set_states(self, states: list[str]) -> None:
        if states == self.states:
            return
        self.states = states
        self.broadcast()
        self.log("TALLY OK", self.payload())

    def broadcast(self) -> None:
        line = f"TALLY OK {self.payload()}\r\n".encode()
        with self.lock:
            dead = []
            for c in self.clients:
                try:
                    c.sendall(line)
                except OSError:
                    dead.append(c)
            for c in dead:
                self.clients.remove(c)

    # ---- ตัวสลับอัตโนมัติ ----
    def autoplay(self) -> None:
        idx = 0
        while True:
            time.sleep(self.cycle)
            states = ["0"] * self.inputs
            states[idx % self.inputs] = "1"                     # program
            states[(idx + 1) % self.inputs] = "2"               # preview
            idx += 1
            self.set_states(states)

    def scripted(self) -> None:
        seq = []
        for item in self.script or []:
            cam, st = item.split(":")
            seq.append((cam.strip().lower(), STATE_CHAR.get(st.strip().lower(), "0")))
        if not seq:
            return
        while True:
            time.sleep(self.cycle)
            states = ["0"] * self.inputs
            for cam, ch in seq:
                try:
                    num = int("".join(ch for ch in cam if ch.isdigit()))
                except ValueError:
                    continue
                if 1 <= num <= self.inputs:
                    states[num - 1] = ch
            self.set_states(states)

    # ---- เซิร์ฟเวอร์ ----
    def handle(self, conn: socket.socket, addr: tuple) -> None:
        self.log("client connected", addr)
        with conn:
            conn.sendall(b"VERSION 26.0.0.1\r\n")
            buf = b""
            try:
                while True:
                    data = conn.recv(1024)
                    if not data:
                        break
                    buf += data
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        cmd = line.decode(errors="replace").strip().upper()
                        if cmd.startswith("SUBSCRIBE TALLY"):
                            with self.lock:
                                self.clients.append(conn)
                            conn.sendall(f"TALLY OK {self.payload()}\r\n".encode())
                            self.log("subscribe tally:", addr)
                        elif cmd.startswith("PING"):
                            conn.sendall(b"PING OK\r\n")
                        elif cmd:
                            self.log("ignore:", cmd)
            except OSError:
                pass
        with self.lock:
            if conn in self.clients:
                self.clients.remove(conn)
        self.log("client gone", addr)

    def serve(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(8)
        self.log(f"listening on {self.host}:{self.port} ({self.inputs} inputs)")

        target = self.scripted if self.script else self.autoplay
        threading.Thread(target=target, daemon=True).start()
        try:
            while True:
                conn, addr = srv.accept()
                threading.Thread(target=self.handle, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt:
            self.log("bye")
        finally:
            srv.close()


def main() -> None:
    ap = argparse.ArgumentParser(description="vMix TCP 8099 tally simulator")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--inputs", type=int, default=8)
    ap.add_argument("--cycle", type=float, default=3.0, help="วินาทีต่อการสลับหนึ่งครั้ง")
    ap.add_argument("--script", default="", help='ล็อกสถานะ เช่น "cam1:program,cam2:preview"')
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    script = [s for s in args.script.split(",") if s.strip()] or None
    FakeVMix(args.host, args.port, args.inputs, args.cycle, script, args.quiet).serve()


if __name__ == "__main__":
    main()
