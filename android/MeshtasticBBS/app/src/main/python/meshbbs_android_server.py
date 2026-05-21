import hashlib
import json
import os
import shutil
import sqlite3
from datetime import datetime


def _now():
    return datetime.now().strftime("%Y/%m/%d %H:%M")


def _now_iso():
    return datetime.now().isoformat()


def _json_dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _hash_password(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class AndroidServerCore:
    def __init__(self):
        home = os.environ.get("HOME") or os.getcwd()
        self.db_path = os.path.join(home, "bbs.db")
        self.backup_dir = os.path.join(home, "meshbbs_backups")
        self.relay_clients = set()
        self.admin_clients = set()
        self.active_client_names = {}
        self._ensure_storage_dirs()
        self._init_db()

    def _conn(self):
        return sqlite3.connect(self.db_path, check_same_thread=False)

    def _ensure_storage_dirs(self):
        db_dir = os.path.dirname(self.db_path)
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
        os.makedirs(self.backup_dir, exist_ok=True)

    def configure_storage(self, db_path: str = "", backup_dir: str = ""):
        old_db_path = self.db_path
        if db_path:
            self.db_path = db_path
        if backup_dir:
            self.backup_dir = backup_dir
        self._ensure_storage_dirs()
        legacy_db = os.path.join(os.path.dirname(self.db_path), "meshbbs_android_server.db")
        if os.path.basename(self.db_path) == "bbs.db" and os.path.exists(legacy_db) and not os.path.exists(self.db_path):
            shutil.copy2(legacy_db, self.db_path)
        if old_db_path != self.db_path and os.path.exists(old_db_path) and not os.path.exists(self.db_path):
            shutil.copy2(old_db_path, self.db_path)
        if not os.path.exists(self.db_path):
            self._restore_latest_backup()
        self._init_db()
        return self.get_dashboard()

    def _backup_file_name(self):
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        return f"bbs-{stamp}.db"

    def _is_compatible_database(self, path: str):
        required = {"boards", "posts", "replies", "users", "admins", "mesh_messages"}
        try:
            with sqlite3.connect(path) as conn:
                rows = conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
            names = {row[0] for row in rows}
            return required.issubset(names)
        except sqlite3.DatabaseError:
            return False

    def _backup_entries(self):
        if not os.path.isdir(self.backup_dir):
            return []
        entries = []
        for name in os.listdir(self.backup_dir):
            path = os.path.join(self.backup_dir, name)
            if not os.path.isfile(path) or not name.lower().endswith(".db"):
                continue
            stat = os.stat(path)
            entries.append(
                {
                    "name": name,
                    "path": path,
                    "size": stat.st_size,
                    "created_at": datetime.fromtimestamp(stat.st_mtime).strftime("%Y/%m/%d %H:%M"),
                    "mtime": stat.st_mtime,
                }
            )
        entries.sort(key=lambda item: item["mtime"], reverse=True)
        return entries

    def _restore_latest_backup(self):
        latest = next(iter(self._backup_entries()), None)
        if latest:
            shutil.copy2(latest["path"], self.db_path)
            return latest["path"]
        return ""

    def backup_database(self):
        self._ensure_storage_dirs()
        with self._conn() as conn:
            conn.execute("PRAGMA wal_checkpoint(FULL)")
            conn.commit()
        target = os.path.join(self.backup_dir, self._backup_file_name())
        shutil.copy2(self.db_path, target)
        return {
            "ok": True,
            "path": target,
            "backup_dir": self.backup_dir,
            "msg": "Database backup completed",
        }

    def import_database(self, source_path: str):
        source_path = (source_path or "").strip()
        if not source_path or not os.path.isfile(source_path):
            return {"ok": False, "msg": "Import source file not found"}
        if not self._is_compatible_database(source_path):
            return {"ok": False, "msg": "Import source is not a MeshBBS database"}

        self._ensure_storage_dirs()
        if os.path.exists(self.db_path):
            current_backup = os.path.join(
                self.backup_dir,
                f"bbs-pre-import-{datetime.now().strftime('%Y%m%d-%H%M%S')}.db",
            )
            with self._conn() as conn:
                conn.execute("PRAGMA wal_checkpoint(FULL)")
                conn.commit()
            shutil.copy2(self.db_path, current_backup)

        shutil.copy2(source_path, self.db_path)
        self._init_db()
        return {
            "ok": True,
            "path": self.db_path,
            "msg": "Database import completed",
        }

    def _init_db(self):
        with self._conn() as conn:
            cur = conn.cursor()
            cur.executescript(
                """
                CREATE TABLE IF NOT EXISTS boards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE NOT NULL,
                    title TEXT NOT NULL,
                    moderator TEXT DEFAULT 'SYSOP',
                    moderator_id TEXT DEFAULT '',
                    post_count INTEGER DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS posts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    board TEXT NOT NULL,
                    author_id TEXT NOT NULL,
                    author TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    reply_count INTEGER DEFAULT 0,
                    push_count INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS replies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    post_id INTEGER NOT NULL,
                    author_id TEXT NOT NULL,
                    author TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS users (
                    node_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    last_seen TEXT,
                    post_count INTEGER DEFAULT 0,
                    online INTEGER DEFAULT 0,
                    banned INTEGER DEFAULT 0,
                    password_hash TEXT DEFAULT '',
                    is_web INTEGER DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS admins (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS mesh_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_id TEXT NOT NULL,
                    from_name TEXT NOT NULL,
                    text TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS post_pushes (
                    post_id INTEGER NOT NULL,
                    user_node_id TEXT NOT NULL,
                    PRIMARY KEY (post_id, user_node_id)
                );
                """
            )
            for migration in [
                "ALTER TABLE users ADD COLUMN banned INTEGER DEFAULT 0",
                "ALTER TABLE users ADD COLUMN password_hash TEXT DEFAULT ''",
                "ALTER TABLE users ADD COLUMN is_web INTEGER DEFAULT 0",
                "ALTER TABLE boards ADD COLUMN moderator_id TEXT DEFAULT ''",
                "ALTER TABLE posts ADD COLUMN push_count INTEGER DEFAULT 0",
            ]:
                try:
                    cur.execute(migration)
                except sqlite3.OperationalError:
                    pass
            if cur.execute("SELECT COUNT(*) FROM admins").fetchone()[0] == 0:
                cur.execute(
                    "INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)",
                    ("admin", _hash_password("admin"), _now_iso()),
                )
            defaults = [
                ("gossiping", "Gossiping"),
                ("tech", "Tech"),
                ("mesh", "Meshtastic"),
                ("local", "Local"),
                ("emergency", "Emergency"),
            ]
            for name, title in defaults:
                cur.execute(
                    "INSERT OR IGNORE INTO boards (name, title, moderator) VALUES (?, ?, 'SYSOP')",
                    (name, title),
                )
            conn.commit()

    def get_boards(self):
        with self._conn() as conn:
            rows = conn.execute(
                """
                SELECT b.name, b.title, b.moderator, b.moderator_id, COUNT(p.id)
                FROM boards b
                LEFT JOIN posts p ON p.board = b.name
                GROUP BY b.id
                ORDER BY b.id
                """
            ).fetchall()
        return [
            {
                "name": row[0],
                "title": row[1],
                "moderator": row[2],
                "moderator_id": row[3] or "",
                "post_count": row[4],
            }
            for row in rows
        ]

    def get_posts(self, board: str, page: int = 1, per_page: int = 10, user_node_id: str = ""):
        offset = (page - 1) * per_page
        push_key = self.push_identity(user_node_id)
        with self._conn() as conn:
            exists = conn.execute("SELECT 1 FROM boards WHERE name=?", (board,)).fetchone()
            if not exists:
                return None, 0
            total = conn.execute("SELECT COUNT(*) FROM posts WHERE board=?", (board,)).fetchone()[0]
            rows = conn.execute(
                """
                SELECT p.id, p.author_id, p.author, p.title, p.reply_count, p.created_at, p.push_count,
                       CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END
                FROM posts p
                LEFT JOIN post_pushes pp ON pp.post_id = p.id AND pp.user_node_id = ?
                WHERE p.board = ?
                ORDER BY p.id DESC
                LIMIT ? OFFSET ?
                """,
                (push_key, board, per_page, offset),
            ).fetchall()
        posts = []
        for row in rows:
            posts.append(
                {
                    "id": row[0],
                    "author_id": row[1],
                    "author": row[2],
                    "title": row[3],
                    "reply_count": row[4],
                    "created_at": row[5],
                    "push_count": row[6],
                    "pushed": bool(row[7]),
                }
            )
        return posts, total

    def get_post(self, post_id: int, user_node_id: str = ""):
        push_key = self.push_identity(user_node_id)
        with self._conn() as conn:
            post = conn.execute(
                """
                SELECT p.id, p.board, p.author_id, p.author, p.title, p.body, p.reply_count, p.created_at,
                       p.push_count, CASE WHEN pp.user_node_id IS NULL THEN 0 ELSE 1 END
                FROM posts p
                LEFT JOIN post_pushes pp ON pp.post_id = p.id AND pp.user_node_id = ?
                WHERE p.id=?
                """,
                (push_key, post_id),
            ).fetchone()
            if not post:
                return None, []
            replies = conn.execute(
                """
                SELECT id, author_id, author, body, created_at
                FROM replies WHERE post_id=?
                ORDER BY id
                """,
                (post_id,),
            ).fetchall()
        return (
            {
                "id": post[0],
                "board": post[1],
                "author_id": post[2],
                "author": post[3],
                "title": post[4],
                "body": post[5],
                "reply_count": post[6],
                "created_at": post[7],
                "push_count": post[8],
                "pushed": bool(post[9]),
            },
            [
                {
                    "id": row[0],
                    "author_id": row[1],
                    "author": row[2],
                    "body": row[3],
                    "created_at": row[4],
                }
                for row in replies
            ],
        )

    def get_stats(self):
        with self._conn() as conn:
            return {
                "posts": conn.execute("SELECT COUNT(*) FROM posts").fetchone()[0],
                "replies": conn.execute("SELECT COUNT(*) FROM replies").fetchone()[0],
                "users": conn.execute("SELECT COUNT(*) FROM users").fetchone()[0],
                "online": conn.execute("SELECT COUNT(*) FROM users WHERE online=1").fetchone()[0],
                "boards": conn.execute("SELECT COUNT(*) FROM boards").fetchone()[0],
                "mesh_messages": conn.execute("SELECT COUNT(*) FROM mesh_messages").fetchone()[0],
            }

    def get_all_users(self):
        with self._conn() as conn:
            rows = conn.execute(
                """
                SELECT node_id, name, post_count, online, banned, password_hash
                FROM users
                ORDER BY online DESC, last_seen DESC
                LIMIT 50
                """
            ).fetchall()
        return [
            {
                "node_id": row[0],
                "name": row[1],
                "post_count": row[2],
                "online": bool(row[3]),
                "banned": bool(row[4]),
                "has_password": bool(row[5]),
            }
            for row in rows
        ]

    def set_user_password(self, node_id: str, password: str):
        if not password:
            return False, "Password is empty"
        with self._conn() as conn:
            cur = conn.execute(
                "UPDATE users SET password_hash=? WHERE node_id=?",
                (_hash_password(password), node_id),
            )
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "User not found"

    def clear_user_password(self, node_id: str):
        with self._conn() as conn:
            cur = conn.execute("UPDATE users SET password_hash='' WHERE node_id=?", (node_id,))
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "User not found"

    def set_user_ban(self, node_id: str, banned: bool):
        with self._conn() as conn:
            cur = conn.execute(
                "UPDATE users SET banned=?, online=? WHERE node_id=?",
                (1 if banned else 0, 0 if banned else 1, node_id),
            )
            conn.commit()
        if banned:
            self.relay_clients.discard(node_id)
            self.admin_clients.discard(node_id)
        return cur.rowcount > 0, None if cur.rowcount > 0 else "User not found"

    def delete_user(self, node_id: str):
        if not node_id:
            return False, "Missing user node id"
        with self._conn() as conn:
            conn.execute("DELETE FROM post_pushes WHERE user_node_id=?", (node_id,))
            cur = conn.execute("DELETE FROM users WHERE node_id=?", (node_id,))
            conn.commit()
        self.relay_clients.discard(node_id)
        self.admin_clients.discard(node_id)
        self.active_client_names.pop(node_id, None)
        return cur.rowcount > 0, None if cur.rowcount > 0 else "User not found"

    def clear_mesh_messages(self):
        with self._conn() as conn:
            conn.execute("DELETE FROM mesh_messages")
            conn.commit()
        return True, None

    def get_all_admins(self):
        with self._conn() as conn:
            rows = conn.execute(
                """
                SELECT id, username, created_at
                FROM admins
                ORDER BY id
                """
            ).fetchall()
        return [
            {
                "id": row[0],
                "username": row[1],
                "created_at": row[2],
            }
            for row in rows
        ]

    def create_admin(self, username: str, password: str):
        if not username or not password:
            return False, "Missing admin username or password"
        with self._conn() as conn:
            try:
                conn.execute(
                    "INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)",
                    (username, _hash_password(password), _now_iso()),
                )
                conn.commit()
                return True, None
            except sqlite3.IntegrityError:
                return False, "Admin already exists"

    def delete_admin(self, admin_id: int):
        with self._conn() as conn:
            cur = conn.execute("DELETE FROM admins WHERE id=?", (admin_id,))
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "Admin not found"

    def change_admin_password(self, admin_id: int, password: str):
        if not password:
            return False, "Password is empty"
        with self._conn() as conn:
            cur = conn.execute(
                "UPDATE admins SET password_hash=? WHERE id=?",
                (_hash_password(password), admin_id),
            )
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "Admin not found"

    def create_board(self, name: str, title: str, moderator: str = "SYSOP", moderator_id: str = ""):
        if not name or not title:
            return False, "Missing board name or title"
        with self._conn() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO boards (name, title, moderator, moderator_id, post_count)
                    VALUES (?, ?, ?, ?, 0)
                    """,
                    (name, title, moderator or "SYSOP", ""),
                )
                conn.commit()
                return True, None
            except sqlite3.IntegrityError:
                return False, "Board already exists"

    def update_board(self, name: str, title: str, moderator: str, moderator_id: str = ""):
        if not name or not title:
            return False, "Missing board name or title"
        with self._conn() as conn:
            cur = conn.execute(
                """
                UPDATE boards
                SET title=?, moderator=?, moderator_id=?
                WHERE name=?
                """,
                (title, moderator or "SYSOP", "", name),
            )
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "Board not found"

    def delete_board(self, name: str):
        with self._conn() as conn:
            post_count = conn.execute("SELECT COUNT(*) FROM posts WHERE board=?", (name,)).fetchone()
            if post_count and post_count[0] > 0:
                return False, "Board still has posts"
            cur = conn.execute("DELETE FROM boards WHERE name=?", (name,))
            conn.commit()
            return cur.rowcount > 0, None if cur.rowcount > 0 else "Board not found"

    def get_recent_posts(self, limit: int = 12):
        with self._conn() as conn:
            rows = conn.execute(
                """
                SELECT id, board, author, title, reply_count, push_count, created_at
                FROM posts
                ORDER BY id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [
            {
                "id": row[0],
                "board": row[1],
                "author": row[2],
                "title": row[3],
                "reply_count": row[4],
                "push_count": row[5],
                "created_at": row[6],
            }
            for row in rows
        ]

    def admin_action(self, action: str, payload: dict):
        action = (action or "").lower()
        if action == "create_board":
            ok, error = self.create_board(
                payload.get("name", "").strip(),
                payload.get("title", "").strip(),
                payload.get("moderator", "SYSOP").strip() or "SYSOP",
                payload.get("moderator_id", "").strip(),
            )
        elif action == "update_board":
            ok, error = self.update_board(
                payload.get("name", "").strip(),
                payload.get("title", "").strip(),
                payload.get("moderator", "SYSOP").strip() or "SYSOP",
                payload.get("moderator_id", "").strip(),
            )
        elif action == "delete_board":
            ok, error = self.delete_board(payload.get("name", "").strip())
        elif action == "set_user_ban":
            ok, error = self.set_user_ban(
                payload.get("node_id", "").strip(),
                bool(payload.get("banned")),
            )
        elif action == "set_user_password":
            ok, error = self.set_user_password(
                payload.get("node_id", "").strip(),
                payload.get("password", ""),
            )
        elif action == "clear_user_password":
            ok, error = self.clear_user_password(payload.get("node_id", "").strip())
        elif action == "delete_user":
            ok, error = self.delete_user(payload.get("node_id", "").strip())
        elif action == "clear_mesh_messages":
            ok, error = self.clear_mesh_messages()
        elif action == "create_admin":
            ok, error = self.create_admin(
                payload.get("username", "").strip(),
                payload.get("password", ""),
            )
        elif action == "delete_admin":
            ok, error = self.delete_admin(int(payload.get("admin_id", 0)))
        elif action == "change_admin_password":
            ok, error = self.change_admin_password(
                int(payload.get("admin_id", 0)),
                payload.get("password", ""),
            )
        else:
            return {"ok": False, "msg": f"Unknown action: {action}"}

        if not ok:
            return {"ok": False, "msg": error or "Action failed"}
        return {"ok": True, "msg": "OK"}

    def get_dashboard(self):
        backups = self._backup_entries()
        return {
            "stats": self.get_stats(),
            "boards": self.get_boards(),
            "users": self.get_all_users(),
            "db_path": self.db_path,
            "backup_dir": self.backup_dir,
            "last_backup_path": backups[0]["path"] if backups else "",
            "backups": backups[:8],
            "admins": self.get_all_admins(),
            "recent_posts": self.get_recent_posts(),
            "relay_clients": len(self.relay_clients),
            "admin_clients": len(self.admin_clients),
        }

    def upsert_user(self, node_id: str, name: str, online: bool = True):
        with self._conn() as conn:
            conn.execute(
                """
                INSERT INTO users (node_id, name, last_seen, online, is_web)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT(node_id) DO UPDATE SET
                    name=excluded.name,
                    last_seen=excluded.last_seen,
                    online=excluded.online
                """,
                (node_id, name, _now_iso(), 1 if online else 0),
            )
            conn.commit()

    def get_online_users(self):
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT node_id, name, post_count FROM users WHERE online=1 AND banned=0"
            ).fetchall()
        return [{"node_id": row[0], "name": row[1], "post_count": row[2]} for row in rows]

    def is_admin_hash(self, username: str, pw_hash: str) -> bool:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT password_hash FROM admins WHERE username=?",
                (username,),
            ).fetchone()
        return bool(row and row[0] == pw_hash)

    def mesh_login(self, name: str, pw_hash: str, node_id: str):
        if not name or not pw_hash:
            return None, False, "Missing login fields", None
        with self._conn() as conn:
            conn.execute("UPDATE users SET online=0 WHERE node_id=? AND name<>?", (node_id, name))
            row = conn.execute(
                "SELECT node_id, password_hash, banned FROM users WHERE name=?",
                (name,),
            ).fetchone()
            if not row:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO users
                    (node_id, name, last_seen, online, password_hash, is_web)
                    VALUES (?, ?, ?, 1, ?, 0)
                    """,
                    (node_id, name, _now_iso(), pw_hash),
                )
                conn.commit()
                self.active_client_names[node_id] = name
                return node_id, True, None, None

            stored_node_id, stored_pw_hash, banned = row
            if banned:
                return None, False, "User banned", None
            if stored_pw_hash and stored_pw_hash != pw_hash:
                return None, False, "Wrong password", None
            kicked_id = stored_node_id if stored_node_id != node_id else None
            conn.execute(
                """
                UPDATE users
                SET node_id=?, last_seen=?, online=1, password_hash=?
                WHERE name=?
                """,
                (node_id, _now_iso(), pw_hash, name),
            )
            conn.commit()
            self.active_client_names[node_id] = name
            return node_id, False, None, kicked_id

    def active_client_name(self, node_id: str):
        return (self.active_client_names.get(node_id) or "").strip()

    def push_identity(self, node_id: str):
        return self.active_client_name(node_id) or (node_id or "").strip()

    def create_post(self, board: str, author_id: str, author: str, title: str, body: str):
        with self._conn() as conn:
            banned = conn.execute("SELECT banned FROM users WHERE node_id=?", (author_id,)).fetchone()
            if banned and banned[0]:
                return None, "User banned"
            exists = conn.execute("SELECT 1 FROM boards WHERE name=?", (board,)).fetchone()
            if not exists:
                return None, f"Board not found: {board}"
            conn.execute(
                """
                INSERT INTO posts (board, author_id, author, title, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (board, author_id, author, title, body, _now()),
            )
            post_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
            conn.execute("UPDATE boards SET post_count=post_count+1 WHERE name=?", (board,))
            conn.execute("UPDATE users SET post_count=post_count+1 WHERE node_id=?", (author_id,))
            conn.commit()
            return post_id, None

    def create_reply(self, post_id: int, author_id: str, author: str, body: str):
        with self._conn() as conn:
            exists = conn.execute("SELECT 1 FROM posts WHERE id=?", (post_id,)).fetchone()
            if not exists:
                return None, "Post not found"
            conn.execute(
                """
                INSERT INTO replies (post_id, author_id, author, body, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (post_id, author_id, author, body, _now()),
            )
            reply_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
            conn.execute("UPDATE posts SET reply_count=reply_count+1 WHERE id=?", (post_id,))
            conn.commit()
            return reply_id, None

    def update_post(self, post_id: int, node_id: str, title: str, body: str):
        with self._conn() as conn:
            post = conn.execute(
                "SELECT author FROM posts WHERE id=?",
                (post_id,),
            ).fetchone()
            if not post:
                return False, "Post not found"
            active_name = self.active_client_name(node_id)
            if not active_name or post[0] != active_name:
                return False, "Permission denied"
            conn.execute(
                "UPDATE posts SET title=?, body=? WHERE id=?",
                (title, body, post_id),
            )
            conn.commit()
            return True, None

    def delete_post(self, post_id: int, node_id: str):
        with self._conn() as conn:
            post = conn.execute(
                "SELECT board, author FROM posts WHERE id=?",
                (post_id,),
            ).fetchone()
            if not post:
                return False, "Post not found", None
            board, author_name = post
            active_name = self.active_client_name(node_id)
            if not active_name or author_name != active_name:
                return False, "Permission denied", None
            conn.execute("DELETE FROM replies WHERE post_id=?", (post_id,))
            conn.execute("DELETE FROM posts WHERE id=?", (post_id,))
            conn.execute("UPDATE boards SET post_count=MAX(0, post_count-1) WHERE name=?", (board,))
            conn.commit()
            return True, None, board

    def delete_reply(self, reply_id: int, node_id: str):
        with self._conn() as conn:
            row = conn.execute(
                "SELECT post_id, author FROM replies WHERE id=?",
                (reply_id,),
            ).fetchone()
            if not row:
                return False, "Reply not found"
            active_name = self.active_client_name(node_id)
            if not active_name or row[1] != active_name:
                return False, "Permission denied"
            conn.execute("DELETE FROM replies WHERE id=?", (reply_id,))
            conn.execute("UPDATE posts SET reply_count=MAX(0, reply_count-1) WHERE id=?", (row[0],))
            conn.commit()
            return True, None

    def update_reply(self, reply_id: int, node_id: str, body: str):
        with self._conn() as conn:
            row = conn.execute(
                "SELECT author FROM replies WHERE id=?",
                (reply_id,),
            ).fetchone()
            if not row:
                return False, "Reply not found"
            active_name = self.active_client_name(node_id)
            if not active_name or row[0] != active_name:
                return False, "Permission denied"
            conn.execute("UPDATE replies SET body=? WHERE id=?", (body, reply_id))
            conn.commit()
            return True, None

    def toggle_push(self, post_id: int, user_node_id: str):
        push_key = self.push_identity(user_node_id)
        with self._conn() as conn:
            existing = conn.execute(
                "SELECT 1 FROM post_pushes WHERE post_id=? AND user_node_id=?",
                (post_id, push_key),
            ).fetchone()
            if existing:
                conn.execute(
                    "DELETE FROM post_pushes WHERE post_id=? AND user_node_id=?",
                    (post_id, push_key),
                )
                conn.execute("UPDATE posts SET push_count=MAX(0, push_count-1) WHERE id=?", (post_id,))
                pushed = False
            else:
                conn.execute(
                    "INSERT INTO post_pushes (post_id, user_node_id) VALUES (?, ?)",
                    (post_id, push_key),
                )
                conn.execute("UPDATE posts SET push_count=push_count+1 WHERE id=?", (post_id,))
                pushed = True
            push_count = conn.execute("SELECT push_count FROM posts WHERE id=?", (post_id,)).fetchone()[0]
            conn.commit()
            return push_count, pushed

    def search_posts(self, keyword: str, field: str, board: str | None, user_node_id: str):
        like = f"%{keyword}%"
        column = "p.author" if field == "author" else "p.title"
        push_key = self.push_identity(user_node_id)
        with self._conn() as conn:
            if board:
                rows = conn.execute(
                    f"""
                    SELECT p.id, p.board, p.author, p.title, p.reply_count, p.push_count, p.created_at,
                           CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END
                    FROM posts p
                    LEFT JOIN post_pushes pp ON pp.post_id = p.id AND pp.user_node_id = ?
                    WHERE p.board = ? AND {column} LIKE ?
                    ORDER BY p.id DESC LIMIT 100
                    """,
                    (push_key, board, like),
                ).fetchall()
            else:
                rows = conn.execute(
                    f"""
                    SELECT p.id, p.board, p.author, p.title, p.reply_count, p.push_count, p.created_at,
                           CASE WHEN pp.user_node_id IS NOT NULL THEN 1 ELSE 0 END
                    FROM posts p
                    LEFT JOIN post_pushes pp ON pp.post_id = p.id AND pp.user_node_id = ?
                    WHERE {column} LIKE ?
                    ORDER BY p.id DESC LIMIT 100
                    """,
                    (push_key, like),
                ).fetchall()
        return [
            {
                "id": row[0],
                "board": row[1],
                "author": row[2],
                "title": row[3],
                "reply_count": row[4],
                "push_count": row[5],
                "created_at": row[6],
                "pushed": bool(row[7]),
            }
            for row in rows
        ]

    def save_mesh_message(self, from_id: str, from_name: str, text: str):
        with self._conn() as conn:
            conn.execute(
                """
                INSERT INTO mesh_messages (from_id, from_name, text, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (from_id, from_name, text, _now()),
            )
            conn.commit()

    def change_current_user_password(self, node_id: str, pw_hash: str):
        active_name = self.active_client_name(node_id)
        if not active_name or not pw_hash:
            return False, "Missing password or login"
        with self._conn() as conn:
            cur = conn.execute(
                "UPDATE users SET password_hash=? WHERE name=?",
                (pw_hash, active_name),
            )
            conn.commit()
        return cur.rowcount > 0, None if cur.rowcount > 0 else "User not found"

    def handle_request(self, cmd: str, args: str, node_id: str, node_name: str):
        cmd = (cmd or "").upper()

        if cmd == "LOGIN":
            parts = args.split(":", 1)
            name = parts[0].strip() if parts else ""
            pw_hash = parts[1].strip() if len(parts) > 1 else ""
            if not name:
                name = node_name or node_id
            actual_id, is_new, error, _ = self.mesh_login(name, pw_hash, node_id)
            if error:
                return _json_dumps({"type": "login_error", "msg": error})
            self.relay_clients.add(actual_id)
            if self.is_admin_hash(name, pw_hash):
                self.admin_clients.add(actual_id)
            else:
                self.admin_clients.discard(actual_id)
            return _json_dumps(
                {
                    "type": "login_ok",
                    "node_id": actual_id,
                    "name": name,
                    "new_user": is_new,
                    "is_admin": actual_id in self.admin_clients,
                }
            )

        if cmd == "LOGOUT":
            self.relay_clients.discard(node_id)
            self.admin_clients.discard(node_id)
            self.active_client_names.pop(node_id, None)
            self.upsert_user(node_id, args or node_name or node_id, False)
            return None

        if cmd in ("LIST", "LIST2"):
            boards = [
                [item["name"], item["title"], item["post_count"], item["moderator"], item["moderator_id"]]
                for item in self.get_boards()
            ]
            return _json_dumps({"t": "B", "b": boards})

        if cmd in ("POSTS", "POSTS2"):
            parts = args.split(":", 1)
            board = parts[0]
            page = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1
            posts, total = self.get_posts(board, page, user_node_id=node_id)
            if posts is None:
                return _json_dumps({"type": "error", "msg": f"Board not found: {board}"})
            compact = [
                [item["id"], item["author_id"], item["author"], item["title"], item["reply_count"], item["created_at"][:10], item["push_count"], 1 if item.get("pushed") else 0]
                for item in posts
            ]
            return _json_dumps({"t": "P", "b": board, "p": compact, "n": total, "g": page})

        if cmd in ("READ", "READ2"):
            post, replies = self.get_post(int(args), node_id)
            if not post:
                return _json_dumps({"type": "error", "msg": "Post not found"})
            post_c = [
                post["id"],
                post["author_id"],
                post["author"],
                post["title"],
                post["body"],
                post["created_at"][:16],
                post["board"],
                post.get("push_count", 0),
                1 if post.get("pushed") else 0,
            ]
            replies_c = [
                [item["id"], item["author_id"], item["author"], item["body"], item["created_at"][:16]]
                for item in replies
            ]
            return _json_dumps({"t": "R", "p": post_c, "r": replies_c})

        if cmd == "POST":
            parts = args.split(":", 3)
            if len(parts) < 4:
                return _json_dumps({"type": "error", "msg": "Bad POST format"})
            board, author, title, body = parts
            author = self.active_client_name(node_id) or author or node_name or node_id
            post_id, error = self.create_post(board, node_id, author, title, body)
            if error:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "post_created", "post_id": post_id})

        if cmd == "REPLY":
            parts = args.split(":", 2)
            if len(parts) < 3:
                return _json_dumps({"type": "error", "msg": "Bad REPLY format"})
            post_id, author, body = int(parts[0]), parts[1], parts[2]
            author = self.active_client_name(node_id) or author or node_name or node_id
            reply_id, error = self.create_reply(post_id, node_id, author, body)
            if error:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "reply_created", "reply_id": reply_id})

        if cmd == "EDIT":
            parts = args.split(":", 2)
            if len(parts) < 3:
                return _json_dumps({"type": "error", "msg": "Bad EDIT format"})
            post_id = int(parts[0])
            ok, error = self.update_post(post_id, node_id, parts[1], parts[2])
            if not ok:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "post_edited", "post_id": post_id})

        if cmd == "DEL":
            ok, error, board = self.delete_post(int(args), node_id)
            if not ok:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "post_deleted", "post_id": int(args), "board": board})

        if cmd == "DELREP":
            ok, error = self.delete_reply(int(args), node_id)
            if not ok:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "reply_deleted", "reply_id": int(args)})

        if cmd == "EDITREP":
            parts = args.split(":", 1)
            if len(parts) < 2:
                return _json_dumps({"type": "error", "msg": "Bad EDITREP format"})
            reply_id = int(parts[0])
            ok, error = self.update_reply(reply_id, node_id, parts[1])
            if not ok:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "reply_edited", "reply_id": reply_id})

        if cmd == "PUSH":
            push_count, pushed = self.toggle_push(int(args), node_id)
            return _json_dumps({"type": "push_updated", "post_id": int(args), "push_count": push_count, "pushed": pushed})

        if cmd == "CHPASS":
            ok, error = self.change_current_user_password(node_id, args.strip())
            if not ok:
                return _json_dumps({"type": "error", "msg": error})
            return _json_dumps({"type": "password_changed", "name": self.active_client_name(node_id)})

        if cmd == "SEARCH":
            parts = args.split(":", 2)
            field = parts[0] if len(parts) > 0 else "title"
            board = parts[1] if len(parts) > 1 else ""
            keyword = parts[2] if len(parts) > 2 else ""
            results = self.search_posts(keyword, field, board or None, node_id)
            return _json_dumps({"type": "search_results", "posts": results, "query": keyword, "total": len(results)})

        if cmd == "CHAT":
            parts = args.split(":", 1)
            author = parts[0] if parts else node_name
            text = parts[1] if len(parts) > 1 else ""
            self.upsert_user(node_id, author, True)
            self.save_mesh_message(node_id, author, text)
            return None

        return _json_dumps({"type": "error", "msg": f"Unknown command: {cmd}"})


_CORE = AndroidServerCore()


def bootstrap():
    return json.dumps(_CORE.get_dashboard(), ensure_ascii=False)


def get_dashboard():
    return json.dumps(_CORE.get_dashboard(), ensure_ascii=False)


def configure_storage(db_path="", backup_dir=""):
    return json.dumps(_CORE.configure_storage(db_path, backup_dir), ensure_ascii=False)


def backup_database():
    return json.dumps(_CORE.backup_database(), ensure_ascii=False)


def import_database(source_path=""):
    return json.dumps(_CORE.import_database(source_path), ensure_ascii=False)


def admin_action(action, payload_json="{}"):
    payload = json.loads(payload_json or "{}")
    return json.dumps(_CORE.admin_action(action, payload), ensure_ascii=False)


def handle_request(cmd, args, node_id, node_name):
    return _CORE.handle_request(cmd, args, node_id, node_name)
