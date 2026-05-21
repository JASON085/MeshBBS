"""
Meshtastic BBS 中繼客戶端 (每個使用者的 PC 上執行)
====================================================
架構：
  您的瀏覽器 ──WS──> 本程式 ──LoRa Mesh──> BBS 伺服器
                   (本機)       (USB)

安裝依賴：
    pip install meshtastic aiohttp pypubsub

啟動：
    python bbs_mesh_relay.py --port COM3 --server !ab12cd34
    python bbs_mesh_relay.py --port /dev/ttyUSB0 --server !ab12cd34

然後用瀏覽器開啟：
    http://localhost:8766

參數說明：
  --port     您的 Meshtastic USB 序列埠 (Windows: COM3, Linux: /dev/ttyUSB0)
  --server   BBS 伺服器的 Meshtastic 節點 ID (格式: !xxxxxxxx)
  --local-port  本機 HTTP 埠號 (預設 8766)

Meshtastic 通訊協定：
  請求  (relay → server): BBS:REQ:<seq>:<CMD>[:<args>]
  回應  (server → relay): BBS:RES:<seq>:<chunk>:<total>:<data>
"""

import asyncio
import json
import threading
import time
import argparse
from pathlib import Path
from collections import defaultdict

try:
    from aiohttp import web
    import aiohttp
except ImportError:
    print("請安裝：pip install aiohttp")
    exit(1)

try:
    import meshtastic
    import meshtastic.serial_interface
    from pubsub import pub
    MESH_OK = True
except ImportError:
    print("[RELAY 警告] meshtastic 未安裝，以模擬模式運行")
    MESH_OK = False

BASE_DIR = Path(__file__).parent

# ── Global state ───────────────────────────────────────────────
_iface       = None
_server_node = None          # BBS server's Meshtastic node ID (e.g. "!ab12cd34")
_my_node_id  = "?"
_my_node_name = "User"
_loop        = None
_seq_counter = 0
_ws_clients: set = set()

# Chunk reassembly: seq -> [chunk0, chunk1, ...]
_chunks: dict = {}
# Expiry tracking to clean up stale partial chunks
_chunk_ts: dict = {}


def _next_seq() -> str:
    global _seq_counter
    _seq_counter = (_seq_counter + 1) % 1000
    return str(_seq_counter)


# ── Meshtastic receive handler ─────────────────────────────────
def _on_receive(packet, interface):
    try:
        text = packet.get("decoded", {}).get("text", "").strip()
        if not text.startswith("BBS:RES:"):
            return

        # Format: BBS:RES:<seq>:<chunk>:<total>:<data>
        parts = text.split(":", 5)
        if len(parts) < 6:
            return
        _, _, seq, chunk_s, total_s, data = parts
        chunk = int(chunk_s)
        total = int(total_s)

        if seq not in _chunks:
            _chunks[seq] = [None] * total
            _chunk_ts[seq] = time.time()
        if 0 <= chunk < total:
            _chunks[seq][chunk] = data

        if all(x is not None for x in _chunks[seq]):
            full = "".join(_chunks[seq])
            del _chunks[seq]
            _chunk_ts.pop(seq, None)
            try:
                msg = json.loads(full)
                asyncio.run_coroutine_threadsafe(_broadcast_ws(msg), _loop)
            except json.JSONDecodeError as e:
                _relay_log(f"JSON 解析失敗: {e}")

        # Clean stale chunks older than 30 s
        now = time.time()
        stale = [k for k, t in _chunk_ts.items() if now - t > 30]
        for k in stale:
            _chunks.pop(k, None)
            _chunk_ts.pop(k, None)

    except Exception as e:
        _relay_log(f"接收處理失敗: {e}")


async def _broadcast_ws(data: dict):
    if not _ws_clients:
        return
    text = json.dumps(data, ensure_ascii=False)
    dead = set()
    for ws in _ws_clients:
        try:
            await ws.send_str(text)
        except Exception:
            dead.add(ws)
    _ws_clients -= dead


# ── Send command to BBS server via Meshtastic ──────────────────
def _send_cmd(cmd: str, args: str = "") -> str:
    seq = _next_seq()
    msg = f"BBS:REQ:{seq}:{cmd}"
    if args:
        msg += f":{args}"

    if _iface and _server_node:
        try:
            _iface.sendText(msg, destinationId=_server_node)
            _relay_log(f"TX → {_server_node}  {msg[:60]}")
        except Exception as e:
            _relay_log(f"發送失敗: {e}")
            # In simulation mode, echo a fake response
            asyncio.run_coroutine_threadsafe(
                _sim_response(seq, cmd, args), _loop)
    else:
        # Simulation / no device mode
        asyncio.run_coroutine_threadsafe(
            _sim_response(seq, cmd, args), _loop)
    return seq


# ── Simulation (no Meshtastic device) ─────────────────────────
async def _sim_response(seq: str, cmd: str, args: str):
    """Minimal simulation so UI can be tested without hardware."""
    await asyncio.sleep(0.2)
    if cmd == "LIST":
        msg = {"type": "boards", "online_users": [],
               "boards": [
                   {"name": "gossiping", "title": "八卦", "moderator": "SYSOP", "post_count": 0},
                   {"name": "tech",      "title": "技術討論", "moderator": "SYSOP", "post_count": 0},
                   {"name": "mesh",      "title": "Meshtastic 討論", "moderator": "SYSOP", "post_count": 0},
               ]}
    elif cmd == "LOGIN":
        name = args or "User"
        msg = {"type": "login_ok", "node_id": _my_node_id,
               "name": name, "online_users": []}
    elif cmd == "POSTS":
        parts = args.split(":", 1)
        board = parts[0]
        msg = {"type": "posts", "board": board, "posts": [], "total": 0, "page": 1}
    elif cmd == "READ":
        msg = {"type": "error", "msg": "模擬模式：無文章資料"}
    else:
        return
    await _broadcast_ws(msg)


# ── WebSocket handler (browser → relay) ───────────────────────
async def _ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    _ws_clients.add(ws)
    _relay_log(f"瀏覽器連線: {request.remote}")
    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                    _translate(data)
                except Exception as e:
                    _relay_log(f"WS 處理失敗: {e}")
            elif msg.type == aiohttp.WSMsgType.ERROR:
                break
    finally:
        _ws_clients.discard(ws)
        _relay_log(f"瀏覽器斷線: {request.remote}")
    return ws


def _escape(s: str) -> str:
    """Escape colons in user content so they don't break the protocol."""
    return str(s or "").replace(":", "：")


def _translate(data: dict):
    """Translate WebSocket message from browser to Meshtastic command."""
    action    = data.get("action", "")
    author    = _escape(data.get("author",    _my_node_name))
    author_id = data.get("author_id", _my_node_id)

    if action == "login":
        name = _escape(data.get("name", "User"))
        _send_cmd("LOGIN", name)

    elif action == "get_boards":
        _send_cmd("LIST")

    elif action == "get_posts":
        board = _escape(data.get("board", "gossiping"))
        page  = int(data.get("page", 1))
        _send_cmd("POSTS", f"{board}:{page}")

    elif action == "get_post":
        post_id = data.get("post_id", 0)
        _send_cmd("READ", str(post_id))

    elif action == "create_post":
        board = _escape(data.get("board", ""))
        title = _escape(data.get("title", ""))
        body  = _escape(data.get("body",  ""))
        _send_cmd("POST", f"{board}:{author}:{title}:{body}")

    elif action == "create_reply":
        post_id = data.get("post_id", 0)
        body    = _escape(data.get("body", ""))
        _send_cmd("REPLY", f"{post_id}:{author}:{body}")

    elif action == "send_mesh":
        text = _escape(data.get("text", ""))
        _send_cmd("CHAT", f"{author}:{text}")

    elif action == "logout":
        _send_cmd("LOGOUT", author)


# ── Meshtastic init ────────────────────────────────────────────
def _start_mesh(port: str, server_node: str, loop: asyncio.AbstractEventLoop):
    global _iface, _server_node, _my_node_id, _my_node_name, _loop
    _loop        = loop
    _server_node = server_node

    if not MESH_OK:
        _relay_log("meshtastic 未安裝，以模擬模式運行（UI 可正常測試）")
        return

    try:
        _relay_log(f"連接 Meshtastic：{port}")
        _iface = meshtastic.serial_interface.SerialInterface(port)
        pub.subscribe(_on_receive, "meshtastic.receive.text")
        info = _iface.getMyNodeInfo()
        num  = info.get("num", 0)
        _my_node_id   = f"!{num:08x}"
        user = info.get("user", {})
        _my_node_name = user.get("longName") or user.get("shortName") or "User"
        _relay_log(f"已連線：{_my_node_name}  節點 ID: {_my_node_id}")
        _relay_log(f"目標 BBS 伺服器：{server_node}")
    except Exception as e:
        _relay_log(f"Meshtastic 連線失敗: {e}  （以模擬模式繼續）")


# ── HTTP: serve bbs_client.html ───────────────────────────────
async def _serve_html(request):
    return web.FileResponse(BASE_DIR / "bbs_client.html")


def _relay_log(msg: str):
    from datetime import datetime
    print(f"[RELAY {datetime.now().strftime('%H:%M:%S')}] {msg}")


# ── Main ───────────────────────────────────────────────────────
async def main(args):
    loop = asyncio.get_running_loop()
    threading.Thread(
        target=_start_mesh,
        args=(args.port, args.server, loop),
        daemon=True
    ).start()

    app = web.Application()
    app.add_routes([
        web.get("/",   _serve_html),
        web.get("/ws", _ws_handler),
    ])

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", args.local_port)
    await site.start()

    node_disp = args.server or "(模擬模式)"
    print(f"""
╔══════════════════════════════════════════════════════╗
║       Meshtastic BBS 中繼客戶端  已啟動              ║
╠══════════════════════════════════════════════════════╣
║  您的 Meshtastic 裝置：{args.port:<30}║
║  BBS 伺服器節點 ID：  {node_disp:<31}║
║  本機 HTTP 埠號：      {args.local_port:<30}║
╠══════════════════════════════════════════════════════╣
║  請用瀏覽器開啟：                                    ║
║    http://localhost:{args.local_port:<35}║
╚══════════════════════════════════════════════════════╝

通訊流程：
  您的瀏覽器 ──WebSocket──> 本程式 ──LoRa Mesh──> BBS伺服器
  (http://localhost:{args.local_port})  (USB:{args.port})   (節點:{node_disp})
""")

    try:
        await asyncio.Future()
    finally:
        await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Meshtastic BBS 中繼客戶端 ── 每個使用者在自己PC上執行",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用範例：
  Windows:  python bbs_mesh_relay.py --port COM3 --server !ab12cd34
  Linux:    python bbs_mesh_relay.py --port /dev/ttyUSB0 --server !ab12cd34

  模擬測試（無硬體）：
            python bbs_mesh_relay.py --port COM3 --server !00000000
"""
    )
    parser.add_argument("--port",
                        default="/dev/ttyUSB0",
                        help="您的 Meshtastic USB 序列埠 (Windows: COM3)")
    parser.add_argument("--server",
                        default="!00000000",
                        help="BBS 伺服器的 Meshtastic 節點 ID (e.g. !ab12cd34)")
    parser.add_argument("--local-port",
                        type=int, default=8766,
                        help="本機 HTTP/WebSocket 埠號 (預設 8766)")
    args = parser.parse_args()
    asyncio.run(main(args))
