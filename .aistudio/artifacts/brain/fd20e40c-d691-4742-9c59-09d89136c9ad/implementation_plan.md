# Clawd Bridge — ТЗ v1.3: Fix Cleartext HTTP & Monotonic Playback Calculation

Plan to resolve Bug A (Cleartext HTTP restrictions on Android API 34+ for loopback client) and Bug B (negative timestamp calculation caused by mixing `SystemClock.elapsedRealtime()` and epoch milliseconds), plus expanding backup file capacity to 512 bytes.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> All details are strictly specified in ТЗ v1.3:
- **Security Config**: Dedicated `network_security_config.xml` permitting cleartext HTTP *strictly* for `127.0.0.1` and `localhost` (no wildcard cleartext).
- **Time Clock Discipline**: Use strictly `SystemClock.elapsedRealtime()` for delta and position calculation. Epoch `System.currentTimeMillis()` is reserved exclusively for the JSON `timestamp` field.
- **Position Bounds & Unknown Handling**: Clamp position between `0` and `durationMs` (`coerceIn`), and if `state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN` (-1), report `position_ms: 0` with `"position_known": false` (otherwise `true`).
- **Backup File Limit**: Increase limit from 200 bytes to 512 bytes in `BackupFileWriter` and `NowPlayingData.toBackupJsonString()`.

---

## 1. Bug Analysis & Root Causes

### Bug A: Cleartext HTTP traffic to 127.0.0.1 not permitted
- **Cause**: Android 9+ (API 28+) disables unencrypted HTTP by default for high-level network stacks (`HttpURLConnection`). While the native `ServerSocket` loopback server receives external browser requests normally, the in-app diagnostic test button failed with `IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted`.
- **Solution**:
  1. Add `app/src/main/res/xml/network_security_config.xml` scoped exclusively to `127.0.0.1` and `localhost`.
  2. Declare `android:networkSecurityConfig="@xml/network_security_config"` in `<application>` within `AndroidManifest.xml`.
  3. Update UI to display the exact HTTP response code (`responseCode`) dynamically, display "Ошибка: <сообщение>" on connection failure without hardcoded "200", and enforce 1500 ms timeouts on background IO.

### Bug B: Negative Position (-1790521365289 ms)
- **Cause**: In `MediaBridgeStateHolder.getCurrentSnapshot()`, `sim.timestamp` (which held epoch milliseconds ~1.73 trillion ms from `System.currentTimeMillis()`) was subtracted from `SystemClock.elapsedRealtime()` (~10 million ms since boot), producing a negative number of ~-1.73 trillion.
- **Solution**:
  1. Store monotonic reference time `lastElapsedRealtime = SystemClock.elapsedRealtime()` in simulator and state holder.
  2. Guard `getCurrentPositionMs()`:
     ```kotlin
     if (state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
         return 0L to false
     }
     val timeDelta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
     val calculated = state.position + (timeDelta * state.playbackSpeed).toLong()
     val clamped = if (duration > 0) calculated.coerceIn(0L, duration) else calculated.coerceAtLeast(0L)
     return clamped to true
     ```
  3. Include `"position_known": true|false` in the JSON response model.
  4. Ensure `formatTimecode()` coerces input to `coerceAtLeast(0L)`.
  5. Raise backup file limit in `BackupFileWriter` from 200 to 512 bytes.

---

## 2. Technical Architecture & Modifications

```
┌─────────────────────────────────────────────────────────────┐
│                       network_security_config.xml           │
│  <domain-config cleartextTrafficPermitted="true">           │
│    <domain>127.0.0.1</domain>                               │
│    <domain>localhost</domain>                               │
│  </domain-config>                                           │
└──────────────────────────────┬──────────────────────────────┘
                               │ Applied via AndroidManifest.xml
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                      MainActivity.kt                        │
│  - Background test request (1500ms timeouts)                │
│  - Displays actual response code (e.g. 200, 500, or error)  │
│  - Timecode formatted safely with coerceAtLeast(0L)        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                  MediaBridgeStateHolder                     │
│  - Uses elapsedRealtime() throughout calculation            │
│  - Handles PLAYBACK_POSITION_UNKNOWN -> position_known=false│
│  - Clamps position strictly in [0, durationMs]              │
└──────────────────────────────┬──────────────────────────────┘
                               │ Produces
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                      NowPlayingData                         │
│  - positionKnown: Boolean = true                            │
│  - JSON output includes "position_known": true|false        │
│  - toBackupJsonString maxBytes increased to 512 bytes       │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Verification & Acceptance Criteria

1. **Compilation & Unit Tests**:
   - `compile_applet` succeeds without errors.
   - Local Robolectric unit tests verify:
     - `position_known: true` on normal playback.
     - `position_known: false` and `position_ms: 0` when `PLAYBACK_POSITION_UNKNOWN`.
     - JSON payload fits within 512 bytes limit.
     - Position never drops below 0 and never exceeds duration.
2. **In-App Cleartext Verification**:
   - Clicking «Протестировать GET /now-playing» succeeds without cleartext exception and displays actual response code (`HTTP 200 OK`) and JSON.
   - When stopped, displays error description without false "200".
3. **Re-export APK**:
   - Run `gradle :app:assembleDebug` and refresh root `ClawdBridge.apk`.
