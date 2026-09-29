package com.example.stencilcreator

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface EditorTarget {
    data class New(val page: PageSize, val projectId: String?) : EditorTarget
    data class Open(val designId: String) : EditorTarget
}

// Everything the editor needs to start. designId == null means never saved.
data class EditorDocument(
    val designId: String?,
    val name: String,
    val projectId: String?,
    val page: PageSize,
    val createdAt: Long,
    val layers: List<Layer>,
    val nextLayerId: Int
)

@Composable
fun StencilApp() {
    val context      = LocalContext.current
    val storage      = remember { DesignStorage(context.filesDir) }
    val libraryState = remember { LibraryState(storage) }
    val exporter     = rememberGalleryExporter()

    var editorTarget      by remember { mutableStateOf<EditorTarget?>(null) }
    var browsingProjectId by remember { mutableStateOf<String?>(null) }

    val target = editorTarget
    if (target == null) {
        ProjectsScreen(
            libraryState     = libraryState,
            storage          = storage,
            currentProjectId = browsingProjectId,
            onNavigate       = { browsingProjectId = it },
            onOpenDesign     = { editorTarget = EditorTarget.Open(it.id) },
            onNewDesign      = { page -> editorTarget = EditorTarget.New(page, browsingProjectId) },
            exportToGallery  = exporter
        )
    } else {
        EditorRoute(
            target       = target,
            libraryState = libraryState,
            storage      = storage,
            exporter     = exporter,
            onExit       = { editorTarget = null }
        )
    }
}

@Composable
private fun EditorRoute(
    target: EditorTarget,
    libraryState: LibraryState,
    storage: DesignStorage,
    exporter: GalleryExporter,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var document by remember(target) { mutableStateOf<EditorDocument?>(null) }

    LaunchedEffect(target) {
        document = when (target) {
            is EditorTarget.New -> EditorDocument(
                designId    = null,
                name        = "Untitled design",
                projectId   = target.projectId,
                page        = target.page,
                createdAt   = System.currentTimeMillis(),
                layers      = listOf(Layer(id = 0, name = "Background", isBackground = true, backgroundColor = Color.White)),
                nextLayerId = 1
            )
            is EditorTarget.Open -> {
                val meta   = libraryState.library.design(target.designId)
                val stored = meta?.let { withContext(Dispatchers.IO) { storage.readDesign(it.id) } }
                if (meta == null || stored == null) {
                    Toast.makeText(context, "Could not open design", Toast.LENGTH_SHORT).show()
                    onExit()
                    return@LaunchedEffect
                }
                EditorDocument(meta.id, meta.name, meta.projectId, meta.page, meta.createdAt, stored.layers, stored.nextLayerId)
            }
        }
    }

    val doc = document
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        key(target) {
            StencilEditor(
                document        = doc,
                libraryState    = libraryState,
                storage         = storage,
                exportToGallery = exporter,
                onExit          = onExit
            )
        }
    }
}
