# SpaceBeam

Real-time kaleidoscope visual synthesizer for Android, meant for party/event use.

## Workflow
- `master` only gets tested states. Claude never commits to it directly
- Claude works on a branch per feature/fix (`feature/<name>`, `fix/<name>`), created from up-to-date `master`
- One commit per finished task, even when I ask for several things in one message; small follow-up fixes go on the same branch. Push the branch so it is backed up
- I test on the device. When I explicitly say "merge", Claude merges the branch into `master` and pushes. No pull requests needed
- Never force-push, create tags or bump the version unless I explicitly ask — releases are mine (a `v*` tag publishes to GitHub + F-Droid)
- Building and installing the debug build on my connected phone (`./gradlew assembleDebug`, `adb install -r`, launch, read logcat) is fine when a change needs testing; never uninstall the app or clear its data without asking (that wipes my presets)
- I may also run/debug from Android Studio myself

## Documentation
- After making changes, check if README.md should be updated to reflect them
- If so, ask whether to update the README as well
- README.md is copied to app assets at build time (Gradle `copyHelpAssets` task) and shown as in-app help via WebView — no manual copy needed, just rebuild

## Architecture
- Single-activity app, split across 16 Kotlin files in `com.calmyjane.spacebeam`
- OpenGL ES 2.0, continuous rendering on `GLSurfaceView`, ping-pong FBOs at 1920x1080 (1280x720 in performance mode)
- All UI is programmatic (no XML layouts except AndroidManifest)
- Effect chain: Sources → Mixer → CamTransform → Color → Edge → Kaleidoscope → Droste → MasterTransform → Tunnel → Swirl → Screen
- Each effect is a `ShaderEffect` subclass with GLSL shaders as inline strings
- Effects with no visible effect are skipped (`isBypassed`); Tunnel/Swirl at 0 still flip 180°, applied lazily by the chain. Mipmaps only built for passes with `needsMipmaps`
- Parameters stored as `PropertyControl` instances with LFO modulation, sensor input, smoothing
- Presets store/restore all PropertyControl snapshots with animated transitions
- Sources: Camera (CameraX + Camera2 interop), RTSP (Media3/ExoPlayer), media playlists, generative shaders, feedback loops
- Recording: `MediaCodec` H.264 encoder → `MediaMuxer`, orientation-aware
- MIDI: BLE MIDI via Android MIDI API (`MidiHelper`)
- Sensors: Accelerometer + gyroscope (`SensorHelper`). Sensor modulation is off until the gyro button enables it (`setEnabled`, fades via `blend`); values are relative to the orientation at switch-on (`relative(ch)`). Tunnel/Swirl have LOOK X/Y params (`T_LOOK_*`, `S_LOOK_*`) used by the factory presets for gyro look-around
- Body tracking (MediaPipe tasks-vision, CPU): `VisionPipeline` owns the single CameraX ImageAnalysis next to the camera preview and feeds every enabled `Tracker`. `Tracker` base (Tracker.kt) holds the shared logic: `values[]` 0..1 smoothed per frame in `tick()` (strength in Settings, prefs `HAND_SMOOTH`/`FACE_SMOOTH`), spike filter `accept()` + adaptive smoothing, circular angle channel (`angleTurns`/`angleOffset`), and `blend` that eases modulation in/out on toggle. `HandTracker`: 6 channels (thumb-to-finger index/middle/ring/pinky, hand tilt wrist→middle knuckle, distance from palm size). `FaceTracker`: 3 channels (head roll on the eye line, distance from face width, mouth width). `PropertyControl.handAmount[6]` / `faceAmount[3]` are signed (0 = off, ±1000 = channel takes over the whole range, negative = inverted; angle on WRAP params is a relative offset), applied in `applyTracker()`, eased during preset transitions. Factory presets in `loadFactoryDefault` bake in hand + face mappings. Models (`app/src/main/assets/*.task`, Apache 2.0, from Google's MediaPipe model storage) are committed to the repo so the build needs no downloads (F-Droid builds from source without fetching extra files)

## File map — UPDATE WHEN FILES/STRUCTURE CHANGE
| File | ~Lines | Content |
|------|--------|---------|
| `MainActivity.kt` | 4055 | Activity lifecycle, onCreate, UI setup, HUD, presets, mask editor, RTSP dialog, recording, gestures, auto-play |
| `PropertyControl.kt` | 1300 | Parameter sliders with LFO, modulation, sensor mapping, details menu, SliderBox |
| `Effects.kt` | 1345 | ShaderEffect base, EffectChain, MaskManager, 8 effect classes (Mixer, Transform, Kaleidoscope, Droste, Tunnel, Swirl, Color, Edge) |
| `SettingsMenu.kt` | 1180 | Settings dialog, help system, markdown-to-HTML, MIDI scanner/mapping UI |
| `KaleidoscopeRenderer.kt` | 1020 | GLSurfaceView.Renderer, SourceChannel, MediaLayer, onDrawFrame, shader setup |
| `SourceControls.kt` | 846 | SourcePropertyControl + Camera/RTSP/Media/Shader/Feedback source controls |
| `MediaPickerDialog.kt` | 460 | Gallery file picker with grid view, folder navigation |
| `MidiHelper.kt` | 398 | BLE MIDI scan, connect, CC processing, binding config import/export |
| `HandTracker.kt` | 355 | HandTracker (MediaPipe hand), TrackingOverlayView (phone-only hand/face overlay + bars), button icons |
| `Tracker.kt` | 235 | VisionPipeline (shared camera analysis stream), Tracker base (smoothing, spike filter, blend) |
| `FaceTracker.kt` | 90 | FaceTracker (MediaPipe face: roll, distance, mouth width) |
| `ShaderHelper.kt` | 384 | GL shader compile/link utility object + BUILTIN_SHADERS map |
| `Utilities.kt` | 198 | BpmManager, UndoManager, PlaylistItem, BlendMode/SourceType enums |
| `VideoRecorder.kt` | 185 | MediaCodec H.264 encoder + AudioRecord → MediaMuxer |
| `ExternalDisplayHelper.kt` | 110 | Miracast/secondary display via Presentation |
| `SensorHelper.kt` | 91 | Accelerometer + gyroscope via SensorManager |

## Creative input welcome
- Feel free to suggest new features, improvements, or simpler approaches if relevant to the conversation
- The app is for live party visuals — prioritize hands-on usability, flexibility, and visual impact

## Use case
- Runs on a Samsung S20 FE, streams to a projector via Miracast dongle
- The phone hosts a WiFi network (no internet) — other phones/tablets connect to it
- Other devices stream their screens into the app via ScreenStream (RTSP), providing visual material from apps like Fraksl, Fluid, or mobile games
- Other devices can also stream their camera via IP Webcam (RTSP), enabling remote camera setups at parties
- Party/DJ logos can be overlaid on the visuals
- Also used for concert after-movies: film instruments while playing, use autoplay mode
- Controlled via SCM Bluetooth MIDI mixer: 8 faders, 8 knobs, play/pause/stop, 8 nav buttons (fwd/bwd, next/last, up/down, left/right), shift button, and 4 buttons per fader (M, S, R, square)
