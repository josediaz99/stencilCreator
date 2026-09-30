package com.example.stencilcreator

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts a 0..100 [FILL_SENSITIVITY_RANGE] value into a per-channel colour tolerance
 * (0..255). 0 only matches pixels that are (near) identical to the fill target - safe against
 * leaking through any visible line, but liable to leave a gap at an anti-aliased edge. 100
 * matches almost anything, closing even a soft, wide anti-aliasing halo, but is more likely to
 * bleed through a thin or low-contrast line entirely. The default sits low enough to leave
 * clean, deliberate lines alone while still closing typical anti-aliasing gaps.
 */
private fun toleranceFor(sensitivity: Float): Int =
    (sensitivity / FILL_SENSITIVITY_RANGE.endInclusive * 255f).roundToInt().coerceIn(0, 255)

/**
 * Paint-bucket fill at [worldPoint]: flood-fills the contiguous region of matching colour in
 * the current composite that contains that point, using 4-connectivity so it stops at any
 * drawn boundary (stroke, shape or image edge). [sensitivity] (see [FILL_SENSITIVITY_RANGE])
 * controls how much a pixel's colour may drift from the fill target and still count as part of
 * the region - raise it to bridge the anti-aliased halo around a thin or soft line so the fill
 * sits flush against it with no visible gap; lower it for crisp, high-contrast lines where a
 * wide margin isn't needed and could otherwise bleed through a nearby similar colour.
 *
 * Returns the filled region as a new element, or null when there is no bounded area to fill
 * there: [worldPoint] falls outside the page, or the matching region reaches the page edge
 * (meaning it isn't enclosed by anything within the canvas).
 */
fun floodFillAt(state: EditorState, worldPoint: Offset, fillColor: Color, sensitivity: Float): DrawElement.Fill? {
    val tolerance = toleranceFor(sensitivity)
    val width  = state.page.widthPx
    val height = state.page.heightPx
    val startX = worldPoint.x.toInt()
    val startY = worldPoint.y.toInt()
    if (startX !in 0 until width || startY !in 0 until height) return null

    val composite = renderDesignToBitmap(state.layers, state.page, scale = 1f)
    val pixels = IntArray(width * height)
    composite.getPixels(pixels, 0, width, 0, 0, width, height)

    val targetColor = pixels[startY * width + startX]
    val visited = BooleanArray(width * height)
    val stack = ArrayDeque<Int>()
    val startIndex = startY * width + startX
    stack.addLast(startIndex)
    visited[startIndex] = true

    var minX = startX; var maxX = startX
    var minY = startY; var maxY = startY
    var touchesEdge = false

    while (stack.isNotEmpty()) {
        val index = stack.removeLast()
        val x = index % width
        val y = index / width

        if (x == 0 || x == width - 1 || y == 0 || y == height - 1) touchesEdge = true
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y

        fun visit(nx: Int, ny: Int) {
            if (nx !in 0 until width || ny !in 0 until height) return
            val neighborIndex = ny * width + nx
            if (visited[neighborIndex]) return
            if (!colorsMatch(pixels[neighborIndex], targetColor, tolerance)) return
            visited[neighborIndex] = true
            stack.addLast(neighborIndex)
        }
        visit(x - 1, y)
        visit(x + 1, y)
        visit(x, y - 1)
        visit(x, y + 1)
    }

    // The matched region ran into the edge of the page, so it isn't a bounded area - leave
    // the canvas untouched rather than flooding an open region.
    if (touchesEdge) return null

    val boxWidth  = maxX - minX + 1
    val boxHeight = maxY - minY + 1
    val fillArgb  = fillColor.toArgb()
    val outPixels = IntArray(boxWidth * boxHeight)
    for (y in 0 until boxHeight) {
        for (x in 0 until boxWidth) {
            val globalIndex = (minY + y) * width + (minX + x)
            outPixels[y * boxWidth + x] = if (visited[globalIndex]) fillArgb else 0
        }
    }

    val bitmap = AndroidBitmap.createBitmap(outPixels, boxWidth, boxHeight, AndroidBitmap.Config.ARGB_8888)
    return DrawElement.Fill(
        bitmap = bitmap.asImageBitmap(),
        start  = Offset(minX.toFloat(), minY.toFloat()),
        end    = Offset((minX + boxWidth).toFloat(), (minY + boxHeight).toFloat()),
        color  = fillColor
    )
}

private fun colorsMatch(a: Int, b: Int, tolerance: Int): Boolean {
    val alphaDiff = ((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF)
    val redDiff   = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
    val greenDiff = ((a ushr 8)  and 0xFF) - ((b ushr 8)  and 0xFF)
    val blueDiff  = (a and 0xFF) - (b and 0xFF)
    return abs(alphaDiff) <= tolerance &&
        abs(redDiff) <= tolerance &&
        abs(greenDiff) <= tolerance &&
        abs(blueDiff) <= tolerance
}
