# SpaceBeam

Real-time kaleidoscope visual synthesizer for Android, meant for party/event use.

## Workflow
- I have Android Studio open alongside Claude Code in a cmd window
- I click run/debug myself in the IDE — don't run builds or launch the app
- I do my own git commits from Android Studio — don't commit, push, or run git unless explicitly asked
- Don't run `./gradlew` or adb commands unless explicitly asked

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
