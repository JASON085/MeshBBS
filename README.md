# Meshtastic BBS

這是一個以 Meshtastic / LoRa 為通訊基礎的 BBS 專案，主要提供：

- Python BBS Server
- 終端機版 Client
- 管理介面與桌面 GUI 工具
- Android 端相關原始碼與打包腳本

專案適合在 Windows、Linux 或 Raspberry Pi 上搭配 Meshtastic 裝置使用。

## MeshBBS 與 Android Server 說明

### MeshBBS

`MeshBBS` 是這個專案的 Android 用戶端 App。

- 提供 Android 手機上的 BBS / Mesh 訊息使用介面
- 透過已安裝的 Meshtastic App 與裝置通訊
- 可瀏覽看板、發文、回文、登入與接收 Mesh 訊息

如果你只是想在 Android 裝置上使用 BBS，用 `MeshBBS` 即可。

### Android Server

Android 版本另外提供一個 server flavor，可作為 `Android Server` / `MeshServer` 使用。

- 讓 Android 裝置本身直接扮演 BBS Server
- 使用 Python + SQLite 在 Android 上維持站台資料
- 仍沿用已安裝的 Meshtastic App 作為無線傳輸路徑
- 可和 `MeshBBS` 用戶端 App 共存於同一台裝置

如果你希望手機本身直接提供 BBS 服務，而不是只當 client，就使用 Android Server 版本。

## 專案結構

- `meshtastic_bbs_server.py`: BBS 伺服器，提供 HTTP / WebSocket / 管理 API
- `mesh_bbs_client.py`: 終端機版 BBS Client
- `bbs_gui.py`: 桌面管理 GUI
- `bbs_mesh_relay.py`: Mesh relay / bridge 工具
- `build_tool.py`: Windows 打包工具
- `build_server.spec` / `build_client.spec`: PyInstaller 設定
- `android/MeshtasticBBS/`: Android App 原始碼

## 環境需求

- Python 3.11 以上
- Meshtastic 裝置，或可連到 Meshtastic 的主機
- Windows、Linux 或 Raspberry Pi

Python 依賴套件：

```bash
pip install meshtastic aiohttp pypubsub colorama pyinstaller
```

如果只需要執行伺服器，不一定會用到 `colorama` 與 `pyinstaller`，但保留安裝可避免部分工具執行時缺套件。

## 安裝方式

建議先建立虛擬環境：

```bash
python -m venv .venv
```

Windows:

```bash
.venv\Scripts\activate
pip install meshtastic aiohttp pypubsub colorama pyinstaller
```

Linux / macOS:

```bash
source .venv/bin/activate
pip install meshtastic aiohttp pypubsub colorama pyinstaller
```

## 執行方式

### 1. 啟動 BBS Server

Windows:

```bash
python meshtastic_bbs_server.py --port COM3
```

Linux / Raspberry Pi:

```bash
python meshtastic_bbs_server.py --port /dev/ttyUSB0
```

如果要自訂管理員密碼：

```bash
python meshtastic_bbs_server.py --port COM3 --admin-password your-password
```

啟動後可使用：

- `http://localhost:8765`
- `http://localhost:8765/admin`

### 2. 啟動終端機 Client

```bash
python mesh_bbs_client.py --port COM3
```

或指定遠端 server node：

```bash
python mesh_bbs_client.py --port COM3 --server !ab12cd34
```

### 3. 啟動桌面 GUI

```bash
python bbs_gui.py
```

### 4. 啟動 Relay

```bash
python bbs_mesh_relay.py --port COM3 --server !ab12cd34
```

### 5. 打包 Windows EXE

```bash
python build_tool.py
```

或直接使用 PyInstaller：

```bash
pyinstaller build_server.spec
pyinstaller build_client.spec
```

## Android 補充

Android 專案位於 `android/MeshtasticBBS/`。  
若要製作 release APK，請自行準備本機簽章檔與 `signing/signing.properties`，不要提交到 GitHub。

Android 專案目前採用 flavor 方式輸出兩種 APK：

- `MeshBBS-<version>.apk`: Android client 版本
- `MeshBBS-server-<version>.apk`: Android Server 版本

其中：

- client 版本主要給一般使用者登入、閱讀、發文與收發 Mesh 訊息
- server 版本主要給要在 Android 裝置上架設 BBS 節點的人使用

可用以下兩種方式提供 release signing 密碼：

1. 建立本機私有檔 `android/MeshtasticBBS/signing/signing.properties`
2. 先設定環境變數 `MESHBBS_RELEASE_STOREPASS`、`MESHBBS_RELEASE_KEYPASS`

專案已提供範例檔：

- `android/MeshtasticBBS/signing/signing.properties.example`

## 上 GitHub 前的安全建議

以下內容建議只留在本機，不要上傳：

- `bbs.db` 與其他 SQLite 資料庫
- `.env`、`.env.*`
- Android 簽章檔、keystore、`signing.properties`
- `build/`、`dist/`、`apk_archive/` 等產物
- 個人 IDE 設定與暫存檔

本 repo 已透過 `.gitignore` 排除上述常見敏感資料與建置產物。
