# UVC Android Pad

Android PAD app scaffold for standard UVC USB cameras. The first implementation targets Android devices that expose UVC cameras through Camera2 as external cameras. The code keeps USB discovery and camera control separated so a native `libuvc` / UVCCamera engine can be introduced later without replacing the app shell.

## Initial Features

- USB UVC attach detection and permission request.
- Live preview from the first external Camera2 device.
- Video recording to the app-specific Movies directory.
- Local playback list and in-app replay.
- Landscape PAD-oriented UI.

## Build

```bash
./gradlew assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/app-debug.apk`.

## Next Engineering Steps

1. Add a native UVC engine module based on `libuvc` or a maintained fork of the classic UVCCamera project when target PAD firmware does not expose USB cameras through Camera2.
2. Add camera capability negotiation for resolution, frame rate, MJPEG/YUYV, and autofocus or exposure controls where supported.
3. Add recording policies: segmented recording, disk quota, retention, and crash-safe file finalization.
4. Add customer-specific live transport such as RTMP, SRT, WebRTC, or private SDK push streaming.
