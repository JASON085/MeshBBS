# Build Info

生成時間：2026-05-26  
用途：提供另一位 AI 工程助手確認目前診斷所對應的版本、flavor 與已知執行環境資訊。

## 1. App build info

來源：
- `android/MeshtasticBBS/app/build.gradle.kts`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

| 項目 | 值 | 來源 |
|---|---|---|
| `versionName` | `b0604p` | `app/build.gradle.kts` |
| `versionCode` | `1` | `app/build.gradle.kts` |
| `MeshtasticRepository.BUILD` | `b0604p` | `MeshtasticRepository.kt` |
| package namespace | `com.meshtastic.bbs` | `app/build.gradle.kts` |
| compileSdk | `34` | `app/build.gradle.kts` |
| minSdk | `26` | `app/build.gradle.kts` |
| targetSdk | `34` | `app/build.gradle.kts` |

## 2. Flavor info

來源：`android/MeshtasticBBS/app/build.gradle.kts`

| Flavor | `SERVER_BUILD` | app label | ABI |
|---|---|---|---|
| `client` | `false` | `MeshBBS` | `arm64-v8a` |
| `server` | `true` | `MeshServer` | `arm64-v8a`, `x86_64` |

## 3. Build type info

最近一次已成功建置：

| Flavor | Build type | APK 名稱 |
|---|---|---|
| client | debug | `MeshBBS-b0604p.apk` |
| server | debug | `MeshBBS-server-b0604p.apk` |

說明：
- 這兩個 APK 是 2026-05-26 這個工作階段剛打好的 debug 版。

## 4. Meshtastic Android App version

目前狀態：

- **工作區內沒有保存實機安裝版本號。**
- 程式碼 mirror 註解顯示目前本地 `DataPacket` 對應的是：

```text
Wire-compatible local mirror of Meshtastic Android 2.7.x DataPacket.
```

這表示：
- **可推定程式碼是以 Meshtastic Android 2.7.x 的 AIDL / Parcelable 結構為對照**
- **不能等同於實機目前安裝版本**

## 5. Node firmware version

目前狀態：

- **未知**
- 工作區沒有保存目前 LoRa node 的 firmware version
- 需要由實機 Meshtastic App / node 管理畫面補充

## 6. LoRa runtime settings

### 6.1 目前可由程式確認

| 項目 | 值 | 來源 |
|---|---|---|
| client `PACKET_HOP_LIMIT` | `4` | `MeshtasticRepository.kt` |
| server `hopLimit` 預設 | `4` | `ServerHostState.hopLimit` |
| server `hopLimit` UI 範圍 | `1..7` | `ServerHostStore.setHopLimit()` |
| server 預設 transportProfile | `LORA_DIRECT` | `ServerHostState.transportProfile` |
| `LORA_DIRECT` | `200 / 250ms` | `TransportProfile` |
| `MQTT_SAFE` | `170 / 600ms` | `TransportProfile` |
| `DEBUG_ULTRA_SAFE` | `120 / 900ms` | `TransportProfile` |

### 6.2 目前無法由程式碼確認

以下需要由實機 Meshtastic 設定頁補充：

| 項目 | 目前狀態 |
|---|---|
| LoRa region | 未知 |
| LoRa preset | 未知 |
| channel 設定 | 未知 |
| node power / modem preset | 未知 |

## 7. MQTT status

本次診斷前提：

| 項目 | 值 |
|---|---|
| MQTT | 關閉 |
| 來源 | 使用者當前描述 |

說明：
- 這不是從程式內自動偵測到的狀態，而是本次問題描述明確指定。

## 8. 建議補充給下一位 AI 助手的實機資訊

以下資訊目前仍缺：

1. Meshtastic Android App version
2. node firmware version
3. LoRa region
4. LoRa preset / modem preset
5. 實測當下 server UI 選到的 `transportProfile`
6. 當下 server UI 的 `hopLimit`
