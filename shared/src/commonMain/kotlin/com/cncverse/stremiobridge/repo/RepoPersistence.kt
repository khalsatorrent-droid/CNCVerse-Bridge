package com.cncverse.stremiobridge.repo

/**
 * Platform-specific persistence for the user's list of repo URLs.
 * Android: SharedPreferences
 * Desktop: local JSON file
 */
expect fun loadRepoUrls(): List<String>
expect fun saveRepoUrls(urls: List<String>)

expect fun loadExtensionSettings(): Map<String, String>
expect fun saveExtensionSettings(settings: Map<String, String>)

/** One key of the live, shared extension-settings map (the one plugins use). */
expect fun getExtensionSetting(key: String): String?
/** Updates one key of the live settings map and persists it (null removes it). */
expect fun setExtensionSetting(key: String, value: String?)

expect fun loadCachedRepoEntries(): List<com.cncverse.stremiobridge.state.RepoEntry>
expect fun saveCachedRepoEntries(entries: List<com.cncverse.stremiobridge.state.RepoEntry>)

expect fun loadCachedAvailablePlugins(): List<com.cncverse.stremiobridge.state.AvailablePlugin>
expect fun saveCachedAvailablePlugins(plugins: List<com.cncverse.stremiobridge.state.AvailablePlugin>)

const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"

/** Repositories every install starts with, next to the default one. */
val BUNDLED_REPO_URLS: List<String> = listOf(
    DEFAULT_REPO_URL,
    "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
    "https://raw.githubusercontent.com/KSHITIJ8473/raghav/builds/repo.json",
    "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json",
    "https://raw.githubusercontent.com/Faisal0786/Desi/main/repo.json",
    ECLIPSIA_REPO_URL,
    WATCHUU_REPO_URL,
)

/** Watchuu extensions: bundled from 1.7.31 (existing installs get it once, [BUNDLED_REPO_URLS_V4]). */
const val WATCHUU_REPO_URL = "https://raw.githubusercontent.com/khalsatorrent-droid/Watchuu/main/repo.json"

/** Added to the bundle after the first six: an existing install gets it once ([BUNDLED_REPO_URLS_V2]), a new one with the rest. */
const val ECLIPSIA_REPO_URL = "https://cs.eclipsia.dpdns.org/repo.json"

/** Addresses that were bundled once and do not work: an install that has one gets [BUNDLED_REPO_URLS_V3] instead (once). */
val RETIRED_REPO_URLS: List<String> = listOf("https://gitea.com/eclipsia/cloudstream/raw/branch/main/repo.json")

/** Replacement for [RETIRED_REPO_URLS]: added once to every install that has not had it. */
val BUNDLED_REPO_URLS_V3: List<String> = listOf(ECLIPSIA_REPO_URL)

/** Repositories added to the bundle after the first version of it: existing installs get just these, once. */
val BUNDLED_REPO_URLS_V2: List<String> = listOf(ECLIPSIA_REPO_URL)

/** Added to the bundle in 1.7.31: existing installs get it once. */
val BUNDLED_REPO_URLS_V4: List<String> = listOf(WATCHUU_REPO_URL)
