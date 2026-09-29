package com.example.stencilcreator

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Page sizes ────────────────────────────────────────────────────────────────

// Resolution used to turn physical units (inches / cm) into page pixels.
// One world unit on the canvas == one exported pixel.
const val PAGE_DPI = 150f

// Largest allowed page edge in pixels; keeps export bitmaps within memory limits.
const val MAX_PAGE_PX = 8000

enum class SizeUnit(val label: String, val pxPerUnit: Float) {
    INCH("in", PAGE_DPI),
    CM("cm", PAGE_DPI / 2.54f),
    PX("px", 1f)
}

data class PageSize(val width: Float, val height: Float, val unit: SizeUnit) {
    val widthPx: Int  get() = (width  * unit.pxPerUnit).roundToInt().coerceAtLeast(1)
    val heightPx: Int get() = (height * unit.pxPerUnit).roundToInt().coerceAtLeast(1)

    val label: String get() = "${formatDimension(width)} × ${formatDimension(height)} ${unit.label}"

    val isWithinLimits: Boolean get() = widthPx <= MAX_PAGE_PX && heightPx <= MAX_PAGE_PX

    fun convertedTo(target: SizeUnit): PageSize {
        if (target == unit) return this
        val factor = unit.pxPerUnit / target.pxPerUnit
        return PageSize(roundForUnit(width * factor, target), roundForUnit(height * factor, target), target)
    }

    fun swapped() = PageSize(height, width, unit)

    // Same unit and same dimensions, in either orientation.
    fun matches(other: PageSize): Boolean {
        if (unit != other.unit) return false
        fun eq(a: Float, b: Float) = abs(a - b) < 0.005f
        return (eq(width, other.width) && eq(height, other.height)) ||
               (eq(width, other.height) && eq(height, other.width))
    }
}

fun roundForUnit(value: Float, unit: SizeUnit): Float =
    if (unit == SizeUnit.PX) value.roundToInt().toFloat() else (value * 100f).roundToInt() / 100f

fun formatDimension(value: Float): String {
    val rounded = (value * 100f).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
    else String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
}

fun parseDimension(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

data class SizePreset(val name: String, val size: PageSize)

val BUILT_IN_SIZE_PRESETS = listOf(
    SizePreset("Letter",    PageSize(8.5f, 11f,   SizeUnit.INCH)),
    SizePreset("Legal",     PageSize(8.5f, 14f,   SizeUnit.INCH)),
    SizePreset("Tabloid",   PageSize(11f,  17f,   SizeUnit.INCH)),
    SizePreset("Super B",   PageSize(13f,  19f,   SizeUnit.INCH)),
    SizePreset("Photo 4×6", PageSize(4f,   6f,    SizeUnit.INCH)),
    SizePreset("Photo 5×7", PageSize(5f,   7f,    SizeUnit.INCH)),
    SizePreset("8×10",      PageSize(8f,   10f,   SizeUnit.INCH)),
    SizePreset("A5",        PageSize(14.8f, 21f,  SizeUnit.CM)),
    SizePreset("A4",        PageSize(21f,  29.7f, SizeUnit.CM)),
    SizePreset("A3",        PageSize(29.7f, 42f,  SizeUnit.CM)),
    SizePreset("Square",    PageSize(1080f, 1080f, SizeUnit.PX)),
    SizePreset("Full HD",   PageSize(1920f, 1080f, SizeUnit.PX))
)

// ── Projects & designs ────────────────────────────────────────────────────────

data class Project(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long
)

data class DesignMeta(
    val id: String,
    val name: String,
    val projectId: String,
    val page: PageSize,
    val createdAt: Long,
    val updatedAt: Long
)

fun newId(): String = UUID.randomUUID().toString()

// Immutable index of every project and design. Projects form a tree through
// parentId (null = top level); every design belongs to exactly one project.
data class Library(
    val projects: List<Project> = emptyList(),
    val designs: List<DesignMeta> = emptyList()
) {
    fun project(id: String?): Project? = id?.let { pid -> projects.firstOrNull { it.id == pid } }

    fun design(id: String): DesignMeta? = designs.firstOrNull { it.id == id }

    fun childProjects(parentId: String?): List<Project> =
        projects.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }

    fun designsIn(projectId: String): List<DesignMeta> =
        designs.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt }

    // The project itself plus every project nested anywhere below it.
    fun subtreeIds(projectId: String): Set<String> {
        val result = mutableSetOf(projectId)
        var frontier = setOf(projectId)
        while (frontier.isNotEmpty()) {
            frontier = projects
                .filter { it.parentId != null && it.parentId in frontier && it.id !in result }
                .map { it.id }
                .toSet()
            result += frontier
        }
        return result
    }

    fun designIdsUnder(projectId: String): List<String> {
        val ids = subtreeIds(projectId)
        return designs.filter { it.projectId in ids }.map { it.id }
    }

    // Root-first chain of projects ending at projectId (empty for top level).
    fun pathTo(projectId: String?): List<Project> {
        val path = mutableListOf<Project>()
        val seen = mutableSetOf<String>()
        var cur = project(projectId)
        while (cur != null && seen.add(cur.id)) {
            path.add(0, cur)
            cur = project(cur.parentId)
        }
        return path
    }

    // Every project in display order, paired with its nesting depth.
    fun flattenedTree(): List<Pair<Project, Int>> {
        val out = mutableListOf<Pair<Project, Int>>()
        fun visit(parentId: String?, depth: Int) {
            for (p in childProjects(parentId)) {
                out += p to depth
                visit(p.id, depth + 1)
            }
        }
        visit(null, 0)
        return out
    }

    fun addProject(project: Project) = copy(projects = projects + project)

    fun renameProject(id: String, name: String) =
        copy(projects = projects.map { if (it.id == id) it.copy(name = name) else it })

    fun renameDesign(id: String, name: String) =
        copy(designs = designs.map { if (it.id == id) it.copy(name = name) else it })

    // Moves a project under newParentId (null = top level). Moving a project
    // into itself or one of its own sub-projects would create a cycle, so it's ignored.
    fun moveProject(id: String, newParentId: String?): Library {
        if (project(id) == null) return this
        if (newParentId != null && (project(newParentId) == null || newParentId in subtreeIds(id))) return this
        return copy(projects = projects.map { if (it.id == id) it.copy(parentId = newParentId) else it })
    }

    fun moveDesign(id: String, projectId: String): Library {
        if (project(projectId) == null) return this
        return copy(designs = designs.map { if (it.id == id) it.copy(projectId = projectId) else it })
    }

    // Joins source into target: the source's designs and direct sub-projects
    // move into the target, then the (now empty) source project is removed.
    fun mergeProject(sourceId: String, targetId: String): Library {
        if (project(sourceId) == null || project(targetId) == null) return this
        if (targetId in subtreeIds(sourceId)) return this
        return copy(
            projects = projects
                .filter { it.id != sourceId }
                .map { if (it.parentId == sourceId) it.copy(parentId = targetId) else it },
            designs = designs.map { if (it.projectId == sourceId) it.copy(projectId = targetId) else it }
        )
    }

    // Removes a project together with all of its sub-projects and their designs.
    fun deleteProject(id: String): Library {
        val ids = subtreeIds(id)
        return copy(
            projects = projects.filter { it.id !in ids },
            designs  = designs.filter { it.projectId !in ids }
        )
    }

    fun deleteDesign(id: String) = copy(designs = designs.filter { it.id != id })

    fun upsertDesign(meta: DesignMeta): Library =
        if (designs.any { it.id == meta.id }) copy(designs = designs.map { if (it.id == meta.id) meta else it })
        else copy(designs = designs + meta)
}
