package com.example.stencilcreator

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.atan2

/** Shapes dragged out shorter than this (world px) are discarded as accidental taps. */
private const val MIN_SHAPE_DRAG_DISTANCE = 5f

/**
 * Tool settings captured when a one-finger gesture begins, so changing the toolbar
 * mid-gesture doesn't alter the stroke being drawn.
 */
private class StrokeGesture(
    val tool: Tool,
    val strokeWidth: Float,
    val isEraser: Boolean,
    val shape: DrawShape?,
    val color: Color,
    val rotation: Float,
    val isMovingSelection: Boolean
)

/**
 * Handles all touch input on the editor canvas.
 *
 * - One finger draws with the current tool, drags out a selection/lasso, or moves the
 *   current selection when a selection tool is active.
 * - Two fingers pinch-zoom, rotate and pan the view, or transform the selection instead
 *   when one exists. Adding a second finger cancels any stroke in progress.
 */
suspend fun PointerInputScope.detectEditorGestures(state: EditorState) {
    val viewport = state.viewport

    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)

        var stroke: StrokeGesture? = null
        var lastMoveWorld = Offset.Zero
        var historyPushed = false

        var prevPointerCount = 1
        var prevCentroid = firstDown.position
        var prevSpan  = 0f
        var prevAngle = 0f

        while (true) {
            val pressed = awaitPointerEvent().changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val pointerCount = pressed.size

            when {
                pointerCount == 1 && prevPointerCount == 1 -> {
                    val worldPos = viewport.screenToWorld(pressed[0].position)
                    val current = stroke
                    when {
                        current == null -> {
                            val started = state.captureStroke()
                            stroke = started
                            if (started.isMovingSelection) {
                                state.pushHistory()
                                historyPushed = true
                                lastMoveWorld = worldPos
                            } else {
                                state.startPreview(started, worldPos)
                            }
                        }
                        current.isMovingSelection -> {
                            val delta = worldPos - lastMoveWorld
                            state.transformSelection { it.translated(delta) }
                            lastMoveWorld = worldPos
                        }
                        else -> state.extendPreview(current, worldPos)
                    }
                    pressed[0].consume()
                }

                pointerCount >= 2 -> {
                    if (stroke != null) {
                        stroke = null
                        state.clearLivePreviews()
                    }

                    val p0 = pressed[0].position
                    val p1 = pressed[1].position
                    val centroid = midpoint(p0, p1)
                    val span  = (p1 - p0).getDistance()
                    val angle = atan2(p1.y - p0.y, p1.x - p0.x).radiansToDegrees()

                    if (prevSpan > 0f) {
                        val zoom = span / prevSpan
                        val rotationDelta = wrapDegrees(angle - prevAngle)
                        val pan = centroid - prevCentroid

                        if (state.isTransformingSelection) {
                            if (!historyPushed) {
                                state.pushHistory()
                                historyPushed = true
                            }
                            val pivot = state.activeSelectionBounds()?.center ?: viewport.screenToWorld(centroid)
                            val worldPan = viewport.screenDeltaToWorld(pan)
                            state.transformSelection {
                                it.scaledAround(pivot, zoom)
                                    .rotatedAround(pivot, rotationDelta)
                                    .translated(worldPan)
                            }
                        } else {
                            viewport.applyPinch(centroid, pan, zoom, rotationDelta)
                        }
                    }
                    prevCentroid = centroid
                    prevSpan  = span
                    prevAngle = angle
                    pressed.forEach { it.consume() }
                }

                // Dropped from two fingers to one: restart pinch tracking on the next pinch
                else -> prevSpan = 0f
            }
            prevPointerCount = pointerCount
        }

        stroke?.let { state.finishStroke(it) }
    }
}

private fun EditorState.captureStroke() = StrokeGesture(
    tool              = selectedTool,
    strokeWidth       = brushSize,
    isEraser          = isErasing,
    shape             = selectedShape,
    color             = penColor,
    rotation          = viewport.rotation,
    isMovingSelection = isTransformingSelection
)

private fun EditorState.startPreview(stroke: StrokeGesture, pos: Offset) {
    when {
        stroke.tool == Tool.SELECT -> {
            liveSelectionStart = pos
            liveSelectionEnd   = pos
        }
        stroke.tool == Tool.LASSO -> liveLassoPoints = listOf(pos)
        stroke.shape == null      -> livePoints = listOf(pos)
        else -> {
            liveShapeStart = pos
            liveShapeEnd   = pos
        }
    }
}

private fun EditorState.extendPreview(stroke: StrokeGesture, pos: Offset) {
    when {
        stroke.tool == Tool.SELECT -> liveSelectionEnd = pos
        stroke.tool == Tool.LASSO  -> liveLassoPoints = liveLassoPoints + pos
        stroke.shape == null       -> livePoints = livePoints + pos
        else                       -> liveShapeEnd = pos
    }
}

/** Turns the finished preview into a selection or a committed element. */
private fun EditorState.finishStroke(stroke: StrokeGesture) {
    when {
        // Selection was moved live; history was pushed when the drag began
        stroke.isMovingSelection -> Unit

        stroke.tool == Tool.SELECT -> {
            val start = liveSelectionStart
            val end   = liveSelectionEnd
            if (start != null && end != null) selectWhere { pointInRect(it, start, end) } else clearSelection()
            liveSelectionStart = null
            liveSelectionEnd   = null
        }

        stroke.tool == Tool.LASSO -> {
            val lasso = liveLassoPoints
            selectWhere { pointInPolygon(it, lasso) }
            liveLassoPoints = emptyList()
        }

        stroke.shape == null -> {
            if (livePoints.isNotEmpty()) {
                addElement(DrawElement.FreeStroke(livePoints, stroke.strokeWidth, stroke.isEraser, stroke.color))
            }
            livePoints = emptyList()
        }

        else -> {
            val start = liveShapeStart
            val end   = liveShapeEnd
            if (start != null && end != null && (end - start).getDistance() >= MIN_SHAPE_DRAG_DISTANCE) {
                addElement(
                    DrawElement.Shape(
                        shape       = stroke.shape,
                        start       = start,
                        end         = end,
                        strokeWidth = stroke.strokeWidth,
                        isEraser    = stroke.isEraser,
                        color       = stroke.color,
                        rotation    = stroke.rotation
                    )
                )
            }
            liveShapeStart = null
            liveShapeEnd   = null
        }
    }
}
