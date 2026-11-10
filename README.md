# PromptCam

**Open-source Android camera streaming app with teleprompter overlay and voice-synced scrolling.**

PromptCam turns your Android phone into a wireless webcam with a built-in teleprompter that automatically scrolls based on your voice. Stream high-quality video over Wi-Fi to your Windows desktop, display your script as an overlay on your phone screen, and let offline voice recognition advance the teleprompter as you speak — no cloud, no USB, no subscription.

---

## Features

- 📹 **Stream Android camera over Wi-Fi to Windows desktop** — like DroidCam, but open source
- 📜 **Teleprompter overlay on phone screen** — your script floats over the camera preview
- 🎙️ **Voice recognition auto-advances teleprompter scroll** — powered by Vosk offline speech recognition
- 🎥 **Virtual camera output** — OBS Virtual Camera compatible, works with Zoom, Teams, Meet, and more
- 🔒 **Offline speech recognition** — no cloud required, your audio never leaves your machine
- 📶 **Wi-Fi only** — no USB cable needed, works anywhere on your local network

---

## Architecture

```
+------------------+     Wi-Fi TCP      +-------------------+
|   Android Phone  |◄──────────────────│   Windows Desktop  │
|                  │   Port 8554 (A/V) │                    │
|  Camera2 + H.264 │◄──────────────────│  Electron + Node   │
|  Audio + AAC     │   Port 8555 (Ctrl)│                    │
|                  │──────────────────►│  TCP Client         │
|  Teleprompter    │   Text + Scroll   │  Vosk Voice Sync    │
|  Overlay         │◄──────────────────│  Virtual Camera     │
|                  │                   │  (OBS DirectShow)   │
+------------------+                   +-------------------+
```

**Data flow:**

1. **Audio/Video stream (Port 8554):** Android captures camera via Camera2 API, encodes to H.264 with MediaCodec, and sends NAL units to the desktop. Audio is captured from the mic and encoded as AAC.

2. **Control channel (Port 8555):** The desktop sends teleprompter text and scroll positions to the phone. Voice recognition runs on the desktop using Vosk; as you speak, the recognized words advance the teleprompter scroll, which is sent back to the phone for the overlay display.

3. **Virtual camera:** The desktop receives the H.264 stream, decodes the frames, and presents them as a virtual camera (via OBS DirectShow or similar) that any video conferencing app can use.

---

## Build Instructions

### Android

1. Open **Android Studio** (Hedgehog 2023.1.1 or later)
2. Import the project from the `android/` directory
3. Gradle sync and build
4. Connect an Android device (API 24+, USB debugging enabled) and run the app

### Desktop

1. Install **Node.js 18+** and **npm**
2. Navigate to the desktop directory:
   ```bash
   cd desktop
   npm install
   ```
3. Download the Vosk speech recognition model:
   ```bash
   mkdir -p models
   cd models
   wget https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   unzip vosk-model-small-en-us-0.15.zip
   ```
4. Run in development:
   ```bash
   npm start
   ```
5. Build a Windows installer:
   ```bash
   npm run dist
   ```

---

## Usage

1. **Launch PromptCam** on your Android phone
2. **Select camera** (front or back), resolution, and bitrate
3. **Note the IP address** displayed on the phone screen
4. **Launch PromptCam Desktop** on your Windows PC
5. **Auto-discover** the phone on your network, or enter the IP address manually
6. **Click Connect** — the camera feed should appear in the desktop preview
7. **Paste or type your script** in the script editor
8. **Click "Send to Phone"** — the script appears as a teleprompter overlay on your phone
9. **Start voice sync** — the teleprompter auto-scrolls as you speak
10. **Enable the virtual camera** in OBS (or other apps) to use your phone's camera as a webcam

---

## Vosk Model

PromptCam uses [Vosk](https://alphacephei.com/vosk/) for offline speech recognition.

- **Download from:** https://alphacephei.com/vosk/models/
- **Recommended model:** `vosk-model-small-en-us-0.15` (40 MB) — fast and lightweight
- **Larger model:** `vosk-model-en-us-0.22` (1.8 GB) — higher accuracy for complex scripts
- **Place model in:** `desktop/models/vosk-model-small-en-us-0.15/`

The model directory structure should look like:

```
desktop/models/
└── vosk-model-small-en-us-0.15/
    ├── am/
    ├── conf/
    ├── graph/
    ├── ivector/
    └── README
```

---

## Binary Protocol

PromptCam uses a lightweight binary protocol over TCP for communication between the Android phone and the desktop. The protocol is defined in [`shared/protocol.js`](shared/protocol.js) and is compatible with Kotlin's `DataInputStream` / `DataOutputStream`.

### Message Format

All messages (except `SCROLL_POSITION`) follow this wire format:

```
┌──────────┬──────────────┬──────────────────────────────────────┐
│ Type (1) │ Length (4)   │ Payload (variable, Length bytes)     │
│ byte     │ Big-Endian   │                                      │
└──────────┴──────────────┴──────────────────────────────────────┘
```

`SCROLL_POSITION` is a special case with no length prefix:

```
┌──────────┬──────────────────────────────────────┐
│ Type (1) │ Percent (4 bytes, IEEE 754 float BE) │
└──────────┴──────────────────────────────────────┘
```

### Message Types

| Type | Hex | Name | Payload | Port |
|------|-----|------|---------|------|
| `VIDEO_FRAME` | `0x01` | H.264 NAL data | Raw NAL units (Annex B or AVCC) | 8554 |
| `AUDIO_FRAME` | `0x02` | AAC audio data | Raw AAC frames (ADTS) | 8554 |
| `TELEPROMPTER_TEXT` | `0x03` | Teleprompter script | UTF-8 JSON: `{"text","fontSize","speed"}` | 8555 |
| `SCROLL_POSITION` | `0x04` | Scroll percentage | 4-byte IEEE 754 float (0.0–1.0) | 8555 |
| `CONTROL_COMMAND` | `0x05` | Control command | UTF-8 command string | 8555 |

### Port Allocation

| Port | Direction | Purpose |
|------|-----------|---------|
| 8554 | Phone → Desktop | Audio/Video stream (VIDEO_FRAME, AUDIO_FRAME) |
| 8555 | Desktop → Phone | Control channel (TELEPROMPTER_TEXT, SCROLL_POSITION, CONTROL_COMMAND) |

### Example (Node.js)

```javascript
const { MessageType, encodeVideoFrame, createMessageReader } = require('./shared/protocol.js');

// Sending a video frame
const nalData = Buffer.from(/* H.264 NAL bytes */);
const message = encodeVideoFrame(nalData);
socket.write(message);

// Receiving messages from a stream
const reader = createMessageReader(({ type, data }) => {
  switch (type) {
    case MessageType.VIDEO_FRAME:
      // data is a Buffer containing H.264 NAL data
      break;
    case MessageType.SCROLL_POSITION:
      // data is a number (float 0.0–1.0)
      break;
    // ...
  }
});

socket.on('data', (chunk) => reader.feed(chunk));
```

### Kotlin Implementation

See the protocol documentation comment block at the top of [`shared/protocol.js`](shared/protocol.js) for Kotlin `DataInputStream` / `DataOutputStream` usage examples.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Android | Kotlin, Jetpack Compose, Camera2 API, MediaCodec |
| Desktop | Electron, Node.js, Vosk, OBS Virtual Camera |
| Transport | TCP over Wi-Fi |
| Video Codec | H.264 (AVC) |
| Audio Codec | AAC |
| Speech Recognition | Vosk (offline) |

---

## Project Structure

```
promptcam/
├── android/                    # Android app (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       └── main/
│   │           ├── AndroidManifest.xml
│   │           ├── java/com/wembassy/promptcam/
│   │           │   ├── MainActivity.kt
│   │           │   ├── ui/
│   │           │   │   ├── CameraScreen.kt
│   │           │   │   ├── TeleprompterOverlay.kt
│   │           │   │   └── SettingsScreen.kt
│   │           │   ├── camera/
│   │           │   │   ├── CameraManager.kt
│   │           │   │   └── VideoEncoder.kt
│   │           │   ├── audio/
│   │           │   │   └── AudioEncoder.kt
│   │           │   ├── network/
│   │           │   │   └── TcpServer.kt
│   │           │   └── protocol/
│   │           │       └── Protocol.kt          # Mirrors shared/protocol.js
│   │           └── res/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/
├── desktop/                    # Desktop app (Electron + Node.js)
│   ├── src/
│   │   ├── main.js             # Electron main process
│   │   ├── renderer/
│   │   │   ├── index.html
│   │   │   ├── app.js
│   │   │   └── styles.css
│   │   ├── client/
│   │   │   ├── TcpClient.js    # Connects to Android
│   │   │   ├── VideoDecoder.js # H.264 → frames
│   │   │   └── VirtualCamera.js # OBS DirectShow output
│   │   ├── voice/
│   │   │   └── VoskEngine.js   # Offline speech recognition
│   │   └── teleprompter/
│   │       └── ScrollSync.js   # Voice → scroll position mapping
│   ├── models/                 # Vosk models (gitignored)
│   ├── package.json
│   ├── electron-builder.yml
│   └── forge.config.js
├── shared/
│   └── protocol.js             # Binary protocol (shared between platforms)
├── .gitignore
├── LICENSE
└── README.md
```

---

## License

MIT License — Copyright (c) 2026 Wembassy. See [`LICENSE`](LICENSE) for details.

---

## Acknowledgements

- [Vosk](https://alphacephei.com/vosk/) — Offline speech recognition by Alpha Cephei
- [OBS Studio](https://obsproject.com/) — Virtual camera technology
- [DroidCam](https://www.dev47apps.com/) — Inspiration for the wireless camera concept
