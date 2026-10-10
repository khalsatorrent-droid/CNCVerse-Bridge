package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

open class AniListApi : SyncAPI() {
    /** Cover image block of an AniList media (some extensions read it from the API's JSON). */
    data class CoverImage(
        val large: String? = null,
        val medium: String? = null,
        val extraLarge: String? = null,
        val color: String? = null,
    )
}
open class MalApi : SyncAPI()
open class KitsuApi : SyncAPI()
