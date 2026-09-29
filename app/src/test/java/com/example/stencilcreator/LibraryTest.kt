package com.example.stencilcreator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private val letter = PageSize(8.5f, 11f, SizeUnit.INCH)

    private fun project(id: String, parent: String? = null) = Project(id, id.uppercase(), parent, 0L)
    private fun design(id: String, projectId: String) = DesignMeta(id, id, projectId, letter, 0L, 0L)

    // a ─ b ─ c,  d (top level)
    private val library = Library(
        projects = listOf(project("a"), project("b", "a"), project("c", "b"), project("d")),
        designs  = listOf(design("d1", "a"), design("d2", "b"), design("d3", "c"), design("d4", "d"))
    )

    @Test fun subtreeIncludesAllDescendants() {
        assertEquals(setOf("a", "b", "c"), library.subtreeIds("a"))
        assertEquals(setOf("d"), library.subtreeIds("d"))
    }

    @Test fun deleteProjectRemovesSubProjectsAndTheirDesigns() {
        val result = library.deleteProject("a")
        assertEquals(listOf("d"), result.projects.map { it.id })
        assertEquals(listOf("d4"), result.designs.map { it.id })
        assertEquals(listOf("d1", "d2", "d3"), library.designIdsUnder("a").sorted())
    }

    @Test fun moveProjectIntoOwnDescendantIsIgnored() {
        assertSame(library, library.moveProject("a", "c"))
        assertSame(library, library.moveProject("a", "a"))
    }

    @Test fun moveProjectChangesParent() {
        val result = library.moveProject("b", "d")
        assertEquals("d", result.project("b")?.parentId)
        assertEquals(
            listOf("a" to 0, "d" to 0, "b" to 1, "c" to 2),
            result.flattenedTree().map { (p, depth) -> p.id to depth }
        )
        assertNull(result.moveProject("b", null).project("b")?.parentId)
    }

    @Test fun mergeMovesDesignsAndSubProjectsThenRemovesSource() {
        val result = library.mergeProject("a", "d")
        assertNull(result.project("a"))
        assertEquals("d", result.project("b")?.parentId)
        assertEquals("d", result.design("d1")?.projectId)
        assertEquals("b", result.design("d2")?.projectId)
        assertEquals(4, result.designs.size)
    }

    @Test fun mergeIntoOwnDescendantIsIgnored() {
        assertSame(library, library.mergeProject("a", "b"))
    }

    @Test fun pathToIsRootFirst() {
        assertEquals(listOf("a", "b", "c"), library.pathTo("c").map { it.id })
        assertTrue(library.pathTo(null).isEmpty())
    }

    @Test fun flattenedTreeHasDepths() {
        assertEquals(
            listOf("a" to 0, "b" to 1, "c" to 2, "d" to 0),
            library.flattenedTree().map { (p, depth) -> p.id to depth }
        )
    }

    @Test fun pageSizeConversion() {
        assertEquals(1275, letter.widthPx)
        assertEquals(1650, letter.heightPx)
        val cm = letter.convertedTo(SizeUnit.CM)
        assertEquals(21.59f, cm.width, 0.001f)
        assertEquals(27.94f, cm.height, 0.001f)
        val px = letter.convertedTo(SizeUnit.PX)
        assertEquals(1275f, px.width, 0f)
    }

    @Test fun pageSizeMatchesEitherOrientationSameUnitOnly() {
        assertTrue(letter.matches(PageSize(11f, 8.5f, SizeUnit.INCH)))
        assertFalse(letter.matches(letter.convertedTo(SizeUnit.CM)))
        assertTrue(PageSize(13f, 19f, SizeUnit.INCH).isWithinLimits)
        assertFalse(PageSize(100f, 100f, SizeUnit.INCH).isWithinLimits)
    }

    @Test fun formatAndParseDimensions() {
        assertEquals("8.5", formatDimension(8.5f))
        assertEquals("11", formatDimension(11f))
        assertEquals("29.7", formatDimension(29.7f))
        assertEquals(8.5f, parseDimension("8,5")!!, 0f)
        assertNull(parseDimension("0"))
        assertNull(parseDimension("abc"))
    }
}
