package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.SimklApi

open class AuthAPI
open class SyncAPI : AuthAPI() {
    open class LibraryMetadata
}
/** The signed-in account of a sync service. The bridge has no accounts, so [AuthRepo.authUser] is always null. */
open class AuthUser
open class AuthRepo(open val api: AuthAPI) {
    open fun authUser(): AuthUser? = null
}
open class SyncRepo(override val api: SyncAPI) : AuthRepo(api)
open class AccountManager {
    companion object {
        val simklApi = SimklApi()
        val aniListApi = com.lagradost.cloudstream3.syncproviders.providers.AniListApi()
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MalApi()
        val kitsuApi = com.lagradost.cloudstream3.syncproviders.providers.KitsuApi()
    }
}