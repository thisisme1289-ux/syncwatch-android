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
