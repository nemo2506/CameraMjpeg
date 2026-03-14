# CameraMjpeg

Application Android Compose de streaming MJPEG avec architecture MVVM.

## Fonctionnalites

- Interface admin complete (demarrer/arreter, port, qualite, camera avant/arriere, veille)
- Detection reseau (SSID Wi-Fi, IP locale)
- URL de flux MJPEG: `http://<ip>:<port>/stream.mjpeg`
- Viewer web simple: `http://<ip>:<port>/`
- Service foreground pour streaming stable en arriere-plan

## Execution

```powershell
cd D:\PATH\TO\CameraMjpeg
.\gradlew.bat :app:installDebug
```

## Permissions requises

- Camera
- Localisation (lecture SSID)
- Internet / reseau
- Wake lock

