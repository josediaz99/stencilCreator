package com.example.stencilcreator

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ProjectsDialog {
    data object NewProject : ProjectsDialog
    data object NewDesign : ProjectsDialog
    data class RenameProject(val project: Project) : ProjectsDialog
    data class MoveProject(val project: Project) : ProjectsDialog
    data class JoinProject(val project: Project) : ProjectsDialog
    data class DeleteProject(val project: Project) : ProjectsDialog
    data class RenameDesign(val design: DesignMeta) : ProjectsDialog
    data class MoveDesign(val design: DesignMeta) : ProjectsDialog
    data class DeleteDesign(val design: DesignMeta) : ProjectsDialog
}

private data class MenuAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

// Browses the project tree. currentProjectId == null shows the top-level projects;
// otherwise the screen shows that project's sub-projects and designs.
@Composable
fun ProjectsScreen(
    libraryState: LibraryState,
    storage: DesignStorage,
    currentProjectId: String?,
    onNavigate: (projectId: String?) -> Unit,
    onOpenDesign: (DesignMeta) -> Unit,
    onNewDesign: (PageSize) -> Unit,
    exportToGallery: GalleryExporter
) {
    val library  = libraryState.library
    val current  = library.project(currentProjectId)
    val children = library.childProjects(current?.id)
    val designs  = current?.let { library.designsIn(it.id) } ?: emptyList()
    var dialog by remember { mutableStateOf<ProjectsDialog?>(null) }

    // The open project may disappear (e.g. deleted); fall back to the top level
    LaunchedEffect(currentProjectId, current) {
        if (currentProjectId != null && current == null) onNavigate(null)
    }
    BackHandler(enabled = current != null) { onNavigate(current?.parentId) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ───────────────────────────────────────────────────────
            Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier          = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.name ?: "Projects",
                            style    = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Breadcrumbs(library.pathTo(current?.id), onNavigate)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current != null) {
                            OutlinedButton(onClick = { onNavigate(current.parentId) }) { Text("‹ Back") }
                        }
                        OutlinedButton(onClick = { dialog = ProjectsDialog.NewProject }) {
                            Text(if (current == null) "+ New Project" else "+ New Sub-project")
                        }
                        Button(onClick = { dialog = ProjectsDialog.NewDesign }) { Text("+ New Design") }
                    }
                }
            }

            // ── Content ──────────────────────────────────────────────────────
            if (children.isEmpty() && designs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (current == null)
                            "No projects yet.\nCreate a project to organize your designs, or start a new design."
                        else
                            "This project is empty.\nStart a new design or add a sub-project.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Adaptive(220.dp),
                    contentPadding        = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(16.dp),
                    modifier              = Modifier.fillMaxSize()
                ) {
                    if (children.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            GridHeader(if (current == null) "Projects" else "Sub-projects")
                        }
                        items(children, key = { it.id }) { project ->
                            ProjectCard(
                                project       = project,
                                designCount   = library.designIdsUnder(project.id).size,
                                subCount      = library.childProjects(project.id).size,
                                onOpen        = { onNavigate(project.id) },
                                actions       = listOf(
                                    MenuAction("Rename")     { dialog = ProjectsDialog.RenameProject(project) },
                                    MenuAction("Move to…")   { dialog = ProjectsDialog.MoveProject(project) },
                                    MenuAction("Join into…") { dialog = ProjectsDialog.JoinProject(project) },
                                    MenuAction("Delete", destructive = true) {
                                        dialog = ProjectsDialog.DeleteProject(project)
                                    }
                                )
                            )
                        }
                    }
                    if (designs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { GridHeader("Designs") }
                        items(designs, key = { it.id }) { design ->
                            DesignCard(
                                design  = design,
                                storage = storage,
                                onOpen  = { onOpenDesign(design) },
                                actions = listOf(
                                    MenuAction("Open")     { onOpenDesign(design) },
                                    MenuAction("Rename")   { dialog = ProjectsDialog.RenameDesign(design) },
                                    MenuAction("Move to…") { dialog = ProjectsDialog.MoveDesign(design) },
                                    MenuAction("Save to Gallery") {
                                        exportToGallery(design.name) {
                                            storage.readDesign(design.id)?.let { renderDesignToBitmap(it.layers, design.page) }
                                        }
                                    },
                                    MenuAction("Delete", destructive = true) {
                                        dialog = ProjectsDialog.DeleteDesign(design)
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    val dismiss = { dialog = null }
    when (val d = dialog) {
        null -> {}
        ProjectsDialog.NewProject -> TextInputDialog(
            title        = if (current == null) "New Project" else "New Sub-project in \"${current.name}\"",
            label        = "Project name",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm    = { libraryState.createProject(it, current?.id); dismiss() },
            onDismiss    = dismiss
        )
        ProjectsDialog.NewDesign -> NewDesignDialog(
            savedSizes        = libraryState.savedSizes,
            onRemoveSavedSize = libraryState::removeSavedSize,
            onCreate          = { page, saveSize ->
                if (saveSize) libraryState.addSavedSize(page)
                dismiss()
                onNewDesign(page)
            },
            onDismiss         = dismiss
        )
        is ProjectsDialog.RenameProject -> TextInputDialog(
            title        = "Rename Project",
            label        = "Project name",
            initialValue = d.project.name,
            confirmLabel = "Rename",
            onConfirm    = { libraryState.renameProject(d.project.id, it); dismiss() },
            onDismiss    = dismiss
        )
        is ProjectsDialog.MoveProject -> ProjectPickerDialog(
            title           = "Move \"${d.project.name}\" to…",
            message         = "The project keeps all of its designs and sub-projects.",
            library         = library,
            excludedIds     = library.subtreeIds(d.project.id),
            disabledTargets = setOf(d.project.parentId),
            allowTopLevel   = true,
            confirmLabel    = "Move",
            onPick          = { libraryState.moveProject(d.project.id, it); dismiss() },
            onDismiss       = dismiss
        )
        is ProjectsDialog.JoinProject -> ProjectPickerDialog(
            title           = "Join \"${d.project.name}\" into…",
            message         = "Everything in \"${d.project.name}\" (designs and sub-projects) moves into the " +
                              "chosen project, then \"${d.project.name}\" is removed.",
            library         = library,
            excludedIds     = library.subtreeIds(d.project.id),
            disabledTargets = emptySet(),
            allowTopLevel   = false,
            confirmLabel    = "Join",
            onPick          = { target ->
                if (target != null) libraryState.mergeProject(d.project.id, target)
                dismiss()
            },
            onDismiss       = dismiss
        )
        is ProjectsDialog.DeleteProject -> {
            val designCount = library.designIdsUnder(d.project.id).size
            val subCount    = library.subtreeIds(d.project.id).size - 1
            ConfirmDialog(
                title        = "Delete \"${d.project.name}\"?",
                message      = "This permanently deletes the project" +
                               (if (subCount > 0) ", ${plural(subCount, "sub-project")}" else "") +
                               " and ${plural(designCount, "design")} inside it. This can't be undone.",
                confirmLabel = "Delete",
                destructive  = true,
                onConfirm    = { libraryState.deleteProject(d.project.id); dismiss() },
                onDismiss    = dismiss
            )
        }
        is ProjectsDialog.RenameDesign -> TextInputDialog(
            title        = "Rename Design",
            label        = "Design name",
            initialValue = d.design.name,
            confirmLabel = "Rename",
            onConfirm    = { libraryState.renameDesign(d.design.id, it); dismiss() },
            onDismiss    = dismiss
        )
        is ProjectsDialog.MoveDesign -> ProjectPickerDialog(
            title           = "Move \"${d.design.name}\" to…",
            message         = null,
            library         = library,
            excludedIds     = emptySet(),
            disabledTargets = setOf(d.design.projectId),
            allowTopLevel   = false,
            confirmLabel    = "Move",
            onPick          = { target ->
                if (target != null) libraryState.moveDesign(d.design.id, target)
                dismiss()
            },
            onDismiss       = dismiss
        )
        is ProjectsDialog.DeleteDesign -> ConfirmDialog(
            title        = "Delete \"${d.design.name}\"?",
            message      = "This permanently deletes the design from the app. Copies already saved to " +
                           "your gallery are not affected.",
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = { libraryState.deleteDesign(d.design.id); dismiss() },
            onDismiss    = dismiss
        )
    }
}

private fun plural(count: Int, noun: String) = "$count $noun" + if (count == 1) "" else "s"

@Composable
private fun Breadcrumbs(path: List<Project>, onNavigate: (String?) -> Unit) {
    Row(
        modifier          = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crumb("All Projects", isLast = path.isEmpty()) { onNavigate(null) }
        path.forEachIndexed { i, project ->
            Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Crumb(project.name, isLast = i == path.lastIndex) { onNavigate(project.id) }
        }
    }
}

@Composable
private fun Crumb(label: String, isLast: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize   = 13.sp,
        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
        color      = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier   = Modifier.clickable(enabled = !isLast, onClick = onClick).padding(vertical = 4.dp)
    )
}

@Composable
private fun GridHeader(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.titleMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ProjectCard(
    project: Project,
    designCount: Int,
    subCount: Int,
    onOpen: () -> Unit,
    actions: List<MenuAction>
) {
    Surface(
        shape           = RoundedCornerShape(12.dp),
        color           = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 2.dp,
        modifier        = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            modifier          = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📁", fontSize = 28.sp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(project.name, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis,
                     color = MaterialTheme.colorScheme.onSecondaryContainer)
                val summary = buildList {
                    add(plural(designCount, "design"))
                    if (subCount > 0) add(plural(subCount, "sub-project"))
                }.joinToString(" · ")
                Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            }
            CardMenu(actions)
        }
    }
}

@Composable
private fun DesignCard(
    design: DesignMeta,
    storage: DesignStorage,
    onOpen: () -> Unit,
    actions: List<MenuAction>
) {
    val thumbnail by produceState<ImageBitmap?>(null, design.id, design.updatedAt) {
        value = withContext(Dispatchers.IO) {
            val file = storage.thumbnailFile(design.id)
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
    }

    Surface(
        shape           = RoundedCornerShape(12.dp),
        color           = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier        = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Column {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color(0xFFE0E0E0))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val thumb = thumbnail
                if (thumb != null) {
                    Image(
                        bitmap             = thumb,
                        contentDescription = design.name,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier.fillMaxSize()
                    )
                }
            }
            Row(
                modifier          = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(design.name, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(design.page.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CardMenu(actions)
            }
        }
    }
}

@Composable
private fun CardMenu(actions: List<MenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("⋮", fontSize = 20.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text    = {
                        Text(
                            action.label,
                            color = if (action.destructive) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { expanded = false; action.onClick() }
                )
            }
        }
    }
}
