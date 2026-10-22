package com.hereliesaz.cleanunderwear.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun releaseBuilds_compareAllFourParts() {
        assertTrue(VersionCompare.isNewer("1.10.5.1", "1.10.4.1"))
        assertTrue(VersionCompare.isNewer("1.10.4.2", "1.10.4.1"))
        assertTrue(VersionCompare.isNewer("1.11.0.1", "1.10.9.9"))
        assertFalse(VersionCompare.isNewer("1.10.4.1", "1.10.4.1"))
        assertFalse(VersionCompare.isNewer("1.10.3.7", "1.10.4.1"))
    }

    @Test
    fun threePartBuilds_ignoreTheBuildNumber() {
        assertFalse(VersionCompare.isNewer("1.10.4.1", "1.10.4"))
        assertTrue(VersionCompare.isNewer("1.10.5.1", "1.10.4"))
    }

    @Test
    fun githubRelease_takesTheNewestBuildNamedInTagOrBody() {
        val json = """
            {"tag_name":"1.10.4","html_url":"https://github.com/HereLiesAz/CleanUnderwear/releases/tag/1.10.4",
             "draft":false,"prerelease":false,
             "body":"# CleanUnderwear 1.10.4\n\nLatest published build in this run: **1.10.4.2**\n\n## Immutable build tags\n- 1.10.4.1\n- 1.10.4.2\n"}
        """.trimIndent()
        val release = GitHubRelease.parse(json)!!
        assertEquals("1.10.4.2", release.version)
        assertEquals("https://github.com/HereLiesAz/CleanUnderwear/releases/tag/1.10.4", release.url)
    }

    @Test
    fun githubRelease_skipsPrereleasesAndJunk() {
        assertNull(GitHubRelease.parse("""{"tag_name":"latest-debug-v1.10","html_url":"x","prerelease":true,"body":"1.10.4.1"}"""))
        assertNull(GitHubRelease.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(GitHubRelease.parse("not json"))
    }

    @Test
    fun githubRelease_toleratesNullBody() {
        val release = GitHubRelease.parse("""{"tag_name":"1.10.5","html_url":"u","prerelease":false,"body":null}""")!!
        assertEquals("1.10.5", release.version)
    }
}
