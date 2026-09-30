package com.example.stencilcreator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** Tools available from the editor toolbar. */
enum class Tool { PEN, ERASER, SELECT, LASSO, FILL }

/** Shapes the pen or eraser can draw instead of a freehand stroke. */
enum class DrawShape { CIRCLE, SQUARE, STAR }

/**
 * A single item drawn on a [Layer].
 *
 * All coordinates are in world space, where one world unit equals one pixel of the
 * exported page and the page occupies the rect (0, 0) → (page width, page height).
 */
sealed class DrawElement {
    abstract val strokeWidth: Float
    abstract val isEraser: Boolean
    abstract val color: Color

    data class FreeStroke(
        val points: List<Offset>,
        override val strokeWidth: Float,
        override val isEraser: Boolean,
        override val color: Color
    ) : DrawElement()

    /**
     * [start] and [end] are opposite corners of the shape's bounding box, and [rotation]
     * is the view rotation (degrees) when it was drawn, so it stays screen-aligned.
     */
    data class Shape(
        val shape: DrawShape,
        val start: Offset,
        val end: Offset,
        override val strokeWidth: Float,
        override val isEraser: Boolean,
        override val color: Color,
        val rotation: Float = 0f
    ) : DrawElement()

    /** An imported picture, positioned with the same box/rotation model as [Shape]. */
    data class Image(
        val bitmap: ImageBitmap,
        val start: Offset,
        val end: Offset,
        override val strokeWidth: Float = 0f,
        override val isEraser: Boolean = false,
        override val color: Color = Color.Unspecified,
        val rotation: Float = 0f
    ) : DrawElement()

    /**
     * The result of a paint-bucket fill: a rasterised mask covering the enclosed region that
     * was flood-filled, positioned with the same box/rotation model as [Shape].
     */
    data class Fill(
        val bitmap: ImageBitmap,
        val start: Offset,
        val end: Offset,
        override val strokeWidth: Float = 0f,
        override val isEraser: Boolean = false,
        override val color: Color = Color.Unspecified,
        val rotation: Float = 0f
    ) : DrawElement()
}

/**
 * A stack of [DrawElement]s composited with [opacity]. Exactly one layer per design is the
 * background layer; it can't be deleted and supplies the page [backgroundColor]
 * (null = transparent).
 */
data class Layer(
    val id: Int,
    val name: String,
    val elements: List<DrawElement> = emptyList(),
    val opacity: Float = 1f,
    val isBackground: Boolean = false,
    val backgroundColor: Color? = Color.White
)
