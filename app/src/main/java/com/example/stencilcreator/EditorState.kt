package com.example.stencilcreator

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap

val BRUSH_SIZE_RANGE = 4f..80f
private const val DEFAULT_PEN_SIZE = 8f
private const val DEFAULT_ERASER_SIZE = 32f

/**
 * How far a pixel's colour may drift from the fill target and still be treated as part of
 * the same region, as a percentage of the full colour range. Low values only bridge crisp,
 * fully-opaque lines; higher values are needed to close the anti-aliased halo around thin or
 * soft-edged lines without a visible gap, at the cost of being more likely to leak through a
 * faint or low-contrast line entirely.
 */
val FILL_SENSITIVITY_RANGE = 0f..100f
private const val DEFAULT_FILL_SENSITIVITY = 15f

/** Newly placed images span this fraction of the canvas's shorter side, each side of centre. */
private const val IMAGE_PLACEMENT_FRACTION = 0.35f

/**
 * All state for one editing session: the layers and their undo history, tool settings,
 * the current selection, in-progress gesture previews and the [viewport].
 *
 * Every edit replaces [layers] with a new list, so list identity is enough to track
 * unsaved changes and to snapshot history.
 */
@Stable
class EditorState(document: EditorDocument) {

    val page: PageSize = document.page
    val createdAt: Long = document.createdAt
    val pageBounds = Rect(0f, 0f, page.widthPx.toFloat(), page.heightPx.toFloat())
    val viewport = EditorViewport()

    // ── Save tracking ─────────────────────────────────────────────────────────

    var designId by mutableStateOf(document.designId)
        private set
    var designName by mutableStateOf(document.name)
        private set
    var projectId by mutableStateOf(document.projectId)
        private set
    private var savedLayers by mutableStateOf(document.layers)

    val isDirty: Boolean get() = layers !== savedLayers

    fun markSaved(id: String, name: String, projectId: String, snapshot: List<Layer>) {
        designId    = id
        designName  = name
        this.projectId = projectId
        savedLayers = snapshot
    }

    // ── Layers & history ──────────────────────────────────────────────────────

    var layers by mutableStateOf(document.layers)
        private set
    var activeLayerIndex by mutableIntStateOf(0)
        private set
    var nextLayerId by mutableIntStateOf(document.nextLayerId)
        private set

    private var undoStack by mutableStateOf(listOf<List<Layer>>())
    private var redoStack by mutableStateOf(listOf<List<Layer>>())

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val activeLayer: Layer? get() = layers.getOrNull(activeLayerIndex)
    val backgroundColor: Color? get() = layers.firstOrNull { it.isBackground }?.backgroundColor

    /** Records the current layers as an undo step and clears the redo history. */
    fun pushHistory() {
        undoStack = undoStack + listOf(layers)
        redoStack = emptyList()
    }

    fun undo() {
        if (!canUndo) return
        redoStack = redoStack + listOf(layers)
        layers    = undoStack.last()
        undoStack = undoStack.dropLast(1)
        clearSelection()
    }

    fun redo() {
        if (!canRedo) return
        undoStack = undoStack + listOf(layers)
        layers    = redoStack.last()
        redoStack = redoStack.dropLast(1)
        clearSelection()
    }

    fun selectLayer(index: Int) {
        if (index == activeLayerIndex) return
        activeLayerIndex = index
        clearSelection()
    }

    fun addLayer() {
        layers = layers + createLayer()
        activeLayerIndex = layers.lastIndex
        clearSelection()
    }

    fun deleteLayer(index: Int) {
        if (layers[index].isBackground) return
        layers = layers.filterIndexed { i, _ -> i != index }
        activeLayerIndex = activeLayerIndex.coerceAtMost(layers.lastIndex)
        clearSelection()
    }

    fun setLayerOpacity(index: Int, opacity: Float) {
        layers = layers.updated(index) { it.copy(opacity = opacity) }
    }

    fun setBackgroundColor(color: Color?) {
        layers = layers.map { if (it.isBackground) it.copy(backgroundColor = color) else it }
    }

    /** Layers that the layer at [sourceIndex] can be merged into. */
    fun combineTargetsFor(sourceIndex: Int): List<IndexedValue<Layer>> =
        layers.withIndex().filter { (i, layer) -> i != sourceIndex && !layer.isBackground }

    /** Appends the source layer's elements to the target layer and removes the source. */
    fun combineLayers(sourceIndex: Int, targetIndex: Int) {
        val source = layers[sourceIndex]
        val merged = layers[targetIndex].let { it.copy(elements = it.elements + source.elements) }
        val newLayers = layers
            .updated(targetIndex) { merged }
            .filterIndexed { i, _ -> i != sourceIndex }

        pushHistory()
        layers = newLayers
        activeLayerIndex = newLayers.indexOfFirst { it.id == merged.id }.coerceAtLeast(0)
        clearSelection()
    }

    /**
     * Adds [bitmap] on a new layer, centred in the current view and selected so it can be
     * moved immediately. It is placed axis-aligned to the page regardless of view rotation.
     */
    fun placeImage(bitmap: AndroidBitmap) {
        val canvasSize = viewport.canvasSize
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val screenHalfWidth = minOf(canvasSize.width, canvasSize.height) * IMAGE_PLACEMENT_FRACTION
        val worldHalfExtent = Offset(screenHalfWidth, screenHalfWidth / aspect) / viewport.scale
        val center = viewport.screenToWorld(canvasSize.center)

        val image = DrawElement.Image(bitmap.asImageBitmap(), center - worldHalfExtent, center + worldHalfExtent)

        pushHistory()
        layers = layers + createLayer(listOf(image))
        activeLayerIndex = layers.lastIndex
        selectedIndices  = setOf(0)
    }

    private fun createLayer(elements: List<DrawElement> = emptyList()): Layer =
        Layer(id = nextLayerId, name = "Layer $nextLayerId", elements = elements).also { nextLayerId++ }

    // ── Elements ──────────────────────────────────────────────────────────────

    fun addElement(element: DrawElement) {
        pushHistory()
        layers = layers.updated(activeLayerIndex) { it.copy(elements = it.elements + element) }
    }

    /**
     * Applies [transform] to every selected element without recording history; callers
     * that drive continuous gestures call [pushHistory] once when the gesture begins.
     */
    fun transformSelection(transform: (DrawElement) -> DrawElement) {
        val selection = selectedIndices
        layers = layers.updated(activeLayerIndex) { layer ->
            layer.copy(elements = layer.elements.mapIndexed { i, el -> if (i in selection) transform(el) else el })
        }
    }

    /**
     * Paint-bucket fill at [point]: flood-fills the enclosed region under it with [penColor],
     * or does nothing if the region there isn't bounded (see [floodFillAt]). [fillSensitivity]
     * controls how much colour drift (e.g. anti-aliasing around a thin or soft line) still
     * counts as part of the region, so the fill can be tuned to sit flush against the line
     * with no gap, whether it's a crisp, wide stroke or a thin, softer one.
     */
    fun performFill(point: Offset) {
        val fill = floodFillAt(this, point, penColor, fillSensitivity) ?: return
        addElement(fill)
    }

    fun deleteSelection() {
        val selection = selectedIndices
        pushHistory()
        layers = layers.updated(activeLayerIndex) { layer ->
            layer.copy(elements = layer.elements.filterIndexed { i, _ -> i !in selection })
        }
        clearSelection()
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    var selectedIndices by mutableStateOf(emptySet<Int>())
        private set

    val hasSelection: Boolean get() = selectedIndices.isNotEmpty()

    /** True when drags and pinches should move the selection rather than draw or pan. */
    val isTransformingSelection: Boolean get() = hasSelection && selectedTool.isSelectionTool

    /** Selects the active layer's elements that have any hit-test point matching [contains]. */
    fun selectWhere(contains: (Offset) -> Boolean) {
        val elements = activeLayer?.elements.orEmpty()
        selectedIndices = elements.indices.filter { i -> elements[i].hitTestPoints().any(contains) }.toSet()
    }

    fun clearSelection() {
        selectedIndices = emptySet()
    }

    fun activeSelectionBounds(): Rect? =
        activeLayer?.let { selectionBounds(it.elements, selectedIndices) }

    // ── Tool settings ─────────────────────────────────────────────────────────

    var selectedTool by mutableStateOf(Tool.PEN)
    var selectedShape by mutableStateOf<DrawShape?>(null)
        private set
    var penColor by mutableStateOf(Color.Black)
    private var penSize by mutableFloatStateOf(DEFAULT_PEN_SIZE)
    private var eraserSize by mutableFloatStateOf(DEFAULT_ERASER_SIZE)
    var fillSensitivity by mutableFloatStateOf(DEFAULT_FILL_SENSITIVITY)

    /** Stroke width for the current tool; the pen and eraser each remember their own size. */
    var brushSize: Float
        get() = if (selectedTool == Tool.PEN) penSize else eraserSize
        set(value) {
            if (selectedTool == Tool.PEN) penSize = value else eraserSize = value
        }

    val isErasing: Boolean get() = selectedTool == Tool.ERASER

    fun toggleShape(shape: DrawShape) {
        selectedShape = if (selectedShape == shape) null else shape
    }

    // ── In-progress gesture previews (world coordinates) ─────────────────────

    var livePoints by mutableStateOf(emptyList<Offset>())
    var liveShapeStart by mutableStateOf<Offset?>(null)
    var liveShapeEnd by mutableStateOf<Offset?>(null)
    var liveSelectionStart by mutableStateOf<Offset?>(null)
    var liveSelectionEnd by mutableStateOf<Offset?>(null)
    var liveLassoPoints by mutableStateOf(emptyList<Offset>())

    fun clearLivePreviews() {
        livePoints         = emptyList()
        liveShapeStart     = null
        liveShapeEnd       = null
        liveSelectionStart = null
        liveSelectionEnd   = null
        liveLassoPoints    = emptyList()
    }
}

val Tool.isSelectionTool: Boolean get() = this == Tool.SELECT || this == Tool.LASSO

private inline fun List<Layer>.updated(index: Int, transform: (Layer) -> Layer): List<Layer> =
    mapIndexed { i, layer -> if (i == index) transform(layer) else layer }
