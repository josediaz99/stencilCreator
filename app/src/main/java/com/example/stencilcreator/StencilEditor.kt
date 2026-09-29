package com.example.stencilcreator

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen space kept clear of the page when fitting it to the view (top buttons, bottom toolbar). */
private val FitInsetTop    = 110.dp
private val FitInsetBottom = 190.dp
private val FitInsetSide   = 24.dp

/**
 * The drawing editor for a single design: canvas, toolbars, layer panel and the
 * save / export / exit flows.
 */
@Composable
fun StencilEditor(
    document: EditorDocument,
    libraryState: LibraryState,
    storage: DesignStorage,
    exportToGallery: GalleryExporter,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope   = rememberCoroutineScope()
    val state   = remember { EditorState(document) }

    var showColorPicker    by remember { mutableStateOf(false) }
    var showLayerPanel     by remember { mutableStateOf(false) }
    var combineSourceIndex by remember { mutableStateOf<Int?>(null) }
    var pendingImage       by remember { mutableStateOf<AndroidBitmap?>(null) }
    var hasFittedView      by remember { mutableStateOf(false) }

    var showSaveDialog    by remember { mutableStateOf(false) }
    var showGalleryPrompt by remember { mutableStateOf(false) }
    var showExitPrompt    by remember { mutableStateOf(false) }
    var exitAfterSave     by remember { mutableStateOf(false) }
    var isSaving          by remember { mutableStateOf(false) }

    fun fitPageToView() = with(density) {
        state.viewport.fitPage(
            pageSize    = state.pageBounds.size,
            topInset    = FitInsetTop.toPx(),
            bottomInset = FitInsetBottom.toPx(),
            sideInset   = FitInsetSide.toPx()
        )
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) pendingImage = decodeBitmap(context, uri)
    }

    fun requestExit() {
        if (state.isDirty) showExitPrompt = true else onExit()
    }
    BackHandler { requestExit() }

    fun saveDesign(name: String, targetProjectId: String) {
        val snapshot    = state.layers
        val nextLayerId = state.nextLayerId
        val id          = state.designId ?: newId()
        val meta        = DesignMeta(id, name, targetProjectId, state.page, state.createdAt, System.currentTimeMillis())
        isSaving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { storage.writeDesign(id, snapshot, nextLayerId, state.page) }.isSuccess
            }
            isSaving = false
            if (saved) {
                libraryState.upsertDesign(meta)
                state.markSaved(id, name, targetProjectId, snapshot)
                showGalleryPrompt = true
            } else {
                exitAfterSave = false
                Toast.makeText(context, "Failed to save design", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportCurrentToGallery() {
        val snapshot = state.layers
        exportToGallery(state.designName) { renderDesignToBitmap(snapshot, state.page) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        EditorCanvas(
            state    = state,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    state.viewport.canvasSize = it.toSize()
                    if (!hasFittedView) {
                        fitPageToView()
                        hasFittedView = true
                    }
                }
        )

        EditorTopBar(
            title = when {
                isSaving      -> "Saving…"
                state.isDirty -> "${state.designName} •  ${state.page.label}"
                else          -> "${state.designName}  ${state.page.label}"
            },
            hasSelection      = state.hasSelection,
            onBack            = ::requestExit,
            onDeselect        = state::clearSelection,
            onDeleteSelection = state::deleteSelection,
            modifier          = Modifier.align(Alignment.TopStart)
        )

        UndoRedoButtons(
            canUndo  = state.canUndo,
            canRedo  = state.canRedo,
            onUndo   = state::undo,
            onRedo   = state::redo,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        if (showLayerPanel) {
            LayerPanel(
                layers                  = state.layers,
                activeLayerIndex        = state.activeLayerIndex,
                onSelectLayer           = state::selectLayer,
                onAddLayer              = state::addLayer,
                onDeleteLayer           = state::deleteLayer,
                onOpacityChange         = state::setLayerOpacity,
                onBackgroundColorChange = state::setBackgroundColor,
                onCombineRequest        = { combineSourceIndex = it },
                onClose                 = { showLayerPanel = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 72.dp)
                    .fillMaxHeight()
                    .padding(bottom = 160.dp)
            )
        }

        EditorToolbar(
            state              = state,
            isLayerPanelOpen   = showLayerPanel,
            onFitToView        = ::fitPageToView,
            onPickColor        = { showColorPicker = true },
            onImportImage      = { imagePicker.launch("image/*") },
            onSave             = { if (!isSaving) showSaveDialog = true },
            onToggleLayerPanel = { showLayerPanel = !showLayerPanel },
            modifier           = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showSaveDialog) {
        SaveDesignDialog(
            initialName      = state.designName,
            initialProjectId = state.projectId,
            library          = libraryState.library,
            onCreateProject  = { libraryState.createProject(it, null) },
            onSave           = { name, targetProjectId ->
                showSaveDialog = false
                saveDesign(name, targetProjectId)
            },
            onDismiss        = {
                showSaveDialog = false
                exitAfterSave  = false
            }
        )
    }

    if (showGalleryPrompt) {
        val finish = {
            showGalleryPrompt = false
            if (exitAfterSave) onExit()
        }
        SavedToAppDialog(
            designName      = state.designName,
            onSaveToGallery = {
                exportCurrentToGallery()
                finish()
            },
            onDismiss       = finish
        )
    }

    if (showExitPrompt) {
        UnsavedChangesDialog(
            designName = state.designName,
            onSave     = {
                showExitPrompt = false
                exitAfterSave  = true
                showSaveDialog = true
            },
            onDiscard  = {
                showExitPrompt = false
                onExit()
            },
            onDismiss  = { showExitPrompt = false }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            title         = "Color",
            color         = state.penColor,
            onColorChange = { state.penColor = it },
            onDismiss     = { showColorPicker = false }
        )
    }

    pendingImage?.let { image ->
        CropDialog(
            bitmap    = image,
            onConfirm = { cropped ->
                pendingImage = null
                state.placeImage(cropped)
            },
            onDismiss = { pendingImage = null }
        )
    }

    combineSourceIndex?.let { sourceIndex ->
        val source  = state.layers.getOrNull(sourceIndex)
        val targets = state.combineTargetsFor(sourceIndex)
        if (source != null && targets.isNotEmpty()) {
            CombineLayersDialog(
                source    = source,
                targets   = targets,
                onCombine = { targetIndex ->
                    state.combineLayers(sourceIndex, targetIndex)
                    combineSourceIndex = null
                },
                onDismiss = { combineSourceIndex = null }
            )
        } else {
            // Nothing to combine with (or the layer is gone): drop the request
            LaunchedEffect(sourceIndex) { combineSourceIndex = null }
        }
    }
}

@Composable
private fun SavedToAppDialog(designName: String, onSaveToGallery: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Saved \"$designName\"") },
        text             = { Text("Your design is saved in the app. Do you also want to save a copy to your gallery?") },
        confirmButton    = { Button(onClick = onSaveToGallery) { Text("Save to Gallery") } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

@Composable
private fun UnsavedChangesDialog(
    designName: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Unsaved changes") },
        text             = { Text("Save your changes to \"$designName\" before leaving?") },
        confirmButton    = { Button(onClick = onSave) { Text("Save") } },
        dismissButton    = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

/** Decodes the image at [uri], or returns null if it can't be read. */
private fun decodeBitmap(context: Context, uri: Uri): AndroidBitmap? =
    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
