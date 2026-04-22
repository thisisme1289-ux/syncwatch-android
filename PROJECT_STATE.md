# PROJECT_STATE.md — SyncWatch Android

> Updated after every code change. Read this before touching any code.
> Newest entry at the top.

---

## Quick status

| Area | Status |
|---|---|
| Project scaffolding | ✅ Done |
| Server connection (Socket.IO) | ✅ Done |
| Home screen (create / join) | ✅ Done |
| Local sync mode | ✅ Done (PlayerController + socket events) |
| Upload mode | ✅ Done (file picker → multipart → media_ready) |
| Screen share mode | ✅ Done (ScreenShareService + Host + Guest) |
| Chat UI | ✅ Done |
| User list | ✅ Done |
| Host settings panel | ⚠️ Partial — ViewModel supports it, no dedicated settings dialog yet |
| Data meter | ✅ Done (HTTP via OkHttp interceptor, WebRTC manual) |

---

## Server details

The Android app connects to the existing SyncWatch server.
Server repo: syncwatch-server (separate repo, no changes needed to start)
Server socket contract: defined in CLAUDE.md — do not deviate from it.

API base URL is read from `local.properties`:
```
SERVER_URL=http://your-server-ip:3000
```

---

## Dependency versions

| Library | Version | Notes |
|---|---|---|
| Kotlin | 1.9.23 | |
| AGP | 8.3.2 | |
| Socket.IO client | 2.1.0 | io.socket:socket.io-client |
| ExoPlayer (media3) | 1.3.1 | androidx.media3 |
| WebRTC | 1.0.32006 | org.webrtc:google-webrtc |
| OkHttp | 4.12.0 | |
| Retrofit | 2.9.0 | |
| Gson | 2.10.1 | used by Retrofit converter |
| Coroutines | 1.7.3 | |

---

## Change log

### [Session 1] — Full initial build

**Files created:**

```
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts                  — deps, BuildConfig SERVER_URL
local.properties                      — template, fill in SERVER_URL
app/proguard-rules.pro

app/src/main/AndroidManifest.xml      — all permissions, ScreenShareService declared
app/src/main/java/com/syncwatch/
  App.kt                              — Application class, notification channels
  MainActivity.kt                     — single-activity host, navigateHome()
  model/
    Room.kt                           — all REST + socket payload data classes
    PlaybackState.kt                  — PlaybackState, PlaybackEvent
  network/
    ApiService.kt                     — Retrofit: createRoom, getRoom, uploadVideo
    ApiClient.kt                      — OkHttp + Retrofit singleton
    SocketManager.kt                  — Socket.IO singleton, all emit/on, SharedFlows
  ui/home/
    HomeViewModel.kt                  — create/join logic, HomeUiState sealed class
    HomeFragment.kt                   — tab UI, observes ViewModel
  ui/watch/
    WatchViewModel.kt                 — room state, user list, chat, playback events
    WatchFragment.kt                  — player, sidebar, upload, screen share UI
    PlayerController.kt               — ExoPlayer wrapper, sync suppression logic
    ChatAdapter.kt                    — ListAdapter for chat messages
    UserListAdapter.kt                — ListAdapter for user list + host transfer
  screenshare/
    ScreenShareService.kt             — foreground service (required Android 10+)
    ScreenShareHost.kt                — MediaProjection → WebRTC offer/ICE sender
    ScreenShareGuest.kt               — WebRTC answer/ICE receiver → SurfaceViewRenderer
  util/
    DataMeter.kt                      — HTTP bytes (OkHttp interceptor) + RTC bytes
    Extensions.kt                     — show/hide, collectFlow, toTimestamp, toChatTime

app/src/main/res/
  layout/
    activity_main.xml
    fragment_home.xml
    fragment_watch.xml
    item_chat_message.xml
    item_user.xml
  values/
    colors.xml
    strings.xml
    themes.xml
  values-night/
    colors.xml
  drawable/
    ic_screen_share.xml
```

**Decisions made this session:**

- `selector_tab_text` color selector placed in `themes.xml` (not a separate file) — simpler
- `WatchFragment` passes its own `CoroutineScope` to `PlayerController` (not ViewModel scope) to ensure ExoPlayer is released with the view, not the ViewModel
- `RoomJoinedPayload` passed as a `Serializable` Bundle arg to `WatchFragment` — keeps factory pattern clean without Parcelable boilerplate
- `ScreenShareHost` uses 720p @ 15fps to keep data usage low; configurable later
- ICE candidate `sdpMid` / `sdpMLineIndex` are stubbed ("0" / 0) — sufficient for one-to-one with no bundling; revisit if multi-guest is added

---

## Known issues / next steps

| Issue | Priority |
|---|---|
| Host settings dialog not implemented (ViewModel supports it) | Medium |
| No permission rationale dialogs (READ_MEDIA_VIDEO, POST_NOTIFICATIONS) | Medium |
| `mipmap/ic_launcher` placeholder not provided — Android Studio generates it | Low |
| No TURN server — WebRTC fails behind symmetric NAT | Known limitation, documented in CLAUDE.md |
| `WatchFragment.viewModelScope` helper at bottom of file is a workaround — clean up | Low |
| `RoomJoinedPayload` needs `@Serializable` or Parcelize if Bundle size becomes an issue | Low |

---

## Architecture decisions

| Decision | Choice | Reason |
|---|---|---|
| Single Activity | Yes | Simpler nav, no back-stack complexity for 2-screen app |
| State management | ViewModel + StateFlow | Standard Android, easy to test |
| Socket threading | IO dispatcher in coroutine | Socket callbacks arrive on background thread, marshal to Main for UI |
| Video player | ExoPlayer (media3) | Handles local files, HTTP streaming, range requests, all needed codecs |
| WebRTC source | org.webrtc:google-webrtc | Official Google build, ScreenCapturerAndroid included |
| DI | Manual singletons | Hilt adds complexity not justified for 2-screen app yet |
| Socket transport | WebSocket only | `transports = ["websocket"]` set in SocketManager — no polling fallback |
| Sensitive data | Memory only | hostToken never written to SharedPreferences |
