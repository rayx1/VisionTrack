# Person Counter — Real-Time AI Detection for Android

A native Android app that uses on-device machine learning to detect and count **faces**, **persons**, and **raised hands** in real time from a live camera feed. Supports physical device cameras (front/back) and IP cameras via RTSP.

> Developed by **MITS Bhubaneswar**

---

## Screenshots

> *(Install the APK on a physical device and point at people to see real-time results)*

---

## Features

| Feature | Description |
|---|---|
| **Face Count** | Detects and counts visible faces using ML Kit Face Detection |
| **Person Count** | Detects full-body persons using EfficientDet-Lite0 (TFLite) |
| **Raised Hand Count** | Detects persons with at least one hand raised via ML Kit Pose Detection |
| **Live Bounding Boxes** | Colour-coded overlays: blue = face, green = person, red = raised hand |
| **IP Camera (RTSP)** | Stream from any RTSP IP camera — with or without credentials |
| **Settings Screen** | Adjust detection threshold and select camera source at runtime |
| **Front / Back Camera** | Switch between device cameras from Settings |
| **Offline Inference** | All ML runs on-device — no internet required for detection |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Camera | CameraX 1.3.4 |
| Person Detection | TensorFlow Lite Task Vision 0.4.4 + EfficientDet-Lite0 (COCO) |
| Face Detection | ML Kit Face Detection 16.1.7 |
| Pose / Hand Detection | ML Kit Pose Detection Accurate 18.0.0-beta4 |
| IP Camera Streaming | Media3 ExoPlayer 1.3.1 (RTSP) |
| UI | ViewBinding + ConstraintLayout + Material 3 |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 34 (Android 14) |

---

## Project Structure

```
person-counter-app/
├── app/src/main/
│   ├── assets/
│   │   └── efficientdet_lite0.tflite      ← TFLite model (download separately)
│   ├── java/com/example/personcounter/
│   │   ├── MainActivity.kt                ← Camera setup, UI, orchestration
│   │   ├── DetectionHelper.kt             ← Face + Person + Pose detection pipeline
│   │   ├── OverlayView.kt                 ← Bounding box / landmark drawing
│   │   ├── IpCameraManager.kt             ← ExoPlayer RTSP stream manager
│   │   ├── SettingsActivity.kt            ← Settings screen
│   │   └── SettingsManager.kt             ← SharedPreferences wrapper
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml
│       │   └── activity_settings.xml
│       └── values/
│           ├── strings.xml
│           ├── colors.xml
│           └── themes.xml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK API 34 installed
- Physical Android device recommended (API 24+) for real-time performance
- JDK 17+

### 1. Clone the Repository

```bash
git clone https://github.com/<your-username>/person-counter-app.git
cd person-counter-app
```

### 2. Download the TFLite Model

The EfficientDet-Lite0 model is not included in the repository due to file size. Download it from TensorFlow Hub:

```bash
curl -L "https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite" \
     -o app/src/main/assets/efficientdet_lite0.tflite
```

Or download manually from:
```
https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
```
Rename the downloaded file to `efficientdet_lite0.tflite` and place it in `app/src/main/assets/`.

### 3. Build & Run

Open the project in Android Studio, let Gradle sync complete, then run on a connected device.

```bash
# Or build from command line
./gradlew assembleDebug
```

The debug APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## How It Works

### Detection Pipeline

Each camera frame goes through three sequential ML stages:

```
Camera Frame (RGBA_8888)
        │
        ▼
┌───────────────────┐
│  Face Detection   │  ML Kit FaceDetector (fast mode, multi-face)
│  → faceCount      │  Blue bounding boxes
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│ Person Detection  │  EfficientDet-Lite0 TFLite (COCO "person" class)
│  → personCount    │  Green bounding boxes
└────────┬──────────┘
         │
         ▼
┌───────────────────────────────┐
│ Raised Hand Detection         │  ML Kit PoseDetector per person crop
│ → raisedHandCount             │  Raised = wrist.y < shoulder.y
│ (max 5 persons, ~200ms each)  │  Red border + ✋ marker
└───────────────────────────────┘
```

### Raised Hand Logic

A hand is classified as **raised** when either wrist landmark is positioned above (lower Y value) its corresponding shoulder landmark, both with in-frame likelihood ≥ 0.5:

```kotlin
wrist.position.y < shoulder.position.y   // Y increases downward in image space
```

### IP Camera Support

RTSP URLs are supported with optional credentials:

| Mode | URL Format |
|---|---|
| No credentials | `rtsp://192.168.1.1:554/stream` |
| With credentials | Enter URL + username + password separately |

Credentials are injected automatically:
```
rtsp://admin:1234@192.168.1.1:554/stream
```

Frames are sampled from the live stream every **200 ms** for ML analysis.

---

## Settings

Tap the **⚙ gear button** (bottom-right) to open Settings.

| Setting | Description | Default |
|---|---|---|
| Detection Threshold | Minimum confidence score (0.10 – 0.95) | 0.50 |
| Camera Source | Back Camera / Front Camera / IP Camera | Back |
| IP Camera URL | RTSP stream address | — |
| IP Username | Leave blank for unauthenticated streams | — |
| IP Password | Leave blank for unauthenticated streams | — |

---

## On-Screen Indicators

```
┌─────────────────────────────────────────┐
│                              ┌────────┐ │
│                              │   3    │ │  ← Face count (blue)
│                              │ faces  │ │
│                              ├────────┤ │
│   [Live Camera Feed]         │   5    │ │  ← Person count (green)
│                              │persons │ │
│                              ├────────┤ │
│                              │   2    │ │  ← Raised hand count (red)
│                              │ raised │ │
│                              └────────┘ │
│ 148ms                           [⚙]    │
└─────────────────────────────────────────┘
```

| Colour | Meaning |
|---|---|
| 🔵 Blue box | Detected face |
| 🟢 Green box | Detected person (full body) |
| 🔴 Red box + ✋ | Person with a raised hand |
| `Xms` chip | Inference time for last frame |

---

## Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | Access device camera (front/back) |
| `INTERNET` | Stream video from IP cameras over the network |

Camera permission is requested at runtime on first launch. Internet permission is granted automatically (non-dangerous).

---

## Performance Notes

- **Physical device** strongly recommended — emulators lack GPU acceleration for TFLite
- Inference runs on a **single background thread** to prevent frame queue buildup
- IP camera frames are analysed at **5 fps** (every 200 ms) to balance latency vs. CPU load
- Raised hand detection is capped at **5 persons per frame** to maintain responsiveness
- On mid-range devices, expect **100–300 ms** total inference time per frame on CPU

---

## Build Environment

| Tool | Version |
|---|---|
| Android Gradle Plugin | 8.4.0 |
| Kotlin | 1.9.24 |
| Gradle | 8.10 |
| JDK | 17 (Eclipse Temurin recommended) |

---

## License

```
MIT License

Copyright (c) 2025 MITS Bhubaneswar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Acknowledgements

- [TensorFlow Lite](https://www.tensorflow.org/lite) — EfficientDet-Lite0 object detection model
- [ML Kit](https://developers.google.com/ml-kit) — Face Detection and Pose Detection APIs
- [CameraX](https://developer.android.com/training/camerax) — Jetpack camera library
- [Media3 / ExoPlayer](https://developer.android.com/media/media3) — RTSP stream playback

---

*Developed by **MITS Bhubaneswar***
