# Build Info

Generated from the current workspace on 2026-05-26.

## 1. App build identity

Source:
- `android/MeshtasticBBS/app/build.gradle.kts`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

| Item | Current value |
|---|---|
| `versionName` | `b0604s` |
| `versionCode` | `1` |
| `MeshtasticRepository.BUILD` | `b0604s` |
| namespace / base applicationId | `com.meshtastic.bbs` |

## 2. Flavors

| Flavor | applicationId | Label | ABI filters |
|---|---|---|---|
| `client` | `com.meshtastic.bbs` | `MeshBBS` | `arm64-v8a` |
| `server` | `com.meshtastic.bbs.server` | `MeshServer` | `arm64-v8a`, `x86_64` |

## 3. Build types

| Build type | Current repo support |
|---|---|
| `debug` | supported |
| `release` | supported |

Latest explicitly built in this workspace:
- `client release`: `MeshBBS-b0604s.apk`
- `server release`: `MeshBBS-server-b0604s.apk`

## 4. Meshtastic integration info

Source:
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`

| Item | Current value |
|---|---|
| Meshtastic package expected by app | `com.geeksville.mesh` |
| Service action | `com.geeksville.mesh.Service` |
| Meshtastic custom app port / `dataType` | `257` |
| Receiver class subscribed by app | `com.meshtastic.bbs.data.MeshPacketReceiver` |

## 5. Meshtastic Android App version

Current status:

- **Not pinned in the repo**
- **Not recorded in build files**
- The code only expects package/service compatibility with `com.geeksville.mesh`

So for external analysis:

```text
Meshtastic Android App version = unknown from repo snapshot
```

## 6. Node firmware / radio runtime

Current status:

- **Not recorded in the repo**
- **Not captured in current debug bundle**

So these are currently unknown:
- node firmware version
- hardware model
- radio preset
- region
- channel bandwidth / spreading factor / coding rate

## 7. LoRa / MQTT state

### From current code

| Item | Current state |
|---|---|
| Client hopLimit | fixed `4` |
| Server hopLimit | runtime-configurable, default `4` |
| Default transport profile | `LORA_DIRECT` |
| MQTT-specific profile exists | yes, `MQTT_SAFE` |
| Automatic MQTT detection | no |

### From the current user-reported reproduction context

The latest reported failure scenario was:

```text
MQTT closed, LoRa direct only
```

That comes from the current debugging context, not from a hardcoded repo-wide switch.

## 8. Python / Android build stack

| Item | Current value |
|---|---|
| minSdk | `26` |
| targetSdk | `34` |
| compileSdk | `34` |
| Java target | `17` |
| Kotlin JVM target | `17` |
| Chaquopy Python version | `3.13` |

## 9. APK archive paths

Current output/archive locations:

- `android/MeshtasticBBS/app/build/outputs/apk/client/release/`
- `android/MeshtasticBBS/app/build/outputs/apk/server/release/`
- `android/MeshtasticBBS/apk_archive/`
