<p align="center">
  <img src="assets/app-logo.png" width="200" alt="MeshBBS Logo">
</p>

<h1 align="center">MeshBBS</h1>

<p align="center">
  <b>Meshtastic / LoRa Mesh 去中心化 Android BBS</b>
</p>

<p align="center">
  官方 APK 發佈與安全自動更新來源
</p>

---

## 📱 關於 MeshBBS

📡 MeshBBS 是什麼？

MeshBBS 是一款以 Meshtastic LoRa Mesh 網路為基礎打造的去中心化離線 BBS 通訊 App。即使在沒有行動網路、Wi-Fi 或基地台的環境下，只要附近有 Meshtastic 節點，就能透過 LoRa Mesh 交換文章與資料。

MeshBBS 支援 看板、文章發布、節點間自動資料同步、斷線續傳、資料完整性驗證、個人身分與數位簽章，並針對 LoRa 低頻寬環境設計 Adaptive Sync、資料壓縮、分段傳輸與遺失資料修復機制，讓短暫接觸的節點也能盡可能快速、穩定地完成同步。

所有資料主要保存在使用者自己的裝置中，不依賴中央伺服器。節點再次相遇時，MeshBBS 會自動比對彼此缺少的資料並進行差異同步，逐步讓訊息隨著人員與節點移動，在 Mesh 網路中擴散。

適合使用於 登山、露營、戶外活動、災害備援、無網路地區、社群 Mesh 網路，以及任何希望在傳統網際網路之外建立獨立通訊管道的情境。

No Internet. No Server. Just Mesh.

沒有網路，也能讓訊息繼續傳下去。
---

## 📥 Download

正式版本請由 GitHub Releases 下載：

**https://github.com/JASON085/MeshBBS/releases/latest**

請只安裝由此官方 Repository 發佈的 APK。

---

## 🔄 Automatic Updates

MeshBBS 支援透過官方 GitHub Releases：

- 自動檢查新版
- 下載官方 APK
- 版本驗證
- SHA-256 完整性驗證
- Release Manifest 數位簽章驗證
- APK 身分與簽章驗證

驗證完成後才會交由 Android 系統進行更新。

---

## 🔐 Security

官方發佈來源：

**https://github.com/JASON085/MeshBBS**

第三方修改、重新封裝或重新散布的 APK 不屬於官方 MeshBBS 發佈版本。

---

## 🔒 Source Code

MeshBBS 為 proprietary software。

本 Repository **不公開程式原始碼**，亦不包含：

- Kotlin / Java Source
- Android Studio Project
- Build Scripts
- Development Tools
- Private Development Documents

本 Repository 僅用於正式程式發佈與自動更新。

---

<p align="center">
  <b>MeshBBS</b><br>
  Decentralized communication over Meshtastic / LoRa Mesh
</p>

<p align="center">
  Copyright © 2026 JASON085.<br>
  All Rights Reserved.
</p>
