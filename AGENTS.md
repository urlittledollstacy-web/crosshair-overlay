# AGENTS.md

Android app: a click-through crosshair overlay. Kotlin, XML layouts, no Compose.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew --no-daemon assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. The repo also has a
`Build APK` GitHub Actions workflow that runs on push.

## Layout

```
app/src/main/java/com/pure/crosshair/
  Catalog.kt            35 reticles drawn from primitives + CrosshairDrawable
  CrosshairLayer.kt     the crosshair window (both renderers)
  ControlPanel.kt       the floating tuning panel
  OverlayService.kt     foreground service owning the overlay + panel
  MaxPriorityService.kt accessibility-service renderer, one layer higher
  MainActivity.kt       main screen
  Prefs.kt              SharedPreferences
```

## Crosshair art

Reticles are **drawn, not imported** — there are no crosshair image assets and
no file picker. Everything lives in `Catalog.kt`.

Adding one means three edits, all in `Catalog.kt`:

1. an entry in the `CrosshairCatalog.all` list (`id` must be unique),
2. its extent in `CrosshairArt.EXTENTS`,
3. a branch in the `when (id)` inside `CrosshairArt.draw`.

Coordinates run -1..1 with the origin at the centre; the origin is the aiming
point. Primitives: `line`, `poly`, `rect`, `dot`, `ring`, `ticks`, `brackets`.

**The extent entry is not optional.** `CrosshairDrawable` rescales every shape by
`TARGET_EXTENT / extentOf(id)` so all reticles occupy the same footprint. Without
it a 4dp dot and a scope would render at wildly different visual sizes for the
same size setting. The value is the largest coordinate magnitude the shape draws,
so it is also a check that nothing escapes the authored square.

Two traps worth remembering, both hit during the initial build:

- A filled `poly` that overlaps itself renders as a bowtie, not the intended
  shape. Overlapping arms want two stroked `line` calls instead.
- Extents must include the radius and tick length, not just the centre offset
  (`abs(offset) + radius`, `offset + length`).

To verify art changes, rasterise the catalog rather than eyeballing the source:
parse the `when (id)` branches and replay the draw calls, then check each tile for
ink coverage, bounding box and edge clearance. That is what caught the bowtie and
the two wrong extents.

## Panel

`ControlPanel` is a window, not an activity, so the app underneath stays visible while you
line the crosshair up. `overlay_panel.xml` backs it.

- The reticles are **one scrolling strip**, not a grid. A grid of all 35 tiles was 392dp
  tall, taller than every other control together, so the sliders fell below the fold.
- The header mark is the selected reticle, drawn live in the selected colour. Keep it that
  way: it is the panel's one moment of showing the product rather than describing it.
- Vertical rhythm groups by section: 4 between a label and its slider, 12 to a new control,
  20 to a new section. Keep space inside a group smaller than space between groups.
- The panel is titled `Overlay`, because `Crosshair` repeated the label right below it.

## Hiding the floating button

`prefs.buttonHidden` removes the button window outright rather than making it transparent, so
nothing on screen can catch a stray tap. It is the only floating way into the panel, so the
escape hatches must keep working: `OverlayService.openPanel()` from the main screen, and the
notification action that toggles it back. The notification text names the hidden state.

## Conventions

- Design tokens are one ink ramp in `res/values/colors.xml`; no gradients.
- Spacing on a 4px grid; sentence case; no letter spacing.
- Comments explain intent, not mechanics. Keep them short.
