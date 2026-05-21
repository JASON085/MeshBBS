# MeshBBS Android 打包建置說明

## 環境需求

| 工具 | 版本 | 說明 |
|------|------|------|
| Android Studio | 任意版本 | 提供 JBR (JDK) 和 SDK |
| Android SDK platform | 34 (android-34) | 在 SDK Manager 安裝 |
| Android SDK build-tools | 34.0.0 | 在 SDK Manager 安裝 |
| Gradle | 8.9 | 由 `build_apk.ps1` 自動下載 |
| Java | 17 或 21 | 使用 Android Studio 內建 JBR |

---

## 一鍵打包（Windows PowerShell）

```powershell
cd "android\MeshtasticBBS"
powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
```

腳本會自動：
1. 尋找 Android Studio 的 JBR（JDK）
2. 下載 Gradle 8.9（第一次，約 130 MB，快取在 `%USERPROFILE%\.gradle_standalone\`）
3. 編譯 debug APK
4. 顯示 APK 路徑並詢問是否開啟資料夾

APK 輸出位置：
```
app\build\outputs\apk\debug\app-debug.apk
```

---

## 換電腦後的設定步驟

### 1. 安裝 Android Studio
下載：https://developer.android.com/studio

### 2. 安裝 Android SDK Platform 34
開啟 Android Studio → Tools → SDK Manager → SDK Platforms
勾選 **Android 14.0 (API 34)** → Apply

### 3. 安裝 Build Tools 34.0.0
SDK Manager → SDK Tools → 勾選 **Android SDK Build-Tools 34** → Apply

### 4. 執行打包腳本
```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```

---

## 手動打包（不用腳本）

```powershell
# 設定環境變數（路徑依實際 Android Studio 安裝位置調整）
$env:JAVA_HOME  = "C:\Program Files\Android\Android Studio1\jbr"
$env:PATH       = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# 執行 Gradle（Gradle 8.9 需先下載或用 gradlew）
gradle assembleDebug
```

---

## 常見問題與已知修正紀錄

### 問題 1：`android.useAndroidX` not enabled
**錯誤**：`Configuration contains AndroidX dependencies, but the android.useAndroidX property is not enabled`
**修正**：在 `gradle.properties` 加入：
```
android.useAndroidX=true
```

### 問題 2：Theme 找不到
**錯誤**：`resource android:style/Theme.Material.NoTitleBar not found`
**修正**：`app/src/main/res/values/themes.xml` 改用：
```xml
<style name="Theme.MeshtasticBBS" parent="android:Theme.Material.Light.NoActionBar">
```

### 問題 3：Kotlin 編譯錯誤（OkHttp）
**原因**：`BbsRepository.kt` 保留了 OkHttp import，但依賴已移除
**修正**：`BbsRepository.kt` 改為空 placeholder（純註解）

### 問題 4：AIDL 未啟用
**錯誤**：`IMeshService` unresolved reference
**修正**：`app/build.gradle.kts` 加入：
```kotlin
buildFeatures {
    compose = true
    aidl   = true
}
```

### 問題 5：APP 閃退（Android 14+）
**原因**：`registerReceiver()` 在 Android 14+（API 34）必須指定 `RECEIVER_EXPORTED`
**修正**：`MeshtasticRepository.kt` 改用：
```kotlin
ContextCompat.registerReceiver(
    context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
```
並在 `BbsViewModel.kt` 改用 `context.applicationContext` 避免 Activity 洩漏

---

## 專案結構

```
android/MeshtasticBBS/
├── build_apk.ps1                    ← 一鍵打包腳本
├── gradle.properties                ← android.useAndroidX=true
├── gradle/
│   ├── libs.versions.toml           ← 所有依賴版本集中管理
│   └── wrapper/
│       └── gradle-wrapper.properties← Gradle 8.9
├── app/
│   ├── build.gradle.kts             ← compileSdk=34, aidl=true
│   └── src/main/
│       ├── AndroidManifest.xml      ← INTERNET + queries Meshtastic
│       ├── aidl/com/geeksville/mesh/
│       │   ├── IMeshService.aidl    ← Meshtastic 服務介面
│       │   └── DataPacket.aidl
│       ├── java/com/geeksville/mesh/
│       │   └── DataPacket.kt        ← 對應 Meshtastic Parcelable
│       └── java/com/meshtastic/bbs/
│           ├── MainActivity.kt
│           ├── data/
│           │   ├── Models.kt        ← 資料模型 + JSON 解析
│           │   └── MeshtasticRepository.kt ← BBS:REQ/RES 協議
│           ├── viewmodel/
│           │   └── BbsViewModel.kt
│           └── ui/
│               ├── theme/           ← Material3 深色主題
│               └── screens/         ← 6 個畫面
```

---

## Meshtastic Plugin 連線說明

APP 透過 Android IPC（AIDL）綁定 Meshtastic APP 的 `IMeshService`，不需要 WiFi。

```
MeshBBS APP
    │  Service Binding (AIDL)
    ▼
Meshtastic APP（com.geeksville.mesh）
    │  藍芽 BLE
    ▼
Meshtastic LoRa 裝置
    │  LoRa 無線
    ▼
BBS 伺服器端 Meshtastic 裝置 → Raspberry Pi（meshtastic_bbs_server.py）
```

通訊協議：`BBS:REQ` / `BBS:RES`（詳見 `MESH_TRANSMISSION_NOTE.md`）

> **注意**：`DataPacket.kt` 的欄位順序必須與手機上安裝的 Meshtastic APP 版本完全一致，
> 否則 Parcelable 反序列化會失敗。如遇問題請對照
> https://github.com/meshtastic/Meshtastic-Android 的 `DataPacket.kt` 調整。
