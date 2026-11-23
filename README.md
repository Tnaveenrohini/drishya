# Flikk — Made in India Video Player 🇮🇳

Kotlin + AndroidX Media3 (ExoPlayer) tho build chesina offline video player. Cinema Black theme.

## Download
Latest APK: **Releases** → `Flikk.apk`
(https://github.com/Tnaveenrohini/drishya/releases/latest)

## Features (v1.1)
- **Cinema Black** home: All / Folders / Playlists / Recent tabs, 16:9 thumbnails, quality badges, "continue watching" bar
- **Playlists & Favourites**: edaina video ni long-press → Favourites / playlist ki add
- **Background play**: 🎧 button — app nunchi bayataki vellina, screen off chesina vinipistundi (notification controls tho)
- **Sleep timer**: 15 / 30 / 45 / 60 min leda "ee video ayyaka"
- **Gesture lock**: 🔒 — pillalu touch chesina aagadu; unlock ki press & hold
- **Telugu / English** app language (⋮ → Language)
- Resume, double-tap ±10s, swipe brightness / volume / seek, subtitles (.srt/.vtt/.ass), speed, audio tracks,
  AC3/DTS audio (FFmpeg), picture-in-picture, auto-rotate, Fit / Zoom / Stretch, "Open with Flikk"

Android 8.0+ (API 26).

## Build
GitHub Actions prathi push ki APK build chesi Release lo pedutundi. Android Studio lo open chesi Run kuda cheyyochu.

`app/flikk.keystore` fixed signing key — updates paata app meedha install avthayi.
Play Store ki vellettappudu kotha private key create chesi, adi repo lo pettakandi.

## License note
FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`) GPL-3.0. Public ga distribute cheste source kuda GPL-3.0 kinda open cheyyali.
