package com.hereliesaz.cleanunderwear.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.google.gson.JsonParser
import com.hereliesaz.cleanunderwear.BuildConfig
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Where this install came from, and so where its updates must come from. */
enum class UpdateChannel(val label: String) {
    PLAY("Google Play"),
    GITHUB("GitHub")
}

/** The outcome of one update check. */
sealed interface UpdateResult {
    val channel: UpdateChannel

    /** [version] is a version name for GitHub, or "build N" for Play, which reports only a code. */
    data class Available(override val channel: UpdateChannel, val version: String, val url: String) : UpdateResult
    data class UpToDate(override val channel: UpdateChannel) : UpdateResult
    data class Failed(override val channel: UpdateChannel, val reason: String) : UpdateResult
}

/**
 * Checks for a newer version through the channel the app was installed from. A Play install is only
 * ever offered Play's update: the GitHub APK is signed with a different key and Android would refuse
 * to install it over the Play build. Every other install (the GitHub release APK, a debug build) is
 * checked against the latest stable GitHub release.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient
) {
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun channel(): UpdateChannel = if (installedFromPlay()) UpdateChannel.PLAY else UpdateChannel.GITHUB

    suspend fun check(): UpdateResult = when (channel()) {
        UpdateChannel.PLAY -> checkPlay()
        UpdateChannel.GITHUB -> checkGitHub()
    }

    private fun installedFromPlay(): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer in PLAY_INSTALLERS
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun checkPlay(): UpdateResult {
        val channel = UpdateChannel.PLAY
        return try {
            val info = AppUpdateManagerFactory.create(context).requestAppUpdateInfo()
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                UpdateResult.Available(channel, "build ${info.availableVersionCode()}", playUrl())
            } else {
                UpdateResult.UpToDate(channel)
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("Play update check failed: ${e.javaClass.simpleName}: ${e.message}")
            UpdateResult.Failed(channel, "Google Play couldn't be reached")
        }
    }

    private suspend fun checkGitHub(): UpdateResult = withContext(Dispatchers.IO) {
        val channel = UpdateChannel.GITHUB
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Failed(channel, "GitHub answered ${response.code}")
                }
                val release = GitHubRelease.parse(response.body.string())
                    ?: return@withContext UpdateResult.Failed(channel, "GitHub's reply couldn't be read")
                if (VersionCompare.isNewer(release.version, currentVersion)) {
                    UpdateResult.Available(channel, release.version, release.url)
                } else {
                    UpdateResult.UpToDate(channel)
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("GitHub update check failed: ${e.javaClass.simpleName}: ${e.message}")
            UpdateResult.Failed(channel, "GitHub couldn't be reached")
        }
    }

    /** Store listing; the caller falls back to [playWebUrl] when no Play Store app handles it. */
    fun playUrl() = "market://details?id=${context.packageName}"

    fun playWebUrl() = "https://play.google.com/store/apps/details?id=${context.packageName}"

    private companion object {
        const val GITHUB_REPO = "HereLiesAz/CleanUnderwear"
        val PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")
    }
}

/** The latest stable release as GitHub reports it. */
data class GitHubRelease(val version: String, val url: String) {
    companion object {
        // Not preceded by a digit or dot, so "v1.11.0.2" yields 1.11.0.2, not 11.0.2.
        private val VERSION = Regex("""(?<![\d.])\d+\.\d+\.\d+(?:\.\d+)?(?![\d.])""")

        /**
         * Release tags here are patch-level ("1.10.4") and group immutable builds listed in the
         * body ("1.10.4.1"), so the newest of the tag and the body's builds *of that tag* is the
         * one on offer. Other dotted numbers in the notes (dependency versions) are ignored.
         */
        fun parse(json: String): GitHubRelease? = try {
            val o = JsonParser.parseString(json).asJsonObject
            if (o.get("draft")?.asBoolean == true || o.get("prerelease")?.asBoolean == true) return null
            val tag = o.get("tag_name")?.asString.orEmpty()
            val body = o.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val url = o.get("html_url")?.asString ?: return null
            val tagVersion = VERSION.find(tag)?.value ?: return null
            val version = (sequenceOf(tagVersion) + VERSION.findAll(body).map { it.value }
                .filter { it == tagVersion || it.startsWith("$tagVersion.") })
                .maxWith(VersionCompare::compare)
            GitHubRelease(version, url)
        } catch (e: Exception) {
            null
        }
    }
}

/** Dotted numeric versions ("1.10.4", "1.10.4.1"). */
object VersionCompare {
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /**
     * Whether [candidate] is newer than [current]. A three-part [current] (a local or debug build,
     * named from version.properties without the build number) is compared on three parts only, so
     * 1.10.4.1 doesn't count as newer than the 1.10.4 it was built from.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val currentParts = parts(current)
        if (currentParts.isEmpty()) return false
        val trimmed = if (currentParts.size == 3) parts(candidate).take(3).joinToString(".") else candidate
        return compare(trimmed, current) > 0
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit) }.takeWhile { it.isNotEmpty() }.map { it.toInt() }
}
