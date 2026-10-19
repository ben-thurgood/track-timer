# Track Timer

An Android app that times Hot Wheels cars between a START and FINISH box drawn over the camera view.

## Getting it onto the phone

1. Install **Android Studio** (free) on your PC, then open this `HotWheelsTimer` folder with *File → Open*.
2. Let it sync. The first sync downloads Gradle, the Android SDK and CameraX, which takes a few minutes. If it suggests upgrading the Android Gradle Plugin, it's fine to accept.
3. On the phone, turn on **Developer options** (Settings → About phone → tap *Build number* 7 times). Then turn on **USB debugging**.
4. Plug the phone in, pick it in the device dropdown at the top of Android Studio, and press ▶ Run.

## Using it

1. Prop the phone up (a tripod or a pile of books) so the whole track is in view. Portrait or landscape both work. Turn auto-rotate on to switch, and the boxes stay over the same bit of track when you rotate. Keep it still, because any wobble looks like a moving car.
2. Drag the green **START** box over the start of the track and the orange **FINISH** box over the end. Use the round handle to resize a box. Narrow boxes across the track work best.
3. Press **Ready!** The app spends a moment learning what the empty track looks like and then locks exposure.
4. Let the car go. You'll hear a beep at the start and another at the finish, and the time is added to the list (★ marks the best).
5. Press **Ready!** again for the next run.

### Measuring the track and speed

- Press **📏 Measure** to measure the track with AR. Slowly sweep the phone over the track until the crosshair turns green. Aim it at the START line and press **＋ Add point** (or tap the screen). Then add a point at each bend and up or down each ramp, finishing at the FINISH line. Press **✓ Use** when you're done.
- The first time, the phone may ask to install **Google Play Services for AR** from the Play Store.
- More points around curves give a more accurate length. Undo removes the last point.
- On phones without AR, or if you'd rather type it, **long-press** the 📏 button and enter the length in cm.
- Once the length is set, each run shows its speed in m/s and its **scale mph**. Hot Wheels are 1:64 scale, so scale mph is how fast a real car would be going. Saved runs remember the track length, so the leaderboard shows their scale speed too.

### Leaderboard

- After a race, press **💾 Save** to keep that time. Tap the picture box to photograph the car with the phone's camera app, then type its name and press Save. If you pick a car you've saved before, its old photo is reused automatically.
- Press **🏆 Leaderboard** at any time to see saved times, fastest first, with medals for the top three. The button at the top switches between **Best per car** and **All runs**.
- Tap a row to see the photo bigger, or to delete that run.
- Times and photos are stored privately on the phone. Uninstalling the app removes them.

A car already sitting in the start box when you press Ready is fine. The clock starts as soon as it moves off.

## Tuning

- **Sensitivity slider**: if it triggers on its own, slide it left. If it misses fast cars, slide it right or make the boxes a bit longer along the track.
- The boxes fill with colour as they detect change. That helps you see what the app sees.
- **Lighting**: even, bright light with no flickering lamps and no shadows moving across the boxes.
- The line under the timer shows the actual **fps**. Timing is accurate to about one frame (±17 ms at 60 fps, ±33 ms at 30 fps).

## How the code is laid out

| File | What it does |
|---|---|
| `MainActivity.kt` | Camera setup (CameraX), picks the fastest frame rate, buttons, results list |
| `OverlayView.kt` | Draws the boxes and handles dragging and resizing |
| `Leaderboard.kt` | Saves and loads leaderboard times and car photos |
| `LeaderboardActivity.kt` | The leaderboard screen |
| `MeasureActivity.kt`, `MeasureOverlay.kt`, `BackgroundRenderer.kt` | AR track measuring (ARCore) |
| `Speed.kt` | m/s, km/h and 1:64 scale mph |
| `TrackTimer.kt` | Runs on each frame: compares pixels in each box with the empty-track baseline, starts and stops the clock using the camera's frame timestamps |

## Ideas for next steps

- **Higher frame rate:** many phones can do 120/240 fps only in slow-motion video mode. That would mean recording a clip and analysing it afterwards rather than live.
- **Sub-frame accuracy:** estimate when the car crossed between two frames from how much of the box it covered.
- Add head-to-head races with two lanes (two finish boxes).
