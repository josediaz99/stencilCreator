package com.example.stencilcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ── New design (page size) dialog ─────────────────────────────────────────────

@Composable
fun NewDesignDialog(
    savedSizes: List<SizePreset>,
    onRemoveSavedSize: (SizePreset) -> Unit,
    onCreate: (page: PageSize, saveSize: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val default = BUILT_IN_SIZE_PRESETS.first().size
    var unit       by remember { mutableStateOf(default.unit) }
    var widthText  by remember { mutableStateOf(formatDimension(default.width)) }
    var heightText by remember { mutableStateOf(formatDimension(default.height)) }
    var saveSize   by remember { mutableStateOf(false) }

    val width  = parseDimension(widthText)
    val height = parseDimension(heightText)
    val page   = if (width != null && height != null) PageSize(width, height, unit) else null
    val isValid = page != null && page.isWithinLimits
    val isKnownSize = page != null &&
        (BUILT_IN_SIZE_PRESETS + savedSizes).any { it.size.matches(page) }

    fun applySize(size: PageSize) {
        unit       = size.unit
        widthText  = formatDimension(size.width)
        heightText = formatDimension(size.height)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier       = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.92f),
            shape          = RoundedCornerShape(16.dp),
            color          = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier            = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("New Design", style = MaterialTheme.typography.titleLarge)

                SectionLabel("Units")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeUnit.entries.forEach { u ->
                        val label = when (u) {
                            SizeUnit.INCH -> "Inches"
                            SizeUnit.CM   -> "Centimeters"
                            SizeUnit.PX   -> "Pixels"
                        }
                        ToolButton(label, unit == u) {
                            // Keep the same physical size, expressed in the new unit
                            if (page != null) applySize(page.convertedTo(u)) else unit = u
                        }
                    }
                }

                SectionLabel("Page size")
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DimensionField("Width", widthText, Modifier.weight(1f)) { widthText = it }
                    Text("×", fontSize = 18.sp)
                    DimensionField("Height", heightText, Modifier.weight(1f)) { heightText = it }
                    Text(unit.label, fontSize = 16.sp)
                    TextButton(onClick = { page?.let { applySize(it.swapped()) } }, enabled = page != null) {
                        Text("⇄ Rotate")
                    }
                }
                val info = when {
                    page == null        -> "Enter a width and height greater than 0."
                    !page.isWithinLimits -> "Too large: ${page.widthPx} × ${page.heightPx} px. " +
                                           "Each side must be at most $MAX_PAGE_PX px."
                    unit == SizeUnit.PX -> "${page.widthPx} × ${page.heightPx} px"
                    else                -> "Exports at ${page.widthPx} × ${page.heightPx} px (${PAGE_DPI.toInt()} DPI)"
                }
                Text(
                    info,
                    fontSize = 13.sp,
                    color    = if (isValid) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.error
                )

                SectionLabel("Common sizes")
                PresetGrid(
                    presets   = BUILT_IN_SIZE_PRESETS,
                    current   = page,
                    onSelect  = { applySize(it.size) },
                    onRemove  = null
                )

                if (savedSizes.isNotEmpty()) {
                    SectionLabel("My sizes")
                    PresetGrid(
                        presets  = savedSizes,
                        current  = page,
                        onSelect = { applySize(it.size) },
                        onRemove = onRemoveSavedSize
                    )
                }

                if (isValid && !isKnownSize) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.clickable { saveSize = !saveSize }
                    ) {
                        Checkbox(checked = saveSize, onCheckedChange = { saveSize = it })
                        Text("Save ${page!!.label} to My sizes")
                    }
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { page?.let { onCreate(it, saveSize && !isKnownSize) } },
                        enabled = isValid
                    ) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DimensionField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value           = value,
        onValueChange   = { text -> onChange(text.filter { it.isDigit() || it == '.' || it == ',' }) },
        label           = { Text(label) },
        singleLine      = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier        = modifier
    )
}

@Composable
private fun PresetGrid(
    presets: List<SizePreset>,
    current: PageSize?,
    onSelect: (SizePreset) -> Unit,
    onRemove: ((SizePreset) -> Unit)?
) {
    val columns = 3
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val selected = current != null && preset.size.matches(current)
                    Surface(
                        shape    = RoundedCornerShape(10.dp),
                        color    = if (selected) MaterialTheme.colorScheme.primaryContainer
                                   else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).clickable { onSelect(preset) }
                    ) {
                        Row(
                            modifier          = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.name, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (preset.name != preset.size.label) {
                                    Text(preset.size.label, fontSize = 12.sp,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (onRemove != null) {
                                TextButton(onClick = { onRemove(preset) }) {
                                    Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ── Save design dialog ────────────────────────────────────────────────────────

// Asks for the design name and the project to store it in. If the user types a
// new project name instead of picking one, that project is created on save.
@Composable
fun SaveDesignDialog(
    initialName: String,
    initialProjectId: String?,
    library: Library,
    onCreateProject: (name: String) -> Project,
    onSave: (name: String, projectId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val tree = library.flattenedTree()
    var name              by remember { mutableStateOf(initialName) }
    var selectedProjectId by remember {
        mutableStateOf(library.project(initialProjectId)?.id ?: tree.firstOrNull()?.first?.id)
    }
    var newProjectName by remember { mutableStateOf(if (tree.isEmpty()) "My Designs" else "") }

    val useNewProject = newProjectName.isNotBlank()
    val canSave = name.isNotBlank() && (useNewProject || selectedProjectId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Design") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Design name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                SectionLabel("Project")
                if (tree.isNotEmpty()) {
                    ProjectTreeList(
                        tree       = tree,
                        selectedId = if (useNewProject) null else selectedProjectId,
                        onSelect   = { selectedProjectId = it; newProjectName = "" }
                    )
                }
                OutlinedTextField(
                    value         = newProjectName,
                    onValueChange = { newProjectName = it },
                    label         = { Text(if (tree.isEmpty()) "Project name" else "…or create a new project") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val projectId = if (useNewProject) onCreateProject(newProjectName).id else selectedProjectId!!
                    onSave(name.trim(), projectId)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectTreeList(
    tree: List<Pair<Project, Int>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    isEnabled: (String) -> Boolean = { true }
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            items(tree, key = { it.first.id }) { (project, depth) ->
                PickerRow(
                    label    = "📁  ${project.name}",
                    indent   = depth,
                    selected = project.id == selectedId,
                    enabled  = isEnabled(project.id),
                    onClick  = { onSelect(project.id) }
                )
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, indent: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = (12 + indent * 20).dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color      = if (enabled) MaterialTheme.colorScheme.onSurface
                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
        )
    }
}

// ── Generic dialogs ───────────────────────────────────────────────────────────

// Distinguishes "Top level" (projectId == null) from "nothing picked yet" (no PickedTarget).
private data class PickedTarget(val projectId: String?)

// Lets the user choose a destination project. When allowTopLevel is set, a
// "Top level" row is offered, reported as a null project id.
@Composable
fun ProjectPickerDialog(
    title: String,
    message: String?,
    library: Library,
    excludedIds: Set<String>,
    disabledTargets: Set<String?>,
    allowTopLevel: Boolean,
    confirmLabel: String,
    onPick: (projectId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val tree = library.flattenedTree().filter { it.first.id !in excludedIds }
    var picked by remember { mutableStateOf<PickedTarget?>(null) }
    val topLevelEnabled = allowTopLevel && null !in disabledTargets

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message != null) Text(message, fontSize = 14.sp)
                if (tree.isEmpty() && !topLevelEnabled) {
                    Text("There are no other projects to choose from.",
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (allowTopLevel) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            PickerRow(
                                label    = "⌂  Top level (no parent project)",
                                indent   = 0,
                                selected = picked != null && picked?.projectId == null,
                                enabled  = topLevelEnabled,
                                onClick  = { picked = PickedTarget(null) }
                            )
                        }
                    }
                    if (tree.isNotEmpty()) {
                        ProjectTreeList(
                            tree       = tree,
                            selectedId = picked?.projectId,
                            onSelect   = { picked = PickedTarget(it) },
                            isEnabled  = { it !in disabledTargets }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { picked?.let { onPick(it.projectId) } }, enabled = picked != null) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value         = value,
                onValueChange = { value = it },
                label         = { Text(label) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text  = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = if (destructive) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor   = MaterialTheme.colorScheme.onError
                ) else ButtonDefaults.buttonColors()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
