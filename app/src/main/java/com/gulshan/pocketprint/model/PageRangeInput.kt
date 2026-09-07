package com.gulshan.pocketprint.model

/**
 * What the two page-range boxes add up to.
 *
 * This exists because of one trap in [PrintOptions]. Its `pageRange` is derived
 * from `pageFrom` and `pageTo`, and it returns null unless both are set and the
 * end is not before the start - and null, everywhere downstream, means *every
 * page*. So a half-filled range does not fail; it silently prints the lot.
 * Somebody asking for page 40 of a 500-page PDF and leaving the second box
 * empty would get all five hundred, and on a label printer that is a roll.
 *
 * The type makes that impossible to reach by accident: an incomplete or
 * contradictory range is [Invalid], which is not a range and is not "all
 * pages" either, and the caller has to decide what to do about it. Only two
 * empty boxes mean [AllPages].
 */
sealed interface PageRangeInput {

    /** Every page. What two empty boxes mean, and the only thing that means it. */
    data object AllPages : PageRangeInput

    /** 1-based and inclusive, as printed on the page and as the user typed it. */
    data class Pages(val from: Int, val to: Int) : PageRangeInput

    /** Typed, but not a range. Never silently treated as every page. */
    data class Invalid(val problem: Problem) : PageRangeInput

    enum class Problem {
        /** One box filled and the other empty - the case that used to print everything. */
        INCOMPLETE,
        NOT_A_NUMBER,

        /** Page zero. Page numbering starts where the user thinks it does. */
        BELOW_FIRST_PAGE,

        /** The end is before the start. */
        REVERSED,
    }

    companion object {
        /** Longer than any document this app will render, and short of overflow. */
        const val MAX_PAGE = 100_000

        fun parse(from: String, to: String): PageRangeInput {
            val start = from.trim()
            val end = to.trim()

            if (start.isEmpty() && end.isEmpty()) return AllPages
            if (start.isEmpty() || end.isEmpty()) return Invalid(Problem.INCOMPLETE)

            val first = start.toIntOrNull() ?: return Invalid(Problem.NOT_A_NUMBER)
            val last = end.toIntOrNull() ?: return Invalid(Problem.NOT_A_NUMBER)

            if (first < 1 || last < 1) return Invalid(Problem.BELOW_FIRST_PAGE)
            if (last < first) return Invalid(Problem.REVERSED)

            return Pages(first.coerceAtMost(MAX_PAGE), last.coerceAtMost(MAX_PAGE))
        }
    }
}

/**
 * Applies a page range, or refuses.
 *
 * Null for [PageRangeInput.Invalid] on purpose: there is no sensible options
 * object for "the user has typed half a range", and returning the unchanged
 * options would be the silent-everything bug wearing a different coat.
 */
fun PrintOptions.withPageRange(input: PageRangeInput): PrintOptions? = when (input) {
    is PageRangeInput.AllPages -> copy(pageFrom = null, pageTo = null)
    is PageRangeInput.Pages -> copy(pageFrom = input.from, pageTo = input.to)
    is PageRangeInput.Invalid -> null
}
