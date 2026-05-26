# Project Overview: MeshBBS Android Client + Android Server

本文件面向「另一位 AI 工程助手快速接手專案」。
內容以目前程式碼實作為準，分析重點放在 `android/MeshtasticBBS/` 的 Android client / Android server。

## 0. 快速摘要

- 這是一個單一 Android App 專案，透過 `productFlavors` 分成兩個 APK：
  - `client` flavor: `MeshBBS`
  - `server` flavor: `MeshServer`
- 兩者共用同一套大部分 Kotlin 程式碼與同一個 `AndroidManifest.xml`，以 `BuildConfig.SERVER_BUILD` 分流入口行為。
- client 不是走 HTTP / Retrofit，而是透過 Meshtastic Android App 的 AIDL service (`IMeshService`) 與 LoRa mesh 溝通。
- server 不是純 Kotlin server，而是 Android foreground service + Chaquopy + Python + SQLite。
- Android server 的 Python 核心檔是 `app/src/main/python/meshbbs_android_server.py`。
- repository 根目錄仍保留 PC/Python 版 server/client/tooling；Android 版是加上去的，不是取代 PC 版。

---

## 1. APP 名稱與用途

### 1.1 `MeshBBS`（Android client）

用途：

- 讓 Android 手機透過已安裝的 Meshtastic App 與 LoRa 節點連線。
- 透過 mesh 傳送 BBS 指令，完成登入、看板列表、文章列表、閱讀、發文、回覆、推文、搜尋等操作。
- 額外提供簡易 mesh chat 畫面。

### 1.2 `MeshServer`（Android server flavor）

用途：

- 讓 Android 裝置本身成為 BBS server 節點。
- 透過 Meshtastic App 的 service / broadcast 通道接收其他節點送來的 BBS 請求。
- 在本機用 Python + SQLite 處理帳號、看板、文章、回覆、管理員、備份等邏輯，再把結果分段送回 mesh。

### 1.3 與 PC 版的關係

- 根目錄仍有 PC/Python 版 server/client：
  - `meshtastic_bbs_server.py`
  - `mesh_bbs_client.py`
  - `bbs_gui.py`
  - `build_tool.py`
- Android server 是「加一個 Android 可攜版 server」，不是重寫或移除 PC server。
- 若未來改 PC server，仍要注意 `build_tool.py` / `build_server.spec` 的 EXE 製作流程不要被破壞。

---

## 2. 主要功能介紹

### 2.1 Android client 主要功能

- 連接 Meshtastic App 並檢查藍牙權限。
- 掃描並顯示 mesh node，區分一般節點與疑似 BBS 節點。
- 收藏常用 server node。
- 選擇目標 BBS server node，或使用廣播模式。
- BBS 登入 / 自動建立新帳號。
- 讀取看板列表、文章列表、文章內容。
- 分頁載入文章列表。
- 發文、回覆、編輯文章、編輯回覆、刪文、刪回覆。
- 推文 / 取消推文。
- 搜尋文章。
- 修改目前使用者密碼。
- 獨立 mesh chat 畫面。
- 發送中與接收中進度 UI。
- 慢速或掉包時的自動重試與提示。

### 2.2 Android server 主要功能

- 啟動 foreground service 充當 BBS server。
- 綁定 Meshtastic App service，接收 `BBS:REQ` / `BBS:REQC` 指令。
- 將請求交給 Python server core 處理。
- 本機 SQLite 儲存 boards / posts / replies / users / admins / mesh_messages / pushes。
- 透過 server dashboard 管理：
  - 啟停 server
  - hopLimit
  - DB 備份 / 匯入 / 匯出 / 刪除
  - 清除 server mesh chat 紀錄
  - 管理員新增 / 刪除 / 改密碼
  - 看板新增 / 編輯 / 刪除
  - 使用者停權 / 解鎖 / 設密碼 / 刪除
  - 最近文章 / 執行紀錄查看
- 支援看板版主權限，版主可刪除自己板上的文章與回覆。

---

## 3. 使用的技術架構

整體屬於「共享單模組 Android App + flavor 分流 + client MVVM-ish + server service/bridge 架構」。

### 3.1 專案層級

- Gradle root 只有一個 Android app module：`:app`
- `client` / `server` 是同一個 module 下的 flavor，不是多 module 拆分
- flavor 主要差異：
  - app 名稱
  - icon
  - `applicationIdSuffix`
  - `BuildConfig.SERVER_BUILD`
  - NDK ABI

### 3.2 Client 架構

資料流大致如下：

`Compose Screen -> BbsViewModel -> MeshtasticRepository -> IMeshService(AIDL) -> Meshtastic App -> LoRa Mesh -> BBS Server`

回應流：

`LoRa Mesh -> Meshtastic App -> BroadcastReceiver / subscribeReceiver -> MeshtasticRepository -> BbsEvent -> BbsViewModel -> Compose UI`

### 3.3 Server 架構

資料流大致如下：

`ServerHostScreen -> AndroidServerService -> MeshtasticServerRepository -> PythonServerBridge -> meshbbs_android_server.py -> sqlite3`

回應流：

`Python JSON response -> MeshtasticServerRepository -> MBBS2 chunk response -> Meshtastic App -> LoRa Mesh`

### 3.4 架構特徵

- client 比較接近 MVVM，但沒有嚴格 domain/data/usecase 分層。
- server 比較像「Service + Store + Python core」模式，不是 MVVM。
- 沒有正式的 Clean Architecture。
- 大檔集中度偏高，幾個核心檔案體積都很大：
  - `MeshtasticRepository.kt`
  - `BbsViewModel.kt`
  - `ServerHostScreen.kt`
  - `meshbbs_android_server.py`

---

## 4. Android 技術棧

| 類別 | 現況 |
|---|---|
| Kotlin / Java | 幾乎全 Kotlin；沒有手寫 Java 業務邏輯 |
| Python | Android server 透過 Chaquopy 內嵌 Python 3.13 |
| Jetpack Compose / XML | UI 幾乎全 Jetpack Compose；未見傳統 XML layout |
| MVVM / MVC / Clean Architecture | client 為 MVVM-ish；server 為 service/store/bridge；非嚴格 Clean Architecture |
| Coroutine / Flow / LiveData | 使用 Coroutine + Flow + StateFlow；未使用 LiveData |
| Room / Retrofit / Firebase | 都未使用 |
| 本機儲存 | SharedPreferences（收藏節點、node cache）+ Python `sqlite3`（server DB） |
| Android IPC | AIDL (`IMeshService`, `DataPacket`, `MeshUser`) |
| 導航 | 手動 screen state 切換；沒有實際使用 `NavHost` |
| JSON / 壓縮 | `org.json` + Base64 + zlib/Deflater/Inflater |

### 4.1 具體判讀

- 語言：
  - Android app: Kotlin
  - Android server core: Python
- UI：
  - `MainActivity.kt` 直接以 `when (screen)` 切換 Compose 畫面
  - `navigation-compose` 有被加入依賴，但目前沒有看到 `NavHost` / `NavController` 真正上場
- 狀態管理：
  - `BbsViewModel` 用 `MutableStateFlow<BbsUiState>`
  - `ServerHostStore` 用 `MutableStateFlow<ServerHostState>`
- 非同步：
  - client: `viewModelScope`, `callbackFlow`, background executor
  - server: `CoroutineScope(Dispatchers.IO + SupervisorJob())` + single-thread executors
- 資料層：
  - client 無 Room，資料幾乎是記憶體狀態 + SharedPreferences 快取
  - server DB 完全在 Python `sqlite3`

---

## 5. 專案目錄結構說明

```text
E:\My work\Meshtastic BBS-Android
├─ android/
│  └─ MeshtasticBBS/
│     ├─ app/
│     │  ├─ build.gradle.kts
│     │  └─ src/
│     │     ├─ main/
│     │     │  ├─ AndroidManifest.xml
│     │     │  ├─ aidl/
│     │     │  │  ├─ com/geeksville/mesh/
│     │     │  │  └─ org/meshtastic/core/
│     │     │  ├─ java/com/meshtastic/bbs/
│     │     │  │  ├─ MainActivity.kt
│     │     │  │  ├─ data/
│     │     │  │  ├─ viewmodel/
│     │     │  │  ├─ ui/screens/
│     │     │  │  ├─ ui/theme/
│     │     │  │  └─ server/
│     │     │  ├─ java/com/geeksville/mesh/
│     │     │  ├─ java/org/meshtastic/core/
│     │     │  ├─ python/meshbbs_android_server.py
│     │     │  └─ res/
│     │     └─ server/
│     │        └─ res/   # server flavor 專用 icon 資源
│     ├─ build_apk.ps1
│     ├─ build_server_apk.ps1
│     ├─ BUILD_GUIDE.md
│     └─ signing/
├─ meshtastic_bbs_server.py
├─ mesh_bbs_client.py
├─ build_tool.py
├─ build_server.spec
├─ build_client.spec
└─ 維護記錄/
```

### 5.1 Android 相關重點

- `app/src/main/` 是 client/server 共用主體。
- `app/src/server/res/` 只有 server flavor 的 launcher 資源。
- 沒有 `app/src/client/` 的專屬邏輯層；client 主要靠 `SERVER_BUILD=false` 分流。

---

## 6. 各模組用途

### 6.1 `com.meshtastic.bbs`

- `MainActivity.kt`
  - app 入口
  - 檢查藍牙權限
  - 根據 `BuildConfig.SERVER_BUILD` 決定進 client 還是 server UI

### 6.2 `com.meshtastic.bbs.viewmodel`

- `BbsViewModel.kt`
  - client 的核心狀態與畫面導航中心
  - 管理登入、讀板、讀文、發文、搜尋、mesh chat、重試、進度條

### 6.3 `com.meshtastic.bbs.data`

- `MeshtasticRepository.kt`
  - client 真正的 Meshtastic 通訊核心
  - 負責 bind service、發送 BBS request、收 response、解壓縮、轉成 `BbsEvent`
- `Models.kt`
  - UI 與協定使用的資料模型 / event 定義 / JSON parse helper
- `MeshPacketReceiver.kt`
  - Manifest 註冊的靜態 receiver
  - 專門接 Meshtastic directed broadcast
- `NodeCacheStore.kt`
  - 將已觀察到的 mesh node 快取到 SharedPreferences
- `FavoriteNodesStore.kt`
  - 收藏 server 節點
- `BbsRepository.kt`
  - 已廢棄 placeholder
  - 註解顯示先前的 WebSocket/WiFi 模式已移除，現在只保留 Meshtastic IPC 路徑

### 6.4 `com.meshtastic.bbs.ui.screens`

- `ServerSelectScreen.kt`
  - 節點掃描、搜尋、收藏、選 server
- `LoginScreen.kt`
  - BBS 登入
- `BoardListScreen.kt`
  - 看板列表、線上使用者、改密碼、登出、進度顯示
- `PostListScreen.kt`
  - 文章列表、搜尋、分頁、發文入口
- `PostViewScreen.kt`
  - 文章閱讀、推文、回覆、編輯 / 刪除
- `ComposeScreen.kt`
  - 發文 / 回覆 / 編輯共用畫面
- `MeshChatScreen.kt`
  - mesh chat
- `ServerHostScreen.kt`
  - Android server 控制台

### 6.5 `com.meshtastic.bbs.server`

- `AndroidServerService.kt`
  - Android server 的生命週期與前景服務
  - 負責接 request、去重、解壓、交給 Python、回傳 response
- `MeshtasticServerRepository.kt`
  - server 端 Meshtastic service binding / packet decode / response chunk send
- `PythonServerBridge.kt`
  - Kotlin 與 Chaquopy Python module 間的橋接
- `ServerHostStore.kt`
  - server host UI state store
- `ServerModels.kt`
  - dashboard / boards / users / backups / admins 等畫面模型

### 6.6 `app/src/main/python`

- `meshbbs_android_server.py`
  - Android server 的實際業務邏輯核心
  - SQLite schema、登入、權限、CRUD、搜尋、管理、備份都在這裡

---

## 7. API 與資料流架構

### 7.1 通訊路徑不是 HTTP API

這個 Android client/server 的主要 API 不是 REST，也不是 Retrofit。
它是自定義的 mesh 協定，跑在 Meshtastic `DataPacket` 上。

### 7.2 client -> server 指令

常見命令：

- `LOGIN`
- `LOGOUT`
- `LIST`
- `POSTS`
- `READ`
- `POST`
- `REPLY`
- `EDIT`
- `DEL`
- `DELREP`
- `EDITREP`
- `PUSH`
- `CHPASS`
- `SEARCH`
- `HEARTBEAT`

### 7.3 封包格式

小型 request：

- `BBS:REQ:<seq>:<cmd>:<args>`

大型 request（例如發文 / 回覆 / 編輯）：

- `BBS:REQC:<seq>:<cmd>:<index>:<total>:<base64-zlib-chunk>`

server response：

- 舊格式支援：`BBS:RES`
- 現在主力格式：`MBBS2|<dest>|<seq>|<index>|<total>\n<binary chunk>`

mesh chat：

- `MBCHAT1` prefix + JSON payload

### 7.4 client 收包流程

1. `MeshtasticRepository.connect()` 綁定 `IMeshService`
2. 註冊 receiver 與 `subscribeReceiver`
3. 收到 `DataPacket`
4. 判斷是否為：
   - node change
   - mesh chat
   - BBS response
5. 若為 chunked response：
   - 暫存在 `pending`
   - 全部到齊後做 Base64 / zlib 解壓
   - 轉 JSON
   - map 成 `BbsEvent`
6. `BbsViewModel.handleEvent()` 更新 UI state

### 7.5 server 收包流程

1. `AndroidServerService` 啟動後建立 `MeshtasticServerRepository`
2. repository 綁定 Meshtastic service、收 directed/private/text packet
3. `AndroidServerService.handleIncomingRequest()` 判斷：
   - `BBS:REQ`
   - `BBS:REQC`
   - plain text `?` help
4. 若為壓縮 request：
   - 重組 chunk
   - Base64 / zlib 解壓
5. 呼叫 `PythonServerBridge.handleRequest()`
6. Python 回傳 JSON response
7. `MeshtasticServerRepository.sendResponse()` 壓縮後切 chunk 送回對方

### 7.6 server 內部資料流

`handle_request(cmd, args, node_id, node_name)` 是 Python 核心入口。

它會分派到：

- `mesh_login`
- `get_boards`
- `get_posts`
- `get_post`
- `create_post`
- `create_reply`
- `update_post`
- `update_reply`
- `delete_post`
- `delete_reply`
- `toggle_push`
- `search_posts`
- `change_current_user_password`

### 7.7 權限判斷重點

- 使用者登入後，server 會追蹤 active client name / node id 對應。
- 看板刪文 / 刪回覆權限會檢查：
  - 作者本人
  - 或 `can_moderate_board(...)` 為真
- 版主判斷同時考慮：
  - `moderator`
  - `moderator_id`

---

## 8. UI 架構與頁面流程

### 8.1 client 畫面流程

主流程：

`ServerSelect -> Login -> BoardList -> PostList -> PostView -> Compose`

支線：

- `BoardList -> MeshChat`
- `PostView -> Compose(reply)`
- `PostView -> Compose(edit)`
- `PostList -> Compose(new post)`

### 8.2 client 導航方式

- 沒有使用 `NavHost`
- `MainActivity` 直接看 `vm.screen.collectAsStateWithLifecycle()`
- `Screen` sealed class 為：
  - `Login`
  - `ServerSelect`
  - `Boards`
  - `Posts(boardName)`
  - `PostView(postId)`
  - `Compose(boardName, replyToPostId, editPostId)`
  - `MeshChat`

### 8.3 client UI 特性

- 全 Compose
- 使用 `rememberSaveable` 保留登入欄位與 compose 畫面內容
- 長時間等待時保持螢幕喚醒
- 讀取 / 發送都有階段式進度顯示
- 大部分畫面是單檔完成，不是拆成大量 reusable components

### 8.4 server UI 流程

server flavor 啟動後直接進 `ServerHostScreen`

主要區塊：

- 狀態列
- 啟動 / 停止 / refresh / lock / hopLimit 控制
- 統計資訊
- DB 管理
- 管理員管理
- 看板管理
- 使用者管理
- 最近文章
- 執行紀錄

### 8.5 server UI 與 service 的關係

- `ServerHostScreen` 顯示 `ServerHostStore.state`
- 啟停透過 `AndroidServerService.start/stop/refresh(...)`
- 真正 server 執行邏輯不在 UI，而在 foreground service

---

## 9. 目前已完成的功能

### 9.1 client 已完成

- Meshtastic service 綁定與廣播接收
- server node 掃描與收藏
- 選 server / 廣播模式
- BBS 登入 / 登出
- 看板列表
- 文章列表 / 分頁
- 文章閱讀
- 發文 / 回覆 / 編輯 / 刪除
- 推文 / 取消推文
- 搜尋
- 變更密碼
- mesh chat
- 收發進度 UI
- 慢速讀取的自動重試
- node cache / favorite cache

### 9.2 server 已完成

- Android foreground service server host
- Meshtastic service 綁定與 request 接收
- Python server core 啟動
- SQLite 初始化與 migration 型補欄位
- boards / posts / replies / users / admins / mesh_messages / post_pushes
- login / heartbeat / session tracking
- board moderation 權限
- DB backup / import / export / delete
- 管理員新增 / 刪除 / 改密碼
- 使用者停權 / 設密碼 / 刪除
- 看板新增 / 編輯 / 刪除
- plain text `?` help reply
- 壓縮 request / chunked response
- hopLimit runtime 設定

### 9.3 Build / packaging 已完成

- client / server 雙 flavor APK 輸出
- 保留舊 APK 的 `apk_archive` 流程
- `build_server_apk.ps1` 直接包 server
- release signing 可由 env vars 或 local properties 提供

---

## 10. 尚未完成或已知問題

### 10.1 明顯的技術債

- `MeshtasticRepository.kt`、`BbsViewModel.kt`、`ServerHostScreen.kt`、`meshbbs_android_server.py` 都偏大，後續維護成本高。
- client 與 server 邏輯耦合在單一 app module 中，雖然實用，但不利長期擴充。
- 沒有自動化測試（unit test / integration test / UI test 都未看到）。

### 10.2 目前可見的已知風險

- 版本號來源不一致：
  - `app/build.gradle.kts` 的 `versionName` 目前是 `b0603a`
  - `MeshtasticRepository.BUILD` 目前是 `b0604l`
  - 打包腳本實際用 `BUILD` 常數命名 APK
  - 這表示「Gradle 版本資訊」與「實際 APK 檔名版本」有分裂風險
- 多個文件與部分字串有編碼/亂碼痕跡：
  - `README.md`
  - `BUILD_GUIDE.md`
  - 部分 Kotlin / server UI 字串
- `navigation-compose` 依賴已存在但未實際使用，容易讓新接手者誤判導航方式。
- 專案很依賴 Meshtastic Android App 的 service / broadcast 行為；若上游 app action 或 AIDL 改動，client/server 都可能受影響。

### 10.3 功能層面的潛在待辦

- 更完整的錯誤恢復與封包遺失診斷工具。
- 更細的模組拆分。
- server UI 的更多維運能力（例如更完整的查詢 / 篩選 / 統計）。
- protocol 文件化仍可再加強。

---

## 11. Build 與執行方式

### 11.1 Android 專案位置

- `android/MeshtasticBBS/`

### 11.2 主要 build 腳本

- 全用腳本：`android/MeshtasticBBS/build_apk.ps1`
- server 專用快捷：`android/MeshtasticBBS/build_server_apk.ps1`

### 11.3 典型指令

```powershell
cd "android\MeshtasticBBS"
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -Variant client -BuildType release
```

```powershell
cd "android\MeshtasticBBS"
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -Variant server -BuildType release
```

```powershell
cd "android\MeshtasticBBS"
powershell -ExecutionPolicy Bypass -File .\build_server_apk.ps1
```

### 11.4 Build 特性

- 會自動找 Android Studio JBR
- 會自動找 Android SDK
- 會下載 / 使用獨立 Gradle 8.9
- 會把既有 APK 複製到 `apk_archive/`，避免舊版消失
- 會依 `MeshtasticRepository.BUILD` 命名 APK
- 可選擇自動 adb install

### 11.5 release signing

可用兩種方式提供：

- 環境變數：
  - `MESHBBS_RELEASE_STOREPASS`
  - `MESHBBS_RELEASE_KEYPASS`
  - 可選 `MESHBBS_RELEASE_KEYALIAS`
- 本機檔案：
  - `android/MeshtasticBBS/signing/signing.properties`
  - 範本：`android/MeshtasticBBS/signing/signing.properties.example`

### 11.6 執行時依賴

- 手機必須安裝並可運作 Meshtastic Android App
- 必須能 bind 到 Meshtastic service
- Android 12+ 需要藍牙相關權限
- server 模式建議忽略電池最佳化

---

## 12. 重要第三方套件

### 12.1 Android 依賴

- `androidx.compose` BOM
- `androidx.compose.ui`
- `androidx.compose.material3`
- `androidx.compose.material:material-icons-extended`
- `androidx.activity:activity-compose`
- `androidx.navigation:navigation-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`
- `androidx.lifecycle:lifecycle-runtime-compose`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- `androidx.core:core-ktx`

### 12.2 關鍵整合

- `com.chaquo.python`
  - 讓 Android server 可以直接跑 Python
- Meshtastic Android AIDL / Parcelable mirror
  - `IMeshService.aidl`
  - `DataPacket.aidl`
  - `MeshUser.aidl`

### 12.3 沒有用到但容易被誤會的技術

- 沒有 Retrofit
- 沒有 Room
- 沒有 Firebase
- 沒有 WebSocket client 資料路徑
- 沒有正式 multi-module clean layering

---

## 13. 程式設計特色與亮點

### 13.1 實務導向的 flavor 設計

- 用一個 app module 同時產出 client 與 server APK
- 共用大部分程式碼，但又保留不同 app identity（`MeshBBS` / `MeshServer`）

### 13.2 不走網路 API，而是直接走 Meshtastic IPC

- 透過 AIDL service + directed broadcast 接 Meshtastic App
- 避免另外建立自家 BLE / serial stack

### 13.3 大 payload 傳輸設計

- request 端有 `BBS:REQC` 壓縮分段
- response 端有 `MBBS2` binary chunk
- 搭配 Base64 / zlib 降低 mesh 傳輸壓力

### 13.4 Android server 採 Python bridge

- 讓現有 Python BBS 邏輯能直接重用到 Android
- SQLite 操作與管理邏輯集中在 Python，比完全重寫 Kotlin server 省成本

### 13.5 UX 針對慢速 mesh 做過調整

- 送出進度
- 接收進度
- keep screen on
- timeout retry
- node cache / favorites

### 13.6 Manifest receiver 的特殊處理很關鍵

- `MeshPacketReceiver` 不是多餘檔案
- 這是為了配合 Meshtastic 2.7.x 的 directed broadcast 行為
- 若移除它，client/server 可能收不到關鍵封包

---

## 14. 建議接手順序

如果要快速上手，建議先看這些檔案：

1. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/MainActivity.kt`
2. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/viewmodel/BbsViewModel.kt`
3. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`
4. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`
5. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`
6. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/PythonServerBridge.kt`
7. `android/MeshtasticBBS/app/src/main/python/meshbbs_android_server.py`
8. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/ui/screens/ServerHostScreen.kt`

---

## 15. 一句話結論

這個專案的 Android 面向，本質上是：

**「用 Compose 寫的 Meshtastic BBS client，加上一個透過 Chaquopy 在 Android 上跑 Python + SQLite 的 BBS server flavor，雙方都直接依附 Meshtastic App 的 AIDL / broadcast 通道來跑 LoRa BBS 協定。」**
