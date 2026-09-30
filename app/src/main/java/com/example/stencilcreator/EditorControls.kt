package com.example.stencilcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Back button, design title, and actions for the current selection. */
@Composable
fun EditorTopBar(
    title: String,
    hasSelection: Boolean,
    onBack: () -> Unit,
    onDeselect: () -> Unit,
    onDeleteSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.statusBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onBack) { Text("‹ Projects") }
        InfoChip(
            text           = title,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            contentColor   = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasSelection) {
            Button(onClick = onDeselect) { Text("Deselect") }
            Button(onClick = onDeleteSelection) { Text("Delete") }
        }
    }
}

@Composable
fun UndoRedoButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.statusBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        Button(onClick = onRedo, enabled = canRedo) { Text("Redo") }
    }
}

/** Bottom toolbar: tools, shapes, view controls, colour, file actions, layers and brush size. */
@Composable
fun EditorToolbar(
    state: EditorState,
    isLayerPanelOpen: Boolean,
    onFitToView: () -> Unit,
    onPickColor: () -> Unit,
    onImportImage: () -> Unit,
    onSave: () -> Unit,
    onToggleLayerPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                ToolButton("Pen",    state.selectedTool == Tool.PEN)    { state.selectedTool = Tool.PEN }
                ToolButton("Eraser", state.selectedTool == Tool.ERASER) { state.selectedTool = Tool.ERASER }
                ToolButton("Fill",   state.selectedTool == Tool.FILL)   { state.selectedTool = Tool.FILL }
                ToolButton("Select", state.selectedTool == Tool.SELECT) { state.selectedTool = Tool.SELECT }
                ToolButton("Lasso",  state.selectedTool == Tool.LASSO)  { state.selectedTool = Tool.LASSO }
                ToolbarDivider()

                ToolButton("Circle", state.selectedShape == DrawShape.CIRCLE) { state.toggleShape(DrawShape.CIRCLE) }
                ToolButton("Square", state.selectedShape == DrawShape.SQUARE) { state.toggleShape(DrawShape.SQUARE) }
                ToolButton("Star",   state.selectedShape == DrawShape.STAR)   { state.toggleShape(DrawShape.STAR) }
                ToolbarDivider()

                InfoChip(
                    text           = "${state.viewport.displayAngle}°",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight     = FontWeight.Medium
                )
                ToolButton("Fit", selected = false, onClick = onFitToView)
                ColorSwatchButton(color = state.penColor, onClick = onPickColor)
                ToolbarDivider()

                ToolButton("Image", selected = false, onClick = onImportImage)
                ToolButton("Save", selected = false, onClick = onSave)
                ToolbarDivider()

                ToolButton("Layers", selected = isLayerPanelOpen, onClick = onToggleLayerPanel)
                InfoChip(
                    text              = state.activeLayer?.name.orEmpty(),
                    containerColor    = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor      = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize          = 12.sp,
                    horizontalPadding = 10.dp
                )
            }

            if (state.selectedTool == Tool.FILL) {
                Text("Fill sensitivity: ${state.fillSensitivity.toInt()}", fontSize = 13.sp)
                Slider(
                    value         = state.fillSensitivity,
                    onValueChange = { state.fillSensitivity = it },
                    valueRange    = FILL_SENSITIVITY_RANGE,
                    modifier      = Modifier.fillMaxWidth()
                )
            } else {
                Text("Size: ${state.brushSize.toInt()}", fontSize = 13.sp)
                Slider(
                    value         = state.brushSize,
                    onValueChange = { state.brushSize = it },
                    valueRange    = BRUSH_SIZE_RANGE,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Pill-shaped toggle used in toolbars. */
@Composable
fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape    = RoundedCornerShape(8.dp),
        color    = if (selected) colors.primary else colors.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text       = label,
            color      = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize   = 14.sp
        )
    }
}

/** Non-interactive label styled to sit alongside [ToolButton]s. */
@Composable
private fun InfoChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = 14.sp,
    horizontalPadding: Dp = 12.dp
) {
    Surface(shape = RoundedCornerShape(8.dp), color = containerColor) {
        Text(
            text       = text,
            modifier   = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
            fontSize   = fontSize,
            fontWeight = fontWeight,
            color      = contentColor
        )
    }
}

@Composable
private fun ColorSwatchButton(color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
