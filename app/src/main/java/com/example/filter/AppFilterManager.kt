package com.example.filter

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppFilterPreset(
    val id: String,
    val title: String,
    val packages: List<String>
)

data class DiscoveredMediaApp(
    val packageName: String,
    val appLabel: String
)

/**
 * Optional package blacklist filter for MediaListenerService.
 * Disabled and empty by default so all players are captured out of the box.
 * Also tracks recently seen media session packages without requiring QUERY_ALL_PACKAGES.
 */
object AppFilterManager {

    private const val PREFS_NAME = "clawd_app_filter_prefs"
    private const val KEY_ENABLED = "filter_enabled"
    private const val KEY_IGNORED_PACKAGES = "ignored_packages"
    private const val KEY_DISCOVERED_PACKAGES = "discovered_packages"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val _isFilterEnabled = MutableStateFlow(false)
    val isFilterEnabled: StateFlow<Boolean> = _isFilterEnabled.asStateFlow()

    private val _ignoredPackages = MutableStateFlow<Set<String>>(emptySet())
    val ignoredPackages: StateFlow<Set<String>> = _ignoredPackages.asStateFlow()

    private val _discoveredApps = MutableStateFlow<List<DiscoveredMediaApp>>(emptyList())
    val discoveredApps: StateFlow<List<DiscoveredMediaApp>> = _discoveredApps.asStateFlow()

    val presets = listOf(
        AppFilterPreset(
            id = "youtube",
            title = "YouTube",
            packages = listOf("com.google.android.youtube", "app.revanced.android.youtube")
        ),
        AppFilterPreset(
            id = "tiktok",
            title = "TikTok",
            packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        ),
        AppFilterPreset(
            id = "messengers",
            title = "Telegram / AprelGram (кружки и голосовые)",
            packages = listOf(
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "com.exteragram.messenger",
                "com.radolyn.ayugram",
                "com.aprelgram.messenger",
                "org.aprelgram.messenger",
                "app.aprelgram.messenger"
            )
        ),
        AppFilterPreset(
            id = "instagram",
            title = "Instagram Reels",
            packages = listOf("com.instagram.android")
        )
    )

    fun init(context: Context) {
        if (prefs != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isFilterEnabled.value = sp.getBoolean(KEY_ENABLED, false)
        _ignoredPackages.value = sp.getStringSet(KEY_IGNORED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        val savedDiscovered = sp.getStringSet(KEY_DISCOVERED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        refreshDiscoveredList(savedDiscovered)
    }

    fun recordSeenPackage(context: Context?, packageName: String?) {
        val cleaned = packageName?.trim()
        if (cleaned.isNullOrEmpty()) return
        if (context != null && prefs == null) {
            init(context)
        }
        val current = _discoveredApps.value.map { it.packageName }.toSet()
        if (cleaned in current) return
        val updated = current + cleaned
        prefs?.edit()?.putStringSet(KEY_DISCOVERED_PACKAGES, updated)?.apply()
        refreshDiscoveredList(updated)
    }

    private fun refreshDiscoveredList(packages: Set<String>) {
        val ctx = appContext
        val list = packages.map { pkg ->
            DiscoveredMediaApp(
                packageName = pkg,
                appLabel = resolveAppLabel(ctx, pkg)
            )
        }.sortedBy { it.appLabel.lowercase() }
        _discoveredApps.value = list
    }

    private fun resolveAppLabel(context: Context?, packageName: String): String {
        if (context == null) return packageName
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() } ?: packageName
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() } ?: packageName
            } catch (_: Throwable) {
                packageName
            }
        }
    }

    fun setFilterEnabled(enabled: Boolean) {
        _isFilterEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun addPackage(packageName: String) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return
        val updated = _ignoredPackages.value + cleaned
        _ignoredPackages.value = updated
        prefs?.edit()?.putStringSet(KEY_IGNORED_PACKAGES, updated)?.apply()
    }

    fun addPackages(packageNames: Collection<String>) {
        val cleaned = packageNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        val updated = _ignoredPackages.value + cleaned
        _ignoredPackages.value = updated
        prefs?.edit()?.putStringSet(KEY_IGNORED_PACKAGES, updated)?.apply()
    }

    fun removePackage(packageName: String) {
        val updated = _ignoredPackages.value - packageName
        _ignoredPackages.value = updated
        prefs?.edit()?.putStringSet(KEY_IGNORED_PACKAGES, updated)?.apply()
    }

    fun removePackages(packageNames: Collection<String>) {
        val updated = _ignoredPackages.value - packageNames.toSet()
        _ignoredPackages.value = updated
        prefs?.edit()?.putStringSet(KEY_IGNORED_PACKAGES, updated)?.apply()
    }

    fun clearAll() {
        _ignoredPackages.value = emptySet()
        prefs?.edit()?.putStringSet(KEY_IGNORED_PACKAGES, emptySet())?.apply()
    }

    fun clearDiscovered() {
        _discoveredApps.value = emptyList()
        prefs?.edit()?.putStringSet(KEY_DISCOVERED_PACKAGES, emptySet())?.apply()
    }

    fun shouldIgnore(packageName: String?): Boolean {
        return evaluateIgnore(
            filterEnabled = _isFilterEnabled.value,
            ignoredSet = _ignoredPackages.value,
            packageName = packageName
        )
    }

    internal fun evaluateIgnore(
        filterEnabled: Boolean,
        ignoredSet: Set<String>,
        packageName: String?
    ): Boolean {
        if (!filterEnabled || packageName.isNullOrBlank()) return false
        return packageName in ignoredSet
    }
}
