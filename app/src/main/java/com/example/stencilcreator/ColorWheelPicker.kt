package com.example.stencilcreator

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val HueSweep = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
)

private fun hsvColor(hue: Float, saturation: Float, value: Float) =
    Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

/** A titled dialog wrapping a [ColorWheelPicker]; changes apply live as the user drags. */
@Composable
fun ColorPickerDialog(
    title: String,
    color: Color,
    onColorChange: (Color) -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                ColorWheelPicker(color = color, onColorChange = onColorChange)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/** HSV picker: a hue/saturation wheel, a brightness slider, and a swatch with the hex code. */
@Composable
fun ColorWheelPicker(color: Color, onColorChange: (Color) -> Unit) {
    val hsv = remember(color) {
        FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
    }
    // Gesture handlers outlive recompositions, so read the latest HSV through state
    val latestHue by rememberUpdatedState(hsv[0])
    val latestSaturation by rememberUpdatedState(hsv[1])
    val latestValue by rememberUpdatedState(hsv[2])

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HueSaturationWheel(
            hue        = hsv[0],
            saturation = hsv[1],
            color      = color,
            onPick     = { hue, saturation -> onColorChange(hsvColor(hue, saturation, latestValue)) }
        )

        Text("Brightness", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        BrightnessSlider(
            value           = hsv[2],
            fullBrightColor = hsvColor(hsv[0], hsv[1], 1f),
            onPick          = { value -> onColorChange(hsvColor(latestHue, latestSaturation, value)) }
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(
                text       = "#%06X".format(color.toArgb() and 0xFFFFFF),
                fontFamily = FontFamily.Monospace,
                fontSize   = 14.sp
            )
        }
    }
}

/** Hue runs around the wheel and saturation from the centre (white) to the rim. */
@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    color: Color,
    onPick: (hue: Float, saturation: Float) -> Unit
) {
    val currentOnPick by rememberUpdatedState(onPick)
    Canvas(
        modifier = Modifier
            .size(220.dp)
            .pointerInput(Unit) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(center.x, center.y)
                fun pick(position: Offset) {
                    val d = position - center
                    val pickedHue = (atan2(d.y, d.x).radiansToDegrees() + 360f) % 360f
                    val pickedSaturation = (sqrt(d.x * d.x + d.y * d.y) / radius).coerceIn(0f, 1f)
                    currentOnPick(pickedHue, pickedSaturation)
                }
                detectDragGestures(
                    onDragStart = { pick(it) },
                    onDrag      = { change, _ -> pick(change.position) }
                )
            }
    ) {
        val radius = minOf(center.x, center.y)
        drawCircle(brush = Brush.sweepGradient(HueSweep, center = center), radius = radius, center = center)
        drawCircle(
            brush  = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = radius),
            radius = radius,
            center = center
        )

        val angle = hue.degreesToRadians()
        val indicator = center + Offset(cos(angle), sin(angle)) * (saturation * radius)
        drawCircle(Color.White, 12f, indicator)
        drawCircle(color, 9f, indicator)
    }
}

@Composable
private fun BrightnessSlider(value: Float, fullBrightColor: Color, onPick: (Float) -> Unit) {
    val currentOnPick by rememberUpdatedState(onPick)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                val thumbRadius = size.height / 2f
                val trackWidth  = (size.width - 2 * thumbRadius).coerceAtLeast(1f)
                fun pick(x: Float) = currentOnPick(((x - thumbRadius) / trackWidth).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { pick(it.x) },
                    onDrag      = { change, _ -> pick(change.position.x) }
                )
            }
    ) {
        val thumbRadius = size.height / 2f
        val trackWidth  = (size.width - 2 * thumbRadius).coerceAtLeast(1f)
        val trackHeight = size.height * 0.4f
        drawRoundRect(
            brush        = Brush.horizontalGradient(
                listOf(Color.Black, fullBrightColor),
                startX = thumbRadius,
                endX   = thumbRadius + trackWidth
            ),
            topLeft      = Offset(thumbRadius, (size.height - trackHeight) / 2f),
            size         = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        val thumbCenter = Offset(thumbRadius + value * trackWidth, size.height / 2f)
        drawCircle(Color.White, thumbRadius, thumbCenter)
        drawCircle(Color.Gray, thumbRadius - 2.dp.toPx(), thumbCenter, style = Stroke(2.dp.toPx()))
    }
}
