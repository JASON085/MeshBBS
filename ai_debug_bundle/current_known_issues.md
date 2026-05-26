# Current Known Issues

Generated from current code state plus the latest reported symptoms.

## 1. LoRa direct `READ` can still fail

Status:
- still reported as unresolved at runtime

Observed symptom pattern:
- `LOGIN` usually works
- board entry and post list may work
- `READ` often fails or stalls

Why it is still open:
- there is no full preserved runtime session in the repo proving exactly which packet/control message is missing during the failed `READ`

## 2. MQTT relay chunk loss is improved but not proven solved

Status:
- partially mitigated

What changed:
- MQTT-safe response profile
- slower response pacing
- selective resend via `BBS:MISS`
- batch metadata + SHA-256

What is still unknown:
- whether directed/private packet handling inside MQTT relay still loses specific control packets or boundary chunks

## 3. Reliable transport exists, but only at batch level

Status:
- partially solved, not end-to-end perfect

Implemented now:
- `BBS:META`
- `BBS:MISS`
- `BBS:ACK`
- full compressed payload SHA-256
- selective resend from cache

Not implemented:
- per-chunk ACK
- congestion control
- adaptive pacing
- explicit packet timestamp correlation

## 4. Selective resend exists, but depends on control-plane delivery

Status:
- important limitation

Current weak points:
- client cannot finalize without `BBS:META`
- if `BBS:META` is repeatedly lost, chunk data alone is insufficient
- if `BBS:MISS` itself is lost, recovery can still fail
- there is no extra ACK for metadata/control messages themselves

## 5. Historical login regression existed around `BBS:META`

Status:
- fixed in current code, but relevant for debugging history

Issue:
- client previously failed to strip `MBBS1` prefix from control packets
- `BBS:META` was therefore not recognized
- login could hang because the response waited forever for metadata

Current expectation:
- fixed in `MeshtasticRepository.decodeControlText()`
- should still be regression-tested in real device runs

## 6. UI retry flooding is no longer the main current problem

Status:
- historical issue reduced

Current code:
- `openPost()` does one `READ`
- `schedulePostListRetry()` is a no-op
- there is no active whole-READ retry scheduler

Implication:
- if `READ` still fails now, the transport layer is a higher-probability cause than UI reflooding

## 7. Missing runtime evidence is itself a major issue

Status:
- still blocking root-cause analysis

What is missing:
- full server log for failed LoRa direct `READ`
- full client log for the same session
- exact `seq`
- exact `totalChunks`
- actual missing indexes
- metadata arrival or loss evidence
- packet timestamps

## 8. No automatic MQTT/LoRa transport detection

Status:
- design limitation

Current behavior:
- server transport profile is operator-selected
- code does not automatically switch based on actual path characteristics

Risk:
- wrong profile can be used accidentally
- LoRa direct can become over-conservative
- MQTT relay can become under-protected

## 9. Response size can still be fundamentally too large for weak links

Status:
- still possible

Even with better chunking:
- `READ` can still produce many chunks
- more chunks mean more opportunities for RF loss
- selective resend helps after loss, but does not eliminate total airtime pressure

## 10. Server/client logs are not persisted as structured session artifacts

Status:
- observability gap

Current state:
- logs exist in UI-visible text streams
- they are useful for manual copy/paste
- they are not automatically saved as correlated structured sessions with timestamps
