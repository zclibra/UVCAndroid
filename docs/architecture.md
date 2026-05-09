# Architecture Plan

## Module Boundary

The app starts with one `:app` module to keep the bootstrap simple. The code should evolve toward these boundaries as customer requirements become clearer:

- `app`: PAD UI, permissions, navigation, playback, and feature orchestration.
- `uvc-core`: stable camera engine interfaces, device model, capture session lifecycle, settings model.
- `uvc-camera2`: Camera2 external-camera implementation for Android firmware that maps UVC devices to `LENS_FACING_EXTERNAL`.
- `uvc-libusb`: native `libuvc` / UVCCamera implementation for firmware that only exposes USB host access.
- `streaming`: live push protocols such as RTMP, SRT, WebRTC, or private customer SDKs.
- `media`: recording, file index, retention, thumbnails, and export.

## Why Start With Camera2

Many Android PAD builds expose standard UVC cameras as external Camera2 devices. That path avoids shipping native USB code on day one and gives a working baseline for preview and recording. The USB detection code remains in place because product UX still needs attach and permission flows, and because native UVC support may be required for customer hardware.

## Reference Direction

The classic GitHub UVCCamera project by saki4510t is a useful reference for non-root USB host access, native UVC capture, and Surface-based preview. For production, prefer a maintained fork or vendor-supported native module, then adapt it behind the `uvc-core` interface rather than wiring it directly into Activities.
