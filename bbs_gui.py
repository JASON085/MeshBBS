
"""
V4版，加入登入帳號，密碼驗證功能
"""
"""
Meshtastic BBS GUI 控制台
=========================
圖形介面，方便啟停伺服器、管理看板與使用者。

安裝依賴：
    pip install meshtastic aiohttp pypubsub

啟動：
    python bbs_gui.py
"""

import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext
import threading
import asyncio
import sys
from pathlib import Path

# ── Import server components ───────────────────────────────────
if not getattr(sys, 'frozen', False):
    sys.path.insert(0, str(Path(__file__).parent))
import meshtastic_bbs_server as _mod
from meshtastic_bbs_server import (
    BBS, MeshInterface, BBSServer,
    setup_admin_routes, setup_public_routes, init_db, LOG_BUFFER,
    _res_dir,
)
from aiohttp import web


# ═══════════════════════════════════════════════════════════════
# 工具：將 Toplevel 對話框置中於父視窗
# ═══════════════════════════════════════════════════════════════
def _center_on_parent(dialog: tk.Toplevel, parent: tk.Misc):
    """計算並設定對話框位置，使其置中於 parent 視窗。"""
    dialog.update_idletasks()
    dw = dialog.winfo_reqwidth()
    dh = dialog.winfo_reqheight()
    px = parent.winfo_rootx()
    py = parent.winfo_rooty()
    pw = parent.winfo_width()
    ph = parent.winfo_height()
    x  = px + (pw - dw) // 2
    y  = py + (ph - dh) // 2
    dialog.geometry(f"+{max(0, x)}+{max(0, y)}")


# ═══════════════════════════════════════════════════════════════
# Helper: Board add/edit dialog
# ═══════════════════════════════════════════════════════════════
class _BoardDialog(tk.Toplevel):
    result = None   # (name, title, mod_name, mod_id)

    def __init__(self, parent, editing=False,
                 name="", title="", mod="SYSOP", mod_id="", users=None):
        super().__init__(parent)
        self.title("編輯看板" if editing else "新增看板")
        self.resizable(False, False)
        self.grab_set()
        self.transient(parent)

        # users: list of {"node_id":..., "name":...}
        self._users = users or []
        # Build display list: "名稱 (node_id)" + "SYSOP (無)" at top
        self._user_display = ["SYSOP（系統）"] + [
            f"{u['name']}  ({u['node_id']})" for u in self._users
        ]
        self._user_ids   = [""] + [u["node_id"] for u in self._users]
        self._user_names = ["SYSOP"] + [u["name"] for u in self._users]

        f = ttk.Frame(self, padding=16)
        f.pack()

        # Board code
        ttk.Label(f, text="看板代碼（英文）：").grid(row=0, column=0, sticky="w", pady=4, padx=(0,8))
        self._e_name = ttk.Entry(f, width=22)
        self._e_name.insert(0, name)
        if editing:
            self._e_name.configure(state="readonly")
        self._e_name.grid(row=0, column=1, pady=4)

        # Board title
        ttk.Label(f, text="看板名稱：").grid(row=1, column=0, sticky="w", pady=4, padx=(0,8))
        self._e_title = ttk.Entry(f, width=22)
        self._e_title.insert(0, title)
        self._e_title.grid(row=1, column=1, pady=4)

        # Moderator dropdown
        ttk.Label(f, text="版主：").grid(row=2, column=0, sticky="w", pady=4, padx=(0,8))
        # Find current selection index (by id first, then by name)
        cur_idx = 0
        if mod_id and mod_id in self._user_ids:
            cur_idx = self._user_ids.index(mod_id)
        elif mod and mod != "SYSOP":
            for i, n in enumerate(self._user_names):
                if n == mod:
                    cur_idx = i; break
        self._v_mod = tk.StringVar(value=self._user_display[cur_idx])
        cb = ttk.Combobox(f, textvariable=self._v_mod,
                          values=self._user_display, width=30, state="readonly")
        cb.current(cur_idx)
        cb.grid(row=2, column=1, pady=4, sticky="w")

        bf = ttk.Frame(f)
        bf.grid(row=3, column=0, columnspan=2, pady=(12, 0))
        ttk.Button(bf, text="確定", command=self._ok).pack(side="left", padx=4)
        ttk.Button(bf, text="取消", command=self.destroy).pack(side="left")

        (self._e_title if editing else self._e_name).focus_set()
        _center_on_parent(self, parent)
        self.wait_window()

    def _ok(self):
        name  = self._e_name.get().strip()
        title = self._e_title.get().strip()
        if not name or not title:
            messagebox.showwarning("提示", "代碼和名稱不能為空", parent=self)
            return
        sel_text = self._v_mod.get()
        try:
            idx = self._user_display.index(sel_text)
        except ValueError:
            idx = 0
        mod_name = self._user_names[idx]
        mod_id   = self._user_ids[idx]
        self.result = (name, title, mod_name, mod_id)
        self.destroy()


# ═══════════════════════════════════════════════════════════════
# Admin account add/change-password dialog
# ═══════════════════════════════════════════════════════════════
class _AdminDialog(tk.Toplevel):
    result = None  # (username, password) or (None, password) for pwd-only change

    def __init__(self, parent, change_password=False, username=""):
        super().__init__(parent)
        self.title("修改密碼" if change_password else "新增管理員帳號")
        self.resizable(False, False)
        self.grab_set()
        self.transient(parent)
        self._change_only = change_password

        f = ttk.Frame(self, padding=16)
        f.pack()

        row = 0
        if not change_password:
            ttk.Label(f, text="帳號：").grid(row=row, column=0, sticky="w", pady=4, padx=(0,8))
            self._e_user = ttk.Entry(f, width=20)
            self._e_user.insert(0, username)
            self._e_user.grid(row=row, column=1, pady=4)
            row += 1

        ttk.Label(f, text="新密碼：").grid(row=row, column=0, sticky="w", pady=4, padx=(0,8))
        self._e_pw1 = ttk.Entry(f, width=20, show="●")
        self._e_pw1.grid(row=row, column=1, pady=4)
        row += 1

        ttk.Label(f, text="確認密碼：").grid(row=row, column=0, sticky="w", pady=4, padx=(0,8))
        self._e_pw2 = ttk.Entry(f, width=20, show="●")
        self._e_pw2.grid(row=row, column=1, pady=4)
        row += 1

        bf = ttk.Frame(f)
        bf.grid(row=row, column=0, columnspan=2, pady=(12, 0))
        ttk.Button(bf, text="確定", command=self._ok).pack(side="left", padx=4)
        ttk.Button(bf, text="取消", command=self.destroy).pack(side="left")

        self._e_pw1.focus_set()
        _center_on_parent(self, parent)
        self.wait_window()

    def _ok(self):
        pw1 = self._e_pw1.get().strip()
        pw2 = self._e_pw2.get().strip()
        if not pw1:
            messagebox.showwarning("提示", "密碼不能為空", parent=self); return
        if pw1 != pw2:
            messagebox.showwarning("提示", "兩次密碼不一致", parent=self); return
        if self._change_only:
            self.result = (None, pw1)
        else:
            uname = self._e_user.get().strip()
            if not uname:
                messagebox.showwarning("提示", "帳號不能為空", parent=self); return
            self.result = (uname, pw1)
        self.destroy()


# ═══════════════════════════════════════════════════════════════
# Main GUI
# ═══════════════════════════════════════════════════════════════
class BBSConsole:
    # ── Server state ────────────────────────────────────────────
    _running   = False
    _loop:    asyncio.AbstractEventLoop = None
    _thread:  threading.Thread          = None
    _bbs:     BBS                       = None
    _mesh:    MeshInterface             = None
    _srv:     BBSServer                 = None
    _runner                             = None
    _http_port = 8765
    _last_log  = 0

    # ── Init ─────────────────────────────────────────────────────
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Meshtastic BBS 控制台")
        self.root.geometry("980x700")
        self.root.minsize(820, 560)

        # Apply a slightly modern theme
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        # Accent colour for important labels
        style.configure("Green.TLabel",  foreground="#007700", font=("", 11, "bold"))
        style.configure("Red.TLabel",    foreground="#aa0000", font=("", 11, "bold"))
        style.configure("Gray.TLabel",   foreground="#888888")
        style.configure("Header.TLabel", font=("", 10, "bold"))

        self._build_ui()
        self.root.after(1000, self._tick)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    # ═══════════════════════════════════════════════════════════
    # UI construction
    # ═══════════════════════════════════════════════════════════
    def _build_ui(self):
        self._build_control_bar()
        self._build_notebook()
        self._build_statusbar()

    # ── Top control bar ──────────────────────────────────────────
    def _build_control_bar(self):
        outer = ttk.LabelFrame(self.root, text="  ⚙  伺服器設定", padding=(10, 6))
        outer.pack(fill="x", padx=10, pady=(8, 4))

        # Row 1: config fields
        row1 = ttk.Frame(outer)
        row1.pack(fill="x")

        ttk.Label(row1, text="Meshtastic Port：", style="Header.TLabel").grid(
            row=0, column=0, sticky="w", padx=(0, 4))
        self._v_mport = tk.StringVar(value="COM3")
        ttk.Entry(row1, textvariable=self._v_mport, width=16).grid(
            row=0, column=1, padx=(0, 12))

        ttk.Label(row1, text="TCP Host（選填）：", style="Header.TLabel").grid(
            row=0, column=2, sticky="w", padx=(0, 4))
        self._v_host = tk.StringVar(value="")
        ttk.Entry(row1, textvariable=self._v_host, width=16).grid(
            row=0, column=3, padx=(0, 12))

        ttk.Label(row1, text="HTTP Port：", style="Header.TLabel").grid(
            row=0, column=4, sticky="w", padx=(0, 4))
        self._v_hport = tk.StringVar(value="8765")
        ttk.Entry(row1, textvariable=self._v_hport, width=7).grid(
            row=0, column=5, padx=(0, 12))

        # Row 2: buttons + status
        row2 = ttk.Frame(outer)
        row2.pack(fill="x", pady=(8, 0))

        self._btn_start = ttk.Button(row2, text="▶  啟動伺服器",
                                     command=self._start_server, width=16)
        self._btn_start.pack(side="left", padx=(0, 8))

        self._btn_stop = ttk.Button(row2, text="■  停止伺服器",
                                    command=self._stop_server, width=16,
                                    state="disabled")
        self._btn_stop.pack(side="left", padx=(0, 20))

        ttk.Label(row2, text="狀態：").pack(side="left")
        self._lbl_srv = ttk.Label(row2, text="● 未啟動", style="Gray.TLabel")
        self._lbl_srv.pack(side="left", padx=(0, 16))

        ttk.Label(row2, text="Mesh：").pack(side="left")
        self._lbl_mesh = ttk.Label(row2, text="● 未連線", style="Gray.TLabel")
        self._lbl_mesh.pack(side="left", padx=(0, 16))

        self._lbl_url = ttk.Label(row2, text="", foreground="#0055cc",
                                  cursor="hand2")
        self._lbl_url.pack(side="left")
        self._lbl_url.bind("<Button-1>", self._open_browser)

    # ── Notebook ─────────────────────────────────────────────────
    def _build_notebook(self):
        self._nb = ttk.Notebook(self.root)
        self._nb.pack(fill="both", expand=True, padx=10, pady=(4, 0))
        self._nb.bind("<<NotebookTabChanged>>", self._on_tab_change)

        self._build_tab_status()
        self._build_tab_boards()
        self._build_tab_users()
        self._build_tab_posts()
        self._build_tab_mesh()
        self._build_tab_admins()
        self._build_tab_logs()

    # ── Tab: Status ───────────────────────────────────────────────
    def _build_tab_status(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  📊 伺服器狀態  ")

        # Stats cards row
        cards = ttk.Frame(tab)
        cards.pack(fill="x", pady=(0, 8))
        self._stat_vars = {}
        card_defs = [
            ("文章總數", "posts",       "#004400"),
            ("回覆總數", "replies",     "#004400"),
            ("使用者數", "users",       "#003366"),
            ("線上人數", "online",      "#006600"),
            ("看板數量", "boards",      "#333300"),
            ("Mesh訊息", "mesh_msg",   "#330033"),
            ("WS連線數", "ws_clients", "#220022"),
            ("Mesh客戶端","mesh_clients","#1a2a00"),
        ]
        for i, (lbl, key, bg) in enumerate(card_defs):
            frm = tk.Frame(cards, bg=bg, relief="ridge", bd=1)
            frm.grid(row=0, column=i, padx=3, sticky="nsew")
            cards.columnconfigure(i, weight=1)
            tk.Label(frm, text=lbl, bg=bg, fg="#aaaaaa",
                     font=("", 9)).pack(pady=(4, 0))
            v = tk.StringVar(value="—")
            self._stat_vars[key] = v
            tk.Label(frm, textvariable=v, bg=bg, fg="#44ff44",
                     font=("Courier", 18, "bold")).pack(pady=(0, 4))

        # Bottom: online users + recent activity side by side
        bot = ttk.Frame(tab)
        bot.pack(fill="both", expand=True)
        bot.columnconfigure(0, weight=1)
        bot.columnconfigure(1, weight=3)

        # Online users
        ol = ttk.LabelFrame(bot, text="線上使用者", padding=4)
        ol.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        self._online_lb = tk.Listbox(ol, selectmode="browse",
                                      font=("Courier", 10), height=14)
        olsb = ttk.Scrollbar(ol, command=self._online_lb.yview)
        self._online_lb.configure(yscrollcommand=olsb.set)
        self._online_lb.pack(side="left", fill="both", expand=True)
        olsb.pack(side="right", fill="y")

        # Recent activity
        act = ttk.LabelFrame(bot, text="最近文章", padding=4)
        act.grid(row=0, column=1, sticky="nsew")
        cols = ("時間", "看板", "作者", "標題")
        self._act_tree = ttk.Treeview(act, columns=cols, show="headings",
                                       selectmode="none", height=14)
        ws = {"時間": 130, "看板": 90, "作者": 90, "標題": 0}
        for c in cols:
            self._act_tree.heading(c, text=c)
            self._act_tree.column(c, width=ws[c],
                                   stretch=(c == "標題"))
        asb = ttk.Scrollbar(act, command=self._act_tree.yview)
        self._act_tree.configure(yscrollcommand=asb.set)
        self._act_tree.pack(side="left", fill="both", expand=True)
        asb.pack(side="right", fill="y")

    # ── Tab: Boards ───────────────────────────────────────────────
    def _build_tab_boards(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  📋 看板管理  ")

        tb = ttk.Frame(tab)
        tb.pack(fill="x", pady=(0, 6))
        ttk.Button(tb, text="＋ 新增看板",  command=self._board_add).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="✏  編輯",      command=self._board_edit).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="✕  刪除",      command=self._board_del).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="↺  重新整理",  command=self._refresh_boards).pack(side="right")

        cols = ("代碼", "名稱", "版主", "版主節點ID", "文章數")
        self._boards_tv = ttk.Treeview(tab, columns=cols, show="headings",
                                        selectmode="browse")
        ws = {"代碼": 120, "名稱": 160, "版主": 100, "版主節點ID": 130, "文章數": 70}
        for c in cols:
            self._boards_tv.heading(c, text=c,
                command=lambda cc=c: self._tv_sort(self._boards_tv, cc))
            self._boards_tv.column(c, width=ws[c],
                                    stretch=(c == "名稱"))
        sb = ttk.Scrollbar(tab, command=self._boards_tv.yview)
        self._boards_tv.configure(yscrollcommand=sb.set)
        self._boards_tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    # ── Tab: Users ────────────────────────────────────────────────
    def _build_tab_users(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  👤 使用者管理  ")

        tb = ttk.Frame(tab)
        tb.pack(fill="x", pady=(0, 6))
        ttk.Button(tb, text="🔒 封禁",      command=self._user_ban).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="🔓 解封",      command=self._user_unban).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="✕  刪除",      command=self._user_del).pack(side="left", padx=(0,4))
        ttk.Separator(tb, orient="vertical").pack(side="left", fill="y", padx=6)
        ttk.Button(tb, text="🔑 重設密碼",  command=self._user_reset_pass).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="🗝 清除密碼",  command=self._user_clear_pass).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="↺  重新整理",  command=self._refresh_users).pack(side="right")

        note = ttk.Label(tab,
            text="🔑 有密碼＝已建立 BBS 帳號  ·  無密碼＝路過裝置（傳過訊息但未登入 BBS）",
            foreground="#888888")
        note.pack(fill="x", pady=(0, 4))

        cols = ("節點ID", "暱稱", "最後上線", "文章", "狀態", "封禁", "密碼")
        self._users_tv = ttk.Treeview(tab, columns=cols, show="headings",
                                       selectmode="browse")
        ws = {"節點ID": 130, "暱稱": 110, "最後上線": 135, "文章": 50, "狀態": 60, "封禁": 55, "密碼": 60}
        for c in cols:
            self._users_tv.heading(c, text=c)
            self._users_tv.column(c, width=ws[c], stretch=(c=="暱稱"))
        self._users_tv.tag_configure("banned",  foreground="#cc3333")
        self._users_tv.tag_configure("online",  foreground="#007700")
        self._users_tv.tag_configure("web",     foreground="#4488cc")
        sb = ttk.Scrollbar(tab, command=self._users_tv.yview)
        self._users_tv.configure(yscrollcommand=sb.set)
        self._users_tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    # ── Tab: Posts ────────────────────────────────────────────────
    def _build_tab_posts(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  📝 文章管理  ")

        # Filter bar
        fb = ttk.Frame(tab)
        fb.pack(fill="x", pady=(0, 6))
        ttk.Label(fb, text="看板：").pack(side="left")
        self._v_post_board = tk.StringVar(value="全部")
        self._post_board_cb = ttk.Combobox(fb, textvariable=self._v_post_board,
                                            width=13, state="readonly")
        self._post_board_cb["values"] = ["全部"]
        self._post_board_cb.pack(side="left", padx=(2, 10))
        ttk.Label(fb, text="搜尋：").pack(side="left")
        self._v_post_q = tk.StringVar()
        q_entry = ttk.Entry(fb, textvariable=self._v_post_q, width=22)
        q_entry.pack(side="left", padx=(2, 6))
        q_entry.bind("<Return>", lambda e: self._refresh_posts())
        ttk.Button(fb, text="🔍 搜尋", command=self._refresh_posts).pack(side="left", padx=(0,10))
        ttk.Button(fb, text="✕  刪除選中", command=self._post_del).pack(side="left")
        ttk.Button(fb, text="↺  重新整理", command=self._refresh_posts).pack(side="right")

        cols = ("編號", "看板", "標題", "作者", "回覆", "時間")
        self._posts_tv = ttk.Treeview(tab, columns=cols, show="headings",
                                       selectmode="browse")
        ws = {"編號": 60, "看板": 95, "標題": 0, "作者": 90, "回覆": 55, "時間": 125}
        for c in cols:
            self._posts_tv.heading(c, text=c,
                command=lambda cc=c: self._tv_sort(self._posts_tv, cc))
            self._posts_tv.column(c, width=ws[c], stretch=(c=="標題"))
        sb = ttk.Scrollbar(tab, command=self._posts_tv.yview)
        self._posts_tv.configure(yscrollcommand=sb.set)
        self._posts_tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        # 雙擊標題欄 → 管理該文章的回覆
        self._posts_tv.bind("<Double-1>", self._on_post_dblclick)

    # ── Tab: Mesh ─────────────────────────────────────────────────
    def _build_tab_mesh(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  📡 Mesh 網路  ")

        tab.columnconfigure(0, weight=1)
        tab.columnconfigure(1, weight=1)
        tab.rowconfigure(1, weight=1)

        # Broadcast row
        bc_f = ttk.LabelFrame(tab, text="廣播訊息", padding=6)
        bc_f.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, 8))
        self._v_bc_msg = tk.StringVar()
        bc_entry = ttk.Entry(bc_f, textvariable=self._v_bc_msg)
        bc_entry.pack(side="left", fill="x", expand=True, padx=(0, 6))
        bc_entry.bind("<Return>", lambda e: self._mesh_broadcast())
        ttk.Button(bc_f, text="📡  廣播", command=self._mesh_broadcast).pack(side="left")

        # Nodes
        nodes_f = ttk.LabelFrame(tab, text="Mesh 節點列表", padding=4)
        nodes_f.grid(row=1, column=0, sticky="nsew", padx=(0, 6))
        ttk.Button(nodes_f, text="↺", command=self._refresh_nodes,
                   width=3).pack(anchor="ne")
        n_cols = ("節點號", "名稱", "型號", "電量", "SNR")
        self._nodes_tv = ttk.Treeview(nodes_f, columns=n_cols, show="headings",
                                       selectmode="none", height=12)
        nw = {"節點號": 90, "名稱": 120, "型號": 90, "電量": 60, "SNR": 55}
        for c in n_cols:
            self._nodes_tv.heading(c, text=c)
            self._nodes_tv.column(c, width=nw[c])
        nsb = ttk.Scrollbar(nodes_f, command=self._nodes_tv.yview)
        self._nodes_tv.configure(yscrollcommand=nsb.set)
        self._nodes_tv.pack(side="left", fill="both", expand=True)
        nsb.pack(side="right", fill="y")

        # Mesh messages
        msgs_f = ttk.LabelFrame(tab, text="近期 Mesh 訊息", padding=4)
        msgs_f.grid(row=1, column=1, sticky="nsew")
        btn_row = ttk.Frame(msgs_f)
        btn_row.pack(fill="x", anchor="n")
        ttk.Button(btn_row, text="↺", command=self._refresh_mesh_msgs,
                   width=3).pack(side="right")
        ttk.Button(btn_row, text="🗑 清空", command=self._clear_mesh_msgs,
                   width=8).pack(side="right", padx=(0, 4))
        m_cols = ("時間", "來源", "訊息")
        self._mesh_msgs_tv = ttk.Treeview(msgs_f, columns=m_cols, show="headings",
                                           selectmode="none", height=12)
        mw = {"時間": 115, "來源": 90, "訊息": 0}
        for c in m_cols:
            self._mesh_msgs_tv.heading(c, text=c)
            self._mesh_msgs_tv.column(c, width=mw[c], stretch=(c=="訊息"))
        msb = ttk.Scrollbar(msgs_f, command=self._mesh_msgs_tv.yview)
        self._mesh_msgs_tv.configure(yscrollcommand=msb.set)
        self._mesh_msgs_tv.pack(side="left", fill="both", expand=True)
        msb.pack(side="right", fill="y")

    # ── Tab: Admins ───────────────────────────────────────────────
    def _build_tab_admins(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  🔑 管理員帳號  ")

        tb = ttk.Frame(tab)
        tb.pack(fill="x", pady=(0, 6))
        ttk.Button(tb, text="＋ 新增帳號",    command=self._admin_add).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="🔑 修改密碼",    command=self._admin_change_pass).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="✕  刪除",        command=self._admin_del).pack(side="left", padx=(0,4))
        ttk.Button(tb, text="↺  重新整理",    command=self._refresh_admins).pack(side="right")

        note = ttk.Label(tab,
            text="⚠ 至少保留一個管理員帳號。帳號用於瀏覽器後台 /admin 登入。",
            foreground="#888888")
        note.pack(fill="x", pady=(0, 4))

        cols = ("編號", "帳號", "建立時間")
        self._admins_tv = ttk.Treeview(tab, columns=cols, show="headings",
                                        selectmode="browse")
        ws = {"編號": 60, "帳號": 200, "建立時間": 0}
        for c in cols:
            self._admins_tv.heading(c, text=c)
            self._admins_tv.column(c, width=ws[c], stretch=(c == "建立時間"))
        sb = ttk.Scrollbar(tab, command=self._admins_tv.yview)
        self._admins_tv.configure(yscrollcommand=sb.set)
        self._admins_tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    # ── Tab: Logs ─────────────────────────────────────────────────
    def _build_tab_logs(self):
        tab = ttk.Frame(self._nb, padding=8)
        self._nb.add(tab, text="  📜 系統日誌  ")

        tb = ttk.Frame(tab)
        tb.pack(fill="x", pady=(0, 4))
        self._v_autoscroll = tk.BooleanVar(value=True)
        ttk.Checkbutton(tb, text="自動捲動", variable=self._v_autoscroll).pack(side="left")
        ttk.Button(tb, text="清除", command=self._logs_clear).pack(side="left", padx=8)

        self._log_box = scrolledtext.ScrolledText(
            tab, state="disabled",
            font=("Courier New", 10),
            background="#1a1a1a", foreground="#c0c0c0",
            insertbackground="#00cc00",
            wrap="word",
        )
        self._log_box.pack(fill="both", expand=True)
        self._log_box.tag_configure("INFO",    foreground="#c0c0c0")
        self._log_box.tag_configure("WARNING", foreground="#cccc00")
        self._log_box.tag_configure("ERROR",   foreground="#ff4444")
        self._log_box.tag_configure("DEBUG",   foreground="#555555")
        self._log_box.tag_configure("time",    foreground="#555555")

    # ── Status bar ────────────────────────────────────────────────
    def _build_statusbar(self):
        bar = tk.Frame(self.root, bg="#333333", height=22)
        bar.pack(fill="x", side="bottom")
        bar.pack_propagate(False)
        self._sb_text = tk.StringVar(value="就緒")
        tk.Label(bar, textvariable=self._sb_text, bg="#333333",
                 fg="#aaaaaa", font=("", 9), anchor="w",
                 padx=8).pack(fill="x")

    # ═══════════════════════════════════════════════════════════
    # Server lifecycle
    # ═══════════════════════════════════════════════════════════
    def _start_server(self):
        if self._running:
            return
        try:
            mesh_port  = self._v_mport.get().strip()
            http_port  = int(self._v_hport.get().strip())
        except ValueError:
            messagebox.showerror("錯誤", "HTTP 埠號必須為數字", parent=self.root)
            return

        init_db()
        self._bbs  = BBS()
        self._bbs.set_all_offline()

        # 第一次啟動（無任何管理員）建立預設帳號，後續不更動密碼
        all_admins = self._bbs.get_admins()
        if not all_admins:
            self._bbs.create_admin("admin", "admin")

        _mod._admin_password = "admin"
        _mod._new_token()

        self._srv  = BBSServer(self._bbs, None)
        self._mesh = MeshInterface(mesh_port, self._bbs, self._srv.broadcast)
        self._srv.mesh             = self._mesh
        self._mesh.kick_ws_fn      = self._srv.kick_ws
        self._srv.notify_device_fn = self._mesh._send_plain_text
        self._http_port = http_port

        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()
        self._running = True

        self._btn_start.configure(state="disabled")
        self._btn_stop.configure(state="normal")
        self._lbl_srv.configure(text="● 執行中", foreground="#007700",
                                 font=("", 11, "bold"))
        self._lbl_url.configure(
            text=f"  🌐 http://localhost:{http_port}  │  後台 /admin")
        self._sb(f"伺服器已啟動  BBS: http://localhost:{http_port} │ 管理: /admin")

        self._refresh_boards()
        self._refresh_users()
        self._refresh_posts()
        self._refresh_admins()

    def _run_loop(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._async_server())
        except Exception:
            pass

    async def _async_server(self):
        base = _res_dir()

        async def _bbs_html(r):
            resp = web.FileResponse(base / "bbs_client.html")
            resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            return resp

        async def _adm_html(r):
            resp = web.FileResponse(base / "admin.html")
            resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            return resp

        app = web.Application()
        app.add_routes([
            web.get("/",      _bbs_html),
            web.get("/admin", _adm_html),
            web.get("/ws",    self._srv.handle_ws),
        ])
        setup_public_routes(app, self._bbs)
        setup_admin_routes(app, self._bbs, self._mesh, self._srv)

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, "0.0.0.0", self._http_port)
        await site.start()

        # Start Meshtastic in a thread, passing this event loop
        threading.Thread(
            target=self._mesh.start,
            args=(self._loop,),
            daemon=True
        ).start()

        # Keep running; poll mesh status
        while True:
            await asyncio.sleep(2)
            if self._mesh.connected:
                node = self._mesh.local_node_name
                self.root.after(0, lambda n=node: self._lbl_mesh.configure(
                    text=f"● {n}", foreground="#007700", font=("",11,"bold")))
            else:
                self.root.after(0, lambda: self._lbl_mesh.configure(
                    text="● 未連線", foreground="#888888"))

    def _stop_server(self):
        if not self._running:
            return
        self._running = False
        self._btn_start.configure(state="disabled")  # 停止完成前禁止再次啟動
        self._btn_stop.configure(state="disabled")
        if self._mesh:
            self._mesh.stop()
            self._mesh = None
        if self._loop and not self._loop.is_closed():
            if self._runner:
                asyncio.run_coroutine_threadsafe(
                    self._runner.cleanup(), self._loop)
            self._loop.call_soon_threadsafe(self._loop.stop)
        self._bbs = None
        # 等待舊事件迴圈執行緒結束，確保序列埠已釋放，再開放重新啟動
        def _finish_stop():
            if self._thread and self._thread.is_alive():
                self._thread.join(timeout=4)
            self.root.after(0, self._after_stop)
        threading.Thread(target=_finish_stop, daemon=True).start()

    def _after_stop(self):
        self._btn_start.configure(state="normal")
        self._btn_stop.configure(state="disabled")
        self._lbl_srv.configure(text="● 已停止", foreground="#888888", font=("", 11))
        self._lbl_mesh.configure(text="● 未連線", foreground="#888888")
        self._lbl_url.configure(text="")
        self._sb("伺服器已停止")

    # ═══════════════════════════════════════════════════════════
    # Periodic refresh
    # ═══════════════════════════════════════════════════════════
    def _tick(self):
        if self._running and self._bbs:
            try:
                self._refresh_status()
                # 若目前顯示使用者管理分頁，即時更新表格
                tab = self._nb.tab(self._nb.select(), "text").strip()
                if "使用者" in tab:
                    self._refresh_users()
            except Exception:
                pass
            self._append_logs()
        self.root.after(3000, self._tick)

    def _on_tab_change(self, _event=None):
        if not self._running or not self._bbs:
            return
        tab = self._nb.tab(self._nb.select(), "text").strip()
        if "看板" in tab:    self._refresh_boards()
        if "使用者" in tab:  self._refresh_users()
        if "文章" in tab:    self._refresh_posts()
        if "管理員" in tab:  self._refresh_admins()
        if "Mesh" in tab:
            self._refresh_nodes()
            self._refresh_mesh_msgs()

    def _refresh_status(self):
        s = self._bbs.get_stats()
        s["ws_clients"]   = len(self._srv.clients) if self._srv else 0
        s["mesh_clients"] = len(self._mesh._relay_clients) if self._mesh else 0
        for k, v in s.items():
            if k in self._stat_vars:
                self._stat_vars[k].set(str(v))

        users = self._bbs.get_online_users()
        self._online_lb.delete(0, "end")
        for u in users:
            self._online_lb.insert("end", f"◆  {u['name']}  ({u['node_id']})")

        act = self._bbs.get_recent_activity(12)
        for i in self._act_tree.get_children():
            self._act_tree.delete(i)
        for a in act:
            self._act_tree.insert("", "end", values=(
                a["time"], a["board"], a["author"], a["title"]))

    def _refresh_boards(self):
        if not self._bbs:
            return
        boards = self._bbs.get_boards()
        for i in self._boards_tv.get_children():
            self._boards_tv.delete(i)
        for b in boards:
            self._boards_tv.insert("", "end", iid=b["name"], values=(
                b["name"], b["title"], b["moderator"],
                b.get("moderator_id", ""), b["post_count"]))
        self._post_board_cb["values"] = ["全部"] + [b["name"] for b in boards]

    def _refresh_users(self):
        if not self._bbs:
            return
        users, _ = self._bbs.get_all_users(per_page=200)
        for i in self._users_tv.get_children():
            self._users_tv.delete(i)
        for u in users:
            if u["banned"]:
                tags = ("banned",)
            elif u["online"]:
                tags = ("online",)
            elif u.get("is_web"):
                tags = ("web",)
            else:
                tags = ()
            self._users_tv.insert("", "end", iid=u["node_id"], tags=tags,
                                   values=(
                u["node_id"], u["name"],
                (u["last_seen"] or "")[:16],
                u["post_count"],
                "🟢 線上" if u["online"] else "⚫ 離線",
                "🔒 是"   if u["banned"] else "  否",
                "🔑 有"   if u.get("has_password") else "  無",
            ))

    def _refresh_posts(self):
        if not self._bbs:
            return
        board  = self._v_post_board.get()
        search = self._v_post_q.get().strip()
        posts, _ = self._bbs.get_all_posts(
            board=None if board == "全部" else board,
            search=search or None,
            per_page=200
        )
        for i in self._posts_tv.get_children():
            self._posts_tv.delete(i)
        for p in posts:
            self._posts_tv.insert("", "end", iid=str(p["id"]), values=(
                p["id"], p["board"], p["title"],
                p["author"], p["reply_count"], p["created_at"],
            ))

    def _refresh_nodes(self):
        if not self._mesh:
            return
        nodes = self._mesh.get_nodes()
        for i in self._nodes_tv.get_children():
            self._nodes_tv.delete(i)
        for n in nodes:
            self._nodes_tv.insert("", "end", values=(
                n["num"], n["name"], n.get("hw_model", "-"),
                f'{n["battery"]}%' if n.get("battery") is not None else "-",
                f'{n["snr"]:.1f}'  if n.get("snr")     is not None else "-",
            ))

    def _refresh_mesh_msgs(self):
        if not self._bbs:
            return
        msgs = self._bbs.get_mesh_messages(50)
        for i in self._mesh_msgs_tv.get_children():
            self._mesh_msgs_tv.delete(i)
        for m in msgs:
            self._mesh_msgs_tv.insert("", "end", values=(
                m["created_at"], m["from_name"], m["text"]))

    def _clear_mesh_msgs(self):
        if not self._bbs:
            return
        if not messagebox.askyesno("確認清空",
                                   "確定要清空所有近期 Mesh 訊息記錄？\n此操作無法復原。",
                                   parent=self.root):
            return
        self._bbs.clear_mesh_messages()
        self._refresh_mesh_msgs()

    def _refresh_admins(self):
        if not self._bbs:
            return
        admins = self._bbs.get_admins()
        for i in self._admins_tv.get_children():
            self._admins_tv.delete(i)
        for a in admins:
            self._admins_tv.insert("", "end", iid=str(a["id"]), values=(
                a["id"], a["username"], (a["created_at"] or "")[:19]))

    # ═══════════════════════════════════════════════════════════
    # Admin account actions
    # ═══════════════════════════════════════════════════════════
    def _admin_add(self):
        if not self._bbs:
            messagebox.showwarning("提示", "請先啟動伺服器", parent=self.root); return
        d = _AdminDialog(self.root)
        if d.result:
            uname, pw = d.result
            ok, err = self._bbs.create_admin(uname, pw)
            if ok:
                self._refresh_admins()
                self._sb(f"管理員帳號 [{uname}] 建立成功")
            else:
                messagebox.showerror("錯誤", err, parent=self.root)

    def _admin_del(self):
        sel = self._admins_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇帳號", parent=self.root); return
        aid      = int(sel[0])
        uname    = self._admins_tv.item(sel[0])["values"][1]
        # Protect last admin
        if len(self._admins_tv.get_children()) <= 1:
            messagebox.showwarning("警告", "至少需要保留一個管理員帳號！", parent=self.root); return
        if messagebox.askyesno("確認刪除", f"刪除管理員帳號「{uname}」？", icon="warning", parent=self.root):
            self._bbs.delete_admin(aid)
            self._refresh_admins()
            self._sb(f"管理員帳號 [{uname}] 已刪除")

    def _admin_change_pass(self):
        sel = self._admins_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇帳號", parent=self.root); return
        aid   = int(sel[0])
        uname = self._admins_tv.item(sel[0])["values"][1]
        d = _AdminDialog(self.root, change_password=True, username=uname)
        if d.result:
            _, new_pw = d.result
            self._bbs.change_admin_password(aid, new_pw)
            self._refresh_admins()
            self._sb(f"管理員帳號 [{uname}] 密碼已更新")

    # ═══════════════════════════════════════════════════════════
    # Board actions
    # ═══════════════════════════════════════════════════════════
    def _get_users_for_mod(self):
        """Return list of users for moderator dropdown."""
        if not self._bbs:
            return []
        users, _ = self._bbs.get_all_users(per_page=500)
        return users

    def _board_add(self):
        if not self._bbs:
            messagebox.showwarning("提示", "請先啟動伺服器", parent=self.root); return
        d = _BoardDialog(self.root, users=self._get_users_for_mod())
        if d.result:
            name, title, mod_name, mod_id = d.result
            ok, err = self._bbs.create_board(name, title, mod_name)
            if ok:
                if mod_id:
                    self._bbs.set_board_moderator(name, mod_id, mod_name)
                self._refresh_boards()
                self._sb(f"看板 [{name}] 建立成功，版主：{mod_name}")
            else:
                messagebox.showerror("錯誤", err, parent=self.root)

    def _board_edit(self):
        sel = self._boards_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇看板", parent=self.root); return
        name = sel[0]
        vals = self._boards_tv.item(name)["values"]
        # vals: (代碼, 名稱, 版主, 版主ID, 文章數)
        cur_mod    = vals[2] if len(vals) > 2 else "SYSOP"
        cur_mod_id = vals[3] if len(vals) > 3 else ""
        d = _BoardDialog(self.root, editing=True,
                         name=name, title=vals[1],
                         mod=cur_mod, mod_id=str(cur_mod_id),
                         users=self._get_users_for_mod())
        if d.result:
            _, new_t, new_m, new_mid = d.result
            self._bbs.update_board(name, new_t, new_m, new_mid)
            self._refresh_boards()
            self._sb(f"看板 [{name}] 已更新，版主：{new_m}")

    def _board_del(self):
        sel = self._boards_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇看板", parent=self.root); return
        name = sel[0]
        cnt  = self._boards_tv.item(name)["values"][3]
        msg  = f"確定刪除看板「{name}」？\n\n⚠ 該看板下共 {cnt} 篇文章將一併刪除！"
        if messagebox.askyesno("確認刪除", msg, icon="warning", parent=self.root):
            self._bbs.delete_board(name)
            self._refresh_boards()
            self._refresh_posts()
            self._sb(f"看板 [{name}] 已刪除")
            if self._srv and self._loop:
                asyncio.run_coroutine_threadsafe(
                    self._srv.broadcast({
                        "type": "boards",
                        "boards": self._bbs.get_boards(),
                        "online_users": self._bbs.get_online_users(),
                    }), self._loop)

    # ═══════════════════════════════════════════════════════════
    # User actions
    # ═══════════════════════════════════════════════════════════
    def _user_ban(self):
        sel = self._users_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇使用者", parent=self.root); return
        nid  = sel[0]
        name = self._users_tv.item(nid)["values"][1]
        if messagebox.askyesno("確認封禁", f"封禁使用者「{name}」？\n封禁後該使用者無法發文及回覆。", parent=self.root):
            self._bbs.ban_user(nid, True)
            self._refresh_users()
            self._sb(f"使用者 [{name}] 已封禁")

    def _user_unban(self):
        sel = self._users_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇使用者", parent=self.root); return
        nid  = sel[0]
        name = self._users_tv.item(nid)["values"][1]
        self._bbs.ban_user(nid, False)
        self._refresh_users()
        self._sb(f"使用者 [{name}] 已解封")

    def _user_del(self):
        sel = self._users_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇使用者", parent=self.root); return
        nid  = sel[0]
        name = self._users_tv.item(nid)["values"][1]
        if messagebox.askyesno("確認刪除", f"刪除使用者「{name}」？", icon="warning", parent=self.root):
            self._bbs.delete_user(nid)
            self._refresh_users()
            self._sb(f"使用者 [{name}] 已刪除")

    def _user_reset_pass(self):
        sel = self._users_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇使用者", parent=self.root); return
        nid  = sel[0]
        name = self._users_tv.item(nid)["values"][1]
        d = _AdminDialog(self.root, change_password=True, username=name)
        if d.result:
            _, new_pw = d.result
            ok = self._bbs.reset_user_password(nid, new_pw)
            if ok:
                self._refresh_users()
                self._sb(f"使用者 [{name}] 密碼已重設")
            else:
                messagebox.showerror("錯誤", "重設密碼失敗", parent=self.root)

    def _user_clear_pass(self):
        sel = self._users_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇使用者", parent=self.root); return
        nid  = sel[0]
        name = self._users_tv.item(nid)["values"][1]
        vals = self._users_tv.item(nid)["values"]
        has_pw = "有" in str(vals[6]) if len(vals) > 6 else False
        if not has_pw:
            messagebox.showinfo("提示", f"「{name}」本來就沒有密碼", parent=self.root); return
        if messagebox.askyesno("確認清除",
                               f"清除「{name}」的密碼？\n\n清除後此帳號將改為 Meshtastic 裝置模式（無密碼登入）",
                               icon="warning", parent=self.root):
            self._bbs.clear_user_password(nid)
            self._refresh_users()
            self._sb(f"使用者 [{name}] 密碼已清除")

    # ═══════════════════════════════════════════════════════════
    # Post actions
    # ═══════════════════════════════════════════════════════════
    def _post_del(self):
        sel = self._posts_tv.selection()
        if not sel:
            messagebox.showinfo("提示", "請先選擇文章", parent=self.root); return
        pid   = int(sel[0])
        title = self._posts_tv.item(sel[0])["values"][2]
        if messagebox.askyesno("確認刪除",
                               f"刪除文章 #{pid}？\n《{title}》\n\n含所有回覆一併刪除。",
                               icon="warning", parent=self.root):
            self._bbs.delete_post(pid)
            self._refresh_posts()
            self._refresh_boards()
            self._sb(f"文章 #{pid} 已刪除")

    def _on_post_dblclick(self, event):
        """雙擊文章列 → 開啟回覆管理對話框。"""
        sel = self._posts_tv.identify_row(event.y)
        if not sel:
            return
        self._posts_tv.selection_set(sel)
        pid   = int(sel)
        title = self._posts_tv.item(sel)["values"][2]
        self._manage_replies(pid, title)

    def _manage_replies(self, post_id: int, post_title: str):
        """彈出視窗，顯示並管理指定文章的所有回覆。"""
        if not self._bbs:
            return
        dlg = tk.Toplevel(self.root)
        dlg.title(f"回覆管理 ── #{post_id} {post_title[:40]}")
        dlg.resizable(True, True)
        dlg.minsize(640, 320)
        _center_on_parent(dlg, self.root)
        dlg.grab_set()

        # Treeview
        frame = ttk.Frame(dlg, padding=8)
        frame.pack(fill="both", expand=True)
        cols = ("回覆ID", "作者", "內容", "時間")
        tv = ttk.Treeview(frame, columns=cols, show="headings", selectmode="browse")
        ws = {"回覆ID": 65, "作者": 100, "內容": 0, "時間": 130}
        for c in cols:
            tv.heading(c, text=c)
            tv.column(c, width=ws[c], stretch=(c == "內容"))
        sb = ttk.Scrollbar(frame, command=tv.yview)
        tv.configure(yscrollcommand=sb.set)
        tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

        def _load():
            for i in tv.get_children():
                tv.delete(i)
            replies = self._bbs.get_post_replies(post_id)
            if not replies:
                tv.insert("", "end", values=("", "", "（此文章尚無回覆）", ""))
            for r in replies:
                body_preview = r["body"].replace("\n", " ")[:80]
                tv.insert("", "end", iid=str(r["id"]), values=(
                    r["id"], r["author"], body_preview, r["created_at"]
                ))

        def _del_reply():
            sel2 = tv.selection()
            if not sel2:
                messagebox.showinfo("提示", "請先選擇要刪除的回覆", parent=dlg)
                return
            rid = int(sel2[0])
            if messagebox.askyesno("確認刪除", f"確定刪除回覆 #{rid}？",
                                   icon="warning", parent=dlg):
                self._bbs.admin_delete_reply(rid)
                _load()
                self._refresh_posts()
                self._sb(f"回覆 #{rid} 已刪除")

        # Button bar
        btn_f = ttk.Frame(dlg, padding=(8, 0, 8, 8))
        btn_f.pack(fill="x")
        ttk.Button(btn_f, text="✕  刪除選中回覆", command=_del_reply).pack(side="left")
        ttk.Button(btn_f, text="↺  重新整理",     command=_load).pack(side="left", padx=(4, 0))
        ttk.Button(btn_f, text="關閉",            command=dlg.destroy).pack(side="right")

        _load()

    # ═══════════════════════════════════════════════════════════
    # Mesh actions
    # ═══════════════════════════════════════════════════════════
    def _mesh_broadcast(self):
        text = self._v_bc_msg.get().strip()
        if not text:
            return
        if not self._mesh:
            messagebox.showwarning("提示", "伺服器未啟動", parent=self.root); return
        ok = self._mesh.send(text)
        self._bbs.save_mesh_message("ADMIN", "ADMIN", text)
        if self._srv and self._loop:
            asyncio.run_coroutine_threadsafe(
                self._srv.broadcast({
                    "type":    "mesh_message",
                    "from":    "ADMIN",
                    "from_id": "ADMIN",
                    "text":    text,
                    "time":    __import__("datetime").datetime.now().strftime("%H:%M:%S"),
                }), self._loop)
        self._v_bc_msg.set("")
        self._refresh_mesh_msgs()
        self._sb(f"Mesh 廣播：{text}  ({'已發送' if ok else '硬體未連線，僅廣播至 WS'})")

    # ═══════════════════════════════════════════════════════════
    # Log viewer
    # ═══════════════════════════════════════════════════════════
    def _append_logs(self):
        new = LOG_BUFFER[self._last_log:]
        if not new:
            return
        self._last_log = len(LOG_BUFFER)
        self._log_box.configure(state="normal")
        for e in new:
            self._log_box.insert("end", f"[{e['time']}] ", "time")
            self._log_box.insert("end", f"[{e['level']}] {e['msg']}\n", e["level"])
        if self._v_autoscroll.get():
            self._log_box.see("end")
        self._log_box.configure(state="disabled")

    def _logs_clear(self):
        self._log_box.configure(state="normal")
        self._log_box.delete("1.0", "end")
        self._log_box.configure(state="disabled")
        self._last_log = len(LOG_BUFFER)

    # ═══════════════════════════════════════════════════════════
    # Utilities
    # ═══════════════════════════════════════════════════════════
    def _open_browser(self, _e=None):
        import webbrowser
        webbrowser.open(f"http://localhost:{self._http_port}")

    def _sb(self, msg: str):
        self._sb_text.set(msg)

    def _tv_sort(self, tv: ttk.Treeview, col: str):
        """Toggle-sort a Treeview by column."""
        data = [(tv.set(k, col), k) for k in tv.get_children("")]
        try:
            data.sort(key=lambda x: int(x[0]))
        except ValueError:
            data.sort()
        for idx, (_, k) in enumerate(data):
            tv.move(k, "", idx)

    def _on_close(self):
        if self._running:
            if not messagebox.askyesno("確認關閉",
                                        "伺服器仍在執行中，確定要關閉？",
                                        parent=self.root):
                return
            self._stop_server()
        self.root.destroy()

    # ═══════════════════════════════════════════════════════════
    # Entry point
    # ═══════════════════════════════════════════════════════════
    def run(self):
        self.root.mainloop()


# ── Main ──────────────────────────────────────────────────────
if __name__ == "__main__":
    app = BBSConsole()
    app.run()
