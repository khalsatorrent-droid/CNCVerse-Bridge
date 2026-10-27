package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleMatchTest {
    private fun r(n: String, y: Int? = null) = SearchResult(n, "u/$n", null, "Movie", y)
    private fun e(n: String?, s: Int?, ep: Int?) = MediaInfoEpisode(n, s, ep, "d/$n/$s/$ep", null)

    @Test
    fun baseNameDropsTagsYearAndSeason() {
        assertEquals("the boys", TitleMatch.baseName("The Boys (2019) Season 4 Hindi DD5.1 1080p WEB-DL"))
    }

    @Test
    fun queriesWithoutBreakingPunctuation() {
        assertEquals("Fast and Furious Hobbs Show", TitleMatch.cleanQuery("Fast & Furious: Hobbs's Show?"))
        val v = TitleMatch.queryVariants("Spider-Man: No Way Home", 2021, listOf("スパイダーマン"))
        assertEquals("Spider-Man No Way Home", v.first())
        assertTrue(v.none { it.contains("スパイダーマン") })
    }

    @Test
    fun rankPrefersTheRightSeasonAndYear() {
        val res = listOf(r("The Boys Presents: Diabolical"), r("The Boys (2019) Season 4 Hindi 1080p"), r("The Boys Season 1"), r("Boys Over Flowers"))
        assertEquals("The Boys (2019) Season 4 Hindi 1080p", TitleMatch.rank(res, "The Boys", 2019, 4).first().name)
        val films = TitleMatch.rank(listOf(r("Dune (1984)"), r("Dune Part Two (2024) Hindi"), r("Dune (2021) 1080p")), "Dune", 2021, null)
        assertEquals(listOf("Dune (2021) 1080p"), films.map { it.name })
    }

    @Test
    fun seasonAndEpisodeFromNames() {
        assertEquals(listOf(2, 2, 2, 1, null), listOf("Season 2", "2nd Season", "Kisskh S02", "Show S01E05", "nothing").map { TitleMatch.seasonFromTitle(it) })
        assertEquals(listOf(12, 7, 5, 3, 9, null), listOf("Episode 12", "Ep. 7 - name", "E05", "#3", "S01E09 title", "The Episode").map { TitleMatch.episodeNumberFromName(it) })
    }

    @Test
    fun numberingStyle() {
        val flat = (1..1100).map { e("Episode $it", null, it) }
        assertEquals(TitleMatch.Numbering.ABSOLUTE, TitleMatch.numbering(flat, mapOf(1 to 61, 2 to 16)))
        assertEquals(TitleMatch.Numbering.TMDB, TitleMatch.numbering((1..26).map { e(null, 1, it) } + (1..18).map { e(null, 2, it) }, mapOf(1 to 26, 2 to 18, 3 to 11)))
        assertEquals(TitleMatch.Numbering.ENTRY, TitleMatch.numbering((1..11).map { e(null, null, it) }, mapOf(1 to 26, 2 to 18)))
    }

    @Test
    fun seriesEpisodeOnPerSeasonAndUnnumberedPages() {
        // descending names, season from the entry title
        assertEquals("Episode 3", TitleMatch.findSeriesEpisode((1..8).map { e("Episode ${9 - it}", null, null) }, 2, 3, 2, null)?.name)
        // the wrong season's page is not used
        assertNull(TitleMatch.findSeriesEpisode((1..8).map { e(null, null, it) }, 2, 3, 1, null))
        // one running list of the whole show
        assertEquals("Episode 273", TitleMatch.findSeriesEpisode((1..1100).map { e("Episode $it", null, it) }, 9, 10, null, 273)?.name)
    }
}
