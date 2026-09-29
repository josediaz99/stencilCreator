package com.example.stencilcreator

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas

/** Screen pixels per world unit. The wide range lets both very large and very small pages fit. */
const val MIN_VIEW_SCALE = 0.02f
const val MAX_VIEW_SCALE = 10f

/**
 * Maps world coordinates (page pixels) to screen coordinates.
 *
 * screen = offset + rotate(world × scale, rotation)
 */
@Stable
class EditorViewport {
    var scale by mutableFloatStateOf(1f)
        private set
    var rotation by mutableFloatStateOf(0f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var canvasSize by mutableStateOf(Size(800f, 1200f))

    /** Rotation normalised to 0..359 whole degrees, for display. */
    val displayAngle: Int
        get() = (((rotation % 360f) + 360f) % 360f).toInt()

    fun screenToWorld(screen: Offset): Offset = screenDeltaToWorld(screen - offset)

    fun screenDeltaToWorld(delta: Offset): Offset = delta.rotatedBy(-rotation) / scale

    /**
     * Resets rotation and scales the page to fit the canvas area that isn't covered by
     * the given insets (in screen px), centring it in that area.
     */
    fun fitPage(pageSize: Size, topInset: Float, bottomInset: Float, sideInset: Float) {
        val availableWidth  = (canvasSize.width  - 2 * sideInset).coerceAtLeast(1f)
        val availableHeight = (canvasSize.height - topInset - bottomInset).coerceAtLeast(1f)
        val fitScale = minOf(availableWidth / pageSize.width, availableHeight / pageSize.height)
            .coerceIn(MIN_VIEW_SCALE, MAX_VIEW_SCALE)

        scale    = fitScale
        rotation = 0f
        offset   = Offset(
            x = (canvasSize.width - pageSize.width * fitScale) / 2f,
            y = topInset + (availableHeight - pageSize.height * fitScale) / 2f
        )
    }

    /**
     * Applies one step of a two-finger gesture: zooms and rotates around [centroid], then pans.
     * The world point under the fingers stays under the fingers.
     */
    fun applyPinch(centroid: Offset, pan: Offset, zoom: Float, rotationDelta: Float) {
        val anchor   = screenToWorld(centroid)
        val newScale = (scale * zoom).coerceIn(MIN_VIEW_SCALE, MAX_VIEW_SCALE)
        val newRotation = rotation + rotationDelta
        offset   = centroid + pan - (anchor * newScale).rotatedBy(newRotation)
        scale    = newScale
        rotation = newRotation
    }

    /** Switches [canvas] from screen coordinates to world coordinates. */
    fun applyTo(canvas: Canvas) {
        canvas.translate(offset.x, offset.y)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)
    }

    /** Reverses [applyTo], switching [canvas] back to screen coordinates. */
    fun revert(canvas: Canvas) {
        canvas.scale(1f / scale, 1f / scale)
        canvas.rotate(-rotation)
        canvas.translate(-offset.x, -offset.y)
    }
}
