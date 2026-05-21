#!/usr/bin/env python3
"""
Meshtastic BBS 文字模式客戶端
================================
透過 USB 連接本機 Meshtastic 裝置，以 LoRa 無線電存取 BBS 伺服器。

安裝依賴：
    pip install meshtastic colorama

執行：
    python mesh_bbs_client.py
    python mesh_bbs_client.py --port COM3
    python mesh_bbs_client.py --port COM3 --server !abc12345
"""

import sys, os, time, json, threading, argparse, textwrap, unicodedata, hashlib, base64, zlib
from queue import Queue, Empty
from datetime import datetime

DEBUG = False  # 由 --debug 旗標啟用

# ── Color support ─────────────────────────────────────────────
try:
    import colorama
    from colorama import Fore, Back, Style
    colorama.init()
    HAS_COLOR = True
except ImportError:
    HAS_COLOR = False
    class _D:
        def __getattr__(self, _): return ""
    Fore = Back = Style = _D()

# ── Meshtastic ────────────────────────────────────────────────
try:
    import meshtastic
    import meshtastic.serial_interface
    try:
        from meshtastic.protobuf import portnums_pb2
    except ImportError:
        portnums_pb2 = None
    from pubsub import pub
except ImportError:
    print("請安裝：pip install meshtastic")
    sys.exit(1)

BBS_PRIVATE_PREFIX = b"MBBS1"
BBS_BINARY_PREFIX = b"MBBS2|"
PRIVATE_APP_PORTNUM = getattr(
    getattr(globals().get("portnums_pb2", None), "PortNum", object),
    "PRIVATE_APP",
    256,
)


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


# ── Key reading ───────────────────────────────────────────────
if sys.platform == "win32":
    import msvcrt
    def _getch():
        ch = msvcrt.getwch()
        if ch in ('\x00', '\xe0'):
            ext = msvcrt.getwch()
            return {'H':'UP','P':'DOWN','K':'LEFT','M':'RIGHT',
                    'I':'PGUP','Q':'PGDN'}.get(ext, '')
        if ch == '\r':   return 'ENTER'
        if ch == '\x1b': return 'ESC'
        if ch == '\x08': return 'BS'
        if ch == '\x18': return 'CTRL_X'
        if ch == '\x11': return 'CTRL_Q'
        return ch
else:
    import tty, termios
    def _getch():
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            ch = sys.stdin.read(1)
            if ch == '\x1b':
                ch2 = sys.stdin.read(1)
                if ch2 == '[':
                    ch3 = sys.stdin.read(1)
                    return {'A':'UP','B':'DOWN','C':'RIGHT','D':'LEFT',
                            '5':'PGUP','6':'PGDN'}.get(ch3,'ESC')
                return 'ESC'
            if ch in ('\r', '\n'): return 'ENTER'
            if ch == '\x7f':       return 'BS'
            if ch == '\x18':       return 'CTRL_X'
            if ch == '\x11':       return 'CTRL_Q'
            return ch
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)

# ── Terminal helpers ──────────────────────────────────────────
W = 64

def cls():
    os.system('cls' if sys.platform == 'win32' else 'clear')

def _dw(s: str) -> int:
    """計算字串的顯示寬度（中文等全形字元算 2）。"""
    return sum(2 if unicodedata.east_asian_width(c) in ('W', 'F') else 1 for c in s)

def _trunc_dw(s: str, max_dw: int) -> str:
    """依顯示寬度截斷字串。"""
    result, cur = [], 0
    for ch in s:
        w = 2 if unicodedata.east_asian_width(ch) in ('W', 'F') else 1
        if cur + w > max_dw:
            break
        result.append(ch)
        cur += w
    return ''.join(result)

def _ljust_dw(s: str, width: int) -> str:
    return s + ' ' * max(0, width - _dw(s))

def _rjust_dw(s: str, width: int) -> str:
    return ' ' * max(0, width - _dw(s)) + s

def _center_dw(s: str, width: int) -> str:
    excess = max(0, width - _dw(s))
    lp = excess // 2
    return ' ' * lp + s + ' ' * (excess - lp)

def hr(ch='─', color=Fore.CYAN):
    print(color + ch * W + Style.RESET_ALL)

def header(title: str, user: str = ""):
    cls()
    top = '╔' + '═' * (W - 2) + '╗'
    bot = '╚' + '═' * (W - 2) + '╝'
    right = f"  {user}" if user else ""
    left  = f"  Meshtastic BBS  ◆  {title}"
    pad   = W - 2 - _dw(left) - _dw(right)
    mid   = left + ' ' * max(0, pad) + right
    while _dw(mid) > W - 2 and mid:
        mid = mid[:-1]
    print(Fore.CYAN + top + Style.RESET_ALL)
    print(Fore.CYAN + '║' + Style.RESET_ALL +
          Fore.YELLOW + mid + Style.RESET_ALL +
          Fore.CYAN + '║' + Style.RESET_ALL)
    print(Fore.CYAN + bot + Style.RESET_ALL)

def status_line(text: str):
    """Show bottom hint bar."""
    hr('─', Fore.CYAN)
    print(f"  {Fore.CYAN}{text}{Style.RESET_ALL}")

def input_text(prompt: str, mask: bool = False) -> str:
    """Line input; mask=True for password."""
    print(Fore.YELLOW + prompt + Style.RESET_ALL, end='', flush=True)
    if mask:
        buf = []
        while True:
            ch = _getch()
            if ch in ('ENTER',):
                print()
                return ''.join(buf)
            elif ch == 'BS' and buf:
                buf.pop()
                print('\b \b', end='', flush=True)
            elif isinstance(ch, str) and len(ch) == 1 and ch.isprintable():
                buf.append(ch)
                print('●', end='', flush=True)
    else:
        return input()

def _read_line(prompt: str = '', max_len: int = 200) -> tuple:
    """讀取一行輸入，支援 Ctrl+X（送出）、Ctrl+Q（取消）。
    回傳 (text, 'ENTER' | 'CTRL_X' | 'CTRL_Q')。
    """
    buf: list = []
    if prompt:
        sys.stdout.write(prompt)
        sys.stdout.flush()
    while True:
        k = _getch()
        if k == 'ENTER':
            sys.stdout.write('\n'); sys.stdout.flush()
            return ''.join(buf), 'ENTER'
        if k == 'CTRL_X':
            sys.stdout.write('\n'); sys.stdout.flush()
            return ''.join(buf), 'CTRL_X'
        if k == 'CTRL_Q':
            sys.stdout.write('\n'); sys.stdout.flush()
            return '', 'CTRL_Q'
        if k == 'BS':
            if buf:
                ch = buf.pop()
                w = 2 if ord(ch) > 127 else 1
                sys.stdout.write('\b' * w + ' ' * w + '\b' * w)
                sys.stdout.flush()
        elif k and len(k) == 1 and len(buf) < max_len:
            buf.append(k)
            sys.stdout.write(k); sys.stdout.flush()

def wrap_text(text: str, width: int = W - 6) -> list[str]:
    lines = []
    for raw in text.splitlines():
        if not raw:
            lines.append('')
        else:
            lines.extend(textwrap.wrap(raw, width) or [''])
    return lines

def press_enter(msg: str = "  按 Enter 繼續..."):
    input(Fore.CYAN + msg + Style.RESET_ALL)


# ═══════════════════════════════════════════════════════════════
# BBS Protocol client
# ═══════════════════════════════════════════════════════════════
_seq_lock = threading.Lock()
_seq_val  = 0

def _next_seq() -> str:
    global _seq_val
    with _seq_lock:
        _seq_val = (_seq_val + 1) % 10000
        return str(_seq_val)


class MeshBBSClient:
    def __init__(self, port: str | None = None, server_id: str | None = None):
        self.port      = port
        self.server_id = server_id
        self.iface     = None
        self.my_id     = ""
        self.my_name   = ""
        self.username  = ""

        self._pending:        dict  = {}  # seq -> {chunks:{}, total:int, ts:float}
        self._completed_seqs: dict  = {}  # seq -> timestamp（防重複處理）
        self._seen_pkt_ids:   set   = set()  # 防止多 topic 訂閱導致重複處理
        self._lock    = threading.Lock()
        self._resp_q  = Queue()    # (seq, obj)
        self.chat_q   = Queue()    # incoming chat messages
        self.kicked   = False      # 被伺服器踢出旗標
        self.kick_msg = ""         # 踢出原因訊息

    # ── Connect ────────────────────────────────────────────────
    def connect(self) -> tuple[bool, str]:
        try:
            self.iface = (meshtastic.serial_interface.SerialInterface(self.port)
                          if self.port else
                          meshtastic.serial_interface.SerialInterface())
            info = self.iface.getMyNodeInfo() or {}
            user = info.get("user", {})
            self.my_id   = user.get("id", "") or ""
            self.my_name = user.get("longName", "") or ""
            if DEBUG:
                print(f"\n[DBG] 連線成功 my_id={self.my_id!r} my_name={self.my_name!r}", flush=True)
            self._subscribe()
            time.sleep(0.8)   # 讓無線電穩定後再開始通訊
            return True, ""
        except Exception as e:
            return False, str(e)

    def _subscribe(self):
        """訂閱 Meshtastic 接收事件。

        meshtastic-python 各版本使用不同 topic 名稱：
        - 舊版: meshtastic.receive.text
        - 新版: meshtastic.receive.text_message_app
        - 通用: meshtastic.receive

        訂閱全部 topic，以相容所有版本。
        重複收到同一封包由 _on_receive 內的 _seen_pkt_ids 去重。
        """
        ALL_TOPICS = (
            "meshtastic.receive",
            "meshtastic.receive.text",
            "meshtastic.receive.text_message_app",
            "meshtastic.receive.private_app",
        )
        # 清除所有舊訂閱
        for topic in ALL_TOPICS:
            try:
                pub.unsubscribe(self._on_receive, topic)
            except Exception:
                pass
        # 訂閱全部 topic（不 break），確保相容各版本
        for topic in ALL_TOPICS:
            try:
                pub.subscribe(self._on_receive, topic)
            except Exception:
                pass

    def reconnect(self, new_port: str | None = None) -> tuple[bool, str]:
        """Close existing connection and reconnect, optionally on a new port."""
        try:
            if self.iface:
                for topic in ("meshtastic.receive",
                              "meshtastic.receive.text",
                              "meshtastic.receive.text_message_app",
                              "meshtastic.receive.private_app"):
                    try:
                        pub.unsubscribe(self._on_receive, topic)
                    except Exception:
                        pass
                try:
                    self.iface.close()
                except Exception:
                    pass
                self.iface = None
        except Exception:
            pass
        if new_port is not None:
            self.port = new_port
        return self.connect()

    def disconnect(self):
        try:
            if self.username:
                self._raw_send("LOGOUT", self.username)
        except Exception:
            pass
        try:
            if self.iface:
                self.iface.close()
        except Exception:
            pass

    def get_nodes(self) -> list[tuple[str, str]]:
        """Return list of (node_id, name) for all visible nodes except self."""
        result = []
        try:
            my = self.my_id
            if self.iface and self.iface.nodes:
                for _nnum, ninfo in self.iface.nodes.items():
                    u   = ninfo.get("user", {})
                    nid = u.get("id", "")
                    nm  = u.get("longName", nid)
                    if nid and nid != my:
                        result.append((nid, nm))
        except Exception:
            pass
        return result

    # ── Message receive ────────────────────────────────────────
    def _on_receive(self, packet, interface=None):
        """處理接收到的 Meshtastic 封包。

        相容 meshtastic.receive / .text / .text_message_app 三種 topic。
        不做 portnum 過濾（避免版本差異導致靜默丟棄），僅依 decoded.text 判斷。
        """
        try:
            # 去重：同一封包 ID 在多個 topic 下可能被觸發多次
            pkt_id = packet.get("id", 0)
            if pkt_id:
                with self._lock:
                    if pkt_id in self._seen_pkt_ids:
                        if DEBUG:
                            print(f"\n[DBG] 重複封包忽略 id={pkt_id}", flush=True)
                        return
                    self._seen_pkt_ids.add(pkt_id)
                    if len(self._seen_pkt_ids) > 200:
                        self._seen_pkt_ids = set(list(self._seen_pkt_ids)[-100:])

            decoded = packet.get("decoded", {})
            payload = _bytes_payload(decoded.get("payload"))
            if payload.startswith(BBS_BINARY_PREFIX):
                self._handle_res_binary(payload)
                return
            text    = (decoded.get("text") or "").strip()
            if not text:
                text = _decode_bbs_private(decoded)
            if DEBUG:
                from_id = str(packet.get("fromId", "?"))
                print(f"\n[DBG] 收到封包 from={from_id} text={text[:80]!r}", flush=True)
            if not text:
                return  # 非文字訊息

            from_id = str(packet.get("fromId", ""))

            if text.startswith("BBS:RES:"):
                self._handle_res(text)
            elif not text.startswith("BBS:"):
                # 偵測伺服器踢出通知（帳號已在另一台裝置登入）
                if from_id == self.server_id and "已被登出" in text:
                    self.kicked   = True
                    self.kick_msg = text
                    return
                # Plain chat — 推入 chat_q
                snum = packet.get("from", 0)
                name = ""
                try:
                    if self.iface and self.iface.nodes:
                        nd = self.iface.nodes.get(str(snum))
                        if nd:
                            name = nd.get("user", {}).get("longName", "")
                except Exception:
                    pass
                self.chat_q.put({
                    "from": name or from_id,
                    "text": text,
                    "time": datetime.now().strftime("%H:%M"),
                })
        except Exception:
            pass

    def _handle_res(self, text: str):
        """解析並重組伺服器回應。

        新格式（廣播模式）: BBS:RES:<dest_id>:<seq>:<idx>:<total>:<data>
        舊格式（相容）    : BBS:RES:<seq>:<idx>:<total>:<data>

        dest_id 以 '!' 開頭代表是新格式；否則視為舊格式（seq 是數字）。
        """
        # 先嘗試新格式（7 段）
        parts7 = text.split(":", 6)
        if DEBUG:
            print(f"\n[DBG] _handle_res parts7={parts7[:4]} my_id={self.my_id!r}", flush=True)
        if len(parts7) == 7 and parts7[2].startswith("!"):
            dest_id = parts7[2]
            # 只在 my_id 確實是有效節點 ID（以 ! 開頭）時才過濾，
            # 並用不分大小寫比較，避免版本差異造成格式不符。
            if (self.my_id and self.my_id.startswith("!")
                    and dest_id.lower() != self.my_id.lower()):
                if DEBUG:
                    print(f"\n[DBG] 過濾：dest_id={dest_id!r} != my_id={self.my_id!r}", flush=True)
                return
            try:
                seq   = parts7[3]
                idx   = int(parts7[4])
                total = int(parts7[5])
                data  = parts7[6]
            except (ValueError, IndexError):
                return
        else:
            # 舊格式（6 段）
            parts6 = text.split(":", 5)
            if len(parts6) < 6:
                return
            try:
                seq   = parts6[2]
                idx   = int(parts6[3])
                total = int(parts6[4])
                data  = parts6[5]
            except (ValueError, IndexError):
                return

        now = time.time()
        with self._lock:
            # 防重複：同一 seq 已處理過則忽略（訂閱多 topic 時可能收到兩次）
            if seq in self._completed_seqs:
                return

            if seq not in self._pending:
                self._pending[seq] = {"chunks": {}, "total": total, "ts": now}
            entry = self._pending[seq]
            entry["chunks"][idx] = data
            entry["total"] = total

            if len(entry["chunks"]) >= entry["total"]:
                full = "".join(entry["chunks"].get(i, "")
                               for i in range(entry["total"]))
                del self._pending[seq]
                try:
                    raw = base64.b64decode(full.encode("ascii"))
                    try:
                        raw = zlib.decompress(raw)   # zlib 壓縮格式
                    except zlib.error:
                        pass                         # 非壓縮格式，直接用原始 bytes
                    obj = json.loads(raw.decode("utf-8"))
                except Exception:
                    try:
                        obj = json.loads(full)       # 相容純文字舊格式
                    except json.JSONDecodeError:
                        obj = {"type": "error", "msg": "回應解析失敗"}
                # 標記為已完成，防止重複推入 queue
                self._completed_seqs[seq] = now
                self._resp_q.put((seq, obj))

            # 清理過期記錄
            cutoff = now - 120
            for k in [k for k, v in self._pending.items()
                      if v.get("ts", now) < cutoff]:
                del self._pending[k]
            for k in [k for k, v in self._completed_seqs.items() if v < cutoff]:
                del self._completed_seqs[k]

    # ── Send & wait ────────────────────────────────────────────
    def _handle_res_binary(self, payload: bytes):
        try:
            nl = payload.find(b"\n")
            if nl <= 0:
                return
            header = payload[:nl].decode("utf-8", errors="ignore")
            parts = header.split("|")
            if len(parts) < 5 or parts[0] != "MBBS2":
                return
            dest_id = parts[1]
            seq = parts[2]
            idx = int(parts[3])
            total = int(parts[4])
            data = payload[nl + 1:]
        except Exception:
            return

        now = time.time()
        with self._lock:
            if seq in self._completed_seqs:
                return
            if self.my_id and self.my_id.startswith("!") and dest_id.lower() != self.my_id.lower():
                return
            if seq not in self._pending:
                self._pending[seq] = {"chunks": {}, "total": total, "ts": now}
            entry = self._pending[seq]
            entry["chunks"][idx] = data
            entry["total"] = total
            if len(entry["chunks"]) >= entry["total"]:
                full = b"".join(entry["chunks"].get(i, b"") for i in range(entry["total"]))
                del self._pending[seq]
                try:
                    raw = zlib.decompress(full)
                    obj = json.loads(raw.decode("utf-8"))
                except Exception:
                    try:
                        obj = json.loads(full.decode("utf-8"))
                    except Exception:
                        obj = {"type": "error", "msg": "回應解析失敗"}
                self._completed_seqs[seq] = now
                self._resp_q.put((seq, obj))

            cutoff = now - 120
            for k in [k for k, v in self._pending.items() if v.get("ts", now) < cutoff]:
                del self._pending[k]
            for k in [k for k, v in self._completed_seqs.items() if v < cutoff]:
                del self._completed_seqs[k]

    def _raw_send(self, cmd: str, args: str = "", seq: str | None = None) -> str:
        if seq is None:
            seq = _next_seq()
        msg  = f"BBS:REQ:{seq}:{cmd}:{args}"
        dest = self.server_id
        payload = BBS_PRIVATE_PREFIX + msg.encode("utf-8")
        if dest:
            self.iface.sendData(payload, destinationId=dest,
                                portNum=PRIVATE_APP_PORTNUM, wantAck=False)
        else:
            self.iface.sendData(payload, portNum=PRIVATE_APP_PORTNUM, wantAck=False)
        return seq

    def request(self, cmd: str, args: str = "", timeout: int = 18) -> tuple[dict | None, str]:
        """Send request and wait for JSON response."""
        seq = self._raw_send(cmd, args)
        deadline = time.time() + timeout
        buf: list[tuple] = []
        while time.time() < deadline:
            try:
                got_seq, obj = self._resp_q.get(timeout=0.25)
                if got_seq == seq:
                    for item in buf:           # restore unrelated responses
                        self._resp_q.put(item)
                    return obj, ""
                buf.append((got_seq, obj))
            except Empty:
                pass
        for item in buf:
            self._resp_q.put(item)
        return None, "等待伺服器回應逾時"

    # ── BBS operations ─────────────────────────────────────────
    def login(self, name: str, password: str, retries: int = 2) -> tuple[bool, str | dict]:
        pw_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
        args    = f"{name}:{pw_hash}"
        last_err = "無回應"
        for attempt in range(retries + 1):
            obj, err = self.request("LOGIN", args, timeout=25)
            if err:
                last_err = err
                if attempt < retries:
                    time.sleep(1)
                continue
            if obj and obj.get("type") == "login_ok":
                self.username = obj.get("name", name)
                return True, obj
            if obj and obj.get("type") == "login_error":
                return False, obj.get("msg", "登入失敗")
            return False, (obj.get("msg", "登入失敗") if obj else "無回應")
        return False, last_err

    def get_boards(self, retries: int = 1) -> tuple[list, str]:
        last_err = "無回應"
        for attempt in range(retries + 1):
            if attempt > 0:
                time.sleep(1)
            obj, err = self.request("LIST", timeout=15)
            if err:
                last_err = err
                continue
            if obj and obj.get("type") == "boards":
                boards = []
                for b in obj.get("boards", []):
                    if isinstance(b, list):
                        # 新版陣列格式: [name, title, post_count]
                        boards.append({
                            "name":       b[0] if len(b) > 0 else "",
                            "title":      b[1] if len(b) > 1 else "",
                            "post_count": b[2] if len(b) > 2 else 0,
                        })
                    else:
                        boards.append(b)
                return boards, ""
            actual_type = obj.get("type", "無回應") if obj else "無回應"
            actual_msg  = obj.get("msg", "") if obj else ""
            last_err = f"取得看板失敗({actual_type}" + (f": {actual_msg})" if actual_msg else ")")
        return [], last_err

    def get_posts(self, board: str, page: int = 1, retries: int = 1) -> tuple[list, int, str]:
        last_err = "無回應"
        for attempt in range(retries + 1):
            if attempt > 0:
                time.sleep(1)
            obj, err = self.request("POSTS", f"{board}:{page}", timeout=15)
            if err:
                last_err = err
                continue
            if obj and obj.get("type") == "posts":
                posts = []
                for p in obj.get("posts", []):
                    if isinstance(p, list):
                        posts.append({
                            "id":          p[0] if len(p) > 0 else 0,
                            "author_id":   p[1] if len(p) > 1 else "",
                            "author":      p[2] if len(p) > 2 else "",
                            "title":       p[3] if len(p) > 3 else "",
                            "reply_count": p[4] if len(p) > 4 else 0,
                            "created_at":  p[5] if len(p) > 5 else "",
                        })
                    else:
                        posts.append(p)
                return posts, obj.get("total", 0), ""
            last_err = obj.get("msg", "失敗") if obj else "無回應"
        return [], 0, last_err

    def get_post(self, post_id: int, retries: int = 1) -> tuple[dict | None, list, str]:
        last_err = "無回應"
        for attempt in range(retries + 1):
            if attempt > 0:
                time.sleep(2)
            obj, err = self.request("READ", str(post_id), timeout=60)
            if err:
                last_err = err
                continue
            if obj and obj.get("type") == "post":
                # 支援緊湊陣列格式（"p"）與舊版物件格式（"post"）
                p = obj.get("p")
                if p and isinstance(p, list):
                    post = {
                        "id":         p[0] if len(p) > 0 else 0,
                        "author_id":  p[1] if len(p) > 1 else "",
                        "author":     p[2] if len(p) > 2 else "",
                        "title":      p[3] if len(p) > 3 else "",
                        "body":       p[4] if len(p) > 4 else "",
                        "created_at": p[5] if len(p) > 5 else "",
                    }
                else:
                    post = obj.get("post", {})
                replies = []
                for r in obj.get("r", obj.get("replies", [])):
                    if isinstance(r, list):
                        replies.append({
                            "id":         r[0] if len(r) > 0 else 0,
                            "author_id":  r[1] if len(r) > 1 else "",
                            "author":     r[2] if len(r) > 2 else "",
                            "body":       r[3] if len(r) > 3 else "",
                            "created_at": r[4] if len(r) > 4 else "",
                        })
                    else:
                        replies.append(r)
                return post, replies, ""
            last_err = obj.get("msg", "失敗") if obj else "無回應"
        return None, [], last_err

    def create_post(self, board: str, title: str, body: str) -> tuple[bool, str]:
        obj, err = self.request("POST", f"{board}:{self.username}:{title}:{body}")
        if err:
            return False, err
        if obj and obj.get("type") == "post_created":
            return True, ""
        return False, (obj.get("msg", "發文失敗") if obj else "無回應")

    def create_reply(self, post_id: int, body: str) -> tuple[bool, str]:
        obj, err = self.request("REPLY", f"{post_id}:{self.username}:{body}")
        if err:
            return False, err
        if obj and obj.get("type") == "reply_created":
            return True, ""
        return False, (obj.get("msg", "回覆失敗") if obj else "無回應")

    def search_posts(self, field: str, board: str, keyword: str) -> tuple[list, str]:
        obj, err = self.request("SEARCH", f"{field}:{board}:{keyword}", timeout=20)
        if err:
            return [], err
        if obj and obj.get("type") in ("search_results", "posts"):
            return obj.get("posts", []), ""
        return [], (obj.get("msg", "搜尋失敗") if obj else "無回應")

    def push_post(self, post_id: int) -> tuple[bool, int, str]:
        obj, err = self.request("PUSH", str(post_id))
        if err:
            return False, 0, err
        if obj and obj.get("type") == "push_updated":
            return obj.get("pushed", False), obj.get("push_count", 0), ""
        return False, 0, (obj.get("msg", "操作失敗") if obj else "無回應")

    def edit_post(self, post_id: int, title: str, body: str) -> tuple[bool, str]:
        obj, err = self.request("EDIT", f"{post_id}:{title}:{body}", timeout=20)
        if err:
            return False, err
        if obj and obj.get("type") == "post_edited":
            return True, ""
        return False, (obj.get("msg", "編輯失敗") if obj else "無回應")

    def delete_post(self, post_id: int) -> tuple[bool, str]:
        obj, err = self.request("DEL", str(post_id))
        if err:
            return False, err
        if obj and obj.get("type") == "post_deleted":
            return True, ""
        return False, (obj.get("msg", "刪除失敗") if obj else "無回應")

    def delete_reply(self, reply_id: int) -> tuple[bool, str]:
        obj, err = self.request("DELREP", str(reply_id))
        if err:
            return False, err
        if obj and obj.get("type") == "reply_deleted":
            return True, ""
        return False, (obj.get("msg", "刪除失敗") if obj else "無回應")

    def send_chat(self, text: str):
        self._raw_send("CHAT", f"{self.username}:{text}")


# ═══════════════════════════════════════════════════════════════
# UI
# ═══════════════════════════════════════════════════════════════
PER_PAGE = 10

class KickedError(Exception):
    """帳號被伺服器強制登出時拋出。"""
    pass

class BBSUI:
    def __init__(self, client: MeshBBSClient):
        self.c  = client
        self._cur_board: dict = {}

    def run(self):
        self._login()

    def _check_kicked(self):
        """若已被伺服器踢出，拋出 KickedError 強制回到登入畫面。"""
        if self.c.kicked:
            raise KickedError(self.c.kick_msg)

    # ── Login ──────────────────────────────────────────────────
    def _login(self):
        _IW = W - 4  # inner box width
        def _box_line(text='', fg=Style.RESET_ALL):
            inner = _ljust_dw(text, _IW)
            while _dw(inner) > _IW:
                inner = inner[:-1]
            print(f"  {Fore.CYAN}│{Style.RESET_ALL}{fg}{inner}{Style.RESET_ALL}{Fore.CYAN}│{Style.RESET_ALL}")

        while True:
            cls()
            # Banner
            print(Fore.CYAN + '╔' + '═'*(W-2) + '╗' + Style.RESET_ALL)
            banner = '◎  Meshtastic BBS  ◆  LoRa 無線討論板系統'
            print(Fore.CYAN + '║' + Style.RESET_ALL +
                  Fore.YELLOW + _center_dw(banner, W - 2) + Style.RESET_ALL +
                  Fore.CYAN + '║' + Style.RESET_ALL)
            print(Fore.CYAN + '╚' + '═'*(W-2) + '╝' + Style.RESET_ALL)
            print()

            # 節點資訊框
            srv = self.c.server_id or '（廣播模式）'
            print(f"  {Fore.CYAN}┌{'─'*_IW}┐{Style.RESET_ALL}")
            _box_line(f"  伺服器節點：{Fore.GREEN}{srv}", Fore.RESET)
            _box_line(f"  我的節點  ：{Fore.GREEN}{self.c.my_id}", Fore.RESET)
            print(f"  {Fore.CYAN}└{'─'*_IW}┘{Style.RESET_ALL}")
            print()

            name = input_text("  帳　　號：").strip()
            if not name:
                continue
            password = input_text("  密　　碼：", mask=True).strip()
            if not password:
                print(Fore.RED + "  ✗  密碼不能為空" + Style.RESET_ALL)
                press_enter()
                continue
            hr('─', Fore.CYAN)
            print(Fore.YELLOW + "  正在登入，請稍候..." + Style.RESET_ALL)
            ok, result = self.c.login(name, password)
            if ok:
                is_new = isinstance(result, dict) and result.get("new_user")
                if is_new:
                    print(Fore.GREEN + f"  ✓  新帳號「{name}」建立成功！" + Style.RESET_ALL)
                    time.sleep(0.8)
                else:
                    print(Fore.GREEN + f"  ✓  歡迎回來，{name}！" + Style.RESET_ALL)
                self.c.kicked   = False
                self.c.kick_msg = ""
                time.sleep(1)
                try:
                    self._boards()
                except KickedError as e:
                    self.c.kicked   = False
                    self.c.kick_msg = ""
                    print()
                    print(Fore.RED + f"  ⚠  {e}" + Style.RESET_ALL)
                    print(Fore.YELLOW + "  請重新登入。" + Style.RESET_ALL)
                    press_enter()
                continue
            print(Fore.RED + f"  ✗  登入失敗：{result}" + Style.RESET_ALL)
            if "逾時" in str(result):
                print(Fore.CYAN + "  提示：請確認伺服器節點已啟動，且無線電訊號良好。" + Style.RESET_ALL)
            press_enter()

    # ── Board list ─────────────────────────────────────────────
    def _boards(self):
        sel = 0
        while True:
            boards, err = self.c.get_boards()
            if err:
                header("看板列表", self.c.username)
                print(Fore.RED + f"  錯誤: {err}" + Style.RESET_ALL)
                print()
                print("  [C] 聊天  [R] 重新整理  [Q] 離開")
                k = _getch().upper()
                if k == 'C': self._chat()
                elif k == 'Q': self._exit()
                continue

            if sel >= len(boards):
                sel = max(0, len(boards) - 1)

            while True:
                header("看板列表", self.c.username)
                # 欄位標題
                hdr = (f"  {'':2}{_rjust_dw('編號',3)} "
                       f"{Fore.CYAN}{_ljust_dw('看板代碼',12)}"
                       f"{_ljust_dw('說　明',24)}"
                       f"{_rjust_dw('篇數',5)}{Style.RESET_ALL}")
                print(hdr)
                hr('─', Fore.CYAN)
                for i, b in enumerate(boards):
                    num   = _rjust_dw(str(i+1), 3)
                    code  = _ljust_dw(_trunc_dw(b.get('name',  ''), 11), 12)
                    title = _ljust_dw(_trunc_dw(b.get('title', ''), 22), 24)
                    cnt   = str(b.get('post_count', 0))
                    if i == sel:
                        print(f"  {Fore.CYAN}►{Style.RESET_ALL} "
                              f"{Back.CYAN}{Fore.BLACK}{num} {code}{title}{_rjust_dw(cnt,5)}{Style.RESET_ALL}")
                    else:
                        print(f"    {Fore.CYAN}{num}{Style.RESET_ALL} "
                              f"{Fore.GREEN}{code}{Style.RESET_ALL}"
                              f"{title}{Fore.YELLOW}{_rjust_dw(cnt,5)}{Style.RESET_ALL}")
                status_line("[↑↓] 選擇  [Enter/→] 進入  [C] 聊天  [R] 重整  [Q] 離開")

                k = _getch()
                self._check_kicked()
                if   k == 'UP'    and sel > 0:             sel -= 1
                elif k == 'DOWN'  and sel < len(boards)-1: sel += 1
                elif k in ('ENTER', 'RIGHT') and boards:
                    self._cur_board = boards[sel]
                    self._posts()
                elif k.upper() == 'S':
                    # 本機搜尋看板
                    kw = self._prompt_keyword("搜尋看板")
                    if kw:
                        kw_l = kw.lower()
                        filtered = [b for b in boards
                                    if kw_l in b.get('name','').lower()
                                    or kw in b.get('title','')]
                        if filtered:
                            self._select_board_from_list(filtered)
                        else:
                            self._msg(f"找不到含「{kw}」的看板")
                elif k == '/':
                    kw = self._prompt_keyword("全站搜尋標題")
                    if kw:
                        results, err = self.c.search_posts("title", "", kw)
                        if err:
                            self._msg(f"搜尋失敗: {err}")
                        else:
                            self._search_results(results, kw, "標題")
                elif k.upper() == 'A':
                    kw = self._prompt_keyword("全站搜尋作者")
                    if kw:
                        results, err = self.c.search_posts("author", "", kw)
                        if err:
                            self._msg(f"搜尋失敗: {err}")
                        else:
                            self._search_results(results, kw, "作者")
                elif k.upper() == 'C': self._chat()
                elif k.upper() == 'R': break   # re-fetch
                elif k.upper() == 'Q' or k == 'ESC': self._exit(); return

    # ── Post list ──────────────────────────────────────────────
    def _posts(self):
        sel  = 0
        page = 1
        while True:
            board_code = self._cur_board.get('name', '')
            board_name = self._cur_board.get('title', board_code)
            posts, total, err = self.c.get_posts(board_code, page)
            if err:
                header(f"► {board_code}", self.c.username)
                print(Fore.RED + f"  錯誤: {err}" + Style.RESET_ALL)
                press_enter()
                return

            total_pages = max(1, (total + PER_PAGE - 1) // PER_PAGE)
            if sel >= len(posts): sel = max(0, len(posts) - 1)

            while True:
                header(f"► {board_name} [{board_code}]", self.c.username)
                print(f"  {Fore.CYAN}第 {page}/{total_pages} 頁，共 {total} 篇{Style.RESET_ALL}")
                # 欄位標題
                print(f"  {'':2}"
                      f"{Fore.CYAN}{_rjust_dw('推',3)}  "
                      f"{_ljust_dw('標　　題',26)}"
                      f"{_ljust_dw('作　者',9)}"
                      f"日期{Style.RESET_ALL}")
                hr('─', Fore.CYAN)
                if not posts:
                    print(f"  {Fore.CYAN}（此版尚無文章，按 P 發表第一篇！）{Style.RESET_ALL}")
                else:
                    for i, p in enumerate(posts):
                        push  = p.get('push_count', 0)
                        title = _trunc_dw(p.get('title', ''), 24)
                        auth  = _trunc_dw(p.get('author', ''), 8)
                        raw_d = (p.get('created_at', '') or '')
                        date  = raw_d[5:10].replace('-', '/') if len(raw_d) >= 10 else raw_d[:5]
                        push_s = f"{push}" if push else ' '
                        if i == sel:
                            print(f"  {Fore.CYAN}►{Style.RESET_ALL} "
                                  f"{Back.CYAN}{Fore.BLACK}"
                                  f"{_rjust_dw(push_s,3)}  "
                                  f"{_ljust_dw(title,24)}  "
                                  f"{_ljust_dw(auth,8)} {date}"
                                  f"{Style.RESET_ALL}")
                        else:
                            pc = (Fore.YELLOW if push else Fore.CYAN)
                            print(f"    "
                                  f"{pc}{_rjust_dw(push_s,3)}{Style.RESET_ALL}  "
                                  f"{_ljust_dw(title,24)}  "
                                  f"{Fore.GREEN}{_ljust_dw(auth,8)}{Style.RESET_ALL} "
                                  f"{Fore.CYAN}{date}{Style.RESET_ALL}")

                parts = []
                if page > 1:            parts.append("[ 上頁")
                if page < total_pages:  parts.append("] 下頁")
                status_line(f"[↑↓]選擇 [Enter/→]讀 [P]發文 [X]推薦 [Y]回覆 [/]搜標題 [A]搜作者 [←]返回  {'  '.join(parts)}")

                k = _getch()
                self._check_kicked()
                if   k == 'UP'   and sel > 0:            sel -= 1
                elif k == 'DOWN' and sel < len(posts)-1: sel += 1
                elif k in ('ENTER', 'RIGHT') and posts:
                    self._read(posts[sel])
                elif k == '[' or k == 'PGUP':
                    if page > 1: page -= 1; sel = 0; break
                elif k == ']' or k == 'PGDN':
                    if page < total_pages: page += 1; sel = 0; break
                elif k == 'LEFT' or k == 'ESC':
                    return
                elif k.upper() == 'P':
                    self._compose()
                    break
                elif k.upper() == 'X' and posts:
                    p = posts[sel]
                    pushed, cnt, err = self.c.push_post(p['id'])
                    if err:
                        self._msg(f"推薦失敗: {err}")
                    elif pushed:
                        self._msg(f"推薦成功！文章 #{p['id']} 共 {cnt} 推")
                    else:
                        self._msg(f"已取消推薦　共 {cnt} 推")
                elif k.upper() == 'Y' and posts:
                    self._reply(posts[sel]['id'])
                elif k == '/':
                    kw = self._prompt_keyword(f"在 [{board_code}] 搜尋標題")
                    if kw:
                        results, err = self.c.search_posts("title", board_code, kw)
                        if err:
                            self._msg(f"搜尋失敗: {err}")
                        else:
                            self._search_results(results, kw, "標題")
                elif k.upper() == 'A':
                    kw = self._prompt_keyword(f"在 [{board_code}] 搜尋作者")
                    if kw:
                        results, err = self.c.search_posts("author", board_code, kw)
                        if err:
                            self._msg(f"搜尋失敗: {err}")
                        else:
                            self._search_results(results, kw, "作者")
                elif k.upper() == 'Q':
                    self._exit(); sys.exit(0)

    # ── Read post ──────────────────────────────────────────────
    def _read(self, summary: dict):
        board_code = self._cur_board.get('name', '')
        post_id    = summary.get('id')
        print(Fore.YELLOW + "  載入中..." + Style.RESET_ALL, end='\r')
        post, replies, err = self.c.get_post(post_id)
        if err or not post:
            print(Fore.RED + f"  讀取失敗: {err}" + Style.RESET_ALL)
            press_enter()
            return

        while True:
            header(f"► {board_code} ► 讀文", self.c.username)
            # 文章資訊框
            _IW = W - 4
            print(f"  {Fore.CYAN}┌{'─'*_IW}┐{Style.RESET_ALL}")
            ttl = _trunc_dw(post.get('title', ''), _IW - 2)
            print(f"  {Fore.CYAN}│{Style.RESET_ALL} {Fore.YELLOW}{_ljust_dw(ttl, _IW-2)}{Style.RESET_ALL}{Fore.CYAN}│{Style.RESET_ALL}")
            push_c = post.get('push_count', 0)
            auth_t = _trunc_dw(post.get('author',''), 12)
            date_t = (post.get('created_at','') or '')[:16]
            push_t = f"推 {push_c}" if push_c else ""
            info   = f" 作者：{Fore.GREEN}{auth_t}{Style.RESET_ALL}  時間：{Fore.CYAN}{date_t}{Style.RESET_ALL}"
            if push_t:
                info += f"  {Fore.YELLOW}{push_t}{Style.RESET_ALL}"
            print(f"  {Fore.CYAN}│{Style.RESET_ALL}{info}")
            print(f"  {Fore.CYAN}└{'─'*_IW}┘{Style.RESET_ALL}")
            print()
            for line in wrap_text(post.get('body', '')):
                print(f"  {line}")

            my_replies = [r for r in replies
                          if r.get('author_id') == self.c.my_id
                          or (not r.get('author_id') and r.get('author') == self.c.username)]

            hr('─', Fore.CYAN)
            if replies:
                print(f"  {Fore.CYAN}◎ 回覆共 {len(replies)} 則{Style.RESET_ALL}")
                for idx, r in enumerate(replies, 1):
                    is_my_r = r in my_replies
                    mark = f"{Fore.YELLOW}[{idx}] " if is_my_r else f"    "
                    r_auth = r.get('author','')
                    r_time = (r.get('created_at','') or '')[:16]
                    print(f"\n  {mark}{Fore.GREEN}{r_auth}{Style.RESET_ALL}"
                          f"  {Fore.CYAN}{r_time}{Style.RESET_ALL}")
                    for line in wrap_text(r.get('body', '')):
                        print(f"    {line}")
            else:
                print(f"  {Fore.CYAN}◎ 尚無回覆{Style.RESET_ALL}")

            is_own = (post.get('author_id') == self.c.my_id
                      or post.get('author') == self.c.username)
            hint_post  = "  [E]編輯 [D]刪除文章" if is_own else ""
            hint_reply = "  [X]刪除回覆" if my_replies else ""
            status_line(f"[R]回覆{hint_post}{hint_reply}  [←/Esc]返回  [Q]離開")

            k = _getch()
            if k.upper() == 'R':
                self._reply(post_id)
                print(Fore.YELLOW + "  重新載入..." + Style.RESET_ALL, end='\r')
                post, replies, err = self.c.get_post(post_id)
                if err: break
            elif k.upper() == 'E' and is_own:
                if self._edit_post(post):
                    print(Fore.YELLOW + "  重新載入..." + Style.RESET_ALL, end='\r')
                    post, replies, err = self.c.get_post(post_id)
                    if err: break
            elif k.upper() == 'D' and is_own:
                if self._delete_post(post):
                    return
            elif k.upper() == 'X' and my_replies:
                target = self._pick_reply_to_delete(my_replies) if len(my_replies) > 1 else my_replies[0]
                if target:
                    self._do_delete_reply(target)
                    print(Fore.YELLOW + "  重新載入..." + Style.RESET_ALL, end='\r')
                    post, replies, err = self.c.get_post(post_id)
                    if err: break
            elif k in ('LEFT', 'ESC'):
                return
            elif k.upper() == 'Q':
                self._exit(); sys.exit(0)

    # ── Compose ────────────────────────────────────────────────
    _POST_TYPES = ['問題', '討論', '心得', '情報', '教學']

    def _compose(self):
        board_code = self._cur_board.get('name', '')

        # Step 1: 選擇文章類型
        while True:
            header(f"► {board_code} ► 發新文 ► 選擇類型", self.c.username)
            hr()
            for i, t in enumerate(self._POST_TYPES, 1):
                print(f"  {Fore.CYAN}{i}{Style.RESET_ALL}.  {t}")
            hr()
            print("  按數字鍵 1–5 選擇類型，Esc 取消")
            k = _getch()
            if k == 'ESC':
                return
            if k in ('1', '2', '3', '4', '5'):
                tag = f"【{self._POST_TYPES[int(k)-1]}】"
                break

        # Step 2: 輸入標題（Ctrl+Q 取消，Ctrl+X 直接跳內文）
        header(f"► {board_code} ► 發新文 ► {tag}", self.c.username)
        print(f"  {Fore.YELLOW}標題前綴: {tag}{Style.RESET_ALL}")
        print(f"  {Fore.CYAN}Ctrl+X 下一步  Ctrl+Q 取消{Style.RESET_ALL}")
        print()
        sys.stdout.write(f"  標題: {Fore.YELLOW}{tag}{Style.RESET_ALL}")
        sys.stdout.flush()
        title_input, title_key = _read_line()
        title_input = title_input.strip()
        if title_key == 'CTRL_Q' or not title_input:
            return
        title = tag + title_input

        # Step 3: 輸入內文
        print()
        print(f"  {Fore.YELLOW}內文：{Style.RESET_ALL}  {Fore.CYAN}Ctrl+X 送出  Ctrl+Q 取消  Enter 換行{Style.RESET_ALL}")
        body = self._read_multiline()
        if not body:
            print(Fore.RED + "  已取消。" + Style.RESET_ALL)
            press_enter(); return

        print(Fore.YELLOW + "\n  發文中..." + Style.RESET_ALL)
        ok, err = self.c.create_post(board_code, title, body)
        if ok:
            print(Fore.GREEN + "  發文成功！" + Style.RESET_ALL)
        else:
            print(Fore.RED + f"  失敗: {err}" + Style.RESET_ALL)
        press_enter()

    # ── Reply ──────────────────────────────────────────────────
    def _reply(self, post_id: int):
        header(f"► 回覆 #{post_id}", self.c.username)
        print(f"  {Fore.YELLOW}回覆內容：{Style.RESET_ALL}  {Fore.CYAN}Ctrl+X 送出  Ctrl+Q 取消  Enter 換行{Style.RESET_ALL}")
        body = self._read_multiline()
        if body is None:
            return
        print(Fore.YELLOW + "  送出中..." + Style.RESET_ALL)
        ok, err = self.c.create_reply(post_id, body)
        if ok:
            print(Fore.GREEN + "  回覆成功！" + Style.RESET_ALL)
        else:
            print(Fore.RED + f"  失敗: {err}" + Style.RESET_ALL)
        press_enter()

    def _read_multiline(self):
        """讀取多行內文。Ctrl+X 送出，Ctrl+Q 取消（回傳 None）。Enter 換行。"""
        lines = []
        while True:
            text, key = _read_line('  ')
            if key == 'CTRL_Q':
                return None
            if key == 'CTRL_X':
                if text:
                    lines.append(text)
                break
            lines.append(text)
        result = "\n".join(lines).strip()
        return result if result else None

    # ── Search & utils ─────────────────────────────────────────
    def _prompt_keyword(self, label: str) -> str:
        """顯示提示列，讀取關鍵字，回傳空字串表示取消。"""
        hr('─', Fore.CYAN)
        sys.stdout.write(f"  {Fore.CYAN}{label}：{Style.RESET_ALL}")
        sys.stdout.flush()
        text, key = _read_line()
        if key == 'CTRL_Q' or not text.strip():
            return ''
        return text.strip()

    def _msg(self, text: str):
        """顯示一行訊息後等 Enter。"""
        hr('─', Fore.CYAN)
        print(f"  {Fore.YELLOW}{text}{Style.RESET_ALL}")
        press_enter()

    def _search_results(self, results: list, query: str, field: str):
        """顯示搜尋結果，可選入文章閱讀。"""
        sel = 0
        while True:
            header(f"搜尋{field}：「{query}」", self.c.username)
            if not results:
                print(f"  {Fore.CYAN}── 找不到相關文章 ──{Style.RESET_ALL}")
                press_enter(); return
            print(f"  共 {len(results)} 筆結果")
            hr()
            for i, p in enumerate(results):
                arrow  = f"{Fore.CYAN}►{Style.RESET_ALL} " if i == sel else "  "
                board  = p.get('board', '')[:8]
                author = p.get('author', '')[:8]
                title  = p.get('title', '')[:22]
                date   = (p.get('created_at', '') or '')[:10]
                if i == sel:
                    print(f"  {arrow}{Back.CYAN}{Fore.BLACK}"
                          f"[{board}] {title:<22} {author:<8} {date}{Style.RESET_ALL}")
                else:
                    print(f"  {arrow}[{Fore.CYAN}{board}{Style.RESET_ALL}]"
                          f" {title:<22} {Fore.GREEN}{author:<8}{Style.RESET_ALL} {date}")
            status_line("[↑↓] 選擇  [Enter] 讀文  [←/Esc] 返回")
            k = _getch()
            if   k == 'UP'   and sel > 0:              sel -= 1
            elif k == 'DOWN' and sel < len(results)-1: sel += 1
            elif k in ('ENTER', 'RIGHT'):
                p = results[sel]
                # 切換 cur_board 再讀文
                self._cur_board = {'name': p.get('board',''), 'title': p.get('board','')}
                self._read(p)
            elif k in ('LEFT', 'ESC'):
                return

    def _select_board_from_list(self, boards: list):
        """從篩選後的看板清單選擇並進入。"""
        sel = 0
        while True:
            header("搜尋結果", self.c.username)
            hr()
            for i, b in enumerate(boards):
                arrow = f"{Fore.CYAN}►{Style.RESET_ALL} " if i == sel else "  "
                code  = b.get('name', '')[:11].ljust(12)
                title = b.get('title', '')[:21].ljust(22)
                cnt   = b.get('post_count', 0)
                if i == sel:
                    print(f"  {arrow}{Back.CYAN}{Fore.BLACK}{code}{title}{cnt:>5}{Style.RESET_ALL}")
                else:
                    print(f"  {arrow}{Fore.GREEN}{code}{Style.RESET_ALL}{title}{cnt:>5}")
            status_line("[↑↓] 選擇  [Enter] 進入  [←/Esc] 返回")
            k = _getch()
            if   k == 'UP'   and sel > 0:              sel -= 1
            elif k == 'DOWN' and sel < len(boards)-1:  sel += 1
            elif k in ('ENTER', 'RIGHT'):
                self._cur_board = boards[sel]
                self._posts()
                return
            elif k in ('LEFT', 'ESC'):
                return

    def _edit_post(self, post: dict) -> bool:
        """編輯自己的文章，成功回傳 True。"""
        header(f"編輯文章 #{post['id']}", self.c.username)
        print(f"  {Fore.CYAN}目前標題: {post.get('title','')}{Style.RESET_ALL}")
        print(f"  {Fore.CYAN}Ctrl+X 確認  Ctrl+Q 取消{Style.RESET_ALL}")
        print()
        sys.stdout.write(f"  新標題: {Fore.YELLOW}")
        sys.stdout.flush()
        title, key = _read_line()
        sys.stdout.write(Style.RESET_ALL)
        if key == 'CTRL_Q' or not title.strip():
            return False
        print()
        print(f"  {Fore.YELLOW}新內文：{Style.RESET_ALL}  {Fore.CYAN}Ctrl+X 送出  Ctrl+Q 取消  Enter 換行{Style.RESET_ALL}")
        body = self._read_multiline()
        if not body:
            return False
        print(Fore.YELLOW + "  儲存中..." + Style.RESET_ALL)
        ok, err = self.c.edit_post(post['id'], title.strip(), body)
        if ok:
            print(Fore.GREEN + "  編輯成功！" + Style.RESET_ALL)
            press_enter()
            return True
        print(Fore.RED + f"  失敗: {err}" + Style.RESET_ALL)
        press_enter()
        return False

    def _delete_post(self, post: dict) -> bool:
        """刪除自己的文章，成功回傳 True。"""
        hr('─', Fore.RED)
        print(f"  {Fore.RED}確定刪除文章「{post.get('title','')}」？{Style.RESET_ALL}")
        print(f"  {Fore.CYAN}[Y] 確認刪除  其他鍵取消{Style.RESET_ALL}")
        k = _getch()
        if k.upper() != 'Y':
            return False
        print(Fore.YELLOW + "  刪除中..." + Style.RESET_ALL)
        ok, err = self.c.delete_post(post['id'])
        if ok:
            print(Fore.GREEN + "  已刪除！" + Style.RESET_ALL)
            press_enter()
            return True
        print(Fore.RED + f"  失敗: {err}" + Style.RESET_ALL)
        press_enter()
        return False

    def _pick_reply_to_delete(self, my_replies: list) -> dict | None:
        """顯示選單讓使用者選要刪除哪則自己的回覆。"""
        header("► 選擇要刪除的回覆", self.c.username)
        hr()
        for i, r in enumerate(my_replies, 1):
            body_preview = _trunc_dw(r.get('body', '').replace('\n', ' '), 40)
            print(f"  {Fore.CYAN}{i}{Style.RESET_ALL}.  {body_preview}")
        hr()
        print(f"  輸入編號選擇，其他鍵取消")
        k = _getch()
        if k.isdigit():
            idx = int(k) - 1
            if 0 <= idx < len(my_replies):
                return my_replies[idx]
        return None

    def _do_delete_reply(self, reply: dict):
        """確認並刪除指定回覆。"""
        hr('─', Fore.RED)
        preview = _trunc_dw(reply.get('body', '').replace('\n', ' '), 30)
        print(f"  {Fore.RED}確定刪除回覆「{preview}」？{Style.RESET_ALL}")
        print(f"  {Fore.CYAN}[Y] 確認刪除  其他鍵取消{Style.RESET_ALL}")
        k = _getch()
        if k.upper() != 'Y':
            return
        print(Fore.YELLOW + "  刪除中..." + Style.RESET_ALL)
        ok, err = self.c.delete_reply(reply.get('id', 0))
        if ok:
            print(Fore.GREEN + "  已刪除！" + Style.RESET_ALL)
        else:
            print(Fore.RED + f"  失敗: {err}" + Style.RESET_ALL)
        press_enter()

    # ── Chat ───────────────────────────────────────────────────
    def _chat(self):
        buf: list[dict] = []
        MAX = 16
        while True:
            # Drain new messages
            while True:
                try:
                    buf.append(self.c.chat_q.get_nowait())
                    if len(buf) > MAX: buf.pop(0)
                except Empty:
                    break

            header("Mesh 通訊", self.c.username)
            hr('─', Fore.CYAN)
            if not buf:
                print(f"  {Fore.CYAN}（尚無訊息，等待中...）{Style.RESET_ALL}")
            for m in buf[-MAX:]:
                is_me = (m['from'] == self.c.username)
                name_c = Fore.YELLOW if is_me else Fore.GREEN
                print(f"  {Fore.CYAN}[{m['time']}]{Style.RESET_ALL}"
                      f" {name_c}{_trunc_dw(m['from'],10)}{Style.RESET_ALL}"
                      f"：{m['text']}")
            status_line("Enter 重整  /q 離開  直接輸入後 Enter 送出")
            text = input(f"  {Fore.YELLOW}►{Style.RESET_ALL} ")
            text = text.strip()
            if text.lower() in ('/q', '/quit'):
                return
            if text:
                self.c.send_chat(text)
                buf.append({
                    "from": self.c.username,
                    "text": text,
                    "time": datetime.now().strftime("%H:%M"),
                })
                if len(buf) > MAX: buf.pop(0)

    # ── Exit ───────────────────────────────────────────────────
    def _exit(self):
        cls()
        print(Fore.YELLOW + "  正在登出..." + Style.RESET_ALL)
        self.c.disconnect()
        print(Fore.GREEN + "  已離線，再見！" + Style.RESET_ALL)
        time.sleep(0.8)


# ═══════════════════════════════════════════════════════════════
# Startup helpers
# ═══════════════════════════════════════════════════════════════

def _pick_com_interactive() -> str | None:
    """顯示系統所有 COM 裝置供快速選擇，回傳選定的 port 字串或 None（取消）。"""
    all_com = _list_all_com_ports()
    if not all_com:
        print(f"  {Fore.RED}找不到任何 COM 裝置，請確認 USB 已插入。{Style.RESET_ALL}")
        press_enter()
        return None
    print()
    print(f"  {Fore.YELLOW}系統 COM 裝置列表：{Style.RESET_ALL}")
    print()
    for i, (dev, desc) in enumerate(all_com, 1):
        print(f"  {Fore.CYAN}{i}{Style.RESET_ALL}.  {Fore.GREEN}{dev}{Style.RESET_ALL}  {desc}")
    print()
    print(f"  {Fore.CYAN}0{Style.RESET_ALL}.  手動輸入  　{Fore.CYAN}Esc{Style.RESET_ALL}.  取消")
    print()
    raw = input_text("  請選擇: ").strip()
    if raw == '' or raw.upper() == 'ESC':
        return None
    if raw == '0':
        return input_text("  請輸入串口 (如 COM3): ").strip() or None
    try:
        idx = int(raw) - 1
        if 0 <= idx < len(all_com):
            return all_com[idx][0]
    except ValueError:
        pass
    return None


def _list_all_com_ports() -> list:
    """回傳系統上所有 COM 裝置（不限制 USB），用於找不到 USB 時的備選清單。"""
    try:
        import serial.tools.list_ports
        result = []
        for p in serial.tools.list_ports.comports():
            desc = p.description or p.device
            result.append((p.device, desc))
        result.sort(key=lambda x: (len(x[0]), x[0]))
        return result
    except Exception:
        return []


def _list_all_usb_ports() -> list:
    """回傳所有 USB 串列裝置，包含描述文字，用於選單顯示。
    每個元素為 (device, description)，如 ('COM6', 'USB 序列裝置')。
    """
    try:
        import serial.tools.list_ports
        result = []
        for p in serial.tools.list_ports.comports():
            hwid = (p.hwid or "").upper()
            # 只列出 USB 串列裝置（hwid 含 VID= 表示有廠商 ID）
            if "VID" in hwid or "USB" in hwid:
                desc = p.description or p.device
                if p.device not in [d for d, _ in result]:
                    result.append((p.device, desc))
        # 依 COM 編號排序 (COM6 < COM10)
        result.sort(key=lambda x: (len(x[0]), x[0]))
        return result
    except Exception:
        return []


def _scan_ports() -> list:
    """回傳已知 Meshtastic 相容裝置的串口清單（關鍵字或 VID/PID 比對）。"""
    try:
        import serial.tools.list_ports
        KEYWORDS = (
            # 英文晶片 / 製造商名稱
            "cp210", "ch340", "ch341", "ftdi", "silabs",
            "usb serial", "usb-serial", "usb serial port",
            "meshtastic", "wch", "uart", "usb-uart", "usb2.0-serial",
            "serial", "prolific",
            # 繁體/簡體中文 Windows 裝置名稱
            "序列",    # "USB 序列裝置" (zh-TW/zh-CN)
            "串行",    # 簡體中文版本
        )
        VID_PID = {
            (0x10C4, 0xEA60),  # Silicon Labs CP2102/CP2104
            (0x10C4, 0xEA70),  # Silicon Labs CP2109
            (0x1A86, 0x7523),  # WCH CH340
            (0x1A86, 0x55D4),  # WCH CH9102
            (0x1A86, 0x7522),  # WCH CH341
            (0x1A86, 0xE00C),  # WCH CH347
            (0x0403, 0x6001),  # FTDI FT232RL
            (0x0403, 0x6015),  # FTDI FT231X
            (0x067B, 0x2303),  # Prolific PL2303
            (0x303A, 0x1001),  # Espressif ESP32-S3 native USB
            (0x303A, 0x4001),  # Espressif ESP32-S2 native USB
        }
        result = []
        for p in serial.tools.list_ports.comports():
            desc = (p.description or "").lower()
            mfr  = (p.manufacturer or "").lower()
            hwid = (p.hwid or "").upper()
            hit = any(k in desc or k in mfr for k in KEYWORDS)
            if not hit and p.vid and p.pid:
                hit = (p.vid, p.pid) in VID_PID
            # 最後備援：hwid 含 VID= 的 USB 裝置（涵蓋任何通用 USB 串列驅動）
            if not hit and "VID" in hwid:
                hit = True
            if hit and p.device not in result:
                result.append(p.device)
        result.sort(key=lambda x: (len(x), x))
        return result
    except Exception:
        return []


def _pick_server(client: MeshBBSClient) -> str | None:
    """Interactive server-node picker."""
    cls()
    print(Fore.CYAN + "═" * W + Style.RESET_ALL)
    print(Fore.YELLOW + "  選擇 BBS 伺服器節點" + Style.RESET_ALL)
    print(Fore.CYAN + "═" * W + Style.RESET_ALL)
    print()
    print("  正在掃描 Mesh 節點...")
    time.sleep(1.5)
    nodes = client.get_nodes()

    options: list[tuple[str | None, str]] = []
    if nodes:
        print("  找到的節點：\n")
        for nid, nm in nodes:
            options.append((nid, nm))
            print(f"  {len(options)}. {Fore.GREEN}{nm}{Style.RESET_ALL}  ({nid})")
    else:
        print(f"  {Fore.CYAN}（未偵測到其他節點，可手動輸入）{Style.RESET_ALL}")

    n = len(options)
    print(f"  {n+1}. 手動輸入節點 ID")
    print(f"  {n+2}. 廣播給所有節點（不指定目標）")
    print()

    while True:
        raw = input_text("  請選擇: ").strip()
        try:
            idx = int(raw)
        except ValueError:
            continue
        if 1 <= idx <= n:
            return options[idx - 1][0]
        elif idx == n + 1:
            nid = input_text("  輸入節點 ID (如 !abc12345): ").strip()
            return nid if nid else None
        elif idx == n + 2:
            return None


def _usb_monitor(client: 'MeshBBSClient'):
    """Background thread: detect USB plug/unplug and auto-reconnect.
    使用 _list_all_usb_ports 以偵測「USB 序列裝置」等通用驅動裝置。
    """
    prev = set(d for d, _ in _list_all_usb_ports())
    while True:
        time.sleep(2)
        try:
            curr = set(d for d, _ in _list_all_usb_ports())
            lost  = prev - curr
            found = curr - prev
            prev  = curr

            if client.port in lost:
                client._usb_lost = True

            if found and getattr(client, '_usb_lost', False):
                new_port = sorted(found, key=lambda x: (len(x), x))[0]
                client._usb_reconnect_port = new_port
        except Exception:
            pass


def main():
    global DEBUG
    ap = argparse.ArgumentParser(description="Meshtastic BBS 文字模式客戶端")
    ap.add_argument("--port",   help="串口，如 COM3 或 /dev/ttyUSB0")
    ap.add_argument("--server", help="BBS 伺服器節點 ID，如 !abc12345")
    ap.add_argument("--debug",  action="store_true", help="顯示封包接收 debug 資訊")
    args = ap.parse_args()
    DEBUG = args.debug

    cls()
    print(Fore.CYAN + "═" * W + Style.RESET_ALL)
    print(Fore.YELLOW + "      Meshtastic BBS 文字模式客戶端" + Style.RESET_ALL)
    print(Fore.CYAN + "═" * W + Style.RESET_ALL)
    print()

    # ── 1. Find port ──────────────────────────────────────────
    port = args.port
    if not port:
        while True:
            print("  自動偵測 Meshtastic 裝置...", end='', flush=True)
            ports = _scan_ports()
            if ports:
                port = ports[0]
                extra = f"（共找到 {len(ports)} 個）" if len(ports) > 1 else ""
                print(Fore.GREEN + f" 找到 {port}{extra}" + Style.RESET_ALL)
                break
            print(Fore.RED + " 未自動識別到裝置" + Style.RESET_ALL)

            # 列出所有 USB 串列裝置供選擇
            all_usb = _list_all_usb_ports()
            if all_usb:
                print()
                print(f"  {Fore.YELLOW}偵測到以下 USB 串列裝置，請選擇：{Style.RESET_ALL}")
                print()
                for i, (dev, desc) in enumerate(all_usb, 1):
                    print(f"  {Fore.CYAN}{i}{Style.RESET_ALL}.  {Fore.GREEN}{dev}{Style.RESET_ALL}  {desc}")
                print()
                print(f"  {Fore.CYAN}R{Style.RESET_ALL}.  重新掃描  "
                      f"  {Fore.CYAN}M{Style.RESET_ALL}.  手動輸入  "
                      f"  {Fore.CYAN}Q{Style.RESET_ALL}.  退出")
                print()
                raw = input_text("  請選擇: ").strip().upper()
                if raw == 'Q':
                    sys.exit(0)
                elif raw == 'M':
                    chosen = _pick_com_interactive()
                    if chosen:
                        port = chosen
                        break
                elif raw == 'R':
                    print()
                    continue
                else:
                    try:
                        idx = int(raw) - 1
                        if 0 <= idx < len(all_usb):
                            port = all_usb[idx][0]
                            break
                    except ValueError:
                        pass
            else:
                # 沒有識別到 USB 裝置，直接列出所有 COM port
                print()
                chosen = _pick_com_interactive()
                if chosen:
                    port = chosen
                    break
                print()

    # ── 2. Connect (with retry) ───────────────────────────────
    client = None
    while True:
        print(f"\n  連接 {port or '(自動偵測)'}...", end='', flush=True)
        client = MeshBBSClient(port=port, server_id=args.server)
        ok, err = client.connect()
        if ok:
            print(Fore.GREEN + " 成功！" + Style.RESET_ALL)
            break
        print(Fore.RED + f" 失敗" + Style.RESET_ALL)
        print(f"  {Fore.RED}錯誤: {err}{Style.RESET_ALL}")
        print()
        print(f"  {Fore.CYAN}[R]{Style.RESET_ALL} 重新掃描裝置  "
              f"{Fore.CYAN}[M]{Style.RESET_ALL} 手動輸入串口  "
              f"{Fore.CYAN}[Q]{Style.RESET_ALL} 退出")
        k = _getch().upper()
        if k == 'Q':
            sys.exit(0)
        elif k == 'M':
            chosen = _pick_com_interactive()
            if chosen:
                port = chosen
        else:
            ports = _scan_ports()
            if ports:
                port = ports[0]
                print(f"  重新偵測到: {Fore.GREEN}{port}{Style.RESET_ALL}")
            else:
                print(f"  {Fore.RED}仍未找到裝置，請確認 USB 連接{Style.RESET_ALL}")
        print()

    print(f"  我的節點: {Fore.GREEN}{client.my_id}{Style.RESET_ALL}  ({client.my_name})")
    print()

    # ── 3. Start USB hotplug monitor ──────────────────────────
    client._usb_lost = False
    client._usb_reconnect_port = None
    threading.Thread(target=_usb_monitor, args=(client,), daemon=True).start()

    # ── 4. Pick server ────────────────────────────────────────
    if not client.server_id:
        client.server_id = _pick_server(client)
        if client.server_id:
            print(f"\n  目標伺服器: {Fore.GREEN}{client.server_id}{Style.RESET_ALL}")
        else:
            print(f"\n  {Fore.CYAN}廣播模式（伺服器需監聽廣播）{Style.RESET_ALL}")
        time.sleep(0.6)

    # ── 5. Start UI (with auto-reconnect on USB hotplug) ──────
    ui = BBSUI(client)
    while True:
        try:
            ui.run()
            break  # normal exit
        except KeyboardInterrupt:
            break
        except SystemExit:
            break
        except Exception:
            pass

        # Check if USB hotplug triggered a reconnect
        new_port = getattr(client, '_usb_reconnect_port', None)
        if new_port and getattr(client, '_usb_lost', False):
            cls()
            print(Fore.YELLOW + f"  偵測到裝置重新插入: {new_port}，正在重新連接..." + Style.RESET_ALL)
            ok, err = client.reconnect(new_port)
            if ok:
                client._usb_lost = False
                client._usb_reconnect_port = None
                print(Fore.GREEN + f"  重新連接成功！" + Style.RESET_ALL)
                time.sleep(0.8)
                ui = BBSUI(client)
            else:
                print(Fore.RED + f"  重新連接失敗: {err}" + Style.RESET_ALL)
                press_enter()
                break
        else:
            break

    client.disconnect()
    cls()
    print(Fore.GREEN + "  Meshtastic BBS 客戶端已結束。" + Style.RESET_ALL)


if __name__ == "__main__":
    main()
