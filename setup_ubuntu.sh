#!/usr/bin/env bash
# Meshtastic BBS - Ubuntu / Raspberry Pi 4 安裝腳本
# 適用：Ubuntu Desktop 24.04.4 (amd64 / arm64)
# 用法：chmod +x setup_ubuntu.sh && ./setup_ubuntu.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="meshtastic-bbs"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
PYTHON="python3"
PIP="pip3"

echo "=== Meshtastic BBS Ubuntu/Raspberry Pi 安裝腳本 ==="
echo ""

# ── 1. 系統套件 ──────────────────────────────────────────────────
echo "[1/5] 更新套件清單並安裝 Python3 及 Tkinter..."
sudo apt-get update -qq
sudo apt-get install -y python3 python3-pip python3-venv python3-tk

# ── 2. Python 相依套件 ───────────────────────────────────────────
echo "[2/5] 安裝 Python 相依套件..."
$PIP install --break-system-packages meshtastic aiohttp pypubsub

# ── 3. 序列埠存取權限 ────────────────────────────────────────────
echo "[3/5] 設定序列埠存取權限（dialout 群組）..."
sudo usermod -aG dialout "$USER"
echo "      ✓ 已將 $USER 加入 dialout 群組（需重新登入才生效）"

# ── 4. 停用 ModemManager（避免干擾 USB 序列埠）──────────────────
echo "[4/5] 停用 ModemManager..."
if systemctl is-active --quiet ModemManager 2>/dev/null; then
    sudo systemctl stop ModemManager
    sudo systemctl disable ModemManager
    echo "      ✓ ModemManager 已停用"
else
    echo "      ✓ ModemManager 未啟動（略過）"
fi

# ── 5. 建立 systemd 服務 ─────────────────────────────────────────
echo "[5/5] 建立 systemd 服務..."
PYTHON_BIN="$(which $PYTHON)"

sudo tee "$SERVICE_FILE" > /dev/null <<EOF
[Unit]
Description=Meshtastic PTT-BBS 伺服器
After=network.target

[Service]
Type=simple
User=${USER}
WorkingDirectory=${SCRIPT_DIR}
ExecStart=${PYTHON_BIN} ${SCRIPT_DIR}/meshtastic_bbs_server.py --port /dev/ttyUSB0
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
echo "      ✓ 服務檔已建立：$SERVICE_FILE"

# 部署 .desktop 啟動捷徑
DESKTOP_FILE="$HOME/.local/share/applications/meshtastic-bbs-gui.desktop"
mkdir -p "$HOME/.local/share/applications"
sed "s|/home/pi/meshtastic-bbs|${SCRIPT_DIR}|g" \
    "${SCRIPT_DIR}/meshtastic-bbs-gui.desktop" > "$DESKTOP_FILE"
chmod +x "$DESKTOP_FILE"
echo "      ✓ 桌面捷徑已建立：$DESKTOP_FILE"

# ── 完成 ─────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║               安裝完成！                        ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "重要：請登出後重新登入，讓 dialout 群組設定生效。"
echo ""
echo "── 確認 Meshtastic 裝置路徑 ────────────────────────"
echo "  插入裝置後執行："
echo "    ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null"
echo ""
echo "── 手動啟動（測試用）────────────────────────────────"
echo "  序列埠連線："
echo "    python3 ${SCRIPT_DIR}/meshtastic_bbs_server.py --port /dev/ttyUSB0"
echo "    python3 ${SCRIPT_DIR}/meshtastic_bbs_server.py --port /dev/ttyACM0"
echo ""
echo "  TCP 連線（Meshtastic 裝置在網路上時）："
echo "    python3 ${SCRIPT_DIR}/meshtastic_bbs_server.py --host 192.168.1.100"
echo ""
echo "── systemd 服務管理 ─────────────────────────────────"
echo "  啟動：sudo systemctl start  ${SERVICE_NAME}"
echo "  停止：sudo systemctl stop   ${SERVICE_NAME}"
echo "  自動啟動：sudo systemctl enable ${SERVICE_NAME}"
echo "  查看日誌：journalctl -u ${SERVICE_NAME} -f"
echo ""
echo "  若裝置路徑不是 /dev/ttyUSB0，請編輯服務檔後重新載入："
echo "    sudo nano $SERVICE_FILE"
echo "    sudo systemctl daemon-reload"
echo ""
echo "── GUI 控制台 ───────────────────────────────────────"
echo "  直接執行："
echo "    python3 ${SCRIPT_DIR}/bbs_gui.py"
echo ""
echo "  或從應用程式選單搜尋「Meshtastic BBS 控制台」"
echo ""
echo "  瀏覽器開啟（伺服器啟動後）："
echo "    BBS：  http://localhost:8765"
echo "    後台：  http://localhost:8765/admin"
