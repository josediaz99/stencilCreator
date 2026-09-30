package com.example.stencilcreator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Padding (world px) added around the selection bounds so the outline doesn't touch the art. */
private const val SELECTION_PADDING = 8f

fun DrawElement.translated(delta: Offset): DrawElement = when (this) {
    is DrawElement.FreeStroke -> copy(points = points.map { it + delta })
    is DrawElement.Shape      -> copy(start = start + delta, end = end + delta)
    is DrawElement.Image      -> copy(start = start + delta, end = end + delta)
    is DrawElement.Fill       -> copy(start = start + delta, end = end + delta)
}

fun DrawElement.scaledAround(pivot: Offset, zoom: Float): DrawElement {
    if (zoom == 1f) return this
    fun scale(p: Offset) = pivot + (p - pivot) * zoom
    return when (this) {
        is DrawElement.FreeStroke -> copy(points = points.map(::scale))
        is DrawElement.Shape      -> copy(start = scale(start), end = scale(end))
        is DrawElement.Image      -> copy(start = scale(start), end = scale(end))
        is DrawElement.Fill       -> copy(start = scale(start), end = scale(end))
    }
}

fun DrawElement.rotatedAround(pivot: Offset, degrees: Float): DrawElement {
    if (degrees == 0f) return this
    fun rotate(p: Offset) = pivot + (p - pivot).rotatedBy(degrees)
    return when (this) {
        is DrawElement.FreeStroke -> copy(points = points.map(::rotate))
        is DrawElement.Shape      -> copy(start = rotate(start), end = rotate(end), rotation = rotation - degrees)
        is DrawElement.Image      -> copy(start = rotate(start), end = rotate(end), rotation = rotation - degrees)
        is DrawElement.Fill       -> copy(start = rotate(start), end = rotate(end), rotation = rotation - degrees)
    }
}

/**
 * Points used to decide whether a selection marquee or lasso captures this element:
 * every point of a freehand stroke, or the centre of a shape/image/fill.
 */
fun DrawElement.hitTestPoints(): List<Offset> = when (this) {
    is DrawElement.FreeStroke -> points
    is DrawElement.Shape      -> listOf(midpoint(start, end))
    is DrawElement.Image      -> listOf(midpoint(start, end))
    is DrawElement.Fill       -> listOf(midpoint(start, end))
}

/** Points whose bounding box encloses everything this element draws. */
private fun DrawElement.outlinePoints(): List<Offset> = when (this) {
    is DrawElement.FreeStroke -> points
    is DrawElement.Shape      -> OrientedBox.fromDiagonal(start, end, rotation).corners
    is DrawElement.Image      -> OrientedBox.fromDiagonal(start, end, rotation).corners
    is DrawElement.Fill       -> OrientedBox.fromDiagonal(start, end, rotation).corners
}

/** World-space bounds of the elements at [indices], padded for display, or null if nothing is selected. */
fun selectionBounds(elements: List<DrawElement>, indices: Set<Int>): Rect? {
    val points = indices.mapNotNull { elements.getOrNull(it) }.flatMap { it.outlinePoints() }
    if (points.isEmpty()) return null
    return Rect(
        left   = points.minOf { it.x } - SELECTION_PADDING,
        top    = points.minOf { it.y } - SELECTION_PADDING,
        right  = points.maxOf { it.x } + SELECTION_PADDING,
        bottom = points.maxOf { it.y } + SELECTION_PADDING
    )
}
