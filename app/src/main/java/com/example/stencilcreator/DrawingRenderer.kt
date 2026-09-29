package com.example.stencilcreator

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Inner-to-outer radius ratio that gives a regular five-pointed star. */
private const val STAR_INNER_RADIUS_RATIO = 0.382f

/** Shapes smaller than this (world px) on both axes are too small to draw. */
private const val MIN_SHAPE_EXTENT = 2f

/** Images whose half-extent falls below this (world px) are skipped. */
private const val MIN_IMAGE_HALF_EXTENT = 1f

fun starPath(center: Offset, outerRadius: Float, innerRadius: Float): Path {
    val path = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angle  = (i * PI / 5.0 - PI / 2.0).toFloat()
        val x = center.x + radius * cos(angle)
        val y = center.y + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Creates the paint for a stroke or shape; eraser paint clears pixels instead of drawing colour. */
fun makePaint(strokeWidth: Float, isEraser: Boolean, fill: Boolean, color: Color): Paint = Paint().apply {
    style = if (fill) PaintingStyle.Fill else PaintingStyle.Stroke
    this.strokeWidth = strokeWidth
    strokeCap  = StrokeCap.Round
    strokeJoin = StrokeJoin.Round
    if (isEraser) blendMode = BlendMode.Clear else this.color = color
}

fun renderShape(
    canvas: Canvas,
    shape: DrawShape,
    start: Offset,
    end: Offset,
    paint: Paint,
    rotation: Float = 0f
) {
    if (abs(end.x - start.x) < MIN_SHAPE_EXTENT && abs(end.y - start.y) < MIN_SHAPE_EXTENT) return

    val box = OrientedBox.fromDiagonal(start, end, rotation)
    val radius = minOf(box.halfWidth, box.halfHeight)

    when (shape) {
        DrawShape.CIRCLE -> canvas.drawCircle(box.center, radius, paint)
        DrawShape.SQUARE -> canvas.withBoxTransform(box) {
            drawRect(Rect(-box.halfWidth, -box.halfHeight, box.halfWidth, box.halfHeight), paint)
        }
        DrawShape.STAR -> canvas.withBoxTransform(box) {
            drawPath(starPath(Offset.Zero, radius, radius * STAR_INNER_RADIUS_RATIO), paint)
        }
    }
}

fun renderImage(canvas: Canvas, image: DrawElement.Image) {
    val box = OrientedBox.fromDiagonal(image.start, image.end, image.rotation)
    if (box.halfWidth < MIN_IMAGE_HALF_EXTENT || box.halfHeight < MIN_IMAGE_HALF_EXTENT) return

    val bitmap = image.bitmap
    canvas.withBoxTransform(box) {
        // Scale from the bitmap's native pixel size to the box's world-space size
        scale(box.halfWidth * 2f / bitmap.width, box.halfHeight * 2f / bitmap.height)
        drawImage(bitmap, Offset(-bitmap.width / 2f, -bitmap.height / 2f), Paint())
    }
}

fun drawElement(canvas: Canvas, element: DrawElement) {
    when (element) {
        is DrawElement.FreeStroke -> {
            if (element.points.size >= 2) {
                val paint = makePaint(element.strokeWidth, element.isEraser, fill = false, element.color)
                canvas.drawPath(polylinePath(element.points), paint)
            }
        }
        is DrawElement.Shape -> {
            // Eraser shapes are filled so they clear their whole area
            val paint = makePaint(element.strokeWidth, element.isEraser, fill = element.isEraser, element.color)
            renderShape(canvas, element.shape, element.start, element.end, paint, element.rotation)
        }
        is DrawElement.Image -> renderImage(canvas, element)
    }
}

/**
 * Renders the page (world rect 0,0 → page px) at the given [scale]. Anything drawn off the
 * page is cropped, and editor overlays (live strokes, selection) are not included.
 */
fun renderDesignToBitmap(layers: List<Layer>, page: PageSize, scale: Float = 1f): AndroidBitmap {
    val width  = (page.widthPx  * scale).roundToInt().coerceAtLeast(1)
    val height = (page.heightPx * scale).roundToInt().coerceAtLeast(1)
    val bitmap = AndroidBitmap.createBitmap(width, height, AndroidBitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap.asImageBitmap())
    val bounds = Rect(Offset.Zero, Size(width.toFloat(), height.toFloat()))

    layers.firstOrNull { it.isBackground }?.backgroundColor?.let { background ->
        canvas.drawRect(bounds, Paint().apply { color = background })
    }

    for (layer in layers) {
        canvas.saveLayer(bounds, Paint().apply { alpha = layer.opacity })
        canvas.scale(scale, scale)
        layer.elements.forEach { drawElement(canvas, it) }
        canvas.restore()
    }
    return bitmap
}

/** Runs [block] with the origin at the box centre and axes aligned to the box. */
private inline fun Canvas.withBoxTransform(box: OrientedBox, block: Canvas.() -> Unit) {
    save()
    translate(box.center.x, box.center.y)
    rotate(-box.rotation)
    block()
    restore()
}
