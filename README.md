# Crosshair

A pure crosshair overlay for Android. It draws one of 35 sigils on top of whatever app you are
using, and it never takes a tap — even at 100% opacity.

- **Always click through.** The crosshair window carries `FLAG_NOT_TOUCHABLE`, so touches land on
  the app underneath at every opacity setting. It is a purely visual layer.
- **Floating control button.** A draggable sigil sits on top of everything. Tap it to tune
  reticle, colour, position, size and opacity live, without leaving the app you are in. It can
  be hidden if you keep catching it mid-game.
- **35 built-in crosshairs**, drawn as vectors so they stay sharp at any size. Pick one from
the panel or from the picker in the app; no image files to import.
- **Minimum Android 8.0** (API 26).

---

## Build it on GitHub

No local toolchain needed.

1. Create a new repository and push this folder to it:

   ```bash
   git init
   git add .
   git commit -m "Crosshair overlay"
   git branch -M main
   git remote add origin https://github.com/YOUR-NAME/YOUR-REPO.git
   git push -u origin main
   ```

2. Open the **Actions** tab. The `Build APK` workflow starts on its own.
3. When it finishes, open the run and download the **crosshair-apk** artifact.
4. Unzip it and install the `.apk` on your phone. Android will ask you to allow installing from
   your browser or file manager the first time.

To cut a release with the APK attached to it, push a tag:

```bash
git tag v1.0 && git push origin v1.0
```

### Building locally instead

Open the folder in Android Studio and hit Run, or:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

---

## First run

1. Open the app and grant **Display over other apps**. Android requires this before anything can
   be drawn on top of another app.
2. Tap **Start overlay**.
3. The floating sigil appears. Switch to your game or app.

## Using the overlay

| Gesture | What it does |
| --- | --- |
| Tap the floating sigil | Opens the tuning panel |
| Drag the floating sigil | Moves it; it snaps to the nearest edge |
| Long press the floating sigil | Shows or hides the crosshair |
| Hide the floating button | Removes it entirely, so a stray tap cannot open the panel |
| Crosshair row in the panel | Switches reticle, live |
| Choose crosshair on the main screen | Opens the full picker, with colour |
| **Move** in the panel | Makes the crosshair draggable until you tap **Done** |
| Tap the crosshair while in Move mode | Snaps it back to dead centre |
| **−** / **+** next to the position sliders | Nudges by exactly one pixel |

The notification also carries Show/Hide and Stop actions.

## Max priority mode

Optional, off by default.

`TYPE_APPLICATION_OVERLAY` is the highest window type a normal app is allowed to use. If some
other floating app keeps covering the crosshair, you can switch to an accessibility service, which
gets `TYPE_ACCESSIBILITY_OVERLAY` — one layer higher.

Turn it on from the main screen; Android will send you to Accessibility settings to enable
"Crosshair max priority". The service draws the crosshair and nothing else: it does not read
screen content, does not observe input, and collects nothing. Everything works without it.

## The 100% opacity catch

Android 12 (API 31) blocks touches that pass through an overlay window belonging to another app
when that window is more than **80% opaque**. It is an anti-tapjacking measure: the system will not
let you tap something you cannot properly see. It drops the touch rather than dimming the overlay,
so the crosshair still *draws* at 100%; taps that land on it just stop reaching the app underneath.

Two things worth knowing:

- It is evaluated per touch, against the windows containing that touch point. Only taps landing on
  the crosshair's own square are affected, not the whole screen.
- Opacity has to be set on the **window** (`LayoutParams.alpha`), not the vie w. This app sets it on
  the window, so lowering the slider genuinely lowers what the system measures.

**The workaround is max priority mode.** Accessibility overlays are trusted windows and are exempt
from the rule entirely, so a crosshair drawn that way stays solid at 100% and still passes every
touch through. That is the main reason the mode exists.

So:

| Your setup | Safe opacity |
| --- | --- |
| Android 11 or older | Up to 100% |
| Android 12+, max priority mode on | Up to 100% |
| Android 12+, max priority mode off | Up to 80% |

The panel shows a warning under the opacity slider whenever your current combination would get
taps blocked. Nothing is capped — the choice stays yours.

This is about the *crosshair's* opacity, and it is the one place a fade is wanted. Everything
else in the app is fully opaque: the panel, the picker and the controls are solid surfaces, not
tinted glass, so nothing behind them shows through while you tune.

At 100% the crosshair is genuinely solid: the stroke is snapped to whole pixels with a minimum of
two, because an anti-aliased line thinner than that has no fully opaque pixel and looks
see-through no matter what the slider says.

### What no overlay can do

Worth knowing before you file a bug:

- Apps that set `FLAG_SECURE` (banking apps, DRM video) blank out overlays by design.
- The lock screen, the notification shade and some system dialogs sit above app overlays.
- Some games take exclusive control of the display surface, and some anti-cheat systems detect or
  refuse to run alongside overlay apps. Check the rules of anything competitive before using this
  with it.

---

## Adding your own crosshairs

Reticles are drawn from primitives, so there are no image files to ship and nothing to
import. To add one, open `app/src/main/java/com/pure/crosshair/Catalog.kt` and:

1. Add an entry to the `all` list with a unique `id` and a display `name`.
2. Add its extent to the `EXTENTS` map in `CrosshairArt` — the largest coordinate magnitude
   the shape draws, in authored units. This is what normalises footprints so every reticle
   looks the same size at a given setting.
3. Add a branch to the `when (id)` in `CrosshairArt.draw`. Coordinates run from -1 to 1 with
   the origin at the centre, and the aiming point is the origin. Shapes are built from
   `line`, `poly`, `rect`, `dot`, `ring`, `ticks` and `brackets`.

Reticles are scaled so every shape occupies the same fraction of the box, which keeps the
picker grid even. The launcher icon and the floating button icon are the `ic_sigil` drawable,
independent of the catalog, so adding a crosshair never changes the app icon.

## Permissions and why

| Permission | Reason |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draw the crosshair and button over other apps |
| `FOREGROUND_SERVICE` + `..._SPECIAL_USE` | Keep the overlay alive while another app is in front |
| `POST_NOTIFICATIONS` | The control notification. Denying it only costs you that notification |
| `RECEIVE_BOOT_COMPLETED` | Only used if you turn on "Start after reboot" |
| `VIBRATE` | Haptic tick on long press |

## Hiding the floating button

If you keep hitting the button by mistake while playing, turn on **Hide the floating button**
on the main screen. The button window is removed rather than made invisible, so there is
nothing on screen to catch a tap, and the crosshair itself is untouched.

The panel is still reachable two ways:

- **Open the tuning panel** on the main screen.
- The notification, whose **Show button** action brings the button back.

The notification text says the button is hidden, so the state is never a mystery.

Nothing leaves the device. There is no network permission, so the app cannot talk to anything.

## Layout

```
app/src/main/java/com/pure/crosshair/
  MainActivity.kt        Permission, start/stop, options
  OverlayService.kt      Foreground service: floating button, panel, default renderer
  CrosshairLayer.kt      The crosshair window itself, plus the Bridge between renderers
  ControlPanel.kt        The tuning panel
  MaxPriorityService.kt  Optional accessibility renderer
  Catalog.kt             The 35 crosshair shapes, drawn as vectors
  Prefs.kt               Settings
```
