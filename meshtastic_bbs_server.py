"""
Meshtastic PTT-風格 BBS 伺服器 v2.0
=====================================
安裝依賴：
    pip install meshtastic aiohttp pypubsub

啟動：
    python meshtastic_bbs_server.py --port COM3
    python meshtastic_bbs_server.py --port /dev/ttyUSB0 --admin-password secret

瀏覽器開啟：
    http://localhost:8765          → BBS 客戶端
    http://localhost:8765/admin    → 後台管理介面
"""

import asyncio
import json
import sqlite3
import logging
import argparse
import threading
import time
import uuid
import sys
import hashlib
import base64
import zlib
from datetime import datetime
from pathlib import Path

try:
    from aiohttp import web
    import aiohttp
except ImportError:
    print("請安裝：pip install aiohttp")
    exit(1)

try:
    import meshtastic
    import meshtastic.serial_interface
    try:
        from meshtastic.protobuf import portnums_pb2
    except ImportError:
        portnums_pb2 = None
    from pubsub import pub
    MESH_AVAILABLE = True
except ImportError:
    print("[警告] meshtastic 套件未安裝，以模擬模式運行")
    MESH_AVAILABLE = False

MESH_TCP_AVAILABLE = False
BBS_PRIVATE_PREFIX = b"MBBS1"
BBS_BINARY_PREFIX = b"MBBS2|"
PRIVATE_APP_PORTNUM = getattr(
    getattr(globals().get("portnums_pb2", None), "PortNum", object),
    "PRIVATE_APP",
    256,
)


def _json_dumps(data) -> str:
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def _bytes_payload(value):
    if value is None:
        return b""
    if isinstance(value, bytes):
        return value
    if isinstance(value, bytearray):
        return bytes(value)
    if isinstance(value, list):
        try:
            return bytes(value)
        except Exception:
            return b""
    if isinstance(value, str):
        return value.encode("latin1", errors="ignore")
    return b""


def _decode_bbs_private(decoded: dict) -> str:
    payload = _bytes_payload(decoded.get("payload"))
    if payload.startswith(BBS_PRIVATE_PREFIX):
        return payload[len(BBS_PRIVATE_PREFIX):].decode("utf-8", errors="ignore").strip()
    return ""


def _res_dir() -> Path:
    """打包資源的基底目錄（_MEIPASS 或原始碼目錄）。"""
    if getattr(sys, 'frozen', False):
        return Path(sys._MEIPASS)
    return Path(__file__).parent

def _data_dir() -> Path:
    """執行期資料目錄（bbs.db 等，永遠與 exe 同一層）。"""
    if getattr(sys, 'frozen', False):
        return Path(sys.executable).parent
    return Path(__file__).parent

def _find_res(filename: str) -> Path:
    """尋找資源檔：frozen 模式下優先查 exe 旁邊（允許不重新打包即更新 HTML），再 fallback 到 _MEIPASS。"""
    if getattr(sys, 'frozen', False):
        override = Path(sys.executable).parent / filename
        if override.exists():
            return override
    return _res_dir() / filename

BASE_DIR = _res_dir()
DB_FILE  = str(_data_dir() / "bbs.db")

# ── Log buffer (for admin panel) ──────────────────────────────
LOG_BUFFER: list = []

class _BufHandler(logging.Handler):
    def emit(self, record):
        LOG_BUFFER.append({
            "time":  datetime.now().strftime("%H:%M:%S"),
            "level": record.levelname,
            "msg":   record.getMessage(),
        })
        if len(LOG_BUFFER) > 500:
            LOG_BUFFER.pop(0)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("MeshBBS")
_bh = _BufHandler()
_bh.setFormatter(logging.Formatter("%(message)s"))
log.addHandler(_bh)

# ── Admin auth ─────────────────────────────────────────────────
_admin_token:    str = ""
_admin_password: str = "admin"

def _hash_password(pwd: str) -> str:
    return hashlib.sha256(pwd.encode("utf-8")).hexdigest()

def _new_token() -> str:
    global _admin_token
    _admin_token = str(uuid.uuid4())
    return _admin_token

def _auth_ok(request) -> bool:
    return bool(_admin_token) and \
           request.headers.get("X-Admin-Token") == _admin_token


# ═══════════════════════════════════════════════════════════════
# 資料庫
# ═══════════════════════════════════════════════════════════════
def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.executescript("""
        CREATE TABLE IF NOT EXISTS boards (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            name         TEXT UNIQUE NOT NULL,
            title        TEXT NOT NULL,
            moderator    TEXT DEFAULT 'SYSOP',
            moderator_id TEXT DEFAULT '',
            post_count   INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS posts (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            board       TEXT NOT NULL,
            author_id   TEXT NOT NULL,
            author      TEXT NOT NULL,
            title       TEXT NOT NULL,
            body        TEXT NOT NULL,
            reply_count INTEGER DEFAULT 0,
            created_at  TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS replies (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id    INTEGER NOT NULL,
            author_id  TEXT NOT NULL,
            author     TEXT NOT NULL,
            body       TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS users (
            node_id    TEXT PRIMARY KEY,
            name       TEXT NOT NULL,
            last_seen  TEXT,
            post_count INTEGER DEFAULT 0,
            online     INTEGER DEFAULT 0,
            banned     INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS mesh_messages (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            from_id    TEXT NOT NULL,
            from_name  TEXT NOT NULL,
            text       TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS admins (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at    TEXT NOT NULL
        );
    """)
    # migrations
    for migration in [
        "ALTER TABLE users   ADD COLUMN banned        INTEGER DEFAULT 0",
        "ALTER TABLE boards  ADD COLUMN moderator_id  TEXT    DEFAULT ''",
        "ALTER TABLE users   ADD COLUMN password_hash TEXT    DEFAULT ''",
        "ALTER TABLE users   ADD COLUMN is_web        INTEGER DEFAULT 0",
        "ALTER TABLE posts   ADD COLUMN push_count    INTEGER DEFAULT 0",
        """CREATE TABLE IF NOT EXISTS post_pushes (
               post_id      INTEGER NOT NULL,
               user_node_id TEXT    NOT NULL,
               PRIMARY KEY (post_id, user_node_id)
           )""",
    ]:
        try:
            c.execute(migration); conn.commit()
        except Exception:
            pass
    # 補標記舊版 WEB- 前綴帳號
    try:
        c.execute("UPDATE users SET is_web=1 WHERE node_id LIKE 'WEB-%' AND is_web=0")
        conn.commit()
    except Exception:
        pass
    # 只在完全沒有管理員帳號時才建立預設 admin
    if c.execute("SELECT COUNT(*) FROM admins").fetchone()[0] == 0:
        c.execute(
            "INSERT INTO admins (username,password_hash,created_at) VALUES (?,?,?)",
            ("admin", _hash_password(_admin_password), datetime.now().isoformat())
        )
    # default boards
    for name, title, mod in [
        ("gossiping", "八卦",            "SYSOP"),
        ("tech",      "技術討論",        "SYSOP"),
        ("mesh",      "Meshtastic 討論", "SYSOP"),
        ("local",     "在地資訊",        "SYSOP"),
        ("emergency", "緊急通報",        "SYSOP"),
    ]:
        c.execute("INSERT OR IGNORE INTO boards (name,title,moderator) VALUES (?,?,?)",
                  (name, title, mod))
    conn.commit()
    conn.close()
    log.info("資料庫初始化完成")


# ═══════════════════════════════════════════════════════════════
# BBS 核心
# ═══════════════════════════════════════════════════════════════
class BBS:
    def __init__(self):
        self.db = DB_FILE

    def _conn(self):
        return sqlite3.connect(self.db, check_same_thread=False)

    # ── 公開 BBS ───────────────────────────────────────────────

    def get_boards(self):
        with self._conn() as c:
            rows = c.execute("""
                SELECT b.name, b.title, b.moderator, b.moderator_id,
                       COUNT(p.id) AS post_count
                FROM boards b
                LEFT JOIN posts p ON p.board = b.name
                GROUP BY b.id
                ORDER BY b.id
            """).fetchall()
        return [{"name": r[0], "title": r[1], "moderator": r[2],
                 "moderator_id": r[3] or "", "post_count": r[4]}
                for r in rows]

    def get_posts(self, board, page=1, per_page=20, user_node_id=""):
        off = (page - 1) * per_page
        with self._conn() as c:
            if not c.execute("SELECT 1 FROM boards WHERE name=?", (board,)).fetchone():
                return None, 0
            total = c.execute("SELECT COUNT(*) FROM posts WHERE board=?", (board,)).fetchone()[0]
            rows = c.execute(
                "SELECT p.id, p.author_id, p.author, p.title, p.reply_count, p.push_count, p.created_at,"
                " CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END"
                " FROM posts p"
                " LEFT JOIN post_pushes pp ON pp.post_id=p.id AND pp.user_node_id=?"
                " WHERE p.board=? ORDER BY p.id DESC LIMIT ? OFFSET ?",
                (user_node_id, board, per_page, off)
            ).fetchall()
        return ([{"id": r[0], "author_id": r[1], "author": r[2], "title": r[3],
                  "reply_count": r[4], "push_count": r[5], "created_at": r[6], "pushed": bool(r[7])}
                 for r in rows], total)

    def toggle_push(self, post_id: int, user_node_id: str):
        """推薦/取消推薦（toggle）；每帳號每篇限一個狀態。"""
        with self._conn() as c:
            existing = c.execute(
                "SELECT 1 FROM post_pushes WHERE post_id=? AND user_node_id=?",
                (post_id, user_node_id)
            ).fetchone()
            if existing:
                c.execute("DELETE FROM post_pushes WHERE post_id=? AND user_node_id=?",
                          (post_id, user_node_id))
                c.execute("UPDATE posts SET push_count=MAX(0,push_count-1) WHERE id=?", (post_id,))
                pushed = False
            else:
                c.execute("INSERT INTO post_pushes (post_id,user_node_id) VALUES (?,?)",
                          (post_id, user_node_id))
                c.execute("UPDATE posts SET push_count=push_count+1 WHERE id=?", (post_id,))
                pushed = True
            row = c.execute("SELECT push_count FROM posts WHERE id=?", (post_id,)).fetchone()
        return (row[0] if row else 0), pushed

    def search_posts(self, keyword: str, field: str, board: str = None, user_node_id: str = ""):
        like = f"%{keyword}%"
        col  = "p.author" if field == "author" else "p.title"
        with self._conn() as c:
            if board:
                rows = c.execute(
                    f"SELECT p.id, p.board, p.author, p.title, p.reply_count, p.push_count,"
                    f" p.created_at,"
                    f" CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END"
                    f" FROM posts p"
                    f" LEFT JOIN post_pushes pp ON pp.post_id=p.id AND pp.user_node_id=?"
                    f" WHERE p.board=? AND {col} LIKE ?"
                    f" ORDER BY p.id DESC LIMIT 100",
                    (user_node_id, board, like)
                ).fetchall()
            else:
                rows = c.execute(
                    f"SELECT p.id, p.board, p.author, p.title, p.reply_count, p.push_count,"
                    f" p.created_at,"
                    f" CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END"
                    f" FROM posts p"
                    f" LEFT JOIN post_pushes pp ON pp.post_id=p.id AND pp.user_node_id=?"
                    f" WHERE {col} LIKE ?"
                    f" ORDER BY p.id DESC LIMIT 100",
                    (user_node_id, like)
                ).fetchall()
        return [{"id": r[0], "board": r[1], "author": r[2], "title": r[3],
                 "reply_count": r[4], "push_count": r[5], "created_at": r[6],
                 "pushed": bool(r[7])} for r in rows]

    def get_post(self, post_id, user_node_id=""):
        with self._conn() as c:
            p = c.execute(
                "SELECT p.id,p.board,p.author,p.title,p.body,p.reply_count,p.created_at,p.author_id,"
                " p.push_count, CASE WHEN pp.user_node_id IS NULL THEN 0 ELSE 1 END"
                " FROM posts p"
                " LEFT JOIN post_pushes pp ON pp.post_id=p.id AND pp.user_node_id=?"
                " WHERE p.id=?",
                (user_node_id, post_id)
            ).fetchone()
            if not p:
                return None, []
            rr = c.execute(
                "SELECT id,author_id,author,body,created_at FROM replies WHERE post_id=? ORDER BY id",
                (post_id,)
            ).fetchall()
        post = {"id": p[0], "board": p[1], "author": p[2], "title": p[3],
                "body": p[4], "reply_count": p[5], "created_at": p[6],
                "author_id": p[7], "push_count": p[8], "pushed": bool(p[9])}
        return post, [{"id": r[0], "author_id": r[1], "author": r[2],
                       "body": r[3], "created_at": r[4]} for r in rr]

    def delete_reply(self, reply_id: int, author_id: str):
        with self._conn() as c:
            row = c.execute(
                "SELECT post_id, author_id FROM replies WHERE id=?", (reply_id,)
            ).fetchone()
            if not row:
                return False, "找不到回覆"
            if row[1] != author_id:
                return False, "無權限刪除此回覆"
            c.execute("DELETE FROM replies WHERE id=?", (reply_id,))
            c.execute("UPDATE posts SET reply_count=MAX(0,reply_count-1) WHERE id=?", (row[0],))
        return True, None

    def update_reply(self, reply_id: int, author_id: str, body: str):
        with self._conn() as c:
            row = c.execute(
                "SELECT author_id FROM replies WHERE id=?", (reply_id,)
            ).fetchone()
            if not row:
                return False, "找不到回覆"
            if row[0] != author_id:
                return False, "無權限編輯此回覆"
            c.execute("UPDATE replies SET body=? WHERE id=?", (body, reply_id))
        return True, None

    def admin_delete_reply(self, reply_id: int):
        """管理員刪除回覆（不限作者）。"""
        with self._conn() as c:
            row = c.execute(
                "SELECT post_id FROM replies WHERE id=?", (reply_id,)
            ).fetchone()
            if not row:
                return False
            c.execute("DELETE FROM replies WHERE id=?", (reply_id,))
            c.execute("UPDATE posts SET reply_count=MAX(0,reply_count-1) WHERE id=?", (row[0],))
        return True

    def get_post_replies(self, post_id: int):
        """取得指定文章的所有回覆（管理介面用）。"""
        with self._conn() as c:
            rows = c.execute(
                "SELECT id, author, body, created_at FROM replies WHERE post_id=? ORDER BY id",
                (post_id,)
            ).fetchall()
        return [{"id": r[0], "author": r[1],
                 "body": r[2], "created_at": r[3]} for r in rows]

    def update_post(self, post_id, author_id, title, body):
        with self._conn() as c:
            p = c.execute("SELECT author_id FROM posts WHERE id=?", (post_id,)).fetchone()
            if not p:
                return False, "找不到文章"
            if p[0] != author_id:
                return False, "無權限編輯此文章"
            c.execute("UPDATE posts SET title=?,body=? WHERE id=?", (title, body, post_id))
        return True, None

    def admin_update_post(self, post_id: int, title: str, body: str):
        with self._conn() as c:
            if not c.execute("SELECT 1 FROM posts WHERE id=?", (post_id,)).fetchone():
                return False, "找不到文章"
            c.execute("UPDATE posts SET title=?,body=? WHERE id=?", (title, body, post_id))
        return True, None

    def is_admin_hash(self, username: str, pw_hash: str) -> bool:
        with self._conn() as c:
            row = c.execute("SELECT password_hash FROM admins WHERE username=?", (username,)).fetchone()
        if row and row[0] == pw_hash:
            return True
        return username == "admin" and pw_hash == _hash_password(_admin_password)

    def mod_update_post(self, post_id: int, mod_id: str, title: str, body: str):
        with self._conn() as c:
            p = c.execute("SELECT board FROM posts WHERE id=?", (post_id,)).fetchone()
            if not p:
                return False, "找不到文章"
            b = c.execute("SELECT moderator_id FROM boards WHERE name=?", (p[0],)).fetchone()
            if not b or b[0] != mod_id:
                return False, "您不是此看板版主，無法編輯"
            c.execute("UPDATE posts SET title=?,body=? WHERE id=?", (title, body, post_id))
        return True, None

    def create_post(self, board, author_id, author, title, body):
        with self._conn() as c:
            row = c.execute("SELECT banned FROM users WHERE node_id=?", (author_id,)).fetchone()
            if row and row[0]:
                return None, "帳號已被封禁"
            if not c.execute("SELECT 1 FROM boards WHERE name=?", (board,)).fetchone():
                return None, f"找不到看板 {board}"
            c.execute(
                "INSERT INTO posts (board,author_id,author,title,body,created_at) VALUES (?,?,?,?,?,?)",
                (board, author_id, author, title, body, datetime.now().strftime("%Y/%m/%d %H:%M"))
            )
            pid = c.execute("SELECT last_insert_rowid()").fetchone()[0]
            c.execute("UPDATE boards SET post_count=post_count+1 WHERE name=?", (board,))
            c.execute("UPDATE users SET post_count=post_count+1 WHERE node_id=?", (author_id,))
        return pid, None

    def create_reply(self, post_id, author_id, author, body):
        with self._conn() as c:
            row = c.execute("SELECT banned FROM users WHERE node_id=?", (author_id,)).fetchone()
            if row and row[0]:
                return None, "帳號已被封禁"
            if not c.execute("SELECT 1 FROM posts WHERE id=?", (post_id,)).fetchone():
                return None, "找不到文章"
            c.execute(
                "INSERT INTO replies (post_id,author_id,author,body,created_at) VALUES (?,?,?,?,?)",
                (post_id, author_id, author, body, datetime.now().strftime("%Y/%m/%d %H:%M"))
            )
            rid = c.execute("SELECT last_insert_rowid()").fetchone()[0]
            c.execute("UPDATE posts SET reply_count=reply_count+1 WHERE id=?", (post_id,))
        return rid, None

    def register_user(self, name: str, password: str):
        """建立新網頁使用者帳號，回傳 (node_id, error)。node_id 即 name。"""
        if not name or not password:
            return None, "帳號和密碼不能為空"
        node_id = name  # 網頁帳號直接用使用者名稱作為 node_id
        with self._conn() as c:
            if c.execute("SELECT 1 FROM users WHERE node_id=?", (node_id,)).fetchone():
                return None, "此帳號名稱已被使用"
            c.execute(
                "INSERT INTO users (node_id,name,last_seen,online,password_hash,is_web)"
                " VALUES (?,?,?,0,?,1)",
                (node_id, name, datetime.now().isoformat(), _hash_password(password))
            )
        log.info("新使用者註冊: %s", name)
        return node_id, None

    def mesh_login(self, name: str, pw_hash: str, node_id: str):
        """Mesh 客戶端帳密登入。
        - 帳號不存在 → 自動建立（回傳 is_new=True）
        - 帳號存在且密碼正確 → 登入成功，更新裝置 node_id
        - 帳號存在且密碼錯誤 → 回傳錯誤
        回傳 (node_id, is_new, error, kicked_id)
        kicked_id: 被擠下線的舊裝置 node_id（None 表示無）
        """
        if not name or not pw_hash:
            return None, False, "帳號和密碼不能為空", None
        with self._conn() as c:
            row = c.execute(
                "SELECT node_id, password_hash, banned FROM users WHERE name=?", (name,)
            ).fetchone()
            if not row:
                # 檢查此 node_id 是否已被自動註冊（例如曾傳過聊天訊息）
                existing = c.execute(
                    "SELECT 1 FROM users WHERE node_id=?", (node_id,)
                ).fetchone()
                if existing:
                    c.execute(
                        "UPDATE users SET name=?, last_seen=?, online=1,"
                        " password_hash=?, is_web=0 WHERE node_id=?",
                        (name, datetime.now().isoformat(), pw_hash, node_id)
                    )
                else:
                    c.execute(
                        "INSERT INTO users (node_id,name,last_seen,online,password_hash,is_web)"
                        " VALUES (?,?,?,1,?,0)",
                        (node_id, name, datetime.now().isoformat(), pw_hash)
                    )
                return node_id, True, None, None
            stored_node_id, stored_pw_hash, banned = row
            if banned:
                return None, False, "此帳號已被封禁", None
            if stored_pw_hash and stored_pw_hash != pw_hash:
                return None, False, "密碼錯誤", None
            # 密碼正確 → 檢查舊裝置是否在線（需踢出）
            kicked_id = None
            if node_id != stored_node_id:
                old_online = c.execute(
                    "SELECT online FROM users WHERE node_id=?", (stored_node_id,)
                ).fetchone()
                if old_online and old_online[0]:
                    kicked_id = stored_node_id
                # 若新 node_id 已被自動建立為暫存帳號，先刪除以避免 UNIQUE 衝突
                c.execute(
                    "DELETE FROM users WHERE node_id=? AND password_hash=''",
                    (node_id,)
                )
            c.execute(
                "UPDATE users SET node_id=?, last_seen=?, online=1, password_hash=?"
                " WHERE node_id=?",
                (node_id, datetime.now().isoformat(), pw_hash, stored_node_id)
            )
            return node_id, False, None, kicked_id

    def verify_user_login(self, name: str, password: str):
        """驗證使用者登入，回傳 (node_id, error)。"""
        with self._conn() as c:
            row = c.execute(
                "SELECT node_id, password_hash, banned FROM users WHERE name=?", (name,)
            ).fetchone()
        if not row:
            return None, "帳號不存在，請先申請帳號"
        node_id, pw_hash, banned = row
        if banned:
            return None, "此帳號已被封禁"
        if pw_hash and pw_hash != _hash_password(password):
            return None, "密碼錯誤"
        return node_id, None

    def upsert_user(self, node_id, name, online=True):
        with self._conn() as c:
            c.execute(
                """INSERT INTO users (node_id,name,last_seen,online,is_web) VALUES (?,?,?,?,0)
                   ON CONFLICT(node_id) DO UPDATE SET
                   name = CASE WHEN users.password_hash = '' OR users.password_hash IS NULL
                               THEN excluded.name ELSE users.name END,
                   last_seen = excluded.last_seen,
                   online    = excluded.online""",
                (node_id, name, datetime.now().isoformat(), 1 if online else 0)
            )

    def get_online_users(self):
        with self._conn() as c:
            rows = c.execute(
                "SELECT node_id,name,post_count FROM users WHERE online=1 AND banned=0"
            ).fetchall()
        return [{"node_id": r[0], "name": r[1], "post_count": r[2]} for r in rows]

    def set_all_offline(self):
        with self._conn() as c:
            c.execute("UPDATE users SET online=0")

    def save_mesh_message(self, from_id, from_name, text):
        with self._conn() as c:
            c.execute(
                "INSERT INTO mesh_messages (from_id,from_name,text,created_at) VALUES (?,?,?,?)",
                (from_id, from_name, text, datetime.now().strftime("%Y/%m/%d %H:%M:%S"))
            )

    # ── Admin ──────────────────────────────────────────────────

    def get_stats(self):
        with self._conn() as c:
            return {
                "posts":    c.execute("SELECT COUNT(*) FROM posts").fetchone()[0],
                "replies":  c.execute("SELECT COUNT(*) FROM replies").fetchone()[0],
                "users":    c.execute("SELECT COUNT(*) FROM users").fetchone()[0],
                "online":   c.execute("SELECT COUNT(*) FROM users WHERE online=1").fetchone()[0],
                "boards":   c.execute("SELECT COUNT(*) FROM boards").fetchone()[0],
                "mesh_msg": c.execute("SELECT COUNT(*) FROM mesh_messages").fetchone()[0],
            }

    def get_all_posts(self, page=1, per_page=30, board=None, search=None):
        off = (page - 1) * per_page
        where, params = [], []
        if board:
            where.append("board=?"); params.append(board)
        if search:
            where.append("(title LIKE ? OR author LIKE ?)"); params += [f"%{search}%"] * 2
        ws = ("WHERE " + " AND ".join(where)) if where else ""
        with self._conn() as c:
            total = c.execute(f"SELECT COUNT(*) FROM posts {ws}", params).fetchone()[0]
            rows  = c.execute(
                f"SELECT id,board,author,title,reply_count,created_at FROM posts "
                f"{ws} ORDER BY id DESC LIMIT ? OFFSET ?",
                params + [per_page, off]
            ).fetchall()
        return ([{"id": r[0], "board": r[1], "author": r[2], "title": r[3],
                  "reply_count": r[4], "created_at": r[5]} for r in rows], total)

    def delete_post(self, post_id):
        with self._conn() as c:
            p = c.execute("SELECT board,author_id FROM posts WHERE id=?", (post_id,)).fetchone()
            if not p:
                return False
            board, author_id = p
            c.execute("DELETE FROM replies WHERE post_id=?", (post_id,))
            c.execute("DELETE FROM posts WHERE id=?", (post_id,))
            c.execute("UPDATE boards SET post_count=MAX(0,post_count-1) WHERE name=?", (board,))
            if author_id:
                c.execute("UPDATE users SET post_count=MAX(0,post_count-1) WHERE node_id=?", (author_id,))
        return True

    def get_all_users(self, page=1, per_page=50):
        off = (page - 1) * per_page
        with self._conn() as c:
            total = c.execute("SELECT COUNT(*) FROM users").fetchone()[0]
            rows  = c.execute(
                "SELECT node_id,name,last_seen,post_count,online,banned,password_hash,is_web FROM users "
                "ORDER BY last_seen DESC LIMIT ? OFFSET ?",
                (per_page, off)
            ).fetchall()
        return ([{"node_id": r[0], "name": r[1], "last_seen": r[2],
                  "post_count": r[3], "online": bool(r[4]), "banned": bool(r[5]),
                  "has_password": bool(r[6]), "is_web": bool(r[7])}
                 for r in rows], total)

    def reset_user_password(self, node_id: str, new_password: str):
        """重設使用者密碼（僅限網頁帳號）。"""
        with self._conn() as c:
            cur = c.execute(
                "UPDATE users SET password_hash=? WHERE node_id=?",
                (_hash_password(new_password), node_id)
            )
            return cur.rowcount > 0

    def clear_user_password(self, node_id: str):
        """清除密碼（轉為 Mesh 裝置帳號模式）。"""
        with self._conn() as c:
            c.execute("UPDATE users SET password_hash='' WHERE node_id=?", (node_id,))

    def ban_user(self, node_id, banned=True):
        with self._conn() as c:
            c.execute("UPDATE users SET banned=? WHERE node_id=?",
                      (1 if banned else 0, node_id))
            return c.rowcount > 0

    def delete_user(self, node_id):
        with self._conn() as c:
            cur = c.execute("DELETE FROM users WHERE node_id=?", (node_id,))
            return cur.rowcount > 0

    def create_board(self, name, title, moderator="SYSOP"):
        with self._conn() as c:
            try:
                c.execute("INSERT INTO boards (name,title,moderator) VALUES (?,?,?)",
                          (name, title, moderator))
                return True, None
            except sqlite3.IntegrityError:
                return False, "看板代碼已存在"

    def update_board(self, name, title, moderator, moderator_id=""):
        with self._conn() as c:
            cur = c.execute(
                "UPDATE boards SET title=?,moderator=?,moderator_id=? WHERE name=?",
                (title, moderator, moderator_id, name)
            )
            return cur.rowcount > 0

    def delete_board(self, name):
        with self._conn() as c:
            ids = [r[0] for r in
                   c.execute("SELECT id FROM posts WHERE board=?", (name,)).fetchall()]
            for pid in ids:
                c.execute("DELETE FROM replies WHERE post_id=?", (pid,))
            c.execute("DELETE FROM posts WHERE board=?", (name,))
            c.execute("DELETE FROM boards WHERE name=?", (name,))

    def set_board_moderator(self, board_name: str, mod_id: str, mod_name: str):
        with self._conn() as c:
            cur = c.execute(
                "UPDATE boards SET moderator=?, moderator_id=? WHERE name=?",
                (mod_name, mod_id, board_name)
            )
            return cur.rowcount > 0

    def get_board_moderator_id(self, board_name: str) -> str:
        with self._conn() as c:
            row = c.execute(
                "SELECT moderator_id FROM boards WHERE name=?", (board_name,)
            ).fetchone()
        return row[0] if row else ""

    # ── Admin accounts ─────────────────────────────────────────

    def get_admins(self):
        with self._conn() as c:
            rows = c.execute(
                "SELECT id,username,created_at FROM admins ORDER BY id"
            ).fetchall()
        return [{"id": r[0], "username": r[1], "created_at": r[2]} for r in rows]

    def create_admin(self, username: str, password: str):
        with self._conn() as c:
            try:
                c.execute(
                    "INSERT INTO admins (username,password_hash,created_at) VALUES (?,?,?)",
                    (username, _hash_password(password), datetime.now().isoformat())
                )
                return True, None
            except sqlite3.IntegrityError:
                return False, "帳號名稱已存在"

    def delete_admin(self, admin_id: int):
        with self._conn() as c:
            cur = c.execute("DELETE FROM admins WHERE id=?", (admin_id,))
            return cur.rowcount > 0

    def change_admin_password(self, admin_id: int, new_password: str):
        with self._conn() as c:
            cur = c.execute(
                "UPDATE admins SET password_hash=? WHERE id=?",
                (_hash_password(new_password), admin_id)
            )
            return cur.rowcount > 0

    def check_admin(self, username: str, password: str) -> bool:
        with self._conn() as c:
            row = c.execute(
                "SELECT password_hash FROM admins WHERE username=?", (username,)
            ).fetchone()
        if row:
            return row[0] == _hash_password(password)
        return username == "admin" and password == _admin_password

    def get_mesh_messages(self, limit=100):
        with self._conn() as c:
            rows = c.execute(
                "SELECT id,from_id,from_name,text,created_at FROM mesh_messages "
                "ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [{"id": r[0], "from_id": r[1], "from_name": r[2],
                 "text": r[3], "created_at": r[4]} for r in reversed(rows)]

    def clear_mesh_messages(self):
        with self._conn() as c:
            c.execute("DELETE FROM mesh_messages")

    def get_recent_activity(self, limit=15):
        with self._conn() as c:
            rows = c.execute(
                "SELECT id,board,author,title,created_at FROM posts ORDER BY id DESC LIMIT ?",
                (limit,)
            ).fetchall()
        return [{"id": r[0], "board": r[1], "author": r[2],
                 "title": r[3], "time": r[4]} for r in rows]


# ═══════════════════════════════════════════════════════════════
# Meshtastic 介面
# ═══════════════════════════════════════════════════════════════
class MeshInterface:
    def __init__(self, port, bbs: BBS, broadcast_fn):
        self.port       = port
        self.host       = None
        self.tcp_port   = 4403
        self.bbs        = bbs
        self.broadcast  = broadcast_fn
        self.iface      = None
        self.loop       = None
        self.connected  = False
        self.local_node_id   = None
        self.local_node_name = "BBS-SERVER"
        self._running   = False
        self._subscribed = False
        self._seen_pkt_ids: set = set()
        self.kick_ws_fn = None  # 設定後可踢出 WebSocket 舊連線
        self._relay_clients: set = set()  # 目前以 PC relay client 登入的 node_id
        self._admin_clients: set = set()  # 以管理員帳號登入的 relay client

    def start(self, loop: asyncio.AbstractEventLoop):
        self.loop     = loop
        self._running = True
        if not MESH_AVAILABLE:
            log.warning("meshtastic 未安裝，跳過硬體連線")
            return
        # 訂閱 pubsub 只做一次
        if not self._subscribed:
            pub.subscribe(self._on_receive,    "meshtastic.receive")
            pub.subscribe(self._on_receive,    "meshtastic.receive.text")
            pub.subscribe(self._on_receive,    "meshtastic.receive.private_app")
            pub.subscribe(self._on_connect,    "meshtastic.connection.established")
            pub.subscribe(self._on_disconnect, "meshtastic.connection.lost")
            self._subscribed = True
        # 不立即連線，由 _reconnect_loop 在短暫延遲後處理
        # 避免重啟時 COM port 尚未被 OS 釋放而導致連線失敗
        t = threading.Thread(target=self._reconnect_loop, daemon=True, name="mesh-reconnect")
        t.start()

    def _try_connect(self):
        """嘗試建立 Meshtastic 連線。--host 指定時用 TCP，否則依序試各序列埠。"""
        # TCP 連線（Raspberry Pi 透過網路連接 Meshtastic 裝置時使用）
        if False:
            if not MESH_TCP_AVAILABLE:
                log.error("meshtastic.tcp_interface 無法載入，請升級 meshtastic 套件")
                self.connected = False
                return
            try:
                if self.iface:
                    try: self.iface.close()
                    except Exception: pass
                    self.iface = None
                log.info("透過 TCP 連接 Meshtastic：%s:%d", self.host, self.tcp_port)
                self.iface = meshtastic.tcp_interface.TCPInterface(
                    self.host, portNumber=self.tcp_port)
                info = self.iface.getMyNodeInfo()
                self.local_node_id   = str(info.get("num", ""))
                user = info.get("user", {})
                self.local_node_name = user.get("longName", "BBS-SERVER")
                self.bbs.upsert_user(self.local_node_id, self.local_node_name, True)
                self.connected = True
                log.info("Meshtastic TCP 已連線：%s (%s) [%s:%d]",
                         self.local_node_name, self.local_node_id,
                         self.host, self.tcp_port)
                return
            except Exception as e:
                log.warning("TCP 連線失敗 [%s:%d]: %s", self.host, self.tcp_port, e)
            self.connected = False
            return

        # 序列埠連線：指定 port → Linux 常見裝置路徑 → 自動掃描
        candidates = []
        if self.port:
            candidates.append(self.port)
        if False:
            # Raspberry Pi / Ubuntu 常見序列埠（USB-to-Serial 及 CDC-ACM 裝置）
            for p in ("/dev/ttyUSB0", "/dev/ttyACM0"):
                if p not in candidates:
                    candidates.append(p)
        # PC版固定使用指定 COM port，不自動掃描或嘗試 Linux/Raspberry Pi 裝置路徑。

        for port in candidates:
            try:
                if self.iface:
                    try:
                        self.iface.close()
                    except Exception:
                        pass
                    self.iface = None
                label = port or "自動偵測"
                log.info("連接 Meshtastic：%s", label)
                self.iface = meshtastic.serial_interface.SerialInterface(port)
                info = self.iface.getMyNodeInfo()
                self.local_node_id   = str(info.get("num", ""))
                user = info.get("user", {})
                self.local_node_name = user.get("longName", "BBS-SERVER")
                self.bbs.upsert_user(self.local_node_id, self.local_node_name, True)
                self.connected = True
                detected = getattr(self.iface, "devPath", None) or port or "auto"
                log.info("Meshtastic 已連線：%s (%s) [%s]",
                         self.local_node_name, self.local_node_id, detected)
                return
            except Exception as e:
                log.warning("連線失敗 [%s]: %s", port or "auto", e)

        self.connected = False

    def _reconnect_loop(self):
        """背景執行緒：啟動後等待 2 秒（給序列埠釋放時間），之後每 5 秒自動重試。"""
        time.sleep(2)
        while self._running:
            if not self.connected:
                log.info("Meshtastic 未連線，嘗試重新連接...")
                self._try_connect()
                if self.connected:
                    self._go(self.broadcast({
                        "type": "mesh_status",
                        "status": "connected",
                        "node": self.local_node_name,
                    }))
            time.sleep(5)

    def stop(self):
        """停止重連執行緒、取消 pubsub 訂閱、關閉串列埠。"""
        self._running = False
        self.connected = False
        self._relay_clients.clear()
        if self._subscribed:
            for topic, handler in [
                ("meshtastic.receive",                self._on_receive),
                ("meshtastic.receive.text",           self._on_receive),
                ("meshtastic.receive.private_app",    self._on_receive),
                ("meshtastic.connection.established", self._on_connect),
                ("meshtastic.connection.lost",        self._on_disconnect),
            ]:
                try:
                    pub.unsubscribe(handler, topic)
                except Exception:
                    pass
            self._subscribed = False
        if self.iface:
            try:
                self.iface.close()
            except Exception:
                pass
            self.iface = None

    def _go(self, coro):
        if self.loop:
            asyncio.run_coroutine_threadsafe(coro, self.loop)

    def _on_connect(self, interface, topic=None):
        self.connected = True
        self._go(self.broadcast({"type": "mesh_status", "status": "connected",
                                  "node": self.local_node_name}))

    def _on_disconnect(self, interface, topic=None):
        self.connected = False
        log.warning("Meshtastic 裝置斷線")
        self._go(self.broadcast({"type": "mesh_status", "status": "disconnected"}))

    def _on_receive(self, packet, interface):
        try:
            pkt_id = packet.get("id", 0)
            if pkt_id:
                if pkt_id in self._seen_pkt_ids:
                    return
                self._seen_pkt_ids.add(pkt_id)
                if len(self._seen_pkt_ids) > 300:
                    self._seen_pkt_ids = set(list(self._seen_pkt_ids)[-150:])
            sid  = str(packet.get("fromId", "unknown"))
            snum = packet.get("from", 0)
            decoded = packet.get("decoded", {})
            text = (decoded.get("text") or "").strip()
            if not text:
                text = _decode_bbs_private(decoded)
            name = self._node_name(interface, snum)
            if not text:
                return

            # BBS relay client command (JSON protocol)
            if text.startswith("BBS:REQ:"):
                self._handle_bbs_req(sid, name, text)
                return

            # 說明指令
            if text in ("?", "/?"):
                threading.Thread(target=self._send_help,
                                 args=(sid,), daemon=True).start()
                return

            # 純文字指令（手機直接輸入）
            _PLAIN_CMDS = ("LOGIN:", "LOGOUT", "LIST", "POSTS:", "READ:", "POST:",
                           "REPLY:", "PUSH:", "EDIT:", "DEL:", "SEARCH:", "CHAT:")
            if text.upper().startswith(_PLAIN_CMDS):
                log.info("純文字指令偵測 from %s: %s", sid, text[:60])
                threading.Thread(target=self._handle_plain_cmd,
                                 args=(sid, name, text), daemon=True).start()
                return

            self.bbs.upsert_user(sid, name, True)
            self.bbs.save_mesh_message(sid, name, text)
            self._go(self.broadcast({
                "type": "mesh_message",
                "from": name, "from_id": sid,
                "text": text, "time": datetime.now().strftime("%H:%M:%S"),
            }))
        except Exception as e:
            log.error("處理 Mesh 訊息失敗: %s", e)

    def _send_plain_text(self, dest_id: str, text: str):
        """將純文字回應分段傳送給指定節點（每則 ≤ 220 bytes UTF-8）。"""
        log.info("_send_plain_text: dest=%s text=%s", dest_id, text[:40])
        if not self.iface:
            log.warning("_send_plain_text: iface 未就緒，無法傳送給 %s", dest_id)
            return
        MAX = 220
        encoded = text.encode("utf-8")
        chunks: list[str] = []
        start = 0
        while start < len(encoded):
            end = min(start + MAX, len(encoded))
            # 若 end 落在多 byte 字元中間（延續位元 10xxxxxx），往回退到字元邊界
            while end > start and end < len(encoded) and (encoded[end] & 0xC0) == 0x80:
                end -= 1
            if end == start:
                end = start + 1
            chunks.append(encoded[start:end].decode("utf-8", errors="replace"))
            start = end
        for i, chunk in enumerate(chunks):
            try:
                self.iface.sendText(chunk, destinationId=dest_id, wantAck=False)
                log.info("純文字回應 %d/%d (%d bytes) → %s",
                         i + 1, len(chunks), len(chunk.encode("utf-8")), dest_id)
            except Exception as e:
                log.warning("純文字回應傳送失敗: %s", e)
            if i < len(chunks) - 1:
                time.sleep(2.5)

    def _handle_plain_cmd(self, sender_id: str, sender_name: str, text: str):
        """處理手機傳來的純文字指令，回覆人類可讀文字。"""
        try:
            self._do_plain_cmd(sender_id, sender_name, text)
        except Exception as e:
            log.error("純文字指令處理失敗 from %s [%s]: %s", sender_id, text[:40], e)
            try:
                self._send_plain_text(sender_id, f"指令處理錯誤：{e}")
            except Exception:
                pass

    def _do_plain_cmd(self, sender_id: str, sender_name: str, text: str):
        """純文字指令實際處理邏輯。"""
        upper = text.upper()
        bbs = self.bbs

        # LOGIN / LOGOUT 不需驗證，其餘指令需確認此裝置已登入帳號
        if not upper.startswith("LOGIN:") and upper not in ("LOGOUT", "LOGOUT:"):
            with bbs._conn() as _c:
                _row = _c.execute(
                    "SELECT name FROM users WHERE node_id=? AND password_hash!=''",
                    (sender_id,)
                ).fetchone()
            if not _row:
                self._send_plain_text(sender_id, "請先登入。指令：LOGIN:<帳號>:<密碼>")
                return

        # LOGIN:<帳號>:<密碼>
        if upper.startswith("LOGIN:"):
            parts = text.split(":", 2)
            name  = parts[1].strip() if len(parts) > 1 else ""
            pw    = parts[2].strip() if len(parts) > 2 else ""
            if not name:
                name = sender_name
            if not pw:
                self._send_plain_text(sender_id, "格式錯誤，請用：LOGIN:<帳號>:<密碼>")
                return
            pw_hash = _hash_password(pw)
            actual_id, is_new, err, kicked_id = bbs.mesh_login(name, pw_hash, sender_id)
            if err:
                self._send_plain_text(sender_id, f"登入失敗：{err}")
                return
            if kicked_id:
                if kicked_id.startswith("!"):
                    self._send_plain_text(kicked_id, f"帳號 [{name}] 已在另一台裝置登入，您已被登出。")
                if self.kick_ws_fn:
                    self._go(self.kick_ws_fn(kicked_id))
            self._go(self.broadcast({"type": "user_join", "name": name, "node_id": actual_id}))
            if is_new:
                self._send_plain_text(sender_id, f"歡迎加入！帳號 [{name}] 已建立並登入。\n輸入 LIST 查看看板。")
            else:
                self._send_plain_text(sender_id, f"登入成功！歡迎回來，{name}。\n輸入 LIST 查看看板。")
            return

        # LOGOUT
        if upper == "LOGOUT" or upper == "LOGOUT:":
            with bbs._conn() as c:
                row = c.execute(
                    "SELECT name FROM users WHERE node_id=? AND online=1", (sender_id,)
                ).fetchone()
                if not row:
                    self._send_plain_text(sender_id, "您目前尚未登入。")
                    return
                logged_name = row[0]
                c.execute("UPDATE users SET online=0 WHERE node_id=?", (sender_id,))
            self._go(self.broadcast({"type": "user_leave", "name": logged_name, "node_id": sender_id}))
            self._send_plain_text(sender_id, f"已登出。再見，{logged_name}！")
            return

        # LIST
        if upper == "LIST" or upper == "LIST:":
            boards = bbs.get_boards()
            if not boards:
                self._send_plain_text(sender_id, "目前沒有看板。")
                return
            lines = ["【看板列表】"]
            for b in boards:
                lines.append(f"  {b['name']}｜{b['title']}｜{b['post_count']}篇")
            lines.append("輸入 POSTS:<看板>:<頁> 查看文章")
            self._send_plain_text(sender_id, "\n".join(lines))
            return

        # POSTS:<看板>:<頁>
        if upper.startswith("POSTS:"):
            parts = text.split(":", 2)
            board = parts[1].strip() if len(parts) > 1 else ""
            try:
                page = int(parts[2].strip()) if len(parts) > 2 and parts[2].strip() else 1
            except ValueError:
                page = 1
            if not board:
                self._send_plain_text(sender_id, "格式錯誤，請用：POSTS:<看板>:<頁>")
                return
            posts, total = bbs.get_posts(board, page, per_page=8)
            if posts is None:
                self._send_plain_text(sender_id, f"找不到看板：{board}")
                return
            if not posts:
                self._send_plain_text(sender_id, f"[{board}] 第{page}頁沒有文章。")
                return
            lines = [f"【{board}】第{page}頁（共{total}篇）"]
            for p in posts:
                lines.append(f"  #{p['id']} {p['title']} /{p['author']}")
            lines.append("輸入 READ:<號碼> 閱讀文章")
            self._send_plain_text(sender_id, "\n".join(lines))
            return

        # READ:<號碼>
        if upper.startswith("READ:"):
            parts = text.split(":", 1)
            try:
                post_id = int(parts[1].strip())
            except (IndexError, ValueError):
                self._send_plain_text(sender_id, "格式錯誤，請用：READ:<文章號碼>")
                return
            post, replies = bbs.get_post(post_id, sender_id)
            if not post:
                self._send_plain_text(sender_id, "找不到文章。")
                return
            header = (f"#{post['id']} {post['title']}\n"
                      f"作者：{post['author']}  {(post['created_at'] or '')[:16]}\n"
                      f"{'─'*20}\n"
                      f"{post['body']}")
            self._send_plain_text(sender_id, header)
            if replies:
                time.sleep(2.5)
                lines = [f"【回覆 {len(replies)} 則】"]
                for r in replies[:5]:
                    lines.append(f"  {r['author']}：{r['body'][:60]}")
                if len(replies) > 5:
                    lines.append(f"  …還有 {len(replies)-5} 則")
                self._send_plain_text(sender_id, "\n".join(lines))
            return

        # PUSH:<號碼>
        if upper.startswith("PUSH:"):
            parts = text.split(":", 1)
            try:
                post_id = int(parts[1].strip())
            except (IndexError, ValueError):
                self._send_plain_text(sender_id, "格式錯誤，請用：PUSH:<文章號碼>")
                return
            push_count, pushed = bbs.toggle_push(post_id, sender_id)
            action = "已推" if pushed else "已取消推"
            self._send_plain_text(sender_id, f"{action} #{post_id}，目前共 {push_count} 推。")
            self._go(self.broadcast({"type": "push_updated", "post_id": post_id,
                                     "push_count": push_count, "pushed": pushed}))
            return

        # REPLY:<文章號>:<作者>:<內容>
        if upper.startswith("REPLY:"):
            parts = text.split(":", 3)
            if len(parts) < 4:
                self._send_plain_text(sender_id, "格式錯誤，請用：REPLY:<文章號>:<作者>:<內容>")
                return
            try:
                post_id = int(parts[1].strip())
            except ValueError:
                self._send_plain_text(sender_id, "文章號碼錯誤。")
                return
            author = parts[2].strip() or sender_name
            body   = parts[3].strip()
            if not body:
                self._send_plain_text(sender_id, "回覆內容不能為空。")
                return
            rid, err = bbs.create_reply(post_id, sender_id, author, body)
            if err:
                self._send_plain_text(sender_id, f"回覆失敗：{err}")
                return
            self._send_plain_text(sender_id, f"回覆成功（#{rid}）。")
            self._go(self.broadcast({"type": "new_reply", "post_id": post_id,
                                     "author": author, "body": body}))
            return

        # POST:<看板>:<作者>:<標題>:<內容>
        if upper.startswith("POST:"):
            parts = text.split(":", 4)
            if len(parts) < 5:
                self._send_plain_text(sender_id, "格式錯誤，請用：POST:<看板>:<作者>:<標題>:<內容>")
                return
            board  = parts[1].strip()
            author = parts[2].strip() or sender_name
            title  = parts[3].strip()
            body   = parts[4].strip()
            if not board or not title or not body:
                self._send_plain_text(sender_id, "看板、標題、內容不能為空。")
                return
            pid, err = bbs.create_post(board, sender_id, author, title, body)
            if err:
                self._send_plain_text(sender_id, f"發文失敗：{err}")
                return
            self._send_plain_text(sender_id, f"發文成功（#{pid}）：{title}")
            self._go(self.broadcast({"type": "new_post", "board": board,
                                     "post_id": pid, "author": author, "title": title}))
            return

        # SEARCH:<field>:<看板>:<關鍵字>
        if upper.startswith("SEARCH:"):
            parts = text.split(":", 3)
            field   = parts[1].strip() if len(parts) > 1 else "title"
            board_s = parts[2].strip() if len(parts) > 2 else ""
            keyword = parts[3].strip() if len(parts) > 3 else ""
            if not keyword:
                self._send_plain_text(sender_id, "格式錯誤，請用：SEARCH:<title|author>:<看板>:<關鍵字>")
                return
            results = bbs.search_posts(keyword, field, board_s or None, sender_id)
            if not results:
                self._send_plain_text(sender_id, f"搜尋「{keyword}」無結果。")
                return
            lines = [f"搜尋「{keyword}」共 {len(results)} 筆："]
            for r in results[:8]:
                lines.append(f"  #{r['id']} {r['title']} /{r['author']}")
            if len(results) > 8:
                lines.append(f"  …還有 {len(results)-8} 筆")
            self._send_plain_text(sender_id, "\n".join(lines))
            return

        # CHAT:<作者>:<訊息>
        if upper.startswith("CHAT:"):
            parts = text.split(":", 2)
            author = parts[1].strip() if len(parts) > 1 else sender_name
            msg    = parts[2].strip() if len(parts) > 2 else ""
            if not msg:
                self._send_plain_text(sender_id, "格式錯誤，請用：CHAT:<作者>:<訊息>")
                return
            bbs.upsert_user(sender_id, author, True)
            bbs.save_mesh_message(sender_id, author, msg)
            self._go(self.broadcast({
                "type": "mesh_message",
                "from": author, "from_id": sender_id,
                "text": msg, "time": datetime.now().strftime("%H:%M:%S"),
            }))
            return

        self._send_plain_text(sender_id, "未知指令，傳送 ? 取得說明。")

    def _help_messages(self) -> tuple[str, str, str]:
        return (
            "MeshBBS 使用說明\n"
            "先登入，再看板，再讀文章。\n"
            "操作文件：https://reurl.cc/Z24Wql",
            "常用指令\n"
            "LOGIN:<帳號>:<密碼>\n"
            "LOGOUT\n"
            "LIST                取得看板列表\n"
            "POSTS:<看板>:<頁>   查看文章列表\n"
            "READ:<號碼>         閱讀文章\n"
            "PUSH:<號碼>         推薦，再按一次取消\n"
            "SEARCH:<title|author>:<看板>:<關鍵字>",
            "發文 / 回覆 / 聊天\n"
            "POST:<看板>:<作者>:<標題>:<內文>\n"
            "REPLY:<文章號碼>:<作者>:<內文>\n"
            "CHAT:<作者>:<訊息>\n"
            "\n"
            "提示\n"
            "看板頁先用 LIST，再用 POSTS:<看板>:1。\n"
            "Relay Client 封包格式：\n"
            "BBS:REQ:<序號>:<指令>:<參數>",
        )

    def _send_help(self, dest_id: str):
        """傳送 BBS 說明給指定節點（分三則）。"""
        msg1 = (
            "【MeshBBS 客戶端下載】\n"
            "https://reurl.cc/Z24Wql"
        )
        msg2 = (
            "【純文字指令】\n"
            "LOGIN:<帳號>:<密碼>\n"
            "LOGOUT\n"
            "LIST\n"
            "POSTS:<看板>:<頁>\n"
            "READ:<號碼>\n"
            "PUSH:<號碼>\n"
            "SEARCH:<title|author>:<看板>:<關鍵字>"
        )
        msg3 = (
            "POST:<看板>:<作者>:<標題>:<內容>\n"
            "REPLY:<文章號>:<作者>:<內容>\n"
            "CHAT:<作者>:<訊息>\n"
            "\n"
            "【進階（Relay Client）】\n"
            "BBS:REQ:<序號>:<命令>:<參數>"
        )
        for msg in self._help_messages():
            try:
                self.iface.sendText(msg, destinationId=dest_id, wantAck=False)
                log.info("說明已送出 (%d bytes) → %s", len(msg.encode("utf-8")), dest_id)
            except Exception as e:
                log.warning("說明傳送失敗: %s", e)
            time.sleep(3.0)

    def _handle_bbs_req(self, sender_id: str, sender_name: str, text: str):
        """Process BBS:REQ:<seq>:<CMD>[:<args>] from a relay client."""
        # Format: BBS:REQ:<seq>:<CMD>[:<args>]
        parts = text.split(":", 4)
        if len(parts) < 5:
            return
        seq  = parts[2]
        cmd  = parts[3]
        args = parts[4] if len(parts) > 4 else ""
        log.info("Mesh BBS 命令  from %s  %s:%s", sender_id, cmd, args[:40])
        try:
            resp = self._exec_bbs_cmd(cmd, args, sender_id, sender_name)
            if resp:
                self._send_mesh_resp(sender_id, seq, resp)
        except Exception as e:
            log.error("BBS命令執行失敗: %s", e)
            err = _json_dumps({"type": "error", "msg": str(e)})
            self._send_mesh_resp(sender_id, seq, err)

    def _exec_bbs_cmd(self, cmd: str, args: str,
                      node_id: str, node_name: str) -> str | None:
        bbs = self.bbs

        if cmd == "LOGIN":
            # 格式：name:sha256hash
            parts = args.split(":", 1)
            name    = parts[0].strip()
            pw_hash = parts[1].strip() if len(parts) > 1 else ""
            if not name:
                name = node_name
            if not pw_hash:
                return json.dumps({"type": "login_error",
                                   "msg": "請升級 Client 以使用帳密登入"})
            actual_id, is_new, err, kicked_id = bbs.mesh_login(name, pw_hash, node_id)
            if err:
                return json.dumps({"type": "login_error", "msg": err},
                                  ensure_ascii=False)
            is_admin = bbs.is_admin_hash(name, pw_hash)
            if kicked_id:
                self._relay_clients.discard(kicked_id)
                self._admin_clients.discard(kicked_id)
                if kicked_id.startswith("!"):
                    self._send_plain_text(kicked_id, f"帳號 [{name}] 已在另一台裝置登入，您已被登出。")
                if self.kick_ws_fn:
                    self._go(self.kick_ws_fn(kicked_id))
            self._relay_clients.add(actual_id)
            if is_admin:
                self._admin_clients.add(actual_id)
            else:
                self._admin_clients.discard(actual_id)
            self._go(self.broadcast({"type": "user_join",
                                     "name": name, "node_id": actual_id}))
            return json.dumps({
                "type": "login_ok", "node_id": actual_id,
                "name": name, "new_user": is_new, "is_admin": is_admin,
            }, ensure_ascii=False)

        elif cmd == "LOGOUT":
            self._relay_clients.discard(node_id)
            self._admin_clients.discard(node_id)
            bbs.upsert_user(node_id, args or node_name, False)
            self._go(self.broadcast({"type": "user_leave",
                                     "name": args, "node_id": node_id}))
            return None

        elif cmd in ("LIST", "LIST2"):
            # 用陣列格式 [name, title, post_count] 大幅壓縮封包，確保單一 chunk 可送完
            boards_compact = [
                [b["name"], b["title"], b["post_count"], b["moderator"], b["moderator_id"]]
                for b in bbs.get_boards()
            ]
            return _json_dumps({
                "type": "boards",
                "boards": boards_compact,
                "t": "B",
                "b": boards_compact,
            })

        elif cmd in ("POSTS", "POSTS2"):
            p = args.split(":", 1)
            board = p[0]
            page  = int(p[1]) if len(p) > 1 else 1
            posts, total = bbs.get_posts(board, page, per_page=10)
            if posts is None:
                return json.dumps({"type": "error", "msg": f"找不到看板 {board}"})
            # 用陣列格式 [id, author_id, author, title, reply_count, created_at[:10]]
            # 大幅壓縮封包大小，每筆文章約節省 50% 傳輸量
            posts_compact = [
                [p2["id"], p2["author_id"], p2["author"],
                 p2["title"], p2["reply_count"], (p2["created_at"] or "")[:10],
                 p2.get("push_count", 0), 1 if p2.get("pushed") else 0]
                for p2 in posts
            ]
            return _json_dumps({
                "type": "posts",
                "board": board,
                "posts": posts_compact,
                "total": total,
                "page": page,
                "t": "P",
                "b": board,
                "p": posts_compact,
                "n": total,
                "g": page,
            })

        elif cmd in ("READ", "READ2"):
            post, replies = bbs.get_post(int(args), node_id)
            if not post:
                return json.dumps({"type": "error", "msg": "找不到文章"})
            # 緊湊格式：post 用陣列 [id,author_id,author,title,body,created_at[:16]]
            #           replies 用陣列 [[author,body,created_at[:16]], ...]
            post_c = [
                post["id"], post["author_id"], post["author"],
                post["title"], post["body"], (post["created_at"] or "")[:16],
                post["board"], post.get("push_count", 0), 1 if post.get("pushed") else 0
            ]
            replies_c = [
                [r["id"], r["author_id"], r["author"], r["body"], (r["created_at"] or "")[:16]]
                for r in replies
            ]
            return _json_dumps({
                "type": "post",
                "post": post,
                "replies": replies,
                "t": "R",
                "p": post_c,
                "r": replies_c,
            })

        elif cmd == "POST":
            # Format: board:author:title:body
            p = args.split(":", 3)
            if len(p) < 4:
                return json.dumps({"type": "error", "msg": "格式錯誤"})
            board, author, title, body = p
            pid, err = bbs.create_post(board, node_id, author, title, body)
            if err:
                return json.dumps({"type": "error", "msg": err})
            self._go(self.broadcast({"type": "new_post", "board": board,
                                     "post_id": pid, "author": author, "title": title}))
            return _json_dumps({"type": "post_created", "post_id": pid})

        elif cmd == "REPLY":
            # Format: post_id:author:body
            p = args.split(":", 2)
            if len(p) < 3:
                return json.dumps({"type": "error", "msg": "格式錯誤"})
            post_id, author, body = int(p[0]), p[1], p[2]
            rid, err = bbs.create_reply(post_id, node_id, author, body)
            if err:
                return json.dumps({"type": "error", "msg": err})
            self._go(self.broadcast({"type": "new_reply", "post_id": post_id,
                                     "author": author, "body": body}))
            return _json_dumps({"type": "reply_created", "reply_id": rid})

        elif cmd == "CHAT":
            # Format: author:text
            p = args.split(":", 1)
            author = p[0]
            text   = p[1] if len(p) > 1 else ""
            bbs.upsert_user(node_id, author, True)
            bbs.save_mesh_message(node_id, author, text)
            self._go(self.broadcast({
                "type": "mesh_message",
                "from": author, "from_id": node_id,
                "text": text, "time": datetime.now().strftime("%H:%M:%S"),
            }))
            return None   # no response needed

        elif cmd == "SEARCH":
            # SEARCH:<field>:<board>:<keyword>  (board 可空 = 全站)
            p       = args.split(":", 3)
            field   = p[0] if len(p) > 0 else "title"
            board_s = p[1] if len(p) > 1 else ""
            keyword = p[2] if len(p) > 2 else ""
            if not keyword:
                return json.dumps({"type": "error", "msg": "請輸入關鍵字"})
            results = bbs.search_posts(keyword, field, board_s or None, node_id)
            return json.dumps({"type": "search_results", "posts": results,
                               "query": keyword, "field": field,
                               "total": len(results)}, ensure_ascii=False)

        elif cmd == "PUSH":
            try:
                post_id = int(args)
            except (ValueError, TypeError):
                return json.dumps({"type": "error", "msg": "格式錯誤"})
            push_count, pushed = bbs.toggle_push(post_id, node_id)
            return json.dumps({"type": "push_updated", "post_id": post_id,
                               "push_count": push_count, "pushed": pushed},
                              ensure_ascii=False)

        elif cmd == "EDIT":
            # EDIT:<post_id>:<title>:<body>
            p = args.split(":", 2)
            if len(p) < 3:
                return json.dumps({"type": "error", "msg": "格式錯誤: EDIT:<id>:<標題>:<內容>"})
            try:
                post_id = int(p[0])
            except ValueError:
                return json.dumps({"type": "error", "msg": "文章編號錯誤"})
            title = p[1].strip()
            body  = p[2].strip()
            if not title or not body:
                return json.dumps({"type": "error", "msg": "標題和內容不能為空"})
            post, _ = bbs.get_post(post_id)
            if not post:
                return json.dumps({"type": "error", "msg": "找不到文章"})
            is_admin = node_id in self._admin_clients
            if is_admin:
                ok, err = bbs.admin_update_post(post_id, title, body)
                edit_type = "post_edited"
            elif post.get("author_id") == node_id:
                ok, err = bbs.update_post(post_id, node_id, title, body)
                edit_type = "post_edited"
            else:
                ok, err = bbs.mod_update_post(post_id, node_id, title, body)
                edit_type = "mod_post_edited"
            if not ok:
                return json.dumps({"type": "error", "msg": err})
            return json.dumps({"type": edit_type, "post_id": post_id})

        elif cmd == "DEL":
            try:
                post_id = int(args)
            except (ValueError, TypeError):
                return json.dumps({"type": "error", "msg": "格式錯誤"})
            post, _ = bbs.get_post(post_id)
            if not post:
                return json.dumps({"type": "error", "msg": "找不到文章"})
            is_admin = node_id in self._admin_clients
            mod_id = bbs.get_board_moderator_id(post["board"])
            if post.get("author_id") != node_id and not is_admin and mod_id != node_id:
                return json.dumps({"type": "error", "msg": "無權限刪除此文章"})
            bbs.delete_post(post_id)
            delete_type = "post_deleted" if post.get("author_id") == node_id else "mod_post_deleted"
            return json.dumps({"type": delete_type, "post_id": post_id,
                               "board": post["board"]})

        elif cmd == "DELREP":
            try:
                reply_id = int(args)
            except (ValueError, TypeError):
                return json.dumps({"type": "error", "msg": "格式錯誤"})
            ok, err = bbs.delete_reply(reply_id, node_id)
            if not ok:
                return json.dumps({"type": "error", "msg": err})
            return _json_dumps({"type": "reply_deleted", "reply_id": reply_id})

        elif cmd == "EDITREP":
            p = args.split(":", 1)
            if len(p) < 2:
                return json.dumps({"type": "error", "msg": "格式錯誤: EDITREP:<id>:<內容>"})
            try:
                reply_id = int(p[0])
            except ValueError:
                return json.dumps({"type": "error", "msg": "回覆編號錯誤"})
            body = p[1].strip()
            if not body:
                return json.dumps({"type": "error", "msg": "回覆內容不能為空"})
            ok, err = bbs.update_reply(reply_id, node_id, body)
            if not ok:
                return json.dumps({"type": "error", "msg": err})
            return _json_dumps({"type": "reply_edited", "reply_id": reply_id})

        return json.dumps({"type": "error", "msg": f"未知命令: {cmd}"})

    def _send_mesh_resp(self, dest_id: str, seq: str, data: str):
        """Send chunked BBS:RES response to the requesting node.

        格式: BBS:RES:<dest_id>:<seq>:<idx>:<total>:<data>

        Meshtastic 硬體限制: 每則訊息最多 237 bytes (UTF-8)。
        標頭 "BBS:RES:!abcdefgh:9999:99:99:" ≈ 30 bytes，data 最多 180 bytes。
        """
        # zlib 壓縮後再 base64，減少 chunk 數量加快傳輸；廣播不傳明碼
        HEADER_OVERHEAD = 32
        MAX_CHUNK = 178

        compressed = zlib.compress(data.encode("utf-8"), level=9)
        b64_data   = compressed
        chunks     = [compressed[i:i + MAX_CHUNK] for i in range(0, len(compressed), MAX_CHUNK)]
        total      = len(chunks)
        log.debug("BBS resp %d bytes → zlib %d bytes → b64 %d bytes → %d chunks",
                  len(data), len(compressed), len(b64_data), total)

        def _do_send():
            iface = self.iface
            if not iface:
                log.warning("BBS 回應失敗：Mesh 介面未就緒 (seq=%s)", seq)
                return
            for i, chunk in enumerate(chunks):
                if not self.iface:
                    log.warning("BBS 回應中斷：Mesh 介面已關閉 (seq=%s chunk=%d)", seq, i)
                    break
                payload = f"MBBS2|{dest_id}|{seq}|{i}|{total}\n".encode("utf-8") + chunk
                msg_bytes = len(payload)
                try:
                    iface.sendData(payload, destinationId=dest_id,
                                   portNum=PRIVATE_APP_PORTNUM, wantAck=False)
                    log.info("BBS chunk seq=%s %d/%d private-DM (%d bytes) -> %s",
                             seq, i + 1, total, msg_bytes, dest_id)
                except Exception as e:
                    log.warning("BBS chunk 傳送失敗 seq=%s chunk=%d: %s",
                                seq, i, e)
                if i < total - 1:
                    time.sleep(0.35)
                continue
                for attempt in range(2):
                    try:
                        if attempt == 0:
                            iface.sendData(payload, destinationId=dest_id,
                                           portNum=PRIVATE_APP_PORTNUM, wantAck=False)
                        else:
                            iface.sendData(payload, portNum=PRIVATE_APP_PORTNUM, wantAck=False)
                        log.info("BBS chunk seq=%s %d/%d %s (%d bytes) → %s",
                                 seq, i + 1, total,
                                 "private-DM" if attempt == 0 else "private-broadcast",
                                 msg_bytes, dest_id)
                    except Exception as e:
                        log.warning("BBS chunk 傳送失敗 seq=%s chunk=%d attempt=%d: %s",
                                    seq, i, attempt + 1, e)
                    if attempt == 0:
                        time.sleep(1.0)   # 兩次之間留足 duty cycle
                if i < total - 1:
                    time.sleep(1.0)       # 不同 chunk 之間留足 duty cycle

        threading.Thread(target=_do_send, daemon=True).start()

    def _node_name(self, interface, node_num):
        try:
            node = (interface.nodes or {}).get(str(node_num), {})
            u = node.get("user", {})
            return u.get("longName") or u.get("shortName") or f"Node-{node_num}"
        except Exception:
            return f"Node-{node_num}"

    def get_nodes(self):
        if not self.iface:
            return []
        try:
            result = []
            for num, node in (self.iface.nodes or {}).items():
                u  = node.get("user", {})
                p  = node.get("position", {})
                dm = node.get("deviceMetrics", {})
                result.append({
                    "num":        num,
                    "name":       u.get("longName") or u.get("shortName") or f"Node-{num}",
                    "short_name": u.get("shortName", "?"),
                    "hw_model":   u.get("hwModel", ""),
                    "snr":        node.get("snr"),
                    "last_heard": node.get("lastHeard"),
                    "battery":    dm.get("batteryLevel"),
                    "lat":        p.get("latitude"),
                    "lon":        p.get("longitude"),
                })
            return result
        except Exception as e:
            log.error("取得節點列表失敗: %s", e)
            return []

    def send(self, text, dest_id=None):
        if not self.iface:
            return False
        try:
            if dest_id:
                self.iface.sendText(text, destinationId=dest_id)
            else:
                self.iface.sendText(text)
            return True
        except Exception as e:
            log.error("傳送失敗: %s", e)
            return False


# ═══════════════════════════════════════════════════════════════
# BBS WebSocket 伺服器
# ═══════════════════════════════════════════════════════════════
class BBSServer:
    def __init__(self, bbs: BBS, mesh: MeshInterface):
        self.bbs     = bbs
        self.mesh    = mesh
        self.clients: set = set()
        self._node_ws: dict = {}   # node_id → ws
        self._ws_node: dict = {}   # ws → node_id
        self.notify_device_fn = None  # 設定後可傳純文字給 Meshtastic 裝置

    async def kick_ws(self, node_id: str):
        """踢出指定 node_id 的 WebSocket 連線。"""
        ws = self._node_ws.pop(node_id, None)
        if not ws:
            return
        self._ws_node.pop(ws, None)
        try:
            await ws.send_str(json.dumps({
                "type": "force_logout",
                "msg": "帳號已在另一台裝置登入，您已被登出。"
            }, ensure_ascii=False))
            # 不立即 close，讓瀏覽器處理完 force_logout 後自行關閉
        except Exception:
            pass

    async def broadcast(self, data):
        if not self.clients:
            return
        msg  = json.dumps(data, ensure_ascii=False)
        dead = set()
        for ws in self.clients:
            try:
                await ws.send_str(msg)
            except Exception:
                dead.add(ws)
        self.clients -= dead

    async def handle_ws(self, request):
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)
        self.clients.add(ws)
        log.info("BBS 客戶端連線: %s", request.remote)
        try:
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        resp = await self._process(data, ws)
                        if resp:
                            await ws.send_str(json.dumps(resp, ensure_ascii=False))
                    except json.JSONDecodeError:
                        await ws.send_str(json.dumps({"type": "error", "msg": "無效的 JSON"}))
                    except Exception as e:
                        log.error("處理訊息失敗: %s", e)
                        await ws.send_str(json.dumps({"type": "error", "msg": str(e)}))
                elif msg.type == aiohttp.WSMsgType.ERROR:
                    break
        finally:
            self.clients.discard(ws)
            nid = self._ws_node.pop(ws, None)
            if nid:
                self._node_ws.pop(nid, None)
            log.info("BBS 客戶端斷線: %s", request.remote)
        return ws

    async def _process(self, msg, ws):
        action = msg.get("action")

        if action == "register":
            name     = msg.get("name", "").strip()
            password = msg.get("password", "").strip()
            node_id, err = self.bbs.register_user(name, password)
            if err:
                return {"type": "register_error", "msg": err}
            return {"type": "register_ok", "name": name}

        if action == "login":
            name     = msg.get("name", "訪客").strip()
            password = msg.get("password", "")
            node_id  = msg.get("node_id", "")

            if password:
                # ── 密碼登入（網頁帳號）──────────────────────────
                uid, err = self.bbs.verify_user_login(name, password)
                if err:
                    return {"type": "login_error", "msg": err}

                # 查舊裝置是否在線
                with self.bbs._conn() as _c:
                    _row = _c.execute(
                        "SELECT online FROM users WHERE node_id=?", (uid,)
                    ).fetchone()
                old_is_online = bool(_row and _row[0])
                log.info("Web登入 [%s] uid=%s old_is_online=%s", name, uid, old_is_online)

                # 踢出舊 WebSocket（若有）
                old_ws = self._node_ws.pop(uid, None)
                if old_ws and old_ws is not ws:
                    self._ws_node.pop(old_ws, None)
                    try:
                        await old_ws.send_str(json.dumps({
                            "type": "force_logout",
                            "msg": "帳號已在另一台裝置登入，您已被登出。"
                        }, ensure_ascii=False))
                        await old_ws.close()
                    except Exception:
                        pass
                elif old_is_online and uid.startswith("!"):
                    # 舊連線是 Meshtastic 純文字裝置 → 在背景執行緒發純文字通知
                    log.info("Web登入踢出 Meshtastic 裝置，發送通知給 %s", uid)
                    if self.notify_device_fn:
                        _uid, _fn, _nm = uid, self.notify_device_fn, name
                        threading.Thread(
                            target=_fn,
                            args=(_uid, f"帳號 [{_nm}] 已在網頁端登入，您已被登出。"),
                            daemon=True
                        ).start()
                    else:
                        log.warning("notify_device_fn 未設定，無法通知 Meshtastic 裝置")

                # 產生 Web 專用 session node_id，更新 DB（舊 node_id 徹底切斷）
                node_id = f"WEB-{str(id(ws))[-8:]}"
                with self.bbs._conn() as _c:
                    _c.execute(
                        "UPDATE users SET node_id=?, last_seen=?, online=1 WHERE node_id=?",
                        (node_id, datetime.now().isoformat(), uid)
                    )
            else:
                # ── Meshtastic 裝置登入（無密碼）────────────────
                if not node_id:
                    node_id = f"GUEST-{str(id(ws))[-6:]}"
                self.bbs.upsert_user(node_id, name, True)

            # 清除此 ws 可能的舊 node_id 對應
            old_nid = self._ws_node.pop(ws, None)
            if old_nid:
                self._node_ws.pop(old_nid, None)

            # 登錄新 ws
            self._node_ws[node_id] = ws
            self._ws_node[ws] = node_id

            await self.broadcast({"type": "user_join", "name": name, "node_id": node_id})
            return {"type": "login_ok", "node_id": node_id, "name": name,
                    "online_users": self.bbs.get_online_users()}

        elif action == "get_boards":
            return {"type": "boards", "boards": self.bbs.get_boards(),
                    "online_users": self.bbs.get_online_users()}

        elif action == "get_posts":
            board     = msg.get("board")
            page      = msg.get("page", 1)
            node_id   = msg.get("author_id", "")
            posts, total = self.bbs.get_posts(board, page, user_node_id=node_id)
            if posts is None:
                return {"type": "error", "msg": f"找不到看板 {board}"}
            return {"type": "posts", "board": board, "posts": posts,
                    "total": total, "page": page}

        elif action == "search_posts":
            keyword   = msg.get("query", "").strip()
            field     = msg.get("field", "title")
            board     = msg.get("board") or None
            node_id   = msg.get("author_id", "")
            if not keyword:
                return {"type": "error", "msg": "請輸入關鍵字"}
            results = self.bbs.search_posts(keyword, field, board, node_id)
            label = "作者" if field == "author" else "標題"
            return {"type": "search_results", "posts": results,
                    "query": keyword, "field": field, "board": board,
                    "label": label, "total": len(results)}

        elif action == "toggle_push":
            post_id  = msg.get("post_id")
            node_id  = msg.get("author_id", "")
            if not post_id or not node_id:
                return {"type": "error", "msg": "參數錯誤"}
            push_count, pushed = self.bbs.toggle_push(post_id, node_id)
            return {"type": "push_updated", "post_id": post_id,
                    "push_count": push_count, "pushed": pushed}

        elif action == "get_post":
            post, replies = self.bbs.get_post(msg.get("post_id"))
            if not post:
                return {"type": "error", "msg": "找不到文章"}
            return {"type": "post", "post": post, "replies": replies}

        elif action == "create_post":
            board     = msg.get("board")
            author_id = msg.get("author_id", "unknown")
            author    = msg.get("author", "匿名")
            title     = msg.get("title", "").strip()
            body      = msg.get("body", "").strip()
            if not title or not body:
                return {"type": "error", "msg": "標題和內容不能為空"}
            pid, err = self.bbs.create_post(board, author_id, author, title, body)
            if err:
                return {"type": "error", "msg": err}
            await self.broadcast({"type": "new_post", "board": board,
                                   "post_id": pid, "author": author, "title": title})
            return {"type": "post_created", "post_id": pid}

        elif action == "create_reply":
            post_id   = msg.get("post_id")
            author_id = msg.get("author_id", "unknown")
            author    = msg.get("author", "匿名")
            body      = msg.get("body", "").strip()
            if not body:
                return {"type": "error", "msg": "回覆內容不能為空"}
            rid, err = self.bbs.create_reply(post_id, author_id, author, body)
            if err:
                return {"type": "error", "msg": err}
            await self.broadcast({"type": "new_reply", "post_id": post_id,
                                   "author": author, "body": body})
            return {"type": "reply_created", "reply_id": rid}

        elif action == "send_mesh":
            text      = msg.get("text", "").strip()
            author    = msg.get("author", "?")
            author_id = msg.get("author_id", "?")
            if text:
                self.bbs.save_mesh_message(author_id, author, text)
                await self.broadcast({
                    "type": "mesh_message",
                    "from": author, "from_id": author_id,
                    "text": text, "time": datetime.now().strftime("%H:%M:%S"),
                })
            return None

        elif action == "logout":
            node_id = msg.get("node_id")
            name    = msg.get("name", "")
            if node_id:
                self.bbs.upsert_user(node_id, name or node_id, False)
            await self.broadcast({"type": "user_leave", "name": name, "node_id": node_id})
            return None

        elif action == "edit_post":
            post_id   = msg.get("post_id")
            author_id = msg.get("author_id", "")
            title     = msg.get("title", "").strip()
            body      = msg.get("body", "").strip()
            if not title or not body:
                return {"type": "error", "msg": "標題和內容不能為空"}
            ok, err = self.bbs.update_post(post_id, author_id, title, body)
            if not ok:
                return {"type": "error", "msg": err}
            return {"type": "post_edited", "post_id": post_id}

        elif action == "delete_own_post":
            post_id   = msg.get("post_id")
            author_id = msg.get("author_id", "")
            post, _   = self.bbs.get_post(post_id)
            if not post:
                return {"type": "error", "msg": "找不到文章"}
            if post.get("author_id") != author_id:
                return {"type": "error", "msg": "無權限刪除此文章"}
            board = post["board"]
            self.bbs.delete_post(post_id)
            return {"type": "post_deleted", "post_id": post_id, "board": board}

        elif action == "mod_delete_post":
            post_id   = msg.get("post_id")
            author_id = msg.get("author_id", "")
            post, _   = self.bbs.get_post(post_id)
            if not post:
                return {"type": "error", "msg": "找不到文章"}
            mod_id = self.bbs.get_board_moderator_id(post["board"])
            if not mod_id or mod_id != author_id:
                return {"type": "error", "msg": "您不是此看板版主，無法刪除"}
            board = post["board"]
            self.bbs.delete_post(post_id)
            log.info("版主 [%s] 刪除文章 #%d", author_id, post_id)
            return {"type": "mod_post_deleted", "post_id": post_id, "board": board}

        elif action == "mod_edit_post":
            post_id   = msg.get("post_id")
            author_id = msg.get("author_id", "")
            title     = msg.get("title", "").strip()
            body      = msg.get("body", "").strip()
            if not title or not body:
                return {"type": "error", "msg": "標題和內容不能為空"}
            ok, err = self.bbs.mod_update_post(post_id, author_id, title, body)
            if not ok:
                return {"type": "error", "msg": err}
            log.info("版主 [%s] 編輯文章 #%d", author_id, post_id)
            return {"type": "mod_post_edited", "post_id": post_id}

        return {"type": "error", "msg": f"未知動作: {action}"}


# ═══════════════════════════════════════════════════════════════
# Admin REST API
# ═══════════════════════════════════════════════════════════════
def setup_admin_routes(app: web.Application, bbs: BBS,
                       mesh: MeshInterface, srv: BBSServer):

    def need_auth(h):
        async def w(request):
            if not _auth_ok(request):
                return web.json_response({"error": "未授權"}, status=401)
            return await h(request)
        return w

    # ── auth ──────────────────────────────────────────────────
    async def admin_login(request):
        data     = await request.json()
        username = data.get("username", "admin").strip()
        password = data.get("password", "").strip()
        if bbs.check_admin(username, password):
            token = _new_token()
            log.info("管理員 [%s] 登入成功", username)
            return web.json_response({"ok": True, "token": token,
                                      "username": username})
        log.warning("管理員登入失敗：帳號 [%s]", username)
        return web.json_response({"error": "帳號或密碼錯誤"}, status=403)

    # ── dashboard ─────────────────────────────────────────────
    @need_auth
    async def admin_stats(request):
        s = bbs.get_stats()
        s["ws_clients"]    = len(srv.clients)
        s["mesh_connected"] = mesh.connected
        s["mesh_node"]     = mesh.local_node_name
        return web.json_response(s)

    @need_auth
    async def admin_recent(request):
        return web.json_response(bbs.get_recent_activity())

    # ── boards ────────────────────────────────────────────────
    @need_auth
    async def admin_get_boards(request):
        return web.json_response(bbs.get_boards())

    @need_auth
    async def admin_create_board(request):
        d = await request.json()
        name = d.get("name", "").strip()
        title = d.get("title", "").strip()
        mod   = d.get("moderator", "SYSOP").strip() or "SYSOP"
        if not name or not title:
            return web.json_response({"error": "名稱和標題不能為空"}, status=400)
        ok, err = bbs.create_board(name, title, mod)
        if not ok:
            return web.json_response({"error": err}, status=400)
        log.info("新增看板: %s", name)
        return web.json_response({"ok": True})

    @need_auth
    async def admin_update_board(request):
        name = request.match_info["name"]
        d = await request.json()
        ok = bbs.update_board(name, d.get("title", ""), d.get("moderator", "SYSOP"))
        return web.json_response({"ok": ok})

    @need_auth
    async def admin_delete_board(request):
        name = request.match_info["name"]
        bbs.delete_board(name)
        log.info("刪除看板: %s", name)
        await srv.broadcast({"type": "boards", "boards": bbs.get_boards(),
                              "online_users": bbs.get_online_users()})
        return web.json_response({"ok": True})

    # ── posts ─────────────────────────────────────────────────
    @need_auth
    async def admin_get_posts(request):
        page   = int(request.rel_url.query.get("page", 1))
        board  = request.rel_url.query.get("board")  or None
        search = request.rel_url.query.get("search") or None
        posts, total = bbs.get_all_posts(page=page, board=board, search=search)
        return web.json_response({"posts": posts, "total": total, "page": page})

    @need_auth
    async def admin_delete_post(request):
        pid = int(request.match_info["id"])
        ok  = bbs.delete_post(pid)
        if not ok:
            return web.json_response({"error": "文章不存在"}, status=404)
        log.info("管理員刪除文章 #%d", pid)
        return web.json_response({"ok": True})

    @need_auth
    async def admin_get_replies(request):
        pid     = int(request.match_info["id"])
        replies = bbs.get_post_replies(pid)
        return web.json_response({"replies": replies})

    @need_auth
    async def admin_delete_reply(request):
        rid = int(request.match_info["reply_id"])
        ok  = bbs.admin_delete_reply(rid)
        if not ok:
            return web.json_response({"error": "回覆不存在"}, status=404)
        log.info("管理員刪除回覆 #%d", rid)
        return web.json_response({"ok": True})

    # ── users ─────────────────────────────────────────────────
    @need_auth
    async def admin_get_users(request):
        page = int(request.rel_url.query.get("page", 1))
        users, total = bbs.get_all_users(page=page)
        return web.json_response({"users": users, "total": total, "page": page})

    @need_auth
    async def admin_ban_user(request):
        node_id = request.match_info["node_id"]
        d       = await request.json()
        bbs.ban_user(node_id, d.get("banned", True))
        log.info("%s 使用者: %s", "封禁" if d.get("banned") else "解封", node_id)
        return web.json_response({"ok": True})

    @need_auth
    async def admin_delete_user(request):
        node_id = request.match_info["node_id"]
        bbs.delete_user(node_id)
        log.info("管理員刪除使用者: %s", node_id)
        return web.json_response({"ok": True})

    # ── mesh ──────────────────────────────────────────────────
    @need_auth
    async def admin_mesh_nodes(request):
        return web.json_response(mesh.get_nodes())

    @need_auth
    async def admin_mesh_broadcast(request):
        d    = await request.json()
        text = d.get("text", "").strip()
        if not text:
            return web.json_response({"error": "訊息不能為空"}, status=400)
        sent = mesh.send(text)
        bbs.save_mesh_message("ADMIN", "ADMIN", text)
        await srv.broadcast({
            "type": "mesh_message",
            "from": "ADMIN", "from_id": "ADMIN",
            "text": text, "time": datetime.now().strftime("%H:%M:%S"),
        })
        log.info("管理員廣播: %s", text)
        return web.json_response({"ok": True, "sent": sent})

    @need_auth
    async def admin_mesh_messages(request):
        limit = int(request.rel_url.query.get("limit", 100))
        return web.json_response(bbs.get_mesh_messages(limit))

    # ── logs ──────────────────────────────────────────────────
    @need_auth
    async def admin_logs(request):
        limit = int(request.rel_url.query.get("limit", 200))
        return web.json_response(LOG_BUFFER[-limit:])

    # ── admin accounts ────────────────────────────────────────
    @need_auth
    async def admin_get_admins(request):
        return web.json_response(bbs.get_admins())

    @need_auth
    async def admin_create_admin(request):
        d        = await request.json()
        username = d.get("username", "").strip()
        password = d.get("password", "").strip()
        if not username or not password:
            return web.json_response({"error": "帳號和密碼不能為空"}, status=400)
        ok, err = bbs.create_admin(username, password)
        if not ok:
            return web.json_response({"error": err}, status=400)
        log.info("新增管理員帳號: %s", username)
        return web.json_response({"ok": True})

    @need_auth
    async def admin_delete_admin(request):
        aid = int(request.match_info["id"])
        ok  = bbs.delete_admin(aid)
        if not ok:
            return web.json_response({"error": "帳號不存在"}, status=404)
        log.info("刪除管理員帳號 id=%d", aid)
        return web.json_response({"ok": True})

    @need_auth
    async def admin_change_password(request):
        aid = int(request.match_info["id"])
        d   = await request.json()
        new_pw = d.get("password", "").strip()
        if not new_pw:
            return web.json_response({"error": "密碼不能為空"}, status=400)
        ok = bbs.change_admin_password(aid, new_pw)
        if not ok:
            return web.json_response({"error": "帳號不存在"}, status=404)
        log.info("變更管理員密碼 id=%d", aid)
        return web.json_response({"ok": True})

    # ── board moderator ───────────────────────────────────────
    @need_auth
    async def admin_set_moderator(request):
        board_name = request.match_info["name"]
        d          = await request.json()
        mod_id     = d.get("moderator_id", "").strip()
        mod_name   = d.get("moderator",    "SYSOP").strip() or "SYSOP"
        bbs.set_board_moderator(board_name, mod_id, mod_name)
        log.info("設定看板 [%s] 版主: %s (%s)", board_name, mod_name, mod_id)
        return web.json_response({"ok": True})

    # register
    app.add_routes([
        web.post  ("/api/admin/login",                        admin_login),
        web.get   ("/api/admin/stats",                        admin_stats),
        web.get   ("/api/admin/recent",                       admin_recent),
        web.get   ("/api/admin/boards",                       admin_get_boards),
        web.post  ("/api/admin/boards",                       admin_create_board),
        web.put   ("/api/admin/boards/{name}",                admin_update_board),
        web.delete("/api/admin/boards/{name}",                admin_delete_board),
        web.post  ("/api/admin/boards/{name}/moderator",      admin_set_moderator),
        web.get   ("/api/admin/posts",                        admin_get_posts),
        web.delete("/api/admin/posts/{id}",                   admin_delete_post),
        web.get   ("/api/admin/posts/{id}/replies",           admin_get_replies),
        web.delete("/api/admin/posts/{id}/replies/{reply_id}", admin_delete_reply),
        web.get   ("/api/admin/users",                        admin_get_users),
        web.post  ("/api/admin/users/{node_id}/ban",          admin_ban_user),
        web.delete("/api/admin/users/{node_id}",              admin_delete_user),
        web.get   ("/api/admin/mesh/nodes",                   admin_mesh_nodes),
        web.post  ("/api/admin/mesh/broadcast",               admin_mesh_broadcast),
        web.get   ("/api/admin/mesh/messages",                admin_mesh_messages),
        web.get   ("/api/admin/logs",                         admin_logs),
        web.get   ("/api/admin/admins",                       admin_get_admins),
        web.post  ("/api/admin/admins",                       admin_create_admin),
        web.delete("/api/admin/admins/{id}",                  admin_delete_admin),
        web.put   ("/api/admin/admins/{id}/password",         admin_change_password),
    ])


# ═══════════════════════════════════════════════════════════════
# 主程式
# ═══════════════════════════════════════════════════════════════
async def serve_bbs(request):
    resp = web.FileResponse(_find_res("bbs_client.html"))
    resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return resp

async def serve_admin(request):
    resp = web.FileResponse(_find_res("admin.html"))
    resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return resp

def setup_public_routes(app: web.Application, bbs: BBS):
    """不需要 token 的公開 API。"""
    async def api_register(request):
        try:
            d        = await request.json()
            name     = d.get("name", "").strip()
            password = d.get("password", "").strip()
        except Exception:
            return web.json_response({"error": "格式錯誤"}, status=400)
        node_id, err = bbs.register_user(name, password)
        if err:
            return web.json_response({"error": err}, status=400)
        return web.json_response({"ok": True, "node_id": node_id})

    app.add_routes([
        web.post("/api/register", api_register),
    ])


async def main(args):
    global _admin_password
    _admin_password = args.admin_password

    init_db()
    bbs = BBS()
    bbs.set_all_offline()

    srv  = BBSServer(bbs, None)
    mesh = MeshInterface(args.port, bbs, srv.broadcast)
    srv.mesh = mesh
    mesh.kick_ws_fn        = srv.kick_ws
    srv.notify_device_fn   = mesh._send_plain_text

    loop = asyncio.get_running_loop()
    threading.Thread(target=mesh.start, args=(loop,), daemon=True).start()

    app = web.Application()
    app.add_routes([
        web.get("/",     serve_bbs),
        web.get("/admin", serve_admin),
        web.get("/ws",    srv.handle_ws),
    ])
    setup_public_routes(app, bbs)
    setup_admin_routes(app, bbs, mesh, srv)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", args.http_port)
    await site.start()

    conn_info = args.port
    print(f"""
╔══════════════════════════════════════════════════╗
║      Meshtastic BBS 伺服器 v2.0 已啟動           ║
╠══════════════════════════════════════════════════╣
║  Meshtastic 裝置：  {conn_info:<29}║
║  HTTP 埠號：        {args.http_port:<29}║
╠══════════════════════════════════════════════════╣
║  BBS 介面：  http://localhost:{args.http_port}             ║
║  後台管理：  http://localhost:{args.http_port}/admin        ║
║  管理密碼：  {args.admin_password:<37}║
╚══════════════════════════════════════════════════╝
""")

    try:
        await asyncio.Future()
    finally:
        await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Meshtastic PTT-BBS 伺服器 v2.0")
    parser.add_argument("--port",           default="COM3",
                        help="Meshtastic USB 序列埠 (Linux: /dev/ttyUSB0 或 /dev/ttyACM0, Windows: COM3)")
    parser.add_argument("--host",           default=None,
                        help="Meshtastic TCP 主機位址（網路連線，取代 --port，例如 192.168.1.100）")
    parser.add_argument("--tcp-port",       type=int, default=4403,
                        help="Meshtastic TCP 埠號（預設 4403，配合 --host 使用）")
    parser.add_argument("--http-port",      type=int, default=8765,
                        help="HTTP/WebSocket 埠號")
    parser.add_argument("--admin-password", default="admin",
                        help="後台管理密碼")
    args = parser.parse_args()
    asyncio.run(main(args))
