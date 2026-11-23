# Drishya — Made in India Video Player 🇮🇳

Kotlin + AndroidX Media3 (ExoPlayer) tho build chesina offline video player.

## Features
- Phone lo unna videos anni **folder-wise** ga chupistundi, thumbnails, duration, search tho
- **Resume**: video aapina chota nunche malli start avthundi (list lo saffron progress bar kanipistundi)
- **Gestures**: left/right double-tap → 10s skip · center double-tap → play/pause ·
  left side swipe ↕ → brightness · right side swipe ↕ → volume · horizontal swipe → seek
- **Subtitles**: MKV/MP4 lo unna subtitles + bayati .srt / .vtt / .ass files load cheyyochu
- **Audio tracks & speed** (0.25×–2×): player settings ⚙ lo
- **AC3 / E-AC3 / DTS / TrueHD audio** FFmpeg decoder tho (MKV movies lo common)
- **Picture-in-Picture**, auto-rotate (video shape batti), Fit / Zoom / Stretch
- Files / WhatsApp / Chrome nunchi **"Open with Drishya"**
- Playlist: folder lo next video auto-play avthundi

Android 8.0 (API 26) and above.

## APK ela teesukovali

### Option A — GitHub (phone nunchi kuda cheyyochu, computer avasaram ledu)
1. github.com lo kotha repository create cheyyandi (e.g. `drishya`).
2. **Add file → Upload files** nokki, ee zip extract chesina files/folders anni upload cheyyandi
   (`.github` folder kuda upload avvali). Commit cheyyandi.
3. Repo lo **Actions** tab ki vellandi → "Build APK" run avthundi (~5–8 min).
4. Run aipoyaka, kinda **Artifacts** lo `Drishya-APK` download cheyyandi → zip extract chesi
   `app-release.apk` install cheyyandi (phone lo "Install unknown apps" allow cheyyali).

> `.github` lanti hidden folders phone browser lo upload avvakapothe: repo lo
> **Add file → Create new file** nokki, peru `.github/workflows/build-apk.yml` ani ichhi,
> ee project lo unna aa file content paste cheyyandi.

### Option B — Android Studio (computer)
1. Android Studio (latest) open → **Open** → `Drishya` folder select cheyyandi.
2. Gradle sync aipoyaka phone ni USB tho connect chesi ▶ **Run** nokkandi.
3. APK kavali ante: **Build → Build APK(s)**.

## Code map
| File | Pani |
|---|---|
| `MainActivity.kt` | Permissions, folders/videos list, search |
| `PlayerActivity.kt` | ExoPlayer, gestures, resume, subtitles, PiP, rotation |
| `Model.kt` | MediaStore nunchi videos load, time/size formatting |
| `Adapters.kt` | Folder & video list rows (Glide thumbnails) |

## License note
FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`) GPL-3.0. Ee app ni public ga
distribute cheste source code kuda GPL-3.0 kinda open cheyyali. Personal use ki em problem ledu.
Vaddu anukunte `app/build.gradle.kts` lo aa line teeseyyandi — AC3/DTS audio matram play avvadu.
