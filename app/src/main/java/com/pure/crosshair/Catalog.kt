package com.pure.crosshair

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * One crosshair in the built-in set.
 *
 * Every crosshair is drawn from vector primitives at the size it is displayed at, so the same
 * artwork stays sharp from a 4dp aiming dot up to a full screen reticle, and there are no image
 * assets to ship, decode or scale.
 */
data class Crosshair(
    val id: String,
    val name: String,
    val group: String
) {
    /**
     * A drawable for this reticle.
     *
     * [thumbnail] picks the rendering intent. A thumbnail is read at 32dp in the picker, where
     * crisp pixels cost a third of the ink and thin shapes turn to dust, so it always smooths.
     * The aiming reticle is the opposite: it must be solid at 100% opacity.
     */
    fun drawable(color: Int, thumbnail: Boolean = false): Drawable =
        CrosshairDrawable(this, color, thumbnail)
}

/** The built-in crosshairs, in the order the picker shows them. */
object CrosshairCatalog {

    val all: List<Crosshair> = listOf(
        Crosshair("dot", "Dot", "Dot"),
        Crosshair("ring", "Ring", "Dot"),
        Crosshair("ring_dot", "Ring dot", "Dot"),
        Crosshair("double_ring", "Double ring", "Dot"),
        Crosshair("hollow_dot", "Hollow", "Dot"),
        Crosshair("square", "Square", "Dot"),
        Crosshair("square_fill", "Square fill", "Dot"),
        Crosshair("diamond", "Diamond", "Dot"),
        Crosshair("diamond_line", "Diamond line", "Dot"),
        Crosshair("hex", "Hex", "Dot"),
        Crosshair("target", "Target", "Dot"),

        Crosshair("cross", "Cross", "Cross"),
        Crosshair("cross_gap", "Cross gap", "Cross"),
        Crosshair("tick", "Tick", "Cross"),
        Crosshair("x_cross", "X", "Cross"),
        Crosshair("x_gap", "X gap", "Cross"),
        Crosshair("mil_cross", "Mil cross", "Cross"),
        Crosshair("mil_dot", "Mil dot", "Cross"),
        Crosshair("scope", "Scope", "Cross"),
        Crosshair("scope_gap", "Scope gap", "Cross"),
        Crosshair("ring_cross", "Ring cross", "Cross"),
        Crosshair("four_dots", "Four dots", "Cross"),

        Crosshair("brackets", "Brackets", "Brackets"),
        Crosshair("brackets_gap", "Brackets open", "Brackets"),
        Crosshair("chevron", "Chevron", "Brackets"),
        Crosshair("triangle", "Triangle", "Brackets"),
        Crosshair("caret", "Caret", "Brackets"),
        Crosshair("t_dot", "T dot", "Brackets"),
        Crosshair("bar_dot", "Bar dot", "Brackets"),
        Crosshair("corner_dots", "Corner dots", "Brackets"),
        Crosshair("arrow", "Arrow", "Brackets"),

        Crosshair("pixel_cross", "Pixel cross", "Pixel"),
        Crosshair("pixel_gap", "Pixel gap", "Pixel"),
        Crosshair("pixel_dot", "Pixel dot", "Pixel"),
        Crosshair("pixel_brackets", "Pixel corners", "Pixel")
    )

    val default: Crosshair = all.first()

    /**
     * Indexed by id. [byId] is read on every frame of a drag and on every settings read, and a
     * linear scan over the catalog showed up in that path.
     */
    private val byId: Map<String, Crosshair> = all.associateBy { it.id }

    fun byId(id: String?): Crosshair? = if (id == null) null else byId[id]

    fun name(id: String?): String = byId(id)?.name ?: default.name

    fun isValid(id: String?): Boolean = id != null && byId.containsKey(id)
}

/**
 * Colours a crosshair can be drawn in.
 *
 * Chosen to stay legible over gameplay: every one of them holds up against a mid-tone scene,
 * which is why there is no mid grey in the list.
 */
object CrosshairColors {

    val all: List<Pair<String, Int>> = listOf(
        "White" to 0xFFFFFFFF.toInt(),
        "Mint" to 0xFF7CE0B0.toInt(),
        "Ice" to 0xFF8FD9FF.toInt(),
        "Violet" to 0xFFC6A9F2.toInt(),
        "Amber" to 0xFFFFC46B.toInt(),
        "Coral" to 0xFFFF8A8A.toInt(),
        "Lime" to 0xFFB9E86B.toInt(),
        "Black" to 0xFF0E0E12.toInt()
    )

    val default: Int = 0xFFFFFFFF.toInt()

    fun name(color: Int): String =
        all.firstOrNull { it.second == color }?.first ?: all.first().first
}

/**
 * Draws one catalog crosshair.
 *
 * Art is authored in a normalised square from -1 to 1, then scaled to whatever bounds the view
 * gives it. Stroke width is a ratio of the size, so a crosshair keeps its proportions at every
 * scale instead of turning into a blob when it is blown up.
 */
class CrosshairDrawable(
    private val model: Crosshair,
    color: Int,
    private val thumbnail: Boolean = false
) : Drawable() {

    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private val fill = Paint().apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /**
     * One Path for the lifetime of this drawable.
     *
     * Every primitive used to build a fresh Path, and the picker strip draws 35 of these per
     * frame while scrolling, so the allocator was doing a few hundred short-lived Paths a
     * frame. Reusing one is safe because drawing happens on the main thread only.
     */
    private val path = Path()

    private var alphaValue = 255

    /** Retints in place, so a colour change does not rebuild every tile's drawable. */
    fun setColor(color: Int) {
        if (stroke.color == color) return
        stroke.color = color
        fill.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return

        stroke.alpha = alphaValue
        fill.alpha = alphaValue

        // Anti-aliasing is the transparency. An anti-aliased edge pixel is a blend of the
        // crosshair colour and whatever is behind it, which is literally partial alpha, and on
        // a two pixel line the "edge" is most of the line: measured, only 13% of a small
        // reticle reached full opacity, so it read as see-through at every setting.
        //
        // So the aiming reticle turns smoothing off at 100% and every drawn pixel is the pure
        // colour. Below 100% the fade is the feature, so smoothing comes back to keep the
        // curve of the rings clean.
        //
        // Thumbnails are the opposite trade. At 32dp crisp rendering drops 13-33% of the ink,
        // which turns the thin shapes (brackets, gaps, squares) ragged or near invisible, and
        // a picker tile is read rather than aimed over. They always smooth.
        val smoothing = thumbnail || alphaValue != 255
        stroke.isAntiAlias = smoothing
        fill.isAntiAlias = smoothing

        // Stroke width is snapped to whole pixels, with a floor of two, so a crisp stroke has
        // no ragged half-pixel edge and the line is always thick enough to be visible.
        stroke.strokeWidth = (size * STROKE_RATIO).roundToInt()
            .coerceAtLeast(MIN_STROKE_PX)
            .toFloat()

        // Art is authored at whatever extent reads best for each shape, so the unit is
        // rescaled per reticle. Without this a dot would draw at a fifth the footprint of a
        // scope at the same size setting, and the picker grid would look uneven.
        val unit = size * 0.5f * INNER * (TARGET_EXTENT / CrosshairArt.extentOf(model.id))
        CrosshairArt.draw(
            canvas, model.id, stroke, fill, path, unit, b.exactCenterX(), b.exactCenterY()
        )
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Stroke width as a fraction of the crosshair's edge length. */
        private const val STROKE_RATIO = 0.045f

        /**
         * Narrowest stroke, in pixels. Two is the smallest width that still has a fully
         * covered pixel, so anything thinner would be permanently translucent.
         */
        private const val MIN_STROKE_PX = 2

        /** Art is authored within -1..1, pulled in slightly so round caps stay inside bounds. */
        private const val INNER = 0.92f

        /** Every reticle is scaled to occupy this fraction of the box, so size is comparable. */
        private const val TARGET_EXTENT = 0.70f
    }
}

/** The geometry. Every shape is built from lines, polygons, circles and arcs. */
private object CrosshairArt {

    /**
     * How far each reticle reaches from its centre, in authored units.
     *
     * Used to normalise footprints. Values are the largest coordinate magnitude each shape
     * draws, so they double as a check that nothing escapes the authored square.
     */
    private val EXTENTS = mapOf(
        "dot" to 0.16f,
        "ring" to 0.28f,
        "ring_dot" to 0.34f,
        "double_ring" to 0.44f,
        "hollow_dot" to 0.40f,
        "square" to 0.40f,
        "square_fill" to 0.22f,
        "diamond" to 0.55f,
        "diamond_line" to 0.55f,
        "hex" to 0.55f,
        "target" to 0.55f,
        "cross" to 0.60f,
        "cross_gap" to 0.70f,
        "tick" to 0.38f,
        "x_cross" to 0.55f,
        "x_gap" to 0.65f,
        "mil_cross" to 0.58f,
        "mil_dot" to 0.42f,
        "scope" to 0.78f,
        "scope_gap" to 0.72f,
        "ring_cross" to 0.62f,
        "four_dots" to 0.55f,
        "brackets" to 0.50f,
        "brackets_gap" to 0.50f,
        "chevron" to 0.50f,
        "triangle" to 0.55f,
        "caret" to 0.42f,
        "t_dot" to 0.52f,
        "bar_dot" to 0.52f,
        "corner_dots" to 0.53f,
        "arrow" to 0.55f,
        "pixel_cross" to 0.60f,
        "pixel_gap" to 0.60f,
        "pixel_dot" to 0.22f,
        "pixel_brackets" to 0.56f
    )

    fun extentOf(id: String): Float = EXTENTS[id] ?: 0.70f

    fun draw(
        canvas: Canvas,
        id: String,
        stroke: Paint,
        fill: Paint,
        path: Path,
        u: Float,
        cx: Float,
        cy: Float
    ) {
        fun x(v: Float) = cx + v * u
        fun y(v: Float) = cy + v * u

        /** Stroked polyline through flat (x, y) pairs. */
        fun line(vararg pts: Float) {
            path.reset()
            path.moveTo(x(pts[0]), y(pts[1]))
            var i = 2
            while (i < pts.size) {
                path.lineTo(x(pts[i]), y(pts[i + 1]))
                i += 2
            }
            canvas.drawPath(path, stroke)
        }

        /** Filled polygon through flat (x, y) pairs. */
        fun poly(vararg pts: Float) {
            path.reset()
            path.moveTo(x(pts[0]), y(pts[1]))
            var i = 2
            while (i < pts.size) {
                path.lineTo(x(pts[i]), y(pts[i + 1]))
                i += 2
            }
            path.close()
            canvas.drawPath(path, fill)
        }

        /** Filled axis aligned rectangle. */
        fun rect(left: Float, top: Float, right: Float, bottom: Float) =
            poly(left, top, right, top, right, bottom, left, bottom)

        fun dot(px: Float, py: Float, r: Float) = canvas.drawCircle(x(px), y(py), r * u, fill)

        fun ring(px: Float, py: Float, r: Float) = canvas.drawCircle(x(px), y(py), r * u, stroke)

        /** Four short ticks on the axes, used by the mil and scope reticles. */
        fun ticks(offset: Float, length: Float) {
            line(offset, -length, offset, length)
            line(-offset, -length, -offset, length)
            line(-length, offset, length, offset)
            line(-length, -offset, length, -offset)
        }

        /** The four corner brackets shared by the bracket and pixel shapes. */
        fun brackets(outer: Float, inner: Float) {
            line(-outer, -outer, -inner, -outer)
            line(-outer, -outer, -outer, -inner)
            line(outer, -outer, inner, -outer)
            line(outer, -outer, outer, -inner)
            line(-outer, outer, -inner, outer)
            line(-outer, outer, -outer, inner)
            line(outer, outer, inner, outer)
            line(outer, outer, outer, inner)
        }

        when (id) {
            "dot" -> dot(0f, 0f, 0.16f)

            "ring" -> ring(0f, 0f, 0.28f)

            "ring_dot" -> {
                ring(0f, 0f, 0.34f)
                dot(0f, 0f, 0.10f)
            }

            "double_ring" -> {
                ring(0f, 0f, 0.44f)
                ring(0f, 0f, 0.24f)
                dot(0f, 0f, 0.08f)
            }

            "hollow_dot" -> {
                ring(0f, 0f, 0.30f)
                ring(0f, 0f, 0.40f)
            }

            "square" -> {
                line(-0.40f, -0.40f, 0.40f, -0.40f, 0.40f, 0.40f, -0.40f, 0.40f, -0.40f, -0.40f)
                dot(0f, 0f, 0.08f)
            }

            "square_fill" -> rect(-0.22f, -0.22f, 0.22f, 0.22f)

            "diamond" -> poly(0f, -0.55f, 0.48f, 0f, 0f, 0.55f, -0.48f, 0f)

            "diamond_line" -> {
                line(0f, -0.55f, 0.48f, 0f, 0f, 0.55f, -0.48f, 0f, 0f, -0.55f)
                dot(0f, 0f, 0.09f)
            }

            "hex" -> line(
                0f, -0.55f,
                -0.476f, -0.275f,
                -0.476f, 0.275f,
                0f, 0.55f,
                0.476f, 0.275f,
                0.476f, -0.275f,
                0f, -0.55f
            )

            "target" -> {
                ring(0f, 0f, 0.55f)
                ring(0f, 0f, 0.34f)
                dot(0f, 0f, 0.12f)
            }

            "cross" -> {
                line(-0.60f, 0f, 0.60f, 0f)
                line(0f, -0.60f, 0f, 0.60f)
                dot(0f, 0f, 0.07f)
            }

            "cross_gap" -> {
                line(-0.70f, 0f, -0.18f, 0f)
                line(0.18f, 0f, 0.70f, 0f)
                line(0f, -0.70f, 0f, -0.18f)
                line(0f, 0.18f, 0f, 0.70f)
                dot(0f, 0f, 0.06f)
            }

            "tick" -> {
                line(-0.38f, 0f, -0.12f, 0f)
                line(0.12f, 0f, 0.38f, 0f)
                line(0f, -0.38f, 0f, -0.12f)
                line(0f, 0.12f, 0f, 0.38f)
                dot(0f, 0f, 0.07f)
            }

            "x_cross" -> {
                line(-0.55f, -0.55f, 0.55f, 0.55f)
                line(0.55f, -0.55f, -0.55f, 0.55f)
                dot(0f, 0f, 0.07f)
            }

            "x_gap" -> {
                line(-0.65f, -0.65f, -0.18f, -0.18f)
                line(0.18f, 0.18f, 0.65f, 0.65f)
                line(0.65f, -0.65f, 0.18f, -0.18f)
                line(-0.18f, 0.18f, -0.65f, 0.65f)
                dot(0f, 0f, 0.06f)
            }

            "mil_cross" -> {
                line(-0.58f, 0f, 0.58f, 0f)
                line(0f, -0.58f, 0f, 0.58f)
                ticks(0.34f, 0.07f)
                dot(0f, 0f, 0.06f)
            }

            "mil_dot" -> {
                ticks(0.34f, 0.08f)
                dot(0f, 0f, 0.10f)
            }

            "scope" -> {
                ring(0f, 0f, 0.52f)
                line(-0.78f, 0f, 0.78f, 0f)
                line(0f, -0.78f, 0f, 0.78f)
                dot(0f, 0f, 0.07f)
            }

            "scope_gap" -> {
                ring(0f, 0f, 0.46f)
                line(-0.72f, 0f, -0.22f, 0f)
                line(0.22f, 0f, 0.72f, 0f)
                line(0f, -0.72f, 0f, -0.22f)
                line(0f, 0.22f, 0f, 0.72f)
                dot(0f, 0f, 0.06f)
            }

            "ring_cross" -> {
                ring(0f, 0f, 0.40f)
                line(-0.62f, 0f, 0.62f, 0f)
                line(0f, -0.62f, 0f, 0.62f)
                dot(0f, 0f, 0.06f)
            }

            "four_dots" -> {
                dot(0f, -0.45f, 0.10f)
                dot(0f, 0.45f, 0.10f)
                dot(-0.45f, 0f, 0.10f)
                dot(0.45f, 0f, 0.10f)
                dot(0f, 0f, 0.09f)
            }

            "arrow" -> {
                line(0f, 0.55f, 0f, -0.15f)
                line(-0.32f, -0.02f, 0f, -0.42f, 0.32f, -0.02f)
            }

            "brackets" -> {
                brackets(0.50f, 0.22f)
                dot(0f, 0f, 0.08f)
            }

            "brackets_gap" -> brackets(0.50f, 0.22f)

            // Two nested V strokes. Drawn stroked rather than filled: as a filled polygon the
            // two arms overlap and the shape reads as a bowtie instead of a chevron.
            "chevron" -> {
                line(-0.50f, 0.14f, 0f, -0.46f, 0.50f, 0.14f)
                line(-0.50f, 0.46f, 0f, -0.14f, 0.50f, 0.46f)
            }

            "triangle" -> {
                poly(0f, -0.55f, 0.50f, 0.40f, -0.50f, 0.40f)
                dot(0f, 0.10f, 0.08f)
            }

            "caret" -> poly(0f, -0.42f, 0.38f, 0.18f, -0.38f, 0.18f)

            "t_dot" -> {
                line(-0.52f, -0.35f, 0.52f, -0.35f)
                line(0f, -0.35f, 0f, 0.52f)
                dot(0f, -0.35f, 0.08f)
            }

            "bar_dot" -> {
                rect(-0.52f, -0.07f, 0.52f, 0.07f)
                dot(0f, -0.30f, 0.09f)
            }

            "corner_dots" -> {
                dot(0f, 0f, 0.13f)
                dot(-0.46f, -0.46f, 0.07f)
                dot(0.46f, -0.46f, 0.07f)
                dot(-0.46f, 0.46f, 0.07f)
                dot(0.46f, 0.46f, 0.07f)
            }

            "pixel_cross" -> {
                rect(-0.12f, -0.60f, 0.12f, 0.60f)
                rect(-0.60f, -0.12f, 0.60f, 0.12f)
            }

            "pixel_gap" -> {
                rect(-0.12f, -0.60f, 0.12f, -0.22f)
                rect(-0.12f, 0.22f, 0.12f, 0.60f)
                rect(-0.60f, -0.12f, -0.22f, 0.12f)
                rect(0.22f, -0.12f, 0.60f, 0.12f)
            }

            "pixel_dot" -> rect(-0.22f, -0.22f, 0.22f, 0.22f)

            "pixel_brackets" -> {
                rect(-0.56f, -0.56f, -0.30f, -0.30f)
                rect(0.30f, -0.56f, 0.56f, -0.30f)
                rect(-0.56f, 0.30f, -0.30f, 0.56f)
                rect(0.30f, 0.30f, 0.56f, 0.56f)
            }

            else -> {
                line(-0.60f, 0f, 0.60f, 0f)
                line(0f, -0.60f, 0f, 0.60f)
                dot(0f, 0f, 0.07f)
            }
        }
    }
}
