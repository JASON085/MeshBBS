# BBS:RES Meshtastic 傳輸方式說明

## 背景

經過測試，`_send_mesh_resp` 的傳輸方式對 Client 是否能收到回應至關重要。
錯誤的傳輸設定會導致 Client 看板列表或文章讀取逾時失敗。

---

## 正確設定（三條不可更改的規則）

### 1. 雙路傳輸（廣播 + DM）

每個 chunk 送兩次，兩次用不同方式：

```python
if attempt == 0:
    iface.sendText(msg, wantAck=False)                          # 廣播
else:
    iface.sendText(msg, destinationId=dest_id, wantAck=False)  # DM 私訊
```

- **attempt=0 廣播**：覆蓋範圍廣，相容所有 Meshtastic 韌體版本
- **attempt=1 DM**：直接送達目標裝置
- Client 任一路徑先收到即可完成組合，加快讀取速度

### 2. 保守 chunk 大小

```python
HEADER_OVERHEAD = 32
MAX_CHUNK_BYTES = 200 - HEADER_OVERHEAD  # = 168 bytes
```

- 不可使用 205 bytes（原本 237-32）
- 227 bytes 的封包加上協議頭，某些韌體會截斷或過濾
- 168 bytes 在實測中可安全通過所有版本韌體

### 3. 傳輸間隔

```python
if attempt == 0:
    time.sleep(1.0)   # 廣播與 DM 之間
if i < total - 1:
    time.sleep(1.0)   # 不同 chunk 之間
```

---

## 訊息格式

```
BBS:RES:<dest_id>:<seq>:<idx>:<total>:<data>
```

廣播模式下，訊息內已含 `dest_id`，Client 靠此欄位過濾非自己的廣播封包。

### 4. Base64 編碼（不傳明碼）

廣播頻道上不傳可讀的 JSON，payload 統一做 base64 編碼：

**Server 端（`_send_mesh_resp`）：**
```python
import base64
b64_data = base64.b64encode(data.encode("utf-8")).decode("ascii")
chunks   = [b64_data[i:i + MAX_CHUNK] for i in range(0, len(b64_data), MAX_CHUNK)]
```

- Base64 是純 ASCII，不需 UTF-8 邊界判斷，任意位置切割皆安全
- `MAX_CHUNK = 200 - 32 = 168` ASCII chars → 加頭後 200 bytes ≤ 237 硬體極限
- 每個 chunk 約 126 bytes 原始資料 × 4/3 ≈ 168 bytes base64

**Client 端（`_handle_res`）：**
```python
import base64
decoded = base64.b64decode(full.encode("ascii")).decode("utf-8")
obj = json.loads(decoded)
# fallback：若解碼失敗，嘗試直接 json.loads(full)（相容舊格式）
```

### 5. zlib 壓縮（加速傳輸）

在 base64 編碼之前先做 zlib 壓縮，大幅減少 chunk 數量：

**Server 端（`_send_mesh_resp`）：**
```python
import zlib
compressed = zlib.compress(data.encode("utf-8"), level=6)
b64_data   = base64.b64encode(compressed).decode("ascii")
chunks     = [b64_data[i:i + MAX_CHUNK] for i in range(0, len(b64_data), MAX_CHUNK)]
```

**Client 端（`_handle_res`）：**
```python
import zlib
raw = base64.b64decode(full.encode("ascii"))
try:
    raw = zlib.decompress(raw)   # zlib 壓縮格式
except zlib.error:
    pass                         # 非壓縮，直接用原始 bytes（相容舊格式）
obj = json.loads(raw.decode("utf-8"))
```

**壓縮效益（實測）：**

| 資料 | 原始 | zlib 後 | base64 後 | chunk 數 |
|------|------|---------|-----------|---------|
| LIST（~260 bytes） | 260B | ~130B | ~173B | 1 chunk（原 3） |
| READ（~600 bytes） | 600B | ~250B | ~333B | 2 chunks（原 4） |

每少一個 chunk 省約 2 秒傳輸等待時間。

---

## Client 端行為

- `entry["chunks"][idx] = data` 是冪等操作，同一 idx 寫兩次相同資料無害
- `_completed_seqs` 確保組合完成後，後續到達的重複封包直接丟棄

---

## 為什麼純 DM 不行？

測試發現，純 DM 私訊在部分 Meshtastic 韌體版本中，對超過約 200 bytes 的訊息有過濾或截斷問題，導致 Client 始終收不到 LIST / READ 回應。LOGIN（105 bytes，只有 1 chunk）因為夠小，用 DM 也能成功，所以不容易察覺這個問題。
