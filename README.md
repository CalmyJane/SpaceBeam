# SpaceBeam

A kaleidoscope camera app optimized for live performance.

## Overview

App for displaying camera with kaleidoscope effect on the phone screen with different settings.

## Features

### Axis

Axis Count - Number of Kaleidoscope axis, uneven numbers will result in non-symmetric results. There's a little lock icon next to the slider you can use to lock the value. Then, when loading a preset, it won't overwrite the current axis setting avoiding jumps and ensuring smooth preset transitions.

### Master

These effects are applied to the final video after the kaleidoscope effect.
Angle - Momentary Angle of the image
Rotation - constantly spinning rotation
Zoom - momentary zoom level
RGB smudge - RGB shift effect

### Camera

These effects are applied to the image coming from the camera before the kaleidoscope is applied.

Angle - Momentary angle
rotation - contant rotation
zoom - momentary zoom
RGB shift - shifts r-g-b channels sideways
Bloom - highlights bright spots
Invert - inverts the colors when set to max
Hue - changes the color
Contrast
Vibrancy - changes saturation

There are buttons to switch the camera (front/back), to flip or rotate the camera image.

### Presets

You can save 8 presets by adjusting the parameters, and then holding a preset, then tap the name again to save, tap anywhere else to cancel. Presets stores all slider values. When selection another preset, the sliders smoothly approach the other preset value. The transition time can be adjusted above the preset slots with a slider. There is a reset button restetting all to a default state.

### UI

There's a button in bottom right to toggle visibility overlay. That puts dark background behind the overlay menu, good for readability, bad for live performances.

### Screenshots

You can use the screenshot button to save a current frame as still.

### Gestures

You can change the parameters Master Rotation, Zoom and Translation X/Y using Gestures (move with 2 fingers, distance controls zoom, angle controls rotation and position controls translation. Making it very intuitive to control.
