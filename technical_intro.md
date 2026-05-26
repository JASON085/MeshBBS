# Technical Intro

`MeshBBS` / `MeshServer` 是一個以 Meshtastic 為傳輸底座的 Android BBS 系統：client 讓手機透過 LoRa mesh 進行登入、看板瀏覽、文章讀寫、搜尋與 mesh chat；server flavor 則把 Android 裝置本身變成可攜式 BBS 節點。整體不是走 HTTP API，而是直接整合 Meshtastic Android App 的 AIDL service 與 packet broadcast，將 BBS 協定封裝在 `DataPacket` 上傳輸。

技術架構採單一 Android app module + `client/server` product flavor。client 端主要是 Kotlin + Jetpack Compose + StateFlow 的 MVVM 風格，入口由 `MainActivity` 依 `BuildConfig.SERVER_BUILD` 分流；畫面狀態集中在 `BbsViewModel`，Meshtastic 通訊集中在 `MeshtasticRepository`。server 端則是 foreground service 架構：`AndroidServerService` 負責生命週期與 request dispatch，`MeshtasticServerRepository` 處理 Meshtastic 綁定與封包收發，`PythonServerBridge` 透過 Chaquopy 呼叫 `meshbbs_android_server.py`，後者用 SQLite 實作 boards、posts、replies、users、admins 與 moderation 規則。

Android 技術棧以 Kotlin、Compose、Coroutines、Flow、AIDL 為主；未使用 Room、Retrofit、Firebase。UI 架構不是 `NavHost`，而是以 sealed `Screen` + `when` 進行手動導航，路徑清楚且便於在弱網路/慢傳輸環境下直接控制流程。client 的資料流為 `Compose Screen -> ViewModel -> MeshtasticRepository -> IMeshService -> Meshtastic App -> LoRa mesh`，回應再經由 `MeshPacketReceiver` 回灌成 `BbsEvent` 更新 UI；server 則是 `ServerHostScreen -> AndroidServerService -> Python bridge -> SQLite core`。

系統亮點在於幾個實務優化：大型發文/回覆請求採 `BBS:REQC` + zlib/Base64 分段壓縮，回應採 `MBBS2` chunk protocol；UI 內建送出進度、接收進度、慢速重試與 keep-screen-on；server 端支援 hopLimit 控制、DB 備份/匯入匯出、管理員與版主管理。對接手者來說，這個專案的核心不是一般 app + REST backend，而是「Compose client + Android foreground service + Python/SQLite server core + Meshtastic IPC transport」的混合式設計。
