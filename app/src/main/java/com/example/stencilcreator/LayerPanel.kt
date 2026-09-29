package com.example.stencilcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Side sheet listing layers top-most first, with per-layer controls and an add button. */
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier        = modifier.width(270.dp),
        shadowElevation = 16.dp,
        shape           = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        color           = MaterialTheme.colorScheme.surface,
        tonalElevation  = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            HorizontalRule()

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (index in layers.indices.reversed()) {
                    val layer = layers[index]
                    LayerRow(
                        layer                   = layer,
                        isActive                = index == activeLayerIndex,
                        onSelect                = { onSelectLayer(index) },
                        onDelete                = { onDeleteLayer(index) },
                        onOpacityChange         = { opacity -> onOpacityChange(index, opacity) },
                        onBackgroundColorChange = onBackgroundColorChange,
                        onCombine               = { onCombineRequest(index) }
                    )
                    HorizontalRule()
                }
            }

            HorizontalRule()
            TextButton(onClick = onAddLayer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("+ Add Layer")
            }
        }
    }
}

/**
 * One layer's entry. The active row expands to show opacity and combine controls, or
 * background colour controls for the background layer.
 */
@Composable
private fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBackgroundColorChange: (Color?) -> Unit,
    onCombine: () -> Unit
) {
    var showBackgroundPicker by remember { mutableStateOf(false) }
    val opacityPercent = "${(layer.opacity * 100).toInt()}%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = layer.name,
                modifier   = Modifier.weight(1f),
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                fontSize   = 14.sp
            )
            if (layer.isBackground) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(layer.backgroundColor ?: Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .clickable { showBackgroundPicker = true }
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Text(opacityPercent, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                    Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (isActive) {
            if (layer.isBackground) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { showBackgroundPicker = true }) { Text("Change Color", fontSize = 12.sp) }
                    TextButton(onClick = { onBackgroundColorChange(null) }) { Text("Transparent", fontSize = 12.sp) }
                }
            } else {
                Text(
                    "Opacity: $opacityPercent",
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
                TextButton(onClick = onCombine, modifier = Modifier.fillMaxWidth()) {
                    Text("Combine with...", fontSize = 13.sp)
                }
            }
        }
    }

    if (showBackgroundPicker && layer.isBackground) {
        ColorPickerDialog(
            title         = "Background Color",
            color         = layer.backgroundColor ?: Color.White,
            onColorChange = onBackgroundColorChange,
            onDismiss     = { showBackgroundPicker = false }
        )
    }
}

/** Lets the user pick which layer the [source] layer should be merged into. */
@Composable
fun CombineLayersDialog(
    source: Layer,
    targets: List<IndexedValue<Layer>>,
    onCombine: (targetIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape          = RoundedCornerShape(16.dp),
            color          = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier            = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Combine \"${source.name}\" with:", style = MaterialTheme.typography.titleMedium)
                targets.forEach { (targetIndex, target) ->
                    Button(onClick = { onCombine(targetIndex) }, modifier = Modifier.fillMaxWidth()) {
                        Text(target.name)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun HorizontalRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
