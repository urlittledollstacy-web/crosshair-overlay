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

## Why a thin stroke looks see-through

The crosshair artwork is drawn as anti-aliased **strokes**, not fills. An anti-aliased stroke only
has a fully opaque core once it is at least two pixels wide; thinner than that every pixel is
partially covered, so the line renders at roughly 60-75% alpha and reads as translucent **even
with the opacity slider at 100 and a fully opaque window**. This is why it looked see-through in
every colour, and why filled shapes (dot, square fill, pixel dot) never had the problem.

`strokeWidth` is therefore snapped to whole pixels with a floor of `MIN_STROKE_PX = 2`. Do not
lower that floor, and do not reintroduce a fractional width: a 1.98px stroke centred on a pixel
boundary covers at most 60% of any pixel.

If a reticle ever looks faint again, measure the **maximum alpha** reached, not the mean. A mean
below 255 is fine and expected on an anti-aliased edge; a max below 255 means no part of the
artwork is opaque.

## Opacity

The crosshair's opacity is a feature, set on the **window** (`LayoutParams.alpha`) rather than the
view, because Android 12+ measures the window when deciding whether a click-through overlay is
obscuring the app below. See the README for the 80% rule.

Everything else is opaque. The panel, the dialog and every control fill are solid steps of the ink
ramp, not translucent white. Do not reintroduce alpha fills: a see-through panel over gameplay is
harder to read than a solid one, and it was explicitly disliked.

Contrast is checked against the solid surfaces, not an assumed backdrop. Worst pair is ~5.2:1
(danger on a pill), which clears AA for body text.

## Performance rules

This is an overlay that runs over games, so it has to stay cheap.

- **No allocation in `draw`.** `CrosshairDrawable` owns one `Path` and reuses it. The reticle strip
  draws 35 of them per frame; allocating a `Path` per primitive was the main source of jank.
- **No binder calls on input.** `Context.screenSize()` goes through `WindowManager` and is a binder
  call. `Prefs` caches it in `metrics`; read `prefs.screen`, and call `refreshMetrics()` only on a
  configuration change. Sliders, drags and nudges all used to call it per event.
- **Skip unchanged work.** `CrosshairLayer.update()` compares against the last applied values and
  returns early; `updateNotification()` skips identical posts. A slider tick should not cost a
  window update or a notification.
- **Retint, do not rebuild.** `CrosshairDrawable.setColor()` changes the paint in place, so a colour
  change does not rebuild 35 drawables.
- **One handler per component**, cleared in teardown. The steppers used to create a handler each.

## Conventions

- Design tokens are one ink ramp in `res/values/colors.xml`; no gradients.
- Spacing on a 4px grid; sentence case; no letter spacing.
- Comments explain intent, not mechanics. Keep them short.
