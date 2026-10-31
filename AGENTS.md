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

## Window ranking, and the panel

`TYPE_ACCESSIBILITY_OVERLAY` is **2032** and `TYPE_APPLICATION_OVERLAY` is **2038**, and the lower
number sits on top. That ordering is the entire reason max priority mode exists: the accessibility
crosshair keeps its place above other floating apps.

It also means the crosshair outranks **our own** panel, which is a `TYPE_APPLICATION_OVERLAY`. So in
max priority mode the crosshair was painted over the panel and hid the reticle strip and the
controls. In normal mode both windows share a type, and the panel wins only because it is added
later, so the normal renderer stays visible behind the panel while you tune.

The accessibility renderer therefore hides itself while the panel is open
(`CrosshairLayer.applySuppression`), keyed off `Bridge.suppressed`. Move mode overrides it, because
dragging the crosshair with the panel open is the point of that mode.

Put this kind of state on `Bridge`, not on a service. Layers are created lazily — switching max
priority on builds a brand new one — so anything a fresh layer needs to know has to outlive the
service that created it.

**Any new window the app adds must be ranked against these two.** A new panel or dialog built as a
`TYPE_APPLICATION_OVERLAY` will be covered by the crosshair in max priority mode, the same way the
old panel was.

## Reticle tiles

Build every reticle tile with `Crosshair.thumbnailTile`. Do not hand-roll an `ImageView` for one.

The picker dialog and the panel strip used to each build their own, and the dialog's copy never
called `setImageDrawable`, so the whole grid rendered as empty boxes. It looked plausible rather
than broken: padding, `scaleType` and a background were all set, and `tintTiles` casts the drawable
with `as?`, so a null one fails silently and nothing crashes. The factory sets the artwork, so a new
surface cannot forget it.

## Per-app auto-show

`Prefs.shouldShowNow(foreground)` is the only place that decides whether the crosshair belongs
on screen. Both renderers call it, so the normal overlay and the accessibility one can never
disagree. Do not re-derive the rule from `prefs.visible` at a call site.

It deliberately returns true for an empty target list and for an unknown foreground. A crosshair
that fails to appear is worse than one that appears when it was not wanted, and the user can turn
the feature off. Hiding on an unknown foreground would strand the crosshair permanently the first
time usage access was missing or an OEM returned nothing.

`AppWatcher` polls UsageStats on its own thread, because the query is a binder call and running
it on the main thread would jank the overlay it exists to drive. It is gated on
`prefs.autoShowEnabled`, so it costs nothing for anyone not using the feature. The foreground
package goes on `Bridge`, so both renderers resolve one poll rather than running two.

## OLED

The page background is `#000000` on purpose, so those pixels are off. Surfaces step up in small
increments from there. Keep new colors on the ramp in `colors.xml`: no surface is translucent, and
nothing is a hardcoded hex outside that file.

## Centring a tile

A thumbnail is centred on its own artwork, not on the origin. Thirty of the shapes are built
around (0,0), so the two are the same; the five in `CrosshairArt.ART_CENTRE` are not. They are
authored with the aiming feature at the origin, which is right for the overlay and wrong for a
tile, where a shape sitting 3-10% off centre just looks like a mistake. `CrosshairDrawable`
applies the shift only when `thumbnail` is true, so aiming never moves.

Size the picker's tiles from the grid's measured width, not the screen width. The dialog is
narrower than the screen, and a cell derived from screen width leaves the grid short of it, with
`GridLayout` parking all the slack on the right. That reads as the whole grid shifted left, which
is easy to mistake for the icons being off centre one by one.

## Thumbnails versus the aiming reticle

`CrosshairDrawable` takes a `thumbnail` flag, and it is not cosmetic. The two contexts want opposite
things:

- **Aiming reticle** (`thumbnail = false`): must be fully opaque at 100%, so anti-aliasing is off
  there. Crisp pixels are the point.
- **Thumbnail** (`thumbnail = true`, used by the panel strip, the header mark and the app preview):
  always smooths. At the 32dp tile size crisp rendering drops 16% of the ink overall and up to a
  third on the thin shapes (brackets, gaps, squares), which makes them ragged or near invisible. A
  tile is read, not aimed over.

Turning AA off for everything was the bug that made the menu icons look broken. Keep the flag on any
new menu or preview call site.

## Anti-aliasing is the transparency

The crosshair is stroked line art, and **anti-aliasing is literally partial alpha**: an edge pixel
is a blend of the crosshair colour and whatever is behind it. On a two pixel line the edge is most
of the line, so at 100% opacity only about 13% of the drawn pixels were the pure colour and the
reticle read as see-through in every colour.

`CrosshairDrawable` therefore turns anti-aliasing **off** when `alphaValue == 255`, and back on
below that. At full opacity every drawn pixel is the pure colour; below it the fade is the feature
and smoothing keeps the rings' curves clean. `isAntiAlias` is set per paint in `draw()`, so the
overlay, the picker tiles and the header mark all follow the slider.

The stroke is also snapped to whole pixels with a floor of `MIN_STROKE_PX = 2`, so a crisp stroke
has no ragged half-pixel edge.

When checking this, measure the **share of drawn pixels that are exactly the colour**, not the
maximum alpha reached. A max of 255 only proves *one* pixel is opaque; a reticle can be 13% solid
and still have a 255 somewhere. The 2px floor alone was not enough and the first fix was wrong for
exactly that reason.

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
