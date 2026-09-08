#!/usr/bin/env python3
"""Small development-only viewer for BootOptim's structured JSONL startup trace.

The game never imports this file. It is intentionally standard-library-only so it can be launched
beside Prism/NeoForge on a development machine without changing the modpack or benchmark process.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import tkinter as tk
from tkinter import ttk


class TraceWindow:
    def __init__(self, root: tk.Tk, path: pathlib.Path, interval_ms: int) -> None:
        self.root = root
        self.path = path
        self.interval_ms = interval_ms
        self.offset = 0
        self.rows = 0
        self.bad_lines = 0
        self.seen_sequences: set[tuple[str, int]] = set()

        root.title("BootOptim startup trace")
        root.geometry("1100x620")

        header = ttk.Frame(root, padding=8)
        header.pack(fill=tk.X)
        self.status = ttk.Label(header, text=f"Waiting for {path}")
        self.status.pack(side=tk.LEFT)
        ttk.Button(header, text="Clear", command=self.clear).pack(side=tk.RIGHT)

        columns = ("uptime", "kind", "phase", "task", "thread", "detail")
        self.table = ttk.Treeview(root, columns=columns, show="headings")
        headings = {
            "uptime": "uptime (ms)",
            "kind": "kind",
            "phase": "phase",
            "task": "task",
            "thread": "thread",
            "detail": "detail",
        }
        widths = {"uptime": 115, "kind": 130, "phase": 180, "task": 150, "thread": 160, "detail": 340}
        for column in columns:
            self.table.heading(column, text=headings[column])
            self.table.column(column, width=widths[column], anchor=tk.W)
        self.table.tag_configure("error", foreground="#b00020")
        self.table.tag_configure("barrier", foreground="#805000")
        self.table.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        scrollbar = ttk.Scrollbar(root, orient=tk.VERTICAL, command=self.table.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.table.configure(yscrollcommand=scrollbar.set)
        self.poll()

    def clear(self) -> None:
        for item in self.table.get_children():
            self.table.delete(item)
        self.offset = 0
        self.rows = 0
        self.bad_lines = 0
        self.seen_sequences.clear()
        self.update_status()

    def poll(self) -> None:
        try:
            size = self.path.stat().st_size
            if size < self.offset:
                # The bootstrap report/trace was replaced for a new process.
                self.clear()
            if size > self.offset:
                with self.path.open("r", encoding="utf-8", errors="replace") as stream:
                    stream.seek(self.offset)
                    chunk = stream.read()
                    self.offset = stream.tell()
                for line in chunk.splitlines():
                    self.add_line(line)
        except FileNotFoundError:
            pass
        except OSError:
            self.bad_lines += 1
        self.update_status()
        self.root.after(self.interval_ms, self.poll)

    def add_line(self, line: str) -> None:
        if not line.strip():
            return
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            self.bad_lines += 1
            return

        sequence = event.get("sequence")
        key = (str(event.get("thread_id", "?")), int(sequence)) if isinstance(sequence, int) else None
        if key is not None and key in self.seen_sequences:
            return
        if key is not None:
            self.seen_sequences.add(key)

        uptime_ns = event.get("uptime_ns")
        uptime_ms = "?" if not isinstance(uptime_ns, (int, float)) else f"{uptime_ns / 1_000_000:,.1f}"
        kind = str(event.get("kind", "?"))
        phase = str(event.get("phase", ""))
        task = str(event.get("task", ""))
        thread = str(event.get("thread", ""))
        detail = str(event.get("detail", ""))
        tag = "error" if kind in {"error", "trace_drop"} else "barrier" if "barrier" in kind else ""
        self.table.insert("", tk.END, values=(uptime_ms, kind, phase, task, thread, detail), tags=(tag,))
        self.rows += 1
        self.table.yview_moveto(1.0)

    def update_status(self) -> None:
        self.status.configure(text=f"{self.path}  |  events={self.rows}  malformed={self.bad_lines}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=pathlib.Path, default=pathlib.Path("logs/bootoptim-trace.jsonl"))
    parser.add_argument("--interval-ms", type=int, default=250)
    args = parser.parse_args()
    root = tk.Tk()
    TraceWindow(root, args.trace, max(50, args.interval_ms))
    root.mainloop()


if __name__ == "__main__":
    main()
