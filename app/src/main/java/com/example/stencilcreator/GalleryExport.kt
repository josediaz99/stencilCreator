package com.example.stencilcreator

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap as AndroidBitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GALLERY_ALBUM = "StencilCreator"
private const val MAX_FILENAME_LENGTH = 60
private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9 _-]")

/**
 * Saves an image to the device gallery: (design name, render) -> Unit.
 * `render` runs off the main thread and may return null if the design can't be produced.
 */
typealias GalleryExporter = (name: String, render: () -> AndroidBitmap?) -> Unit

private data class PendingExport(val name: String, val render: () -> AndroidBitmap?)

/** Writes [bitmap] as a PNG to Pictures/StencilCreator. Returns the new image's URI, or null on failure. */
fun saveBitmapToGallery(context: Context, bitmap: AndroidBitmap, name: String): Uri? {
    val safeName = name.replace(UNSAFE_FILENAME_CHARS, "_").trim().take(MAX_FILENAME_LENGTH).ifBlank { "Stencil" }
    val filename = "${safeName}_${System.currentTimeMillis()}.png"
    val usesScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (usesScopedStorage) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(AndroidBitmap.CompressFormat.PNG, 100, out)
    }
    if (usesScopedStorage) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return uri
}

/**
 * Returns a [GalleryExporter] that renders and saves in the background, asking for the
 * legacy storage permission first on Android 9 and below.
 */
@Composable
fun rememberGalleryExporter(): GalleryExporter {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    fun export(request: PendingExport) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { request.render()?.let { saveBitmapToGallery(context, it, request.name) } }.getOrNull()
            }
            val message = if (uri != null) "Saved \"${request.name}\" to gallery" else "Failed to save image"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pending
        pending = null
        when {
            !granted        -> Toast.makeText(context, "Storage permission needed to save", Toast.LENGTH_SHORT).show()
            request != null -> export(request)
        }
    }

    return { name, render ->
        val request = PendingExport(name, render)
        if (hasGalleryWriteAccess(context)) {
            export(request)
        } else {
            pending = request
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

private fun hasGalleryWriteAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
