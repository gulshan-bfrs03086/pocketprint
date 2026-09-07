package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.PageRangeInput
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.withPageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PrintOptions.pageRange is null unless both ends are set and in order, and
 * null means every page everywhere downstream. So the dangerous input is not a
 * nonsense one - it is a half-finished one. "Print page 40" with the second box
 * still empty came out as all five hundred pages, and on a label printer that
 * is a roll on the floor.
 *
 * These pin that every reading of the boxes other than "both empty" either
 * produces the exact range asked for or refuses, and never quietly widens to
 * everything.
 */
class PageRangeTest {

    @Test
    fun `two empty boxes mean every page`() {
        assertEquals(PageRangeInput.AllPages, PageRangeInput.parse("", ""))
        assertEquals(PageRangeInput.AllPages, PageRangeInput.parse("  ", " "))
    }

    @Test
    fun `a filled range is taken as typed`() {
        assertEquals(PageRangeInput.Pages(2, 7), PageRangeInput.parse("2", "7"))
        assertEquals(PageRangeInput.Pages(3, 3), PageRangeInput.parse(" 3 ", "3"))
    }

    @Test
    fun `half a range is invalid, not every page`() {
        // The whole reason this type exists.
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.INCOMPLETE),
            PageRangeInput.parse("40", ""),
        )
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.INCOMPLETE),
            PageRangeInput.parse("", "40"),
        )
    }

    @Test
    fun `a reversed range is refused rather than quietly swapped`() {
        // Swapping would print pages the user did not ask for and never say so.
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.REVERSED),
            PageRangeInput.parse("9", "2"),
        )
    }

    @Test
    fun `page numbering starts at one`() {
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.BELOW_FIRST_PAGE),
            PageRangeInput.parse("0", "5"),
        )
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.BELOW_FIRST_PAGE),
            PageRangeInput.parse("1", "0"),
        )
    }

    @Test
    fun `text that is not a number is refused`() {
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.NOT_A_NUMBER),
            PageRangeInput.parse("one", "5"),
        )
        // Out of Int range: toIntOrNull gives null, which must not become "all".
        assertEquals(
            PageRangeInput.Invalid(PageRangeInput.Problem.NOT_A_NUMBER),
            PageRangeInput.parse("1", "99999999999999"),
        )
    }

    @Test
    fun `an absurd but valid number is clamped rather than refused`() {
        assertEquals(
            PageRangeInput.Pages(1, PageRangeInput.MAX_PAGE),
            PageRangeInput.parse("1", "999999"),
        )
    }

    @Test
    fun `applying a range puts both ends on the options`() {
        val applied = PrintOptions().withPageRange(PageRangeInput.Pages(2, 5))

        assertEquals(2, applied?.pageFrom)
        assertEquals(5, applied?.pageTo)
        assertEquals(2..5, applied?.pageRange)
    }

    @Test
    fun `applying all pages clears both ends`() {
        val narrowed = PrintOptions(pageFrom = 2, pageTo = 5)

        val applied = narrowed.withPageRange(PageRangeInput.AllPages)

        assertNull(applied?.pageFrom)
        assertNull(applied?.pageTo)
        assertNull(applied?.pageRange)
    }

    @Test
    fun `an invalid range yields no options at all`() {
        // Not the old options and not a cleared range: there is nothing sensible
        // to print for half a range, so the caller is made to notice.
        val applied = PrintOptions(pageFrom = 2, pageTo = 5)
            .withPageRange(PageRangeInput.Invalid(PageRangeInput.Problem.INCOMPLETE))

        assertNull(applied)
    }

    @Test
    fun `every parse of a non-empty pair is either a range or a refusal`() {
        // The property behind all of the above: nothing typed into either box
        // can ever come back as AllPages, which is the only value that widens a
        // job to the whole document.
        val inputs = listOf(
            "1" to "", "" to "1", "0" to "0", "5" to "1", "x" to "1", "1" to "x",
            "-1" to "3", "1" to "-3", "999999999999" to "1",
        )

        inputs.forEach { (from, to) ->
            val parsed = PageRangeInput.parse(from, to)
            assertEquals(
                "parse($from, $to) must not mean every page",
                PageRangeInput.Invalid::class.java,
                parsed.javaClass,
            )
        }
    }
}
