package com.example.stencilcreator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun Float.degreesToRadians(): Float = this * (PI / 180.0).toFloat()

fun Float.radiansToDegrees(): Float = this * (180.0 / PI).toFloat()

/** Wraps an angle difference into the range [-180, 180] degrees. */
fun wrapDegrees(degrees: Float): Float = when {
    degrees > 180f  -> degrees - 360f
    degrees < -180f -> degrees + 360f
    else            -> degrees
}

/** Rotates this vector about the origin by [degrees] (clockwise on screen, where y points down). */
fun Offset.rotatedBy(degrees: Float): Offset {
    if (degrees == 0f) return this
    val rad = degrees.degreesToRadians()
    val cos = cos(rad)
    val sin = sin(rad)
    return Offset(x * cos - y * sin, x * sin + y * cos)
}

fun midpoint(a: Offset, b: Offset): Offset = (a + b) / 2f

/** Builds a path through [points] in order, optionally closing it back to the first point. */
fun polylinePath(points: List<Offset>, closed: Boolean = false): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    if (closed) close()
}

/**
 * A rectangle rotated about its centre.
 *
 * Box-shaped elements ([DrawElement.Shape], [DrawElement.Image]) are stored as a world-space
 * diagonal plus the rotation they were placed at. Rotating the diagonal into the box's own
 * frame recovers its true width and height.
 */
data class OrientedBox(
    val center: Offset,
    val halfWidth: Float,
    val halfHeight: Float,
    val rotation: Float
) {
    val corners: List<Offset>
        get() = listOf(
            Offset(-halfWidth, -halfHeight),
            Offset( halfWidth, -halfHeight),
            Offset( halfWidth,  halfHeight),
            Offset(-halfWidth,  halfHeight)
        ).map { center + it.rotatedBy(-rotation) }

    companion object {
        fun fromDiagonal(start: Offset, end: Offset, rotation: Float): OrientedBox {
            val local = (end - start).rotatedBy(rotation)
            return OrientedBox(
                center     = midpoint(start, end),
                halfWidth  = abs(local.x) / 2f,
                halfHeight = abs(local.y) / 2f,
                rotation   = rotation
            )
        }
    }
}

fun pointInRect(p: Offset, a: Offset, b: Offset): Boolean =
    p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

/** Even-odd ray-casting test; polygons with fewer than three points contain nothing. */
fun pointInPolygon(p: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
            inside = !inside
        }
        j = i
    }
    return inside
}
