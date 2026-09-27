# Crosshair

A pure crosshair overlay for Android. It draws one of 35 sigils on top of whatever app you are
using, and it never takes a tap — even at 100% opacity.

- **Always click through.** The crosshair window carries `FLAG_NOT_TOUCHABLE`, so touches land on
  the app underneath at every opacity setting. It is a purely visual layer.
- **Floating control button.** A draggable sigil sits on top of everything. Tap it to tune
  position, size and opacity live, without leaving the app you are in.
- **35 bundled crosshairs**, sliced from the source sheet. Swappable for your own PNGs.
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

### What no overlay can do

Worth knowing before you file a bug:

- Apps that set `FLAG_SECURE` (banking apps, DRM video) blank out overlays by design.
- The lock screen, the notification shade and some system dialogs sit above app overlays.
- Some games take exclusive control of the display surface, and some anti-cheat systems detect or
  refuse to run alongside overlay apps. Check the rules of anything competitive before using this
  with it.

---

## Swapping in your own crosshairs

1. Drop transparent PNGs into `app/src/main/res/drawable-nodpi/`. Lowercase names, digits and
   underscores only.
2. Add them to the list in `app/src/main/java/com/pure/crosshair/Catalog.kt`.

Entry 0 does double duty as the launcher icon and the floating button icon, so if you replace it,
regenerate the icons in `app/src/main/res/mipmap-*/` too.

Square images work best. The image centre is the aiming point, so centre your artwork in the
canvas or plan on nudging it with the position sliders.

## Permissions and why

| Permission | Reason |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draw the crosshair and button over other apps |
| `FOREGROUND_SERVICE` + `..._SPECIAL_USE` | Keep the overlay alive while another app is in front |
| `POST_NOTIFICATIONS` | The control notification. Denying it only costs you that notification |
| `RECEIVE_BOOT_COMPLETED` | Only used if you turn on "Start after reboot" |
| `VIBRATE` | Haptic tick on long press |

Nothing leaves the device. There is no network permission, so the app cannot talk to anything.

## Layout

```
app/src/main/java/com/pure/crosshair/
  MainActivity.kt        Permission, start/stop, options
  OverlayService.kt      Foreground service: floating button, panel, default renderer
  CrosshairLayer.kt      The crosshair window itself, plus the Bridge between renderers
  ControlPanel.kt        The tuning panel
  MaxPriorityService.kt  Optional accessibility renderer
  Catalog.kt             The 35 crosshair drawables
  Prefs.kt               Settings
```
