package com.example.stencilcreator

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ── Domain types ──────────────────────────────────────────────────────────────

enum class Tool { PEN, ERASER }
enum class DrawShape { CIRCLE, SQUARE, STAR }

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

    data class Shape(
        val shape: DrawShape,
        val start: Offset,
        val end: Offset,
        override val strokeWidth: Float,
        override val isEraser: Boolean,
        override val color: Color
    ) : DrawElement()
}

// ── Drawing helpers ───────────────────────────────────────────────────────────

fun starPath(center: Offset, outerR: Float, innerR: Float): Path {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val angle = (i * PI / 5.0 - PI / 2.0).toFloat()
        val x = center.x + r * cos(angle)
        val y = center.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun makePaint(strokeWidth: Float, isEraser: Boolean, fill: Boolean, color: Color): Paint = Paint().apply {
    style = if (fill) PaintingStyle.Fill else PaintingStyle.Stroke
    this.strokeWidth = strokeWidth
    strokeCap = StrokeCap.Round
    strokeJoin = StrokeJoin.Round
    if (isEraser) blendMode = BlendMode.Clear else this.color = color
}

fun renderShape(canvas: Canvas, shape: DrawShape, start: Offset, end: Offset, paint: Paint) {
    val left   = minOf(start.x, end.x)
    val top    = minOf(start.y, end.y)
    val right  = maxOf(start.x, end.x)
    val bottom = maxOf(start.y, end.y)
    if (right - left < 2f && bottom - top < 2f) return
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    when (shape) {
        DrawShape.CIRCLE -> canvas.drawCircle(Offset(cx, cy), minOf(right - left, bottom - top) / 2f, paint)
        DrawShape.SQUARE -> canvas.drawRect(Rect(left, top, right, bottom), paint)
        DrawShape.STAR   -> {
            val outerR = minOf(right - left, bottom - top) / 2f
            canvas.drawPath(starPath(Offset(cx, cy), outerR, outerR * 0.382f), paint)
        }
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StencilEditor() }
    }
}

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun StencilEditor() {
    var elements  by remember { mutableStateOf(listOf<DrawElement>()) }
    var redoStack by remember { mutableStateOf(listOf<DrawElement>()) }

    var livePoints     by remember { mutableStateOf(listOf<Offset>()) }
    var liveShapeStart by remember { mutableStateOf<Offset?>(null) }
    var liveShapeEnd   by remember { mutableStateOf<Offset?>(null) }

    var selectedTool  by remember { mutableStateOf(Tool.PEN) }
    var selectedShape by remember { mutableStateOf<DrawShape?>(null) }
    var penSize       by remember { mutableStateOf(8f) }
    var eraserSize    by remember { mutableStateOf(32f) }
    var penColor      by remember { mutableStateOf(Color.Black) }
    var showColorPicker by remember { mutableStateOf(false) }

    // View transform — screen = rotate(world * scale, rotation) + offset
    var viewScale    by remember { mutableStateOf(1f) }
    var viewRotation by remember { mutableStateOf(0f) }
    var viewOffset   by remember { mutableStateOf(Offset.Zero) }

    val currentStrokeWidth by rememberUpdatedState(if (selectedTool == Tool.PEN) penSize else eraserSize)
    val currentIsEraser    by rememberUpdatedState(selectedTool == Tool.ERASER)
    val currentShape       by rememberUpdatedState(selectedShape)
    val currentColor       by rememberUpdatedState(penColor)

    fun screenToWorld(screen: Offset): Offset {
        val d = screen - viewOffset
        val rad = -viewRotation * (PI / 180.0).toFloat()
        val c = cos(rad); val s = sin(rad)
        return Offset(d.x * c - d.y * s, d.x * s + d.y * c) / viewScale
    }

    // Normalize cumulative rotation to [0, 360) for display
    val displayAngle = ((viewRotation % 360f) + 360f) % 360f

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Drawing canvas ────────────────────────────────────────────────────

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)

                        var drawing = false
                        var capturedWidth  = 0f
                        var capturedEraser = false
                        var capturedShape: DrawShape? = null
                        var capturedColor  = Color.Black

                        var prevPointerCount = 1
                        var prevCentroid = firstDown.position
                        var prevSpan = 0f
                        var prevAngle = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val pCount = pressed.size

                            when {
                                pCount == 1 && prevPointerCount == 1 -> {
                                    val worldPos = screenToWorld(pressed[0].position)
                                    if (!drawing) {
                                        drawing = true
                                        capturedWidth  = currentStrokeWidth
                                        capturedEraser = currentIsEraser
                                        capturedShape  = currentShape
                                        capturedColor  = currentColor
                                        if (capturedShape == null) livePoints = listOf(worldPos)
                                        else { liveShapeStart = worldPos; liveShapeEnd = worldPos }
                                    } else {
                                        if (liveShapeStart != null) liveShapeEnd = worldPos
                                        else livePoints = livePoints + worldPos
                                    }
                                    pressed[0].consume()
                                }

                                pCount >= 2 -> {
                                    if (drawing) {
                                        drawing = false
                                        livePoints = emptyList()
                                        liveShapeStart = null; liveShapeEnd = null
                                    }
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val centroid = (p0 + p1) / 2f
                                    val vec = p1 - p0
                                    val span = vec.getDistance()
                                    val angle = atan2(vec.y, vec.x) * (180f / PI.toFloat())

                                    if (prevSpan > 0f) {
                                        val zoom = span / prevSpan
                                        var dRot = angle - prevAngle
                                        if (dRot >  180f) dRot -= 360f
                                        if (dRot < -180f) dRot += 360f
                                        val dPan = centroid - prevCentroid

                                        val worldPt  = screenToWorld(centroid)
                                        val newScale = (viewScale * zoom).coerceIn(0.2f, 5f)
                                        val newRot   = viewRotation + dRot
                                        val rad = newRot * (PI / 180.0).toFloat()
                                        val c = cos(rad); val s = sin(rad)
                                        val sc = worldPt * newScale
                                        viewOffset   = centroid + dPan - Offset(sc.x * c - sc.y * s, sc.x * s + sc.y * c)
                                        viewScale    = newScale
                                        viewRotation = newRot
                                    }
                                    prevCentroid = centroid
                                    prevSpan = span
                                    prevAngle = angle
                                    pressed.forEach { it.consume() }
                                }

                                else -> prevSpan = 0f
                            }
                            prevPointerCount = pCount
                        }

                        if (drawing) {
                            when {
                                capturedShape == null -> {
                                    if (livePoints.isNotEmpty()) {
                                        elements  = elements + DrawElement.FreeStroke(livePoints, capturedWidth, capturedEraser, capturedColor)
                                        redoStack = emptyList()
                                    }
                                    livePoints = emptyList()
                                }
                                else -> {
                                    val s = liveShapeStart; val e = liveShapeEnd
                                    if (s != null && e != null && (e - s).getDistance() >= 5f) {
                                        elements  = elements + DrawElement.Shape(capturedShape, s, e, capturedWidth, capturedEraser, capturedColor)
                                        redoStack = emptyList()
                                    }
                                    liveShapeStart = null; liveShapeEnd = null
                                }
                            }
                        }
                    }
                }
        ) {
            drawRect(Color.White)

            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                canvas.translate(viewOffset.x, viewOffset.y)
                canvas.rotate(viewRotation)
                canvas.scale(viewScale, viewScale)

                for (el in elements) {
                    when (el) {
                        is DrawElement.FreeStroke -> {
                            if (el.points.size >= 2) {
                                val paint = makePaint(el.strokeWidth, el.isEraser, false, el.color)
                                val path = Path().apply {
                                    moveTo(el.points[0].x, el.points[0].y)
                                    el.points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                canvas.drawPath(path, paint)
                            }
                        }
                        is DrawElement.Shape -> {
                            val paint = makePaint(el.strokeWidth, el.isEraser, el.isEraser, el.color)
                            renderShape(canvas, el.shape, el.start, el.end, paint)
                        }
                    }
                }

                if (livePoints.size >= 2) {
                    val paint = makePaint(currentStrokeWidth, currentIsEraser, false, currentColor)
                    val path = Path().apply {
                        moveTo(livePoints[0].x, livePoints[0].y)
                        livePoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    canvas.drawPath(path, paint)
                }

                val ls = liveShapeStart; val le = liveShapeEnd; val ps = currentShape
                if (ls != null && le != null && ps != null) {
                    val paint = makePaint(currentStrokeWidth, currentIsEraser, currentIsEraser, currentColor)
                    renderShape(canvas, ps, ls, le, paint)
                }

                canvas.restore()
            }
        }

        // ── Undo / Redo ───────────────────────────────────────────────────────

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { redoStack = redoStack + elements.last(); elements = elements.dropLast(1) },
                enabled = elements.isNotEmpty()
            ) { Text("Undo") }
            Button(
                onClick = { elements = elements + redoStack.last(); redoStack = redoStack.dropLast(1) },
                enabled = redoStack.isNotEmpty()
            ) { Text("Redo") }
        }

        // ── Bottom toolbar ────────────────────────────────────────────────────

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton("Pen",    selectedTool == Tool.PEN)    { selectedTool = Tool.PEN }
                    ToolButton("Eraser", selectedTool == Tool.ERASER) { selectedTool = Tool.ERASER }

                    Box(Modifier.height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                    ToolButton("Circle", selectedShape == DrawShape.CIRCLE) {
                        selectedShape = if (selectedShape == DrawShape.CIRCLE) null else DrawShape.CIRCLE
                    }
                    ToolButton("Square", selectedShape == DrawShape.SQUARE) {
                        selectedShape = if (selectedShape == DrawShape.SQUARE) null else DrawShape.SQUARE
                    }
                    ToolButton("Star", selectedShape == DrawShape.STAR) {
                        selectedShape = if (selectedShape == DrawShape.STAR) null else DrawShape.STAR
                    }

                    Box(Modifier.height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                    // Rotation angle badge
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = "${displayAngle.toInt()}°",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Color swatch — opens the color picker dialog
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(penColor)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showColorPicker = true }
                    )
                }

                val displaySize = if (selectedTool == Tool.PEN) penSize.toInt() else eraserSize.toInt()
                Text("Size: $displaySize", fontSize = 13.sp)
                Slider(
                    value = if (selectedTool == Tool.PEN) penSize else eraserSize,
                    onValueChange = { v -> if (selectedTool == Tool.PEN) penSize = v else eraserSize = v },
                    valueRange = 4f..80f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ── Color picker dialog ───────────────────────────────────────────────────

    if (showColorPicker) {
        Dialog(onDismissRequest = { showColorPicker = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Color", style = MaterialTheme.typography.titleLarge)
                    ColorWheelPicker(color = penColor) { penColor = it }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showColorPicker = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

// ── Color wheel picker ────────────────────────────────────────────────────────

@Composable
fun ColorWheelPicker(color: Color, onColorChange: (Color) -> Unit) {
    // Parse current color into HSV so we can draw and move the indicator correctly
    val hsv = remember(color) {
        FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
    }
    // Always-fresh values for the pointerInput closures (which never recompose)
    val latestHue by rememberUpdatedState(hsv[0])
    val latestSat by rememberUpdatedState(hsv[1])
    val latestVal by rememberUpdatedState(hsv[2])

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Hue + saturation wheel ────────────────────────────────────────────
        // SweepGradient starts at 0° (3 o'clock / east), matching atan2 convention,
        // so indicator position and picked color are always consistent.
        val hueColors = listOf(
            Color(0xFFFF0000), // 0°   Red
            Color(0xFFFFFF00), // 60°  Yellow
            Color(0xFF00FF00), // 120° Green
            Color(0xFF00FFFF), // 180° Cyan
            Color(0xFF0000FF), // 240° Blue
            Color(0xFFFF00FF), // 300° Magenta
            Color(0xFFFF0000)  // 360° Red — closes the circle
        )

        Canvas(
            modifier = Modifier
                .size(220.dp)
                .pointerInput(Unit) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = minOf(cx, cy)
                    fun pick(pos: Offset) {
                        val dx = pos.x - cx; val dy = pos.y - cy
                        val h = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                        val s = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
                        onColorChange(Color(AndroidColor.HSVToColor(floatArrayOf(h, s, latestVal))))
                    }
                    detectDragGestures(
                        onDragStart = { pick(it) },
                        onDrag = { change, _ -> pick(change.position) }
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy)
            val center = Offset(cx, cy)

            // Hue sweep
            drawCircle(brush = Brush.sweepGradient(hueColors, center = center), radius = radius, center = center)
            // Saturation overlay: white (centre, sat = 0) → transparent (edge, sat = 1)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center
            )

            // Selection indicator
            val angle = hsv[0] * (PI / 180.0).toFloat()
            val ic = Offset(cx + cos(angle) * hsv[1] * radius, cy + sin(angle) * hsv[1] * radius)
            drawCircle(Color.White, 12f, ic)  // white halo
            drawCircle(color, 9f, ic)          // actual color fill
        }

        // ── Brightness slider ─────────────────────────────────────────────────
        Text("Brightness", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        // The gradient end-color is the fully-bright version of the current hue/sat,
        // so the track always shows the correct colour range.
        val fullBrightColor = Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(Unit) {
                    val thumbR = size.height / 2f
                    val trackW = (size.width - 2 * thumbR).coerceAtLeast(1f)
                    fun pick(x: Float) {
                        val v = ((x - thumbR) / trackW).coerceIn(0f, 1f)
                        onColorChange(Color(AndroidColor.HSVToColor(floatArrayOf(latestHue, latestSat, v))))
                    }
                    detectDragGestures(
                        onDragStart = { pick(it.x) },
                        onDrag = { change, _ -> pick(change.position.x) }
                    )
                }
        ) {
            val h = size.height
            val thumbR = h / 2f
            val trackW = (size.width - 2 * thumbR).coerceAtLeast(1f)
            val trackH = h * 0.4f
            val trackTop = (h - trackH) / 2f

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, fullBrightColor),
                    startX = thumbR,
                    endX = thumbR + trackW
                ),
                topLeft = Offset(thumbR, trackTop),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )

            val thumbX = thumbR + hsv[2] * trackW
            drawCircle(Color.White, thumbR, Offset(thumbX, h / 2f))
            drawCircle(Color.Gray, thumbR - 2.dp.toPx(), Offset(thumbX, h / 2f), style = DrawStroke(2.dp.toPx()))
        }

        // ── Preview swatch + hex code ─────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(
                text = "#%06X".format(color.toArgb() and 0xFFFFFF),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }
    }
}

// ── Reusable toggle button ────────────────────────────────────────────────────

@Composable
fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp
        )
    }
}
