package com.example.stencilcreator

import android.graphics.BitmapFactory
import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.roundToInt

// Layers and next layer id of a design as stored on disk.
data class StoredDesign(val layers: List<Layer>, val nextLayerId: Int)

// On-disk layout (app-private storage, removed on uninstall):
//   library/library.json          projects + design metadata
//   library/sizes.json            user-saved custom page sizes
//   library/designs/<id>/design.json, thumb.png, img_<n>.png
class DesignStorage(baseDir: File) {
    private val root        = File(baseDir, "library").apply { mkdirs() }
    private val designsDir  = File(root, "designs").apply { mkdirs() }
    private val libraryFile = File(root, "library.json")
    private val sizesFile   = File(root, "sizes.json")

    fun thumbnailFile(designId: String) = File(File(designsDir, designId), THUMB_FILE)

    // ── Library index ─────────────────────────────────────────────────────────

    fun loadLibrary(): Library {
        if (!libraryFile.exists()) return Library()
        return runCatching {
            val json = JSONObject(libraryFile.readText())
            val projects = json.getJSONArray("projects").objects().map {
                Project(
                    id        = it.getString("id"),
                    name      = it.getString("name"),
                    parentId  = it.optStringOrNull("parentId"),
                    createdAt = it.optLong("createdAt")
                )
            }
            val designs = json.getJSONArray("designs").objects().map {
                DesignMeta(
                    id        = it.getString("id"),
                    name      = it.getString("name"),
                    projectId = it.getString("projectId"),
                    page      = pageFromJson(it.getJSONObject("page")),
                    createdAt = it.optLong("createdAt"),
                    updatedAt = it.optLong("updatedAt")
                )
            }
            Library(projects, designs)
        }.getOrDefault(Library())
    }

    fun saveLibrary(library: Library) {
        val json = JSONObject()
            .put("version", 1)
            .put("projects", JSONArray(library.projects.map {
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("parentId", it.parentId ?: JSONObject.NULL)
                    .put("createdAt", it.createdAt)
            }))
            .put("designs", JSONArray(library.designs.map {
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("projectId", it.projectId)
                    .put("page", pageToJson(it.page))
                    .put("createdAt", it.createdAt)
                    .put("updatedAt", it.updatedAt)
            }))
        writeAtomically(libraryFile, json.toString())
    }

    // ── Saved page sizes ──────────────────────────────────────────────────────

    fun loadSavedSizes(): List<SizePreset> {
        if (!sizesFile.exists()) return emptyList()
        return runCatching {
            JSONArray(sizesFile.readText()).objects().map {
                SizePreset(it.getString("name"), pageFromJson(it.getJSONObject("page")))
            }
        }.getOrDefault(emptyList())
    }

    fun saveSizes(sizes: List<SizePreset>) {
        val json = JSONArray(sizes.map { JSONObject().put("name", it.name).put("page", pageToJson(it.size)) })
        writeAtomically(sizesFile, json.toString())
    }

    // ── Design contents ───────────────────────────────────────────────────────

    // Writes into a temp directory and swaps it in, so a failed save never
    // leaves a half-written design behind.
    fun writeDesign(id: String, layers: List<Layer>, nextLayerId: Int, page: PageSize) {
        val dir = File(designsDir, id)
        val tmp = File(designsDir, "$id.tmp").apply { deleteRecursively(); mkdirs() }

        val imageNames = IdentityHashMap<ImageBitmap, String>()
        fun imageName(bitmap: ImageBitmap): String = imageNames.getOrPut(bitmap) {
            val name = "img_${imageNames.size}.png"
            File(tmp, name).outputStream().use {
                bitmap.asAndroidBitmap().compress(AndroidBitmap.CompressFormat.PNG, 100, it)
            }
            name
        }

        val json = JSONObject()
            .put("version", 1)
            .put("nextLayerId", nextLayerId)
            .put("layers", JSONArray(layers.map { layerToJson(it, ::imageName) }))
        File(tmp, DESIGN_FILE).writeText(json.toString())

        val thumbScale = (THUMB_MAX_PX.toFloat() / maxOf(page.widthPx, page.heightPx)).coerceAtMost(1f)
        File(tmp, THUMB_FILE).outputStream().use {
            renderDesignToBitmap(layers, page, thumbScale).compress(AndroidBitmap.CompressFormat.PNG, 100, it)
        }

        val old = File(designsDir, "$id.old").apply { deleteRecursively() }
        if (dir.exists() && !dir.renameTo(old)) error("Could not replace design $id")
        if (!tmp.renameTo(dir)) {
            old.renameTo(dir)
            error("Could not write design $id")
        }
        old.deleteRecursively()
    }

    fun readDesign(id: String): StoredDesign? {
        val dir  = File(designsDir, id)
        val file = File(dir, DESIGN_FILE)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val images = mutableMapOf<String, ImageBitmap?>()
            fun loadImage(name: String): ImageBitmap? = images.getOrPut(name) {
                BitmapFactory.decodeFile(File(dir, name).path)?.asImageBitmap()
            }
            val layers = json.getJSONArray("layers").objects().map { layerFromJson(it, ::loadImage) }
            StoredDesign(layers, json.optInt("nextLayerId", layers.maxOf { it.id } + 1))
        }.getOrNull()
    }

    fun deleteDesign(id: String) {
        File(designsDir, id).deleteRecursively()
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val DESIGN_FILE  = "design.json"
        const val THUMB_FILE   = "thumb.png"
        const val THUMB_MAX_PX = 512
    }
}

// ── JSON mapping ──────────────────────────────────────────────────────────────

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

// Two decimals is far below a pixel and keeps stroke-heavy designs compact.
private fun Float.r(): Double = (this * 100f).roundToInt() / 100.0

private fun JSONObject.float(key: String): Float = getDouble(key).toFloat()

private fun pageToJson(page: PageSize) = JSONObject()
    .put("width", page.width.toDouble())
    .put("height", page.height.toDouble())
    .put("unit", page.unit.name)

private fun pageFromJson(json: JSONObject) = PageSize(
    width  = json.float("width"),
    height = json.float("height"),
    unit   = SizeUnit.valueOf(json.getString("unit"))
)

private fun layerToJson(layer: Layer, imageName: (ImageBitmap) -> String) = JSONObject()
    .put("id", layer.id)
    .put("name", layer.name)
    .put("opacity", layer.opacity.toDouble())
    .put("isBackground", layer.isBackground)
    .put("backgroundColor", layer.backgroundColor?.toArgb() ?: JSONObject.NULL)
    .put("elements", JSONArray(layer.elements.map { elementToJson(it, imageName) }))

private fun layerFromJson(json: JSONObject, loadImage: (String) -> ImageBitmap?) = Layer(
    id              = json.getInt("id"),
    name            = json.getString("name"),
    elements        = json.getJSONArray("elements").objects().mapNotNull { elementFromJson(it, loadImage) },
    opacity         = json.float("opacity"),
    isBackground    = json.getBoolean("isBackground"),
    backgroundColor = if (json.isNull("backgroundColor")) null else Color(json.getInt("backgroundColor"))
)

private fun elementToJson(el: DrawElement, imageName: (ImageBitmap) -> String): JSONObject = when (el) {
    is DrawElement.FreeStroke -> JSONObject()
        .put("type", "stroke")
        .put("points", JSONArray().apply { el.points.forEach { put(it.x.r()); put(it.y.r()) } })
        .put("strokeWidth", el.strokeWidth.r())
        .put("isEraser", el.isEraser)
        .put("color", el.color.toArgb())
    is DrawElement.Shape -> JSONObject()
        .put("type", "shape")
        .put("shape", el.shape.name)
        .putOffset("start", el.start)
        .putOffset("end", el.end)
        .put("strokeWidth", el.strokeWidth.r())
        .put("isEraser", el.isEraser)
        .put("color", el.color.toArgb())
        .put("rotation", el.rotation.r())
    is DrawElement.Image -> JSONObject()
        .put("type", "image")
        .put("file", imageName(el.bitmap))
        .putOffset("start", el.start)
        .putOffset("end", el.end)
        .put("rotation", el.rotation.r())
}

private fun elementFromJson(json: JSONObject, loadImage: (String) -> ImageBitmap?): DrawElement? =
    when (json.getString("type")) {
        "stroke" -> {
            val pts = json.getJSONArray("points")
            DrawElement.FreeStroke(
                points      = (0 until pts.length() / 2).map {
                    Offset(pts.getDouble(it * 2).toFloat(), pts.getDouble(it * 2 + 1).toFloat())
                },
                strokeWidth = json.float("strokeWidth"),
                isEraser    = json.getBoolean("isEraser"),
                color       = Color(json.getInt("color"))
            )
        }
        "shape" -> DrawElement.Shape(
            shape       = DrawShape.valueOf(json.getString("shape")),
            start       = json.getOffset("start"),
            end         = json.getOffset("end"),
            strokeWidth = json.float("strokeWidth"),
            isEraser    = json.getBoolean("isEraser"),
            color       = Color(json.getInt("color")),
            rotation    = json.float("rotation")
        )
        "image" -> loadImage(json.getString("file"))?.let { bitmap ->
            DrawElement.Image(
                bitmap   = bitmap,
                start    = json.getOffset("start"),
                end      = json.getOffset("end"),
                rotation = json.float("rotation")
            )
        }
        else -> null
    }

private fun JSONObject.putOffset(key: String, o: Offset): JSONObject =
    put(key, JSONArray().put(o.x.r()).put(o.y.r()))

private fun JSONObject.getOffset(key: String): Offset {
    val arr = getJSONArray(key)
    return Offset(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat())
}

// ── Observable library state ──────────────────────────────────────────────────

// Holds the library index as Compose state and persists every change.
class LibraryState(private val storage: DesignStorage) {
    var library by mutableStateOf(storage.loadLibrary())
        private set
    var savedSizes by mutableStateOf(storage.loadSavedSizes())
        private set

    private fun update(transform: (Library) -> Library) {
        library = transform(library)
        storage.saveLibrary(library)
    }

    fun createProject(name: String, parentId: String?): Project {
        val project = Project(newId(), name.trim(), parentId, System.currentTimeMillis())
        update { it.addProject(project) }
        return project
    }

    fun renameProject(id: String, name: String) = update { it.renameProject(id, name.trim()) }
    fun renameDesign(id: String, name: String)  = update { it.renameDesign(id, name.trim()) }
    fun moveProject(id: String, newParentId: String?) = update { it.moveProject(id, newParentId) }
    fun moveDesign(id: String, projectId: String) = update { it.moveDesign(id, projectId) }
    fun mergeProject(sourceId: String, targetId: String) = update { it.mergeProject(sourceId, targetId) }
    fun upsertDesign(meta: DesignMeta) = update { it.upsertDesign(meta) }

    fun deleteProject(id: String) {
        val designIds = library.designIdsUnder(id)
        update { it.deleteProject(id) }
        designIds.forEach(storage::deleteDesign)
    }

    fun deleteDesign(id: String) {
        update { it.deleteDesign(id) }
        storage.deleteDesign(id)
    }

    fun addSavedSize(size: PageSize) {
        if (savedSizes.any { it.size.matches(size) }) return
        savedSizes = savedSizes + SizePreset(size.label, size)
        storage.saveSizes(savedSizes)
    }

    fun removeSavedSize(preset: SizePreset) {
        savedSizes = savedSizes - preset
        storage.saveSizes(savedSizes)
    }
}
