package com.example.stencilcreator

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** At 1× zoom the whole image fills this fraction of the preview. */
private const val FIT_MARGIN = 0.9f
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 8f

/** Touches within this distance (px) of a corner grab the handle instead of panning. */
private const val HANDLE_TOUCH_RADIUS = 60f
private const val HANDLE_RADIUS = 14f

/** Smallest allowed crop edge, in preview px. */
private const val MIN_CROP_SIZE = 20f

/** Vertical space (dp) reserved for the title, hint and buttons around the preview. */
private const val DIALOG_CHROME_HEIGHT_DP = 220

private val PreviewBackground = Color(0xFF1A1A1A)
private val OutsideCropScrim  = Color(0x88000000)
private val HandleColor       = Color(0xFF1976D2)

/**
 * Maps between image pixels and preview-canvas pixels. The image is centred in the
 * canvas, offset by [pan] and drawn at [scale] preview px per image px.
 */
private class CropViewTransform(
    private val canvasSize: Size,
    private val imageSize: Size,
    private val pan: Offset,
    val scale: Float
) {
    private val origin = Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan

    fun imageToCanvas(p: Offset): Offset =
        origin + (p - Offset(imageSize.width / 2f, imageSize.height / 2f)) * scale

    fun canvasToImage(p: Offset): Offset =
        (p - origin) / scale + Offset(imageSize.width / 2f, imageSize.height / 2f)

    val imageTopLeft: Offset get() = imageToCanvas(Offset.Zero)
}

/** Lets the user crop [bitmap] by dragging corner handles, with pinch-zoom and pan. */
@Composable
fun CropDialog(
    bitmap: AndroidBitmap,
    onConfirm: (AndroidBitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val imageSize   = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var cropRect  by remember { mutableStateOf(Rect(Offset.Zero, imageSize)) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoom      by remember { mutableFloatStateOf(1f) }

    // Size the preview to the image's aspect ratio within the available screen space
    val configuration   = LocalConfiguration.current
    val maxWidthDp      = configuration.screenWidthDp * 0.95f
    val maxHeightDp     = (configuration.screenHeightDp - DIALOG_CHROME_HEIGHT_DP).coerceAtLeast(120).toFloat()
    val imageAspect     = imageSize.width / imageSize.height
    val previewWidthDp  = minOf(maxWidthDp, maxHeightDp * imageAspect)
    val previewHeightDp = previewWidthDp / imageAspect

    val previewSize = with(LocalDensity.current) { Size(previewWidthDp.dp.toPx(), previewHeightDp.dp.toPx()) }
    val fitScale = minOf(previewSize.width / imageSize.width, previewSize.height / imageSize.height) * FIT_MARGIN

    fun currentTransform() = CropViewTransform(previewSize, imageSize, panOffset, fitScale * zoom)

    /** Drags crop corner [corner] (0 = TL, 1 = TR, 2 = BR, 3 = BL) until the finger lifts. */
    suspend fun AwaitPointerEventScope.dragCorner(corner: Int) {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
            val transform = currentTransform()
            val target = transform.canvasToImage(change.position).let {
                Offset(it.x.coerceIn(0f, imageSize.width), it.y.coerceIn(0f, imageSize.height))
            }
            cropRect = cropRect.withCornerMovedTo(corner, target, MIN_CROP_SIZE / transform.scale.coerceAtLeast(0.001f))
            change.consume()
        }
    }

    /** One finger pans the preview; two fingers pinch-zoom around their centroid. */
    suspend fun AwaitPointerEventScope.panAndZoom(start: PointerInputChange) {
        var lastPosition = start.position
        var prevDistance = 0f
        var isZooming    = false

        while (true) {
            val pressed = awaitPointerEvent().changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                val p0 = pressed[0].position
                val p1 = pressed[1].position
                val centroid = midpoint(p0, p1)
                val distance = (p1 - p0).getDistance()

                if (isZooming && prevDistance > 0f) {
                    val newZoom = (zoom * distance / prevDistance).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val ratio   = newZoom / zoom
                    // Keep the image point under the centroid fixed while zooming
                    val fromCenter = centroid - Offset(previewSize.width / 2f, previewSize.height / 2f)
                    panOffset = fromCenter - (fromCenter - panOffset) * ratio
                    zoom      = newZoom
                }
                prevDistance = distance
                isZooming    = true
                pressed.forEach { it.consume() }
            } else {
                val position = pressed[0].position
                if (isZooming) lastPosition = position
                isZooming    = false
                prevDistance = 0f
                panOffset   += position - lastPosition
                lastPosition = position
                pressed[0].consume()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier       = Modifier.fillMaxWidth(0.95f).wrapContentHeight(),
            shape          = RoundedCornerShape(16.dp),
            color          = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier            = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Crop Image",
                    style    = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Start)
                )
                Text(
                    "Drag corners to crop. Pinch to zoom, drag to pan.",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                Canvas(
                    modifier = Modifier
                        .width(previewWidthDp.dp)
                        .height(previewHeightDp.dp)
                        .pointerInput(previewSize) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val transform = currentTransform()
                                val nearest = cropRect.corners()
                                    .map { transform.imageToCanvas(it) }
                                    .withIndex()
                                    .minBy { (_, corner) -> (corner - down.position).getDistanceSquared() }

                                val grabbedHandle = (nearest.value - down.position).getDistance() < HANDLE_TOUCH_RADIUS
                                if (grabbedHandle) dragCorner(nearest.index) else panAndZoom(down)
                            }
                        }
                ) {
                    drawCropPreview(imageBitmap, currentTransform(), cropRect)
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onConfirm(bitmap.cropTo(cropRect)) }) { Text("Place") }
                }
            }
        }
    }
}

private fun DrawScope.drawCropPreview(
    image: ImageBitmap,
    transform: CropViewTransform,
    cropRect: Rect
) {
    // Dark background so the image edges are always visible
    drawRect(PreviewBackground)

    drawIntoCanvas { canvas ->
        val topLeft = transform.imageTopLeft
        canvas.save()
        canvas.translate(topLeft.x, topLeft.y)
        canvas.scale(transform.scale, transform.scale)
        canvas.drawImage(image, Offset.Zero, Paint())
        canvas.restore()
    }

    val crop = Rect(transform.imageToCanvas(cropRect.topLeft), transform.imageToCanvas(cropRect.bottomRight))

    // Dim everything outside the crop area
    if (crop.top > 0f) {
        drawRect(OutsideCropScrim, Offset.Zero, Size(size.width, crop.top))
    }
    if (crop.bottom < size.height) {
        drawRect(OutsideCropScrim, Offset(0f, crop.bottom), Size(size.width, size.height - crop.bottom))
    }
    if (crop.left > 0f) {
        drawRect(OutsideCropScrim, Offset(0f, crop.top), Size(crop.left, crop.height))
    }
    if (crop.right < size.width) {
        drawRect(OutsideCropScrim, Offset(crop.right, crop.top), Size(size.width - crop.right, crop.height))
    }

    drawRect(Color.White, topLeft = crop.topLeft, size = crop.size, style = Stroke(2f))

    cropRect.corners().map { transform.imageToCanvas(it) }.forEach { corner ->
        drawCircle(Color.White, HANDLE_RADIUS, center = corner)
        drawCircle(HandleColor, HANDLE_RADIUS - 4f, center = corner)
    }
}

/** Corners in handle order: top-left, top-right, bottom-right, bottom-left. */
private fun Rect.corners(): List<Offset> = listOf(topLeft, topRight, bottomRight, bottomLeft)

/** Moves one corner to [point], keeping the opposite edges fixed and at least [minSize] apart. */
private fun Rect.withCornerMovedTo(corner: Int, point: Offset, minSize: Float): Rect {
    val newLeft   = point.x.coerceAtMost(right - minSize)
    val newRight  = point.x.coerceAtLeast(left + minSize)
    val newTop    = point.y.coerceAtMost(bottom - minSize)
    val newBottom = point.y.coerceAtLeast(top + minSize)
    return when (corner) {
        0    -> Rect(newLeft, newTop, right, bottom)
        1    -> Rect(left, newTop, newRight, bottom)
        2    -> Rect(left, top, newRight, newBottom)
        3    -> Rect(newLeft, top, right, newBottom)
        else -> this
    }
}

private fun AndroidBitmap.cropTo(rect: Rect): AndroidBitmap {
    val x = rect.left.toInt().coerceIn(0, width - 1)
    val y = rect.top.toInt().coerceIn(0, height - 1)
    val w = rect.width.toInt().coerceIn(1, width - x)
    val h = rect.height.toInt().coerceIn(1, height - y)
    return AndroidBitmap.createBitmap(this, x, y, w, h)
}
