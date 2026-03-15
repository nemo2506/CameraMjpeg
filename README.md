# CameraMjpeg (v1)

## Francais (FR)

Application Android de streaming camera en MJPEG avec serveur HTTP integre.

### Fonctionnalites

- Streaming MJPEG depuis le telephone Android via `GET /stream.mjpeg`.
- Viewer web integre sur `GET /`, `GET /viewer` et `GET /monitor`.
- Interface admin Compose: demarrage/arret, port, qualite JPEG, camera avant/arriere, mode veille.
- Port configurable et applique en direct.
- Detection reseau: Wi-Fi, SSID, IP locale, URL de flux, URL viewer, URL API batterie.
- Monitoring web avec cadre bas (snapshot, FPS, resolution, batterie).
- Endpoint batterie JSON temps reel `GET /api/battery` (champ `charging: true/false`).
- Endpoint statut `GET /api/status` (clients, fps, uptime, tailles/compteurs).
- API de gestion des images (`save`, `list`, `delete`, `clear`).
- Service foreground Android `MjpegStreamingService` avec WakeLock CPU.

### SDK et compatibilite Android

Configuration dans `app/build.gradle.kts`:

- `versionName`: `1.0`
- `versionCode`: `1`
- `minSdk`: `24` (Android 7.0+)
- `targetSdk`: `36`
- `compileSdk`: `36`
- Java/Kotlin JVM target: `11`

Permissions principales dans `app/src/main/AndroidManifest.xml`:

- `android.permission.CAMERA`
- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.WAKE_LOCK`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_CAMERA`

### Endpoints HTTP

Pages:

- `GET /` -> page monitoring/viewer
- `GET /viewer` -> page monitoring/viewer
- `GET /monitor` -> page monitoring/viewer

Flux et snapshots:

- `GET /stream.mjpeg` -> flux MJPEG multipart
- `GET /snapshot.jpg` -> image JPEG instantanee

API JSON:

- `GET /api/status` -> statut serveur/stream
- `GET /api/battery` -> batterie en temps reel

Exemple `GET /api/battery`:

```json
{
  "ok": true,
  "levelPercent": 82,
  "charging": true,
  "temperatureC": 31.4,
  "timestampMs": 1773571200000
}
```

API images:

- `GET|POST /api/image/save` -> sauvegarde la derniere frame
- `GET /api/image/list` -> liste des images sauvegardees
- `GET|POST /api/image/delete?name=<fichier>` -> suppression d'une image
- `GET|POST /api/image/clear` -> suppression de toutes les images

### Usage rapide

1. Installer et lancer l'application sur le telephone.
2. Donner les permissions camera/localisation demandees par l'app.
3. Dans l'admin, verifier ou modifier le port puis appuyer sur demarrer.
4. Recuperer l'IP locale affichee dans la section reseau.
5. Ouvrir un navigateur sur le meme reseau:
   - `http://<ip-telephone>:<port>/` (monitoring)
   - `http://<ip-telephone>:<port>/stream.mjpeg` (flux brut)
6. Verifier la batterie via `http://<ip-telephone>:<port>/api/battery`.

Endpoints utiles en usage:

- Viewer: `http://<ip-telephone>:<port>/`, `http://<ip-telephone>:<port>/viewer`, `http://<ip-telephone>:<port>/monitor`
- Flux: `http://<ip-telephone>:<port>/stream.mjpeg`
- Snapshot: `http://<ip-telephone>:<port>/snapshot.jpg`
- Favicon: `http://<ip-telephone>:<port>/favicon.ico`
- Status JSON: `http://<ip-telephone>:<port>/api/status`
- Batterie JSON: `http://<ip-telephone>:<port>/api/battery`
- Images JSON: `http://<ip-telephone>:<port>/api/image/list`

### Build local

```powershell
Set-Location "D:\PATH\TO\CameraMjpeg"
.\gradlew.bat :app:assembleDebug
```

Installation debug ciblee sur un device ADB:

```powershell
$apk = "D:\PATH\TO\CameraMjpeg\app\build\intermediates\apk\debug\app-debug.apk"
adb devices
adb -s <device-serial> install -r $apk
```

### Notes d'usage

- Le titre HTML du viewer utilise dynamiquement le modele du telephone.
- Si `INSTALL_FAILED_USER_RESTRICTED` apparait: activer `Installation via USB` dans les options developpeur du telephone.
- Si `INSTALL_FAILED_OLDER_SDK` apparait: le telephone est en dessous de `minSdk 24`.

---

## English (EN)

Android app for MJPEG camera streaming with an embedded HTTP server.

### Features

- MJPEG streaming from Android phone via `GET /stream.mjpeg`.
- Built-in web viewer on `GET /`, `GET /viewer`, and `GET /monitor`.
- Compose admin UI: start/stop, port, JPEG quality, front/rear camera, keep-awake mode.
- Configurable port applied live.
- Network detection: Wi-Fi, SSID, local IP, stream URL, viewer URL, battery API URL.
- Web monitoring bottom frame (snapshot, FPS, resolution, battery).
- Real-time battery JSON endpoint `GET /api/battery` (field `charging: true/false`).
- Status endpoint `GET /api/status` (clients, fps, uptime, counters/sizes).
- Image management API (`save`, `list`, `delete`, `clear`).
- Android foreground service `MjpegStreamingService` with CPU WakeLock.

### Android SDK and compatibility

Current configuration in `app/build.gradle.kts`:

- `versionName`: `1.0`
- `versionCode`: `1`
- `minSdk`: `24` (Android 7.0+)
- `targetSdk`: `36`
- `compileSdk`: `36`
- Java/Kotlin JVM target: `11`

Main permissions in `app/src/main/AndroidManifest.xml`:

- `android.permission.CAMERA`
- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.WAKE_LOCK`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_CAMERA`

### HTTP endpoints

Pages:

- `GET /` -> monitoring/viewer page
- `GET /viewer` -> monitoring/viewer page
- `GET /monitor` -> monitoring/viewer page

Stream and snapshots:

- `GET /stream.mjpeg` -> multipart MJPEG stream
- `GET /snapshot.jpg` -> current JPEG snapshot

JSON APIs:

- `GET /api/status` -> server/stream status
- `GET /api/battery` -> real-time battery

Example `GET /api/battery`:

```json
{
  "ok": true,
  "levelPercent": 82,
  "charging": true,
  "temperatureC": 31.4,
  "timestampMs": 1773571200000
}
```

Image APIs:

- `GET|POST /api/image/save` -> save latest frame
- `GET /api/image/list` -> list saved images
- `GET|POST /api/image/delete?name=<file>` -> delete one image
- `GET|POST /api/image/clear` -> delete all images

### Quick start

1. Install and launch the app on the phone.
2. Grant camera/location permissions requested by the app.
3. In admin screen, verify or change port then tap start.
4. Get the local IP shown in the network section.
5. Open a browser on the same network:
   - `http://<phone-ip>:<port>/` (monitoring)
   - `http://<phone-ip>:<port>/stream.mjpeg` (raw stream)
6. Check battery endpoint at `http://<phone-ip>:<port>/api/battery`.

Useful endpoints during usage:

- Viewer: `http://<phone-ip>:<port>/`, `http://<phone-ip>:<port>/viewer`, `http://<phone-ip>:<port>/monitor`
- Stream: `http://<phone-ip>:<port>/stream.mjpeg`
- Snapshot: `http://<phone-ip>:<port>/snapshot.jpg`
- Favicon: `http://<phone-ip>:<port>/favicon.ico`
- Status JSON: `http://<phone-ip>:<port>/api/status`
- Battery JSON: `http://<phone-ip>:<port>/api/battery`
- Images JSON: `http://<phone-ip>:<port>/api/image/list`

### Local build

```powershell
Set-Location "D:\PATH\TO\CameraMjpeg"
.\gradlew.bat :app:assembleDebug
```

Debug install on a specific ADB device:

```powershell
$apk = "D:\PATH\TO\CameraMjpeg\app\build\intermediates\apk\debug\app-debug.apk"
adb devices
adb -s <device-serial> install -r $apk
```

### Usage notes

- The viewer HTML title uses dynamic phone model detection.
- If `INSTALL_FAILED_USER_RESTRICTED` appears, enable `Install via USB` in Developer options.
- If `INSTALL_FAILED_OLDER_SDK` appears, the phone is below `minSdk 24`.

