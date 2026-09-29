package com.example.stencilcreator

import android.graphics.DashPathEffect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val DeskColor         = Color(0xFF9E9E9E)
private val CheckerLight      = Color(0xFFF5F5F5)
private val CheckerDark       = Color(0xFFE0E0E0)
private val PageOutlineColor  = Color(0x66000000)
private val SelectionColor    = Color(0xFF2196F3)
private val CheckerCellSize   = 20.dp

/** The zoomable, rotatable drawing surface: renders the page and routes touch input. */
@Composable
fun EditorCanvas(state: EditorState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.pointerInput(state) { detectEditorGestures(state) }) {
        drawRect(DeskColor)
        drawIntoCanvas { canvas ->
            drawPageBackground(canvas, state)
            drawLayers(canvas, state)
            drawOverlays(canvas, state)
        }
    }
}

private fun DrawScope.drawPageBackground(canvas: Canvas, state: EditorState) {
    val viewport = state.viewport
    canvas.save()
    viewport.applyTo(canvas)

    val background = state.backgroundColor
    if (background != null) {
        canvas.drawRect(state.pageBounds, Paint().apply { color = background })
    } else {
        // Transparent page: draw the checkerboard in screen space, clipped to the page,
        // so the number of cells stays bounded at any zoom level
        canvas.clipRect(state.pageBounds)
        viewport.revert(canvas)
        drawCheckerboard(canvas, CheckerCellSize.toPx())
    }
    canvas.restore()
}

private fun DrawScope.drawCheckerboard(canvas: Canvas, cellSize: Float) {
    val light = Paint().apply { color = CheckerLight }
    val dark  = Paint().apply { color = CheckerDark }
    val columns = (size.width / cellSize).toInt() + 1
    val rows    = (size.height / cellSize).toInt() + 1
    for (row in 0..rows) {
        for (column in 0..columns) {
            canvas.drawRect(
                Rect(Offset(column * cellSize, row * cellSize), Size(cellSize, cellSize)),
                if ((row + column) % 2 == 0) dark else light
            )
        }
    }
}

/** Draws each layer into its own offscreen buffer so erasers only affect that layer. */
private fun DrawScope.drawLayers(canvas: Canvas, state: EditorState) {
    for ((index, layer) in state.layers.withIndex()) {
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = layer.opacity })
        state.viewport.applyTo(canvas)
        canvas.clipRect(state.pageBounds)

        layer.elements.forEach { drawElement(canvas, it) }
        if (index == state.activeLayerIndex) drawLivePreview(canvas, state)

        canvas.restore()
    }
}

private fun drawLivePreview(canvas: Canvas, state: EditorState) {
    val points = state.livePoints
    if (points.size >= 2) {
        val paint = makePaint(state.brushSize, state.isErasing, fill = false, state.penColor)
        canvas.drawPath(polylinePath(points), paint)
    }

    val start = state.liveShapeStart
    val end   = state.liveShapeEnd
    val shape = state.selectedShape
    if (start != null && end != null && shape != null) {
        val paint = makePaint(state.brushSize, state.isErasing, fill = state.isErasing, state.penColor)
        renderShape(canvas, shape, start, end, paint, state.viewport.rotation)
    }
}

/** Page outline, marquee/lasso previews and the selection box. Line widths stay constant on screen. */
private fun DrawScope.drawOverlays(canvas: Canvas, state: EditorState) {
    val scale = state.viewport.scale
    val selectionPaint = Paint().apply {
        style       = PaintingStyle.Stroke
        strokeWidth = 2f / scale
        color       = SelectionColor
    }
    selectionPaint.asFrameworkPaint().pathEffect = DashPathEffect(floatArrayOf(10f / scale, 5f / scale), 0f)

    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    state.viewport.applyTo(canvas)

    canvas.drawRect(state.pageBounds, Paint().apply {
        style       = PaintingStyle.Stroke
        strokeWidth = 1f / scale
        color       = PageOutlineColor
    })

    val marqueeStart = state.liveSelectionStart
    val marqueeEnd   = state.liveSelectionEnd
    if (marqueeStart != null && marqueeEnd != null) {
        canvas.drawRect(Rect(marqueeStart, marqueeEnd).normalized(), selectionPaint)
    }

    if (state.liveLassoPoints.size >= 2) {
        canvas.drawPath(polylinePath(state.liveLassoPoints, closed = true), selectionPaint)
    }

    state.activeSelectionBounds()?.let { canvas.drawRect(it, selectionPaint) }

    canvas.restore()
}

private fun Rect.normalized() = Rect(
    left   = minOf(left, right),
    top    = minOf(top, bottom),
    right  = maxOf(left, right),
    bottom = maxOf(top, bottom)
)
