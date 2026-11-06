# Clawd Bridge — Android Media Sync Service

A lightweight, zero-overhead background media bridge for Android that captures live playback metadata and sub-millisecond playback positions across all media players (SoundCloud, Spotify, YouTube Music, VK, Yandex Music, etc.) and serves it locally over HTTP (`http://127.0.0.1:44444/now-playing`) for desktop widgets and Telegram pet plugins.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following parameters were confirmed during interactive planning:

- **Confirmed Decision 1 (Network Interface)**: Strictly localhost only (`127.0.0.1:44444`) for zero network attack surface and maximum local performance.
- **Confirmed Decision 2 (Diagnostic Tools)**: Built-in simulated test player included with realistic metadata and playback controls for instant verification in the Android emulator without needing 3rd-party accounts.
- **Confirmed Decision 3 (Disk Backup Target)**: Throttled atomic backup written to public `Download/ClaudePet/sync.json` via scoped storage / MediaStore API, ensuring safety across Android 8 through Android 15.

---

## 1. Overview & Core Concept

- **What It Does**: Runs a silent background service listening to active Android media sessions via `NotificationListenerService` and `MediaSessionManager`. Calculates exact sub-millisecond track progress dynamically. Exposes a local, zero-disk-IO HTTP endpoint at `127.0.0.1:44444/now-playing` returning JSON data with CORS headers enabled.
- **Target Audience**: Developers, power users, and Telegram bot/plugin integrations needing microsecond-accurate music synchronization (lyrics, animated avatars, pet widgets).
- **Key Value**: 0.0% idle CPU footprint, sub-millisecond time accuracy, no third-party HTTP dependencies (pure native Android `ServerSocket`), and resilience against vendor battery optimization kills.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Onboarding & Permission Authorization**:
   - Status badge shows red: *"Notification Access Required"*.
   - User taps *"Enable Notification Access"*, opening system Settings directly to notification listener configuration.
   - Returning to the app updates the badge to glowing green: *"Bridge Active & Listening"*.
2. **Battery Optimization Whitelist**:
   - User taps *"Disable Battery Optimization"*, firing `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for MIUI / HyperOS / OneUI longevity.
3. **Live Monitor & Diagnostics**:
   - Live telemetry card updates at 4 Hz (250 ms) showing player package icon, artist, title, dynamic millisecond timecode (`01:23.45 / 03:45.00`), and real-time playback state.
   - Live HTTP request counter and last request timestamp.
   - One-tap button to test the endpoint directly and preview the raw JSON response in a bottom sheet / expandable panel.
4. **Built-in Test Player**:
   - Quick toggle to launch an internal `MediaSession` simulation ("Juice WRLD — Lean Wit Me") to verify the endpoint immediately even without Spotify or SoundCloud installed on the emulator.

### Visual Identity & Theme
- **Aesthetic Direction**: High-tech audio engineer workstation / cybernetic console. Deep charcoal and obsidian tones (`#121316`) paired with neon emerald (`#00E676`) for active states, electric cyan (`#00E5FF`) for live timecodes, and subtle amber warning tones.
- **Typography & Hierarchy**: Clean monospace styling for timecodes, URLs, and JSON fields; bold M3 typography for headers and status badges.
- **Component Polish**: Surface elevation cards with subtle 1dp glowing borders, spring animations for playback state transitions, and accessible touch targets (≥ 48dp).

---

## 3. Key Product Decisions & Trade-Offs

### 1. In-Memory HTTP Server (`HttpServerDaemon`)
- **Approach**: Pure native Java `ServerSocket` bound strictly to `InetAddress.getByName("127.0.0.1")` on port `44444`. Uses buffered socket I/O to handle incoming `GET /now-playing` requests in RAM.
- **Why**: Zero external library dependencies, near-instantaneous response time (< 1ms), and negligible RAM footprint (~2 MB).
- **Idle Behavior**: Thread blocks inside `serverSocket.accept()` when no requests arrive, consuming 0% CPU.

### 2. Sub-Millisecond Time Precision Calculation
- **Approach**: Dynamic real-time extrapolation:
  $$\text{positionMs} = \text{state.position} + (\text{SystemClock.elapsedRealtime}() - \text{state.lastPositionUpdateTime}) \times \text{state.playbackSpeed}$$
- **Why**: Standard Android playback callbacks only fire upon play/pause/seek events. Extrapolating via monotonic hardware clock guarantees sub-millisecond precision continuously.

### 3. Dual Storage Architecture (RAM-to-RAM + Atomic Throttled File)
- **Primary**: RAM-to-RAM HTTP response on every request.
- **Backup**: Writes atomic payload to `/sdcard/Download/ClaudePet/sync.json` (`sync.tmp` $\to$ atomic rename) throttled to once every 2000 ms to prevent flash memory wear.

---

## 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────┐
│                       ANDROID SYSTEM                        │
│  Spotify / SoundCloud / YouTube Music / Built-in Test Player│
└──────────────────────────────┬──────────────────────────────┘
                               │ Active MediaSession callbacks
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 MediaListenerService                        │
│  - Extends NotificationListenerService                      │
│  - Registers MediaController.Callback                       │
│  - Extrapolates currentPositionMs in real time              │
│  - Throttled atomic backup to /Download/ClaudePet/sync.json │
└──────────────────────┬───────────────────────┬──────────────┘
                       │ Updates               │ Reads State
                       ▼                       ▼
┌───────────────────────────────┐    ┌────────────────────────┐
│     NowPlayingRepository      │    │    HttpServerDaemon    │
│  (StateFlow<NowPlayingState>) │    │  - Port 44444          │
└──────────────┬────────────────┘    │  - 127.0.0.1           │
               │ Collects            │  - GET /now-playing    │
               ▼                     └────────────▲───────────┘
┌───────────────────────────────┐                 │
│         MainActivity          │                 │ Local HTTP
│  - M3 Live Monitor UI         │                 │ Request
│  - Permission Checkers        │                 │
│  - Built-in Test Simulator    │         Telegram Pet Plugin /
└───────────────────────────────┘         Browser / Desktop App
```

### Response Schema Specification
- **When playing**:
  ```json
  {
    "has_media": true,
    "player": "com.soundcloud.android",
    "artist": "Juice WRLD",
    "title": "Lean Wit Me",
    "position_ms": 24350,
    "duration_ms": 175000,
    "position_sec": 24.35,
    "is_playing": true,
    "timestamp": 1730868200123
  }
  ```
- **When idle / paused**:
  ```json
  {
    "has_media": false,
    "is_playing": false
  }
  ```

---

## 5. Verification Plan

1. **Compilation Check**: Run `compile_applet` to ensure pure native implementation builds cleanly without missing SDK references.
2. **Local Logic Tests**: Robolectric test suite validating:
   - JSON serialization format matches exact schema.
   - Dynamic sub-millisecond calculation with speed multipliers.
   - Throttled disk writer respects minimum 2-second interval and atomic renaming.
3. **Interactive Emulator Verification**:
   - Verify UI notification permission status indicator and intent launching.
   - Launch built-in test player and verify live monitor updating at 4 Hz with millisecond precision.
