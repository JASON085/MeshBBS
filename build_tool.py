#!/usr/bin/env python3
"""
Meshtastic BBS 打包工具
========================
一鍵將 Server / Client 打包成單一 EXE 檔案。
"""

import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import subprocess
import threading
import sys
import os
import time
from pathlib import Path

BASE = Path(__file__).parent

# ── 顏色主題 ───────────────────────────────────────────────────
BG       = "#1a1a2e"
BG2      = "#16213e"
BG3      = "#0f3460"
FG       = "#e0e0e0"
FG_DIM   = "#888888"
CYAN     = "#33ffff"
GREEN    = "#33ff33"
YELLOW   = "#ffff33"
RED      = "#ff4444"
ACCENT   = "#0f3460"


class BuildTool:
    def __init__(self, root: tk.Tk):
        self.root = root
        self._building = False
        self._start_time = 0.0

        root.title("Meshtastic BBS 打包工具")
        root.geometry("760x560")
        root.resizable(True, True)
        root.configure(bg=BG)
        root.minsize(620, 460)

        self._build_ui()
        self._check_pyinstaller()

    # ── UI ────────────────────────────────────────────────────
    def _build_ui(self):
        # 標題列
        title_bar = tk.Frame(self.root, bg=BG3, height=44)
        title_bar.pack(fill="x")
        tk.Label(
            title_bar, text="◎  Meshtastic BBS  打包工具",
            font=("Consolas", 14, "bold"),
            bg=BG3, fg=CYAN, pady=10,
        ).pack(side="left", padx=16)

        # 主體
        body = tk.Frame(self.root, bg=BG, padx=14, pady=10)
        body.pack(fill="both", expand=True)

        # ── 打包按鈕區 ──────────────────────────────────────
        btn_frame = tk.Frame(body, bg=BG)
        btn_frame.pack(fill="x", pady=(0, 10))

        self._btn_server = self._make_btn(
            btn_frame,
            label="打包 伺服器 EXE",
            sub="bbs_gui.py  →  MeshtasticBBS_Server.exe",
            color=CYAN,
            cmd=lambda: self._start_build("server"),
        )
        self._btn_server.pack(side="left", fill="both", expand=True, padx=(0, 6))

        self._btn_client = self._make_btn(
            btn_frame,
            label="打包 客戶端 EXE",
            sub="mesh_bbs_client.py  →  MeshtasticBBS_Client.exe",
            color=GREEN,
            cmd=lambda: self._start_build("client"),
        )
        self._btn_client.pack(side="left", fill="both", expand=True, padx=(6, 0))

        # ── 狀態列 ──────────────────────────────────────────
        status_frame = tk.Frame(body, bg=BG2, pady=6, padx=10)
        status_frame.pack(fill="x", pady=(0, 8))
        status_frame.columnconfigure(1, weight=1)

        tk.Label(status_frame, text="狀態：", bg=BG2, fg=FG_DIM,
                 font=("Consolas", 11)).grid(row=0, column=0, sticky="w")

        self._status_var = tk.StringVar(value="就緒")
        tk.Label(status_frame, textvariable=self._status_var,
                 bg=BG2, fg=YELLOW, font=("Consolas", 11, "bold"),
                 anchor="w").grid(row=0, column=1, sticky="w")

        self._elapsed_var = tk.StringVar(value="")
        tk.Label(status_frame, textvariable=self._elapsed_var,
                 bg=BG2, fg=FG_DIM, font=("Consolas", 10),
                 anchor="e").grid(row=0, column=2, sticky="e", padx=(10, 0))

        # 進度條
        self._pb = ttk.Progressbar(body, mode="indeterminate", length=200)
        self._pb.pack(fill="x", pady=(0, 8))
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TProgressbar", troughcolor=BG2, background=CYAN,
                        thickness=6)

        # ── 記錄區 ──────────────────────────────────────────
        log_hdr = tk.Frame(body, bg=BG)
        log_hdr.pack(fill="x")
        tk.Label(log_hdr, text="建置記錄", bg=BG, fg=FG_DIM,
                 font=("Consolas", 10)).pack(side="left")

        self._log = scrolledtext.ScrolledText(
            body,
            bg="#0a0a14", fg=FG,
            font=("Consolas", 10),
            insertbackground=CYAN,
            relief="flat", bd=0,
            state="disabled",
            height=16,
        )
        self._log.pack(fill="both", expand=True, pady=(4, 8))

        # 設定 log 顏色 tag
        self._log.tag_config("info",    foreground=FG)
        self._log.tag_config("ok",      foreground=GREEN)
        self._log.tag_config("warn",    foreground=YELLOW)
        self._log.tag_config("error",   foreground=RED)
        self._log.tag_config("header",  foreground=CYAN)
        self._log.tag_config("dim",     foreground=FG_DIM)

        # ── 底部按鈕 ────────────────────────────────────────
        bot = tk.Frame(body, bg=BG)
        bot.pack(fill="x")

        self._make_small_btn(bot, "開啟 dist/ 資料夾", self._open_dist).pack(side="left")
        self._make_small_btn(bot, "清除記錄", self._clear_log).pack(side="left", padx=8)

        tk.Label(bot,
                 text="打包完成的 EXE 放在 dist/ 目錄",
                 bg=BG, fg=FG_DIM, font=("Consolas", 10),
                 ).pack(side="right")

    def _make_btn(self, parent, label, sub, color, cmd):
        """建立大型打包按鈕（Frame 模擬）。"""
        frame = tk.Frame(parent, bg=BG2, cursor="hand2", relief="flat", bd=0)

        inner = tk.Frame(frame, bg=BG2, pady=14)
        inner.pack(fill="both", expand=True)

        tk.Label(inner, text=label, bg=BG2, fg=color,
                 font=("Consolas", 13, "bold")).pack()
        tk.Label(inner, text=sub, bg=BG2, fg=FG_DIM,
                 font=("Consolas", 9)).pack()

        for w in (frame, inner, *inner.winfo_children()):
            w.bind("<Button-1>", lambda e, c=cmd: c())
            w.bind("<Enter>",    lambda e, f=frame: f.config(bg="#1e3a5f"))
            w.bind("<Leave>",    lambda e, f=frame: f.config(bg=BG2))

        return frame

    def _make_small_btn(self, parent, text, cmd):
        b = tk.Button(
            parent, text=text, command=cmd,
            bg=BG3, fg=FG, activebackground="#1e3a5f", activeforeground=CYAN,
            font=("Consolas", 10), relief="flat", padx=12, pady=4,
            cursor="hand2",
        )
        return b

    # ── 記錄輸出 ──────────────────────────────────────────────
    def _log_write(self, text: str, tag: str = "info"):
        self._log.config(state="normal")
        self._log.insert("end", text, tag)
        self._log.see("end")
        self._log.config(state="disabled")

    def _log_line(self, text: str, tag: str = "info"):
        self._log_write(text + "\n", tag)

    def _clear_log(self):
        self._log.config(state="normal")
        self._log.delete("1.0", "end")
        self._log.config(state="disabled")

    # ── 建置邏輯 ──────────────────────────────────────────────
    def _check_pyinstaller(self):
        try:
            result = subprocess.run(
                [sys.executable, "-m", "PyInstaller", "--version"],
                capture_output=True, text=True, cwd=str(BASE)
            )
            ver = result.stdout.strip() or result.stderr.strip()
            self._log_line(f"PyInstaller 版本：{ver}", "ok")
        except Exception as e:
            self._log_line(f"找不到 PyInstaller：{e}", "error")
            self._log_line("請先執行：pip install pyinstaller", "warn")

    def _start_build(self, target: str):
        if self._building:
            messagebox.showwarning("建置中", "目前正在打包，請稍候完成。")
            return

        spec = "build_server.spec" if target == "server" else "build_client.spec"
        spec_path = BASE / spec
        if not spec_path.exists():
            messagebox.showerror("找不到 spec", f"找不到 {spec}")
            return

        self._building   = True
        self._start_time = time.time()
        label = "伺服器" if target == "server" else "客戶端"

        self._set_buttons_state("disabled")
        self._pb.start(12)
        self._status_var.set(f"正在打包 {label} EXE…")
        self._elapsed_var.set("")

        self._log_line(f"\n{'─'*60}", "dim")
        self._log_line(f"  開始打包 {label}  [{spec}]", "header")
        self._log_line(f"{'─'*60}", "dim")

        # 啟動計時器
        self._update_elapsed()

        # 在背景 thread 執行
        threading.Thread(
            target=self._run_build,
            args=(spec, label, target),
            daemon=True,
        ).start()

    def _run_build(self, spec: str, label: str, target: str):
        cmd = [
            sys.executable, "-m", "PyInstaller",
            spec, "--noconfirm", "--clean",
        ]
        try:
            proc = subprocess.Popen(
                cmd,
                cwd=str(BASE),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            for line in proc.stdout:
                line = line.rstrip()
                if not line:
                    continue
                # 依關鍵字決定顏色
                if "ERROR" in line or "error" in line.lower():
                    tag = "error"
                elif "WARNING" in line:
                    tag = "warn"
                elif "completed successfully" in line or "Building EXE" in line:
                    tag = "ok"
                elif line.startswith("INFO:"):
                    tag = "dim"
                else:
                    tag = "info"
                self.root.after(0, self._log_line, line, tag)

            proc.wait()
            success = (proc.returncode == 0)
        except Exception as e:
            self.root.after(0, self._log_line, f"執行失敗：{e}", "error")
            success = False

        self.root.after(0, self._build_done, label, success, target)

    def _build_done(self, label: str, success: bool, target: str):
        self._pb.stop()
        self._building = False
        elapsed = time.time() - self._start_time

        self._log_line(f"{'─'*60}", "dim")
        if success:
            self._status_var.set(f"{label} 打包完成 ✓")
            self._log_line(f"  ✓ {label} EXE 打包成功！耗時 {elapsed:.1f} 秒", "ok")
            self._log_line(f"  輸出目錄：{BASE / 'dist'}", "ok")
            # 顯示 EXE 檔案大小
            exe_name = "MeshtasticBBS_Server.exe" if target == "server" else "MeshtasticBBS_Client.exe"
            exe_path = BASE / "dist" / exe_name
            if exe_path.exists():
                size_mb = exe_path.stat().st_size / (1024 * 1024)
                self._log_line(f"  檔案大小：{exe_name}  {size_mb:.1f} MB", "ok")
        else:
            self._status_var.set(f"{label} 打包失敗 ✗")
            self._log_line(f"  ✗ 打包失敗，請查看上方錯誤訊息。", "error")
        self._log_line(f"{'─'*60}\n", "dim")
        self._elapsed_var.set(f"耗時 {elapsed:.1f}s")
        self._set_buttons_state("normal")

    def _update_elapsed(self):
        if self._building:
            elapsed = time.time() - self._start_time
            self._elapsed_var.set(f"{elapsed:.0f}s …")
            self.root.after(1000, self._update_elapsed)

    def _set_buttons_state(self, state: str):
        fg_s = CYAN  if state == "normal" else FG_DIM
        fg_c = GREEN if state == "normal" else FG_DIM
        # 直接改 label 顏色來模擬 disabled 效果（Frame 模擬按鈕）
        for w in self._btn_server.winfo_children():
            for ww in ([w] + list(w.winfo_children())):
                try:
                    if ww.cget("fg") in (CYAN, FG_DIM):
                        ww.config(fg=fg_s)
                except Exception:
                    pass
        for w in self._btn_client.winfo_children():
            for ww in ([w] + list(w.winfo_children())):
                try:
                    if ww.cget("fg") in (GREEN, FG_DIM):
                        ww.config(fg=fg_c)
                except Exception:
                    pass

    # ── 工具 ──────────────────────────────────────────────────
    def _open_dist(self):
        dist = BASE / "dist"
        dist.mkdir(exist_ok=True)
        if sys.platform == "win32":
            os.startfile(str(dist))
        elif sys.platform == "darwin":
            subprocess.run(["open", str(dist)])
        else:
            subprocess.run(["xdg-open", str(dist)])


# ── 入口 ──────────────────────────────────────────────────────
def main():
    root = tk.Tk()
    app  = BuildTool(root)
    root.mainloop()


if __name__ == "__main__":
    main()
