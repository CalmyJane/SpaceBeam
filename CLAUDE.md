# SpaceBeam

Real-time kaleidoscope visual synthesizer for Android, meant for party/event use.

## Workflow
- I have Android Studio open alongside Claude Code in a cmd window
- I click run/debug myself in the IDE — don't run builds or launch the app
- I do my own git commits from Android Studio — don't commit, push, or run git unless explicitly asked
- Don't run `./gradlew` or adb commands unless explicitly asked

## Documentation
- After making changes, check if README.md should be updated to reflect them
- If so, ask whether to update the README as well
- README.md is copied to app assets at build time (Gradle `copyHelpAssets` task) and shown as in-app help via WebView — no manual copy needed, just rebuild

## Architecture
- Single-activity app, split across 13 Kotlin files in `com.calmyjane.spacebeam`
- OpenGL ES 2.0, continuous rendering on `GLSurfaceView`, ping-pong FBOs at 1920x1080
- All UI is programmatic (no XML layouts except AndroidManifest)
- Effect chain: Sources → Mixer → CamTransform → Color → Edge → Kaleidoscope → MasterTransform → Tunnel → Swirl → Screen
- Each effect is a `ShaderEffect` subclass with GLSL shaders as inline strings
- Parameters stored as `PropertyControl` instances with LFO modulation, sensor input, smoothing
- Presets store/restore all PropertyControl snapshots with animated transitions
- Sources: Camera (CameraX + Camera2 interop), RTSP (Media3/ExoPlayer), media playlists, generative shaders, feedback loops
- Recording: `MediaCodec` H.264 encoder → `MediaMuxer`, orientation-aware
- MIDI: BLE MIDI via Android MIDI API (`MidiHelper`)
- Sensors: Accelerometer + gyroscope (`SensorHelper`)

## File map — UPDATE WHEN FILES/STRUCTURE CHANGE
| File | ~Lines | Content |
|------|--------|---------|
| `MainActivity.kt` | 3890 | Activity lifecycle, onCreate, UI setup, HUD, presets, mask editor, RTSP dialog, recording, gestures, auto-play |
| `PropertyControl.kt` | 1212 | Parameter sliders with LFO, modulation, sensor mapping, details menu, SliderBox |
| `Effects.kt` | 1173 | ShaderEffect base, EffectChain, MaskManager, 7 effect classes (Mixer, Transform, Kaleidoscope, Tunnel, Swirl, Color, Edge) |
| `SettingsMenu.kt` | 1166 | Settings dialog, help system, markdown-to-HTML, MIDI scanner/mapping UI |
| `KaleidoscopeRenderer.kt` | 994 | GLSurfaceView.Renderer, SourceChannel, MediaLayer, onDrawFrame, shader setup |
| `SourceControls.kt` | 846 | SourcePropertyControl + Camera/RTSP/Media/Shader/Feedback source controls |
| `MediaPickerDialog.kt` | 460 | Gallery file picker with grid view, folder navigation |
| `MidiHelper.kt` | 398 | BLE MIDI scan, connect, CC processing, binding config import/export |
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
