package com.example.stencilcreator

import android.graphics.BitmapFactory
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ── Domain types ──────────────────────────────────────────────────────────────

enum class Tool { PEN, ERASER, SELECT, LASSO }
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
        override val color: Color,
        val rotation: Float = 0f
    ) : DrawElement()

    // start/end define the world-space bounding vector; rotation = viewRotation at placement
    // so the image appears screen-aligned when first placed. Same transform math as Shape.
    data class Image(
        val bitmap: ImageBitmap,
        val start: Offset,
        val end: Offset,
        override val strokeWidth: Float = 0f,
        override val isEraser: Boolean = false,
        override val color: Color = Color.Unspecified,
        val rotation: Float = 0f
    ) : DrawElement()
}

data class Layer(
    val id: Int,
    val name: String,
    val elements: List<DrawElement> = emptyList(),
    val opacity: Float = 1f,
    val isBackground: Boolean = false,
    val backgroundColor: Color? = Color.White
)

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

fun renderShape(canvas: Canvas, shape: DrawShape, start: Offset, end: Offset, paint: Paint, rotation: Float = 0f) {
    val left   = minOf(start.x, end.x)
    val top    = minOf(start.y, end.y)
    val right  = maxOf(start.x, end.x)
    val bottom = maxOf(start.y, end.y)
    if (right - left < 2f && bottom - top < 2f) return
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val dx = end.x - start.x
    val dy = end.y - start.y
    val rad = rotation * (PI / 180.0).toFloat()
    val cosR = cos(rad); val sinR = sin(rad)
    val halfW = abs(dx * cosR - dy * sinR) / 2f
    val halfH = abs(dx * sinR + dy * cosR) / 2f
    when (shape) {
        DrawShape.CIRCLE -> canvas.drawCircle(Offset(cx, cy), minOf(halfW, halfH), paint)
        DrawShape.SQUARE -> {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-rotation)
            canvas.drawRect(Rect(-halfW, -halfH, halfW, halfH), paint)
            canvas.restore()
        }
        DrawShape.STAR -> {
            val outerR = minOf(halfW, halfH)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-rotation)
            canvas.drawPath(starPath(Offset.Zero, outerR, outerR * 0.382f), paint)
            canvas.restore()
        }
    }
}

fun renderImage(canvas: Canvas, el: DrawElement.Image) {
    val dx = el.end.x - el.start.x
    val dy = el.end.y - el.start.y
    val cx = (el.start.x + el.end.x) / 2f
    val cy = (el.start.y + el.end.y) / 2f
    val rad = el.rotation * (PI / 180.0).toFloat()
    val cosR = cos(rad); val sinR = sin(rad)
    val halfW = abs(dx * cosR - dy * sinR) / 2f
    val halfH = abs(dx * sinR + dy * cosR) / 2f
    if (halfW < 1f || halfH < 1f) return
    canvas.save()
    canvas.translate(cx, cy)
    canvas.rotate(-el.rotation)
    // Scale image from its native pixel dimensions to the desired world-space dimensions
    canvas.scale(halfW * 2f / el.bitmap.width, halfH * 2f / el.bitmap.height)
    canvas.drawImage(el.bitmap, Offset(-el.bitmap.width / 2f, -el.bitmap.height / 2f), Paint())
    canvas.restore()
}

// ── Selection helpers ─────────────────────────────────────────────────────────

fun DrawElement.translated(delta: Offset): DrawElement = when (this) {
    is DrawElement.FreeStroke -> copy(points = points.map { it + delta })
    is DrawElement.Shape      -> copy(start = start + delta, end = end + delta)
    is DrawElement.Image      -> copy(start = start + delta, end = end + delta)
}

fun DrawElement.scaledAround(pivot: Offset, zoom: Float): DrawElement {
    if (zoom == 1f) return this
    fun scale(p: Offset) = pivot + (p - pivot) * zoom
    return when (this) {
        is DrawElement.FreeStroke -> copy(points = points.map { scale(it) })
        is DrawElement.Shape      -> copy(start = scale(start), end = scale(end))
        is DrawElement.Image      -> copy(start = scale(start), end = scale(end))
    }
}

fun DrawElement.rotatedAround(pivot: Offset, degrees: Float): DrawElement {
    if (degrees == 0f) return this
    val rad = degrees * (PI / 180.0).toFloat()
    val c = cos(rad); val s = sin(rad)
    fun rot(p: Offset): Offset {
        val dx = p.x - pivot.x; val dy = p.y - pivot.y
        return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
    }
    return when (this) {
        is DrawElement.FreeStroke -> copy(points = points.map { rot(it) })
        is DrawElement.Shape      -> copy(start = rot(start), end = rot(end), rotation = rotation - degrees)
        is DrawElement.Image      -> copy(start = rot(start), end = rot(end), rotation = rotation - degrees)
    }
}

fun pointInRect(p: Offset, a: Offset, b: Offset): Boolean =
    p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

fun pointInPolygon(p: Offset, poly: List<Offset>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val xi = poly[i].x; val yi = poly[i].y
        val xj = poly[j].x; val yj = poly[j].y
        if ((yi > p.y) != (yj > p.y) && p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi)
            inside = !inside
        j = i
    }
    return inside
}

fun selectionBounds(elements: List<DrawElement>, indices: Set<Int>): Rect? {
    if (indices.isEmpty()) return null
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    fun expand(p: Offset) {
        if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
    }
    fun expandBoxElement(start: Offset, end: Offset, rotation: Float) {
        val dx = end.x - start.x; val dy = end.y - start.y
        val cx = (start.x + end.x) / 2f; val cy = (start.y + end.y) / 2f
        val rad = rotation * (PI / 180.0).toFloat()
        val cosR = cos(rad); val sinR = sin(rad)
        val halfW = abs(dx * cosR - dy * sinR) / 2f
        val halfH = abs(dx * sinR + dy * cosR) / 2f
        listOf(
            Offset(cx - halfW * cosR - halfH * sinR, cy + halfW * sinR - halfH * cosR),
            Offset(cx + halfW * cosR - halfH * sinR, cy - halfW * sinR - halfH * cosR),
            Offset(cx + halfW * cosR + halfH * sinR, cy - halfW * sinR + halfH * cosR),
            Offset(cx - halfW * cosR + halfH * sinR, cy + halfW * sinR + halfH * cosR)
        ).forEach { expand(it) }
    }
    for (i in indices) {
        when (val el = elements.getOrNull(i) ?: continue) {
            is DrawElement.FreeStroke -> el.points.forEach { expand(it) }
            is DrawElement.Shape      -> expandBoxElement(el.start, el.end, el.rotation)
            is DrawElement.Image      -> expandBoxElement(el.start, el.end, el.rotation)
        }
    }
    return if (maxX >= minX) Rect(minX - 8f, minY - 8f, maxX + 8f, maxY + 8f) else null
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
    val context = LocalContext.current

    var layers by remember { mutableStateOf(listOf(
        Layer(id = 0, name = "Background", isBackground = true, backgroundColor = Color.White)
    )) }
    var activeLayerIndex by remember { mutableStateOf(0) }
    var nextLayerId      by remember { mutableStateOf(1) }
    var undoStack by remember { mutableStateOf(listOf<List<Layer>>()) }
    var redoStack by remember { mutableStateOf(listOf<List<Layer>>()) }

    var livePoints     by remember { mutableStateOf(listOf<Offset>()) }
    var liveShapeStart by remember { mutableStateOf<Offset?>(null) }
    var liveShapeEnd   by remember { mutableStateOf<Offset?>(null) }

    var selectedIndices    by remember { mutableStateOf(setOf<Int>()) }
    var liveSelectionStart by remember { mutableStateOf<Offset?>(null) }
    var liveSelectionEnd   by remember { mutableStateOf<Offset?>(null) }
    var liveLassoPoints    by remember { mutableStateOf(listOf<Offset>()) }

    var selectedTool  by remember { mutableStateOf(Tool.PEN) }
    var selectedShape by remember { mutableStateOf<DrawShape?>(null) }
    var penSize       by remember { mutableStateOf(8f) }
    var eraserSize    by remember { mutableStateOf(32f) }
    var penColor      by remember { mutableStateOf(Color.Black) }
    var showColorPicker   by remember { mutableStateOf(false) }
    var showLayerPanel    by remember { mutableStateOf(false) }
    var combineSourceIdx  by remember { mutableStateOf<Int?>(null) }

    var pendingBitmap by remember { mutableStateOf<AndroidBitmap?>(null) }

    var viewScale    by remember { mutableStateOf(1f) }
    var viewRotation by remember { mutableStateOf(0f) }
    var viewOffset   by remember { mutableStateOf(Offset.Zero) }
    var canvasSize   by remember { mutableStateOf(Size(800f, 1200f)) }

    val currentStrokeWidth by rememberUpdatedState(if (selectedTool == Tool.PEN) penSize else eraserSize)
    val currentIsEraser    by rememberUpdatedState(selectedTool == Tool.ERASER)
    val currentShape       by rememberUpdatedState(selectedShape)
    val currentColor       by rememberUpdatedState(penColor)
    val currentTool        by rememberUpdatedState(selectedTool)
    val currentLayers      by rememberUpdatedState(layers)
    val currentActiveIdx   by rememberUpdatedState(activeLayerIndex)

    fun screenToWorld(screen: Offset): Offset {
        val d = screen - viewOffset
        val rad = -viewRotation * (PI / 180.0).toFloat()
        val c = cos(rad); val s = sin(rad)
        return Offset(d.x * c - d.y * s, d.x * s + d.y * c) / viewScale
    }

    // Place a bitmap on a brand-new layer centred on the current view.
    // Image is placed at rotation = 0 (world-axis-aligned) so its orientation is
    // independent from the canvas rotation — the user rotates it manually via select.
    fun placeImage(bmp: AndroidBitmap) {
        val imageBitmap = bmp.asImageBitmap()
        val aspect      = bmp.width.toFloat() / bmp.height.toFloat()
        val screenHalfW = minOf(canvasSize.width, canvasSize.height) * 0.35f
        val screenHalfH = screenHalfW / aspect
        val worldHalfW  = screenHalfW / viewScale
        val worldHalfH  = screenHalfH / viewScale

        val wc    = screenToWorld(Offset(canvasSize.width / 2, canvasSize.height / 2))
        val start = wc - Offset(worldHalfW, worldHalfH)
        val end   = wc + Offset(worldHalfW, worldHalfH)

        val el       = DrawElement.Image(imageBitmap, start, end, rotation = 0f)
        val newLayer = Layer(id = nextLayerId, name = "Layer $nextLayerId", elements = listOf(el))
        nextLayerId++

        undoStack = undoStack + listOf(layers)
        redoStack = emptyList()
        layers           = layers + newLayer
        activeLayerIndex = layers.lastIndex
        selectedIndices  = setOf(0)  // auto-select so user can move immediately
    }

    // Gallery picker
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val stream = context.contentResolver.openInputStream(uri)
            val bmp    = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bmp != null) pendingBitmap = bmp
        }
    }

    val displayAngle = ((viewRotation % 360f) + 360f) % 360f

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Drawing canvas ────────────────────────────────────────────────────

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)

                        var drawing          = false
                        var capturedWidth    = 0f
                        var capturedEraser   = false
                        var capturedShape: DrawShape? = null
                        var capturedColor    = Color.Black
                        var capturedRotation = 0f
                        var capturedTool     = Tool.PEN
                        var capturedIsMoving = false
                        var movePrevWorld    = Offset.Zero
                        var selectionSnapshotPushed = false

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
                                        drawing          = true
                                        capturedTool     = currentTool
                                        capturedWidth    = currentStrokeWidth
                                        capturedEraser   = currentIsEraser
                                        capturedShape    = currentShape
                                        capturedColor    = currentColor
                                        capturedRotation = viewRotation
                                        capturedIsMoving = selectedIndices.isNotEmpty() &&
                                            (capturedTool == Tool.SELECT || capturedTool == Tool.LASSO)
                                        if (capturedIsMoving) {
                                            undoStack = undoStack + listOf(currentLayers)
                                            redoStack = emptyList()
                                            selectionSnapshotPushed = true
                                        }
                                        when {
                                            capturedIsMoving            -> movePrevWorld = worldPos
                                            capturedTool == Tool.SELECT -> {
                                                liveSelectionStart = worldPos; liveSelectionEnd = worldPos
                                            }
                                            capturedTool == Tool.LASSO  -> liveLassoPoints = listOf(worldPos)
                                            capturedShape == null       -> livePoints = listOf(worldPos)
                                            else -> { liveShapeStart = worldPos; liveShapeEnd = worldPos }
                                        }
                                    } else {
                                        when {
                                            capturedIsMoving -> {
                                                val delta = worldPos - movePrevWorld
                                                val curEls = currentLayers[currentActiveIdx].elements
                                                layers = currentLayers.mapIndexed { i, l ->
                                                    if (i == currentActiveIdx) l.copy(
                                                        elements = curEls.mapIndexed { j, el ->
                                                            if (j in selectedIndices) el.translated(delta) else el
                                                        }
                                                    ) else l
                                                }
                                                movePrevWorld = worldPos
                                            }
                                            capturedTool == Tool.SELECT -> liveSelectionEnd = worldPos
                                            capturedTool == Tool.LASSO  -> liveLassoPoints = liveLassoPoints + worldPos
                                            liveShapeStart != null      -> liveShapeEnd = worldPos
                                            else                        -> livePoints = livePoints + worldPos
                                        }
                                    }
                                    pressed[0].consume()
                                }

                                pCount >= 2 -> {
                                    if (drawing) {
                                        drawing            = false
                                        livePoints         = emptyList()
                                        liveShapeStart     = null; liveShapeEnd     = null
                                        liveSelectionStart = null; liveSelectionEnd = null
                                        liveLassoPoints    = emptyList()
                                    }
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val centroid = (p0 + p1) / 2f
                                    val vec  = p1 - p0
                                    val span = vec.getDistance()
                                    val angle = atan2(vec.y, vec.x) * (180f / PI.toFloat())

                                    val isSelectionMode = selectedIndices.isNotEmpty() &&
                                        (currentTool == Tool.SELECT || currentTool == Tool.LASSO)

                                    if (prevSpan > 0f) {
                                        val zoom = span / prevSpan
                                        var dRot = angle - prevAngle
                                        if (dRot >  180f) dRot -= 360f
                                        if (dRot < -180f) dRot += 360f
                                        val dPan = centroid - prevCentroid

                                        if (isSelectionMode) {
                                            if (!selectionSnapshotPushed) {
                                                undoStack = undoStack + listOf(currentLayers)
                                                redoStack = emptyList()
                                                selectionSnapshotPushed = true
                                            }
                                            val curEls = currentLayers[currentActiveIdx].elements
                                            val bounds = selectionBounds(curEls, selectedIndices)
                                            val pivot  = if (bounds != null)
                                                Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                                            else screenToWorld(centroid)
                                            val invRad = -viewRotation * (PI / 180.0).toFloat()
                                            val rc = cos(invRad); val rs = sin(invRad)
                                            val worldDelta = Offset(
                                                dPan.x * rc - dPan.y * rs,
                                                dPan.x * rs + dPan.y * rc
                                            ) / viewScale
                                            layers = currentLayers.mapIndexed { i, l ->
                                                if (i == currentActiveIdx) l.copy(
                                                    elements = curEls.mapIndexed { j, el ->
                                                        if (j in selectedIndices)
                                                            el.scaledAround(pivot, zoom)
                                                                .rotatedAround(pivot, dRot)
                                                                .translated(worldDelta)
                                                        else el
                                                    }
                                                ) else l
                                            }
                                        } else {
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

                        // ── Gesture ended ─────────────────────────────────────────────────────
                        if (drawing) {
                            val capLayers = currentLayers
                            val capIdx    = currentActiveIdx
                            when {
                                capturedTool == Tool.SELECT && !capturedIsMoving -> {
                                    val s = liveSelectionStart; val e = liveSelectionEnd
                                    val layerEls = capLayers[capIdx].elements
                                    selectedIndices = if (s != null && e != null) {
                                        layerEls.indices.filter { i ->
                                            when (val el = layerEls[i]) {
                                                is DrawElement.FreeStroke -> el.points.any { pointInRect(it, s, e) }
                                                is DrawElement.Shape      -> pointInRect((el.start + el.end) / 2f, s, e)
                                                is DrawElement.Image      -> pointInRect((el.start + el.end) / 2f, s, e)
                                            }
                                        }.toSet()
                                    } else emptySet()
                                    liveSelectionStart = null; liveSelectionEnd = null
                                }
                                capturedTool == Tool.LASSO && !capturedIsMoving -> {
                                    val layerEls = capLayers[capIdx].elements
                                    selectedIndices = layerEls.indices.filter { i ->
                                        when (val el = layerEls[i]) {
                                            is DrawElement.FreeStroke -> el.points.any { pointInPolygon(it, liveLassoPoints) }
                                            is DrawElement.Shape      -> pointInPolygon((el.start + el.end) / 2f, liveLassoPoints)
                                            is DrawElement.Image      -> pointInPolygon((el.start + el.end) / 2f, liveLassoPoints)
                                        }
                                    }.toSet()
                                    liveLassoPoints = emptyList()
                                }
                                capturedTool == Tool.SELECT || capturedTool == Tool.LASSO -> { /* move complete */ }
                                capturedShape == null -> {
                                    if (livePoints.isNotEmpty()) {
                                        undoStack = undoStack + listOf(capLayers)
                                        redoStack = emptyList()
                                        layers = capLayers.mapIndexed { i, l ->
                                            if (i == capIdx) l.copy(
                                                elements = l.elements + DrawElement.FreeStroke(livePoints, capturedWidth, capturedEraser, capturedColor)
                                            ) else l
                                        }
                                    }
                                    livePoints = emptyList()
                                }
                                else -> {
                                    val s = liveShapeStart; val e = liveShapeEnd
                                    if (s != null && e != null && (e - s).getDistance() >= 5f) {
                                        undoStack = undoStack + listOf(capLayers)
                                        redoStack = emptyList()
                                        layers = capLayers.mapIndexed { i, l ->
                                            if (i == capIdx) l.copy(
                                                elements = l.elements + DrawElement.Shape(capturedShape, s, e, capturedWidth, capturedEraser, capturedColor, capturedRotation)
                                            ) else l
                                        }
                                    }
                                    liveShapeStart = null; liveShapeEnd = null
                                }
                            }
                        }
                    }
                }
        ) {
            // ── Canvas background ─────────────────────────────────────────────
            val bgLayer = layers.first { it.isBackground }
            val bgColor = bgLayer.backgroundColor
            if (bgColor != null) {
                drawRect(bgColor)
            } else {
                val cell = 20.dp.toPx()
                val cols = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (row in 0..rows) {
                    for (col in 0..cols) {
                        drawRect(
                            color    = if ((row + col) % 2 == 0) Color(0xFFE0E0E0) else Color(0xFFF5F5F5),
                            topLeft  = Offset(col * cell, row * cell),
                            size     = Size(cell, cell)
                        )
                    }
                }
            }

            drawIntoCanvas { canvas ->
                // ── Render each layer ─────────────────────────────────────────
                for ((layerIdx, layer) in layers.withIndex()) {
                    val isActiveLayer = layerIdx == activeLayerIndex
                    val layerPaint = Paint().apply { alpha = layer.opacity }
                    canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
                    canvas.translate(viewOffset.x, viewOffset.y)
                    canvas.rotate(viewRotation)
                    canvas.scale(viewScale, viewScale)

                    for (el in layer.elements) {
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
                                renderShape(canvas, el.shape, el.start, el.end, paint, el.rotation)
                            }
                            is DrawElement.Image -> renderImage(canvas, el)
                        }
                    }

                    if (isActiveLayer) {
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
                            renderShape(canvas, ps, ls, le, paint, viewRotation)
                        }
                    }

                    canvas.restore()
                }

                // ── Selection overlays ────────────────────────────────────────
                val dashStep = 10f / viewScale
                val dashGap  = 5f  / viewScale
                val selPaint = Paint().apply {
                    style       = PaintingStyle.Stroke
                    strokeWidth = 2f / viewScale
                    color       = Color(0xFF2196F3)
                }
                selPaint.asFrameworkPaint().pathEffect =
                    android.graphics.DashPathEffect(floatArrayOf(dashStep, dashGap), 0f)

                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                canvas.translate(viewOffset.x, viewOffset.y)
                canvas.rotate(viewRotation)
                canvas.scale(viewScale, viewScale)

                val lss = liveSelectionStart; val lse = liveSelectionEnd
                if (lss != null && lse != null) {
                    canvas.drawRect(
                        Rect(minOf(lss.x, lse.x), minOf(lss.y, lse.y), maxOf(lss.x, lse.x), maxOf(lss.y, lse.y)),
                        selPaint
                    )
                }

                if (liveLassoPoints.size >= 2) {
                    val lassoPath = Path().apply {
                        moveTo(liveLassoPoints[0].x, liveLassoPoints[0].y)
                        liveLassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    canvas.drawPath(lassoPath, selPaint)
                }

                val activeElements = layers.getOrNull(activeLayerIndex)?.elements ?: emptyList()
                selectionBounds(activeElements, selectedIndices)?.let { bounds ->
                    canvas.drawRect(bounds, selPaint)
                }

                canvas.restore()
            }
        }

        // ── Selection actions ─────────────────────────────────────────────────

        if (selectedIndices.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { selectedIndices = emptySet() }) { Text("Deselect") }
                Button(onClick = {
                    undoStack = undoStack + listOf(layers)
                    redoStack = emptyList()
                    layers = layers.mapIndexed { i, l ->
                        if (i == activeLayerIndex)
                            l.copy(elements = l.elements.filterIndexed { j, _ -> j !in selectedIndices })
                        else l
                    }
                    selectedIndices = emptySet()
                }) { Text("Delete") }
            }
        }

        // ── Undo / Redo ───────────────────────────────────────────────────────

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    redoStack = redoStack + listOf(layers)
                    layers    = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    selectedIndices = emptySet()
                },
                enabled = undoStack.isNotEmpty()
            ) { Text("Undo") }
            Button(
                onClick = {
                    undoStack = undoStack + listOf(layers)
                    layers    = redoStack.last()
                    redoStack = redoStack.dropLast(1)
                    selectedIndices = emptySet()
                },
                enabled = redoStack.isNotEmpty()
            ) { Text("Redo") }
        }

        // ── Layer panel ───────────────────────────────────────────────────────

        if (showLayerPanel) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp)
                    .fillMaxHeight()
                    .padding(bottom = 160.dp)
                    .width(270.dp),
                shadowElevation = 16.dp,
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                LayerPanel(
                    layers = layers,
                    activeLayerIndex = activeLayerIndex,
                    onSelectLayer = { idx ->
                        if (idx != activeLayerIndex) {
                            activeLayerIndex = idx
                            selectedIndices  = emptySet()
                        }
                    },
                    onAddLayer = {
                        val newLayer = Layer(id = nextLayerId, name = "Layer $nextLayerId")
                        nextLayerId++
                        layers = layers + newLayer
                        activeLayerIndex = layers.lastIndex
                        selectedIndices  = emptySet()
                    },
                    onDeleteLayer = { idx ->
                        if (!layers[idx].isBackground) {
                            layers = layers.filterIndexed { i, _ -> i != idx }
                            activeLayerIndex = activeLayerIndex.coerceAtMost(layers.lastIndex)
                            selectedIndices  = emptySet()
                        }
                    },
                    onOpacityChange = { idx, opacity ->
                        layers = layers.mapIndexed { i, l -> if (i == idx) l.copy(opacity = opacity) else l }
                    },
                    onBackgroundColorChange = { color ->
                        layers = layers.map { if (it.isBackground) it.copy(backgroundColor = color) else it }
                    },
                    onCombineRequest = { idx -> combineSourceIdx = idx },
                    onClose = { showLayerPanel = false }
                )
            }
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
                    ToolButton("Select", selectedTool == Tool.SELECT) { selectedTool = Tool.SELECT }
                    ToolButton("Lasso",  selectedTool == Tool.LASSO)  { selectedTool = Tool.LASSO }

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

                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = "${displayAngle.toInt()}°",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(penColor)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showColorPicker = true }
                    )

                    Box(Modifier.height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                    ToolButton("Image", false) { imageLauncher.launch("image/*") }

                    Box(Modifier.height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                    ToolButton("Layers", showLayerPanel) { showLayerPanel = !showLayerPanel }

                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            text = layers.getOrNull(activeLayerIndex)?.name ?: "",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
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

    // ── Image crop dialog ─────────────────────────────────────────────────────

    val bmp = pendingBitmap
    if (bmp != null) {
        CropDialog(
            bitmap    = bmp,
            onConfirm = { finalBmp ->
                pendingBitmap = null
                placeImage(finalBmp)
            },
            onDismiss = { pendingBitmap = null }
        )
    }

    // ── Combine layers dialog ─────────────────────────────────────────────────

    val combineIdx = combineSourceIdx
    if (combineIdx != null) {
        val sourceLayer   = layers.getOrNull(combineIdx)
        val targetOptions = layers.withIndex().filter { (i, l) -> i != combineIdx && !l.isBackground }
        if (sourceLayer != null && targetOptions.isNotEmpty()) {
            Dialog(onDismissRequest = { combineSourceIdx = null }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Combine \"${sourceLayer.name}\" with:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        targetOptions.forEach { (targetIdx, targetLayer) ->
                            Button(
                                onClick = {
                                    val mergedLayer = layers[targetIdx].copy(
                                        elements = layers[targetIdx].elements + sourceLayer.elements
                                    )
                                    val newLayers = layers
                                        .mapIndexed { i, l -> if (i == targetIdx) mergedLayer else l }
                                        .filterIndexed { i, _ -> i != combineIdx }
                                    undoStack = undoStack + listOf(layers)
                                    redoStack = emptyList()
                                    layers = newLayers
                                    activeLayerIndex = newLayers.indexOfFirst { it.id == mergedLayer.id }
                                        .coerceAtLeast(0)
                                    selectedIndices  = emptySet()
                                    combineSourceIdx = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(targetLayer.name) }
                        }
                        TextButton(
                            onClick  = { combineSourceIdx = null },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Cancel") }
                    }
                }
            }
        } else {
            combineSourceIdx = null
        }
    }
}

// ── Image crop dialog ─────────────────────────────────────────────────────────

@Composable
fun CropDialog(
    bitmap: AndroidBitmap,
    onConfirm: (AndroidBitmap) -> Unit,
    onDismiss: () -> Unit
) {
    var cropRect  by remember { mutableStateOf(
        Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    )}
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableStateOf(1f) }
    val imageBitmap = remember { bitmap.asImageBitmap() }

    val conf          = LocalConfiguration.current
    val dialogWidthDp = conf.screenWidthDp * 0.95f
    val availHeightDp = (conf.screenHeightDp - 220).coerceAtLeast(120).toFloat()
    val imgAspect     = bitmap.width.toFloat() / bitmap.height.toFloat()
    val displayWidthDp: Float
    val displayHeightDp: Float
    if (dialogWidthDp / imgAspect <= availHeightDp) {
        displayWidthDp  = dialogWidthDp
        displayHeightDp = dialogWidthDp / imgAspect
    } else {
        displayHeightDp = availHeightDp
        displayWidthDp  = availHeightDp * imgAspect
    }

    val density  = LocalDensity.current
    val cpxW     = with(density) { displayWidthDp.dp.toPx() }
    val cpxH     = with(density) { displayHeightDp.dp.toPx() }
    // fitScale shrinks the full image to 90% of the canvas at 1× zoom
    val fitScale = minOf(cpxW / bitmap.width, cpxH / bitmap.height) * 0.9f

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
                Text("Crop Image", style = MaterialTheme.typography.titleLarge,
                     modifier = Modifier.align(Alignment.Start))
                Text(
                    "Drag corners to crop. Pinch to zoom, drag to pan.",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start)
                )

                Canvas(
                    modifier = Modifier
                        .width(displayWidthDp.dp)
                        .height(displayHeightDp.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)

                                // Compute corner positions in canvas pixels to detect handle touch
                                val ts = fitScale * zoomScale
                                val cx = cpxW / 2f + panOffset.x
                                val cy = cpxH / 2f + panOffset.y
                                val corners = listOf(
                                    Offset(cropRect.left,  cropRect.top),
                                    Offset(cropRect.right, cropRect.top),
                                    Offset(cropRect.right, cropRect.bottom),
                                    Offset(cropRect.left,  cropRect.bottom)
                                )
                                val cornerDisplay = corners.map { pt ->
                                    Offset(cx + (pt.x - bitmap.width  / 2f) * ts,
                                           cy + (pt.y - bitmap.height / 2f) * ts)
                                }
                                val handleIdx = cornerDisplay.indices.minByOrNull { i ->
                                    val d = cornerDisplay[i] - down.position
                                    d.x * d.x + d.y * d.y
                                }
                                val nearHandle = handleIdx != null && run {
                                    val d = cornerDisplay[handleIdx!!] - down.position
                                    d.x * d.x + d.y * d.y < 60f * 60f
                                }

                                if (nearHandle) {
                                    // ── Handle drag mode ──────────────────────
                                    while (true) {
                                        val event   = awaitPointerEvent()
                                        val pressed = event.changes.firstOrNull { it.pressed } ?: break
                                        val curTs   = fitScale * zoomScale
                                        val curCx   = cpxW / 2f + panOffset.x
                                        val curCy   = cpxH / 2f + panOffset.y
                                        val ix = ((pressed.position.x - curCx) / curTs + bitmap.width  / 2f)
                                            .coerceIn(0f, bitmap.width.toFloat())
                                        val iy = ((pressed.position.y - curCy) / curTs + bitmap.height / 2f)
                                            .coerceIn(0f, bitmap.height.toFloat())
                                        val minPx = 20f / curTs.coerceAtLeast(0.001f)
                                        cropRect = when (handleIdx!!) {
                                            0 -> Rect(ix.coerceAtMost(cropRect.right  - minPx), iy.coerceAtMost(cropRect.bottom - minPx), cropRect.right,  cropRect.bottom)
                                            1 -> Rect(cropRect.left, iy.coerceAtMost(cropRect.bottom - minPx), ix.coerceAtLeast(cropRect.left + minPx), cropRect.bottom)
                                            2 -> Rect(cropRect.left, cropRect.top, ix.coerceAtLeast(cropRect.left + minPx), iy.coerceAtLeast(cropRect.top + minPx))
                                            3 -> Rect(ix.coerceAtMost(cropRect.right  - minPx), cropRect.top, cropRect.right,  iy.coerceAtLeast(cropRect.top + minPx))
                                            else -> cropRect
                                        }
                                        pressed.consume()
                                    }
                                } else {
                                    // ── Pan / pinch-zoom mode ─────────────────
                                    var lastPos   = down.position
                                    var prevDist  = 0f
                                    var isZooming = false

                                    while (true) {
                                        val event   = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (pressed.size >= 2) {
                                            val p0       = pressed[0].position
                                            val p1       = pressed[1].position
                                            val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                            val dist     = (p1 - p0).getDistance()

                                            if (isZooming && prevDist > 0f) {
                                                val newZoom = (zoomScale * dist / prevDist).coerceIn(0.5f, 8f)
                                                val ratio   = (fitScale * newZoom) / (fitScale * zoomScale)
                                                panOffset = Offset(
                                                    centroid.x - cpxW / 2f - (centroid.x - cpxW / 2f - panOffset.x) * ratio,
                                                    centroid.y - cpxH / 2f - (centroid.y - cpxH / 2f - panOffset.y) * ratio
                                                )
                                                zoomScale = newZoom
                                            }
                                            prevDist  = dist
                                            isZooming = true
                                            pressed.forEach { it.consume() }
                                        } else {
                                            if (isZooming) lastPos = pressed[0].position
                                            isZooming = false
                                            prevDist  = 0f
                                            panOffset += pressed[0].position - lastPos
                                            lastPos    = pressed[0].position
                                            pressed[0].consume()
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    // Dark background so image edges are always visible
                    drawRect(Color(0xFF1A1A1AL))

                    val curTs   = fitScale * zoomScale
                    val imgLeft = size.width  / 2f + panOffset.x - bitmap.width  / 2f * curTs
                    val imgTop  = size.height / 2f + panOffset.y - bitmap.height / 2f * curTs

                    drawContext.canvas.save()
                    drawContext.canvas.translate(imgLeft, imgTop)
                    drawContext.canvas.scale(curTs, curTs)
                    drawContext.canvas.drawImage(imageBitmap, Offset.Zero, Paint())
                    drawContext.canvas.restore()

                    // Convert image-space point to canvas-space
                    fun imgToDisplay(pt: Offset) = Offset(
                        size.width  / 2f + panOffset.x + (pt.x - bitmap.width  / 2f) * curTs,
                        size.height / 2f + panOffset.y + (pt.y - bitmap.height / 2f) * curTs
                    )

                    val tl       = imgToDisplay(Offset(cropRect.left,  cropRect.top))
                    val br       = imgToDisplay(Offset(cropRect.right, cropRect.bottom))
                    val crLeft   = tl.x;  val crTop    = tl.y
                    val crRight  = br.x;  val crBottom = br.y
                    val crW      = crRight - crLeft;  val crH = crBottom - crTop

                    // Darken outside crop region
                    val overlay = Color(0x88000000L)
                    if (crTop    > 0f)           drawRect(overlay, Offset.Zero,              Size(size.width, crTop))
                    if (crBottom < size.height)  drawRect(overlay, Offset(0f, crBottom),     Size(size.width, size.height - crBottom))
                    if (crLeft   > 0f)           drawRect(overlay, Offset(0f, crTop),        Size(crLeft, crH))
                    if (crRight  < size.width)   drawRect(overlay, Offset(crRight, crTop),   Size(size.width - crRight, crH))

                    // Crop border
                    drawRect(Color.White, topLeft = Offset(crLeft, crTop), size = Size(crW, crH), style = DrawStroke(2f))

                    // Corner handles
                    val hr = 14f
                    listOf(
                        imgToDisplay(Offset(cropRect.left,  cropRect.top)),
                        imgToDisplay(Offset(cropRect.right, cropRect.top)),
                        imgToDisplay(Offset(cropRect.right, cropRect.bottom)),
                        imgToDisplay(Offset(cropRect.left,  cropRect.bottom))
                    ).forEach { corner ->
                        drawCircle(Color.White,        hr,      center = corner)
                        drawCircle(Color(0xFF1976D2L), hr - 4f, center = corner)
                    }
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        val x = cropRect.left.toInt().coerceIn(0, bitmap.width - 1)
                        val y = cropRect.top.toInt().coerceIn(0, bitmap.height - 1)
                        val w = (cropRect.right  - cropRect.left).toInt().coerceIn(1, bitmap.width  - x)
                        val h = (cropRect.bottom - cropRect.top ).toInt().coerceIn(1, bitmap.height - y)
                        onConfirm(AndroidBitmap.createBitmap(bitmap, x, y, w, h))
                    }) { Text("Place") }
                }
            }
        }
    }
}

// ── Layer panel composable ────────────────────────────────────────────────────

@Composable
fun LayerPanel(
    layers: List<Layer>,
    activeLayerIndex: Int,
    onSelectLayer: (Int) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Int) -> Unit,
    onOpacityChange: (Int, Float) -> Unit,
    onBackgroundColorChange: (Color?) -> Unit,
    onCombineRequest: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (idx in layers.indices.reversed()) {
                val layer    = layers[idx]
                val isActive = idx == activeLayerIndex
                LayerRow(
                    layer           = layer,
                    isActive        = isActive,
                    onSelect        = { onSelectLayer(idx) },
                    onDelete        = { onDeleteLayer(idx) },
                    onOpacityChange = { opacity -> onOpacityChange(idx, opacity) },
                    onBgColorChange = if (layer.isBackground) onBackgroundColorChange else null,
                    onCombine       = if (!layer.isBackground) { { onCombineRequest(idx) } } else null
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        TextButton(onClick = onAddLayer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("+ Add Layer")
        }
    }
}

// ── Layer row ─────────────────────────────────────────────────────────────────

@Composable
fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBgColorChange: ((Color?) -> Unit)?,
    onCombine: (() -> Unit)?
) {
    var showBgColorPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = layer.name,
                modifier   = Modifier.weight(1f),
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                fontSize   = 14.sp
            )
            if (onBgColorChange != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(layer.backgroundColor ?: Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .clickable { showBgColorPicker = true }
                )
                Spacer(Modifier.width(8.dp))
            }
            if (!layer.isBackground) {
                Text(
                    text     = "${(layer.opacity * 100).toInt()}%",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                    Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (isActive) {
            if (!layer.isBackground) {
                Text(
                    "Opacity: ${(layer.opacity * 100).toInt()}%",
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value         = layer.opacity,
                    onValueChange = onOpacityChange,
                    valueRange    = 0f..1f,
                    modifier      = Modifier.fillMaxWidth()
                )
                if (onCombine != null) {
                    TextButton(onClick = onCombine, modifier = Modifier.fillMaxWidth()) {
                        Text("Combine with...", fontSize = 13.sp)
                    }
                }
            } else {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { showBgColorPicker = true }) { Text("Change Color", fontSize = 12.sp) }
                    TextButton(onClick = { onBgColorChange?.invoke(null) }) { Text("Transparent", fontSize = 12.sp) }
                }
            }
        }
    }

    if (showBgColorPicker && onBgColorChange != null) {
        Dialog(onDismissRequest = { showBgColorPicker = false }) {
            Surface(
                shape          = RoundedCornerShape(16.dp),
                color          = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier            = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Background Color", style = MaterialTheme.typography.titleLarge)
                    ColorWheelPicker(color = layer.backgroundColor ?: Color.White) { onBgColorChange(it) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showBgColorPicker = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

// ── Color wheel picker ────────────────────────────────────────────────────────

@Composable
fun ColorWheelPicker(color: Color, onColorChange: (Color) -> Unit) {
    val hsv = remember(color) {
        FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
    }
    val latestHue by rememberUpdatedState(hsv[0])
    val latestSat by rememberUpdatedState(hsv[1])
    val latestVal by rememberUpdatedState(hsv[2])

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val hueColors = listOf(
            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
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
            val cx = size.width / 2f; val cy = size.height / 2f
            val radius = minOf(cx, cy)
            val center = Offset(cx, cy)
            drawCircle(brush = Brush.sweepGradient(hueColors, center = center), radius = radius, center = center)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = radius),
                radius = radius, center = center
            )
            val angle = hsv[0] * (PI / 180.0).toFloat()
            val ic = Offset(cx + cos(angle) * hsv[1] * radius, cy + sin(angle) * hsv[1] * radius)
            drawCircle(Color.White, 12f, ic)
            drawCircle(color, 9f, ic)
        }

        Text("Brightness", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

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
            val h = size.height; val thumbR = h / 2f
            val trackW = (size.width - 2 * thumbR).coerceAtLeast(1f)
            val trackH = h * 0.4f; val trackTop = (h - trackH) / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Color.Black, fullBrightColor), startX = thumbR, endX = thumbR + trackW),
                topLeft = Offset(thumbR, trackTop), size = Size(trackW, trackH), cornerRadius = CornerRadius(trackH / 2f)
            )
            val thumbX = thumbR + hsv[2] * trackW
            drawCircle(Color.White, thumbR, Offset(thumbX, h / 2f))
            drawCircle(Color.Gray, thumbR - 2.dp.toPx(), Offset(thumbX, h / 2f), style = DrawStroke(2.dp.toPx()))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp).clip(CircleShape).background(color)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(text = "#%06X".format(color.toArgb() and 0xFFFFFF), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}

// ── Reusable toggle button ────────────────────────────────────────────────────

@Composable
fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape    = RoundedCornerShape(8.dp),
        color    = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text       = label,
            color      = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize   = 14.sp
        )
    }
}
