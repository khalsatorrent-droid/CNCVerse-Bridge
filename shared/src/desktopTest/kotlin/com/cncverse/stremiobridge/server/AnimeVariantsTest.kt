package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertTrue

class AnimeVariantsTest {
    private val info = AnimeInfo(
        titles = listOf("Lv999 no Murabito", "The Villager of Level 999"),
        year = 2025, episodeCount = 12, format = "TV", seasonHint = null, aniListId = null, malId = null
    )

    @Test
    fun respellingsAreSearched() {
        val terms = AnimeMatcher.searchTerms(info)
        for (want in listOf("Lv 999 no Murabito", "Level 999 no Murabito", "Villager of Level 999", "The Villager of Lv999")) {
            assertTrue(terms.any { it.equals(want, true) }, "missing '$want' in $terms")
        }
    }
}
