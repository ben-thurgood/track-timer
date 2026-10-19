# Track Timer

An Android app that times Hot Wheels cars between a START and FINISH box drawn over the camera view.

## Getting it onto the phone

1. Install **Android Studio** (free) on your PC, then open this `HotWheelsTimer` folder with *File → Open*.
2. Let it sync. The first sync downloads Gradle, the Android SDK and CameraX, which takes a few minutes. If it suggests upgrading the Android Gradle Plugin, it's fine to accept.
3. On the phone, turn on **Developer options** (Settings → About phone → tap *Build number* 7 times). Then turn on **USB debugging**.
4. Plug the phone in, pick it in the device dropdown at the top of Android Studio, and press ▶ Run.

## Using it

1. Prop the phone up (a tripod or a pile of books) so the whole track is in view. Keep it still, because any wobble looks like a moving car.
2. Drag the green **START** box over the start of the track and the orange **FINISH** box over the end. Use the round handle to resize a box. Narrow boxes across the track work best.
3. Press **Ready!** The app spends a moment learning what the empty track looks like and then locks exposure.
4. Let the car go. You'll hear a beep at the start and another at the finish, and the time is added to the list (★ marks the best).
5. Press **Ready!** again for the next run.

A car already sitting in the start box when you press Ready is fine. The clock starts as soon as it moves off.

## Tuning

- **Sensitivity slider**: if it triggers on its own, slide it left. If it misses fast cars, slide it right or make the boxes a bit longer along the track.
- The boxes fill with colour as they detect change. That helps you see what the app sees.
- **Lighting**: even, bright light with no flickering lamps and no shadows moving across the boxes.
- The top-left corner shows the actual **fps**. Timing is accurate to about one frame (±17 ms at 60 fps, ±33 ms at 30 fps).

## How the code is laid out

| File | What it does |
|---|---|
| `MainActivity.kt` | Camera setup (CameraX), picks the fastest frame rate, buttons, results list |
| `OverlayView.kt` | Draws the boxes and handles dragging and resizing |
| `TrackTimer.kt` | Runs on each frame: compares pixels in each box with the empty-track baseline, starts and stops the clock using the camera's frame timestamps |

## Ideas for next steps

- **Higher frame rate:** many phones can do 120/240 fps only in slow-motion video mode. That would mean recording a clip and analysing it afterwards rather than live.
- **Sub-frame accuracy:** estimate when the car crossed between two frames from how much of the box it covered.
- Name the cars, save results between sessions, and add head-to-head races with two lanes (two finish boxes).
