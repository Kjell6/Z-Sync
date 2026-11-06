package de.kjell.zencompanion

import android.webkit.PermissionRequest
import de.kjell.zencompanion.data.SitePermission
import de.kjell.zencompanion.data.SitePermissionChoice
import de.kjell.zencompanion.data.SitePermissionDecision
import de.kjell.zencompanion.data.sitePermissionDecision
import de.kjell.zencompanion.ui.browser.BrowserPrompt
import de.kjell.zencompanion.ui.browser.grantableResources
import de.kjell.zencompanion.ui.browser.isPageLocalDownload
import de.kjell.zencompanion.ui.browser.isWebDownload
import de.kjell.zencompanion.ui.browser.nameWithExtension
import de.kjell.zencompanion.ui.browser.pageFileScript
import de.kjell.zencompanion.ui.browser.safeDownloadName
import de.kjell.zencompanion.ui.browser.sitePermissionsFor
import de.kjell.zencompanion.ui.screens.systemAccessGranted
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageRequestsTests {
    private fun decision(vararg answers: Pair<SitePermission, SitePermissionChoice?>) =
        sitePermissionDecision(answers.map { it.first }) { kind -> answers.first { it.first == kind }.second }

    @Test
    fun anUnknownSiteIsAsked() {
        assertEquals(SitePermissionDecision.ASK, decision(SitePermission.CAMERA to null))
    }

    @Test
    fun oneBlockedKindBlocksTheWholeRequest() {
        assertEquals(
            SitePermissionDecision.DENY,
            decision(
                SitePermission.CAMERA to SitePermissionChoice.ALLOW,
                SitePermission.MICROPHONE to SitePermissionChoice.BLOCK,
            ),
        )
    }

    @Test
    fun everyKindMustBeAllowedToSkipTheQuestion() {
        assertEquals(
            SitePermissionDecision.ASK,
            decision(SitePermission.CAMERA to SitePermissionChoice.ALLOW, SitePermission.MICROPHONE to null),
        )
        assertEquals(
            SitePermissionDecision.GRANT,
            decision(
                SitePermission.CAMERA to SitePermissionChoice.ALLOW,
                SitePermission.MICROPHONE to SitePermissionChoice.ALLOW,
            ),
        )
    }

    @Test
    fun anEmptyRequestIsNeverGranted() {
        assertEquals(SitePermissionDecision.ASK, sitePermissionDecision(emptyList()) { null })
    }

    @Test
    fun onlyCameraAndMicrophoneCanBeGranted() {
        val resources = arrayOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
        )
        assertEquals(listOf(SitePermission.CAMERA, SitePermission.MICROPHONE), sitePermissionsFor(resources))
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            grantableResources(resources),
        )
        assertTrue(sitePermissionsFor(arrayOf(PermissionRequest.RESOURCE_MIDI_SYSEX)).isEmpty())
    }

    @Test
    fun approximateLocationIsEnough() {
        assertTrue(systemAccessGranted(SitePermission.LOCATION) { it.endsWith("COARSE_LOCATION") })
        assertFalse(systemAccessGranted(SitePermission.CAMERA) { it.endsWith("COARSE_LOCATION") })
    }

    @Test
    fun aPromptAnswersOnce() {
        val answers = mutableListOf<Boolean>()
        val prompt = BrowserPrompt.Permission("example.com", listOf(SitePermission.CAMERA)) { answers += it }
        prompt.send(true)
        prompt.dismiss()
        assertEquals(listOf(true), answers)
    }

    @Test
    fun aDismissedPromptRefuses() {
        val answers = mutableListOf<Boolean>()
        BrowserPrompt.Permission("example.com", listOf(SitePermission.LOCATION)) { answers += it }.dismiss()
        assertEquals(listOf(false), answers)
    }

    @Test
    fun aDismissedLoginCancels() {
        var cancelled = 0
        val login = BrowserPrompt.HttpLogin("router", { _, _ -> }, { cancelled++ })
        login.dismiss()
        login.dismiss()
        login.signIn("a", "b")
        assertEquals(1, cancelled)
    }

    @Test
    fun aPageNameStaysInsideTheFolder() {
        assertEquals(".._.._etc_passwd".trim('.'), safeDownloadName("../../etc/passwd"))
        assertEquals("download", safeDownloadName("..."))
        assertEquals("download", safeDownloadName(null))
        assertEquals("report.pdf", safeDownloadName("report.pdf"))
    }

    @Test
    fun aNameWithoutExtensionGainsTheFilesOwn() {
        assertEquals("download.png", nameWithExtension("download", "png"))
        assertEquals("a.txt", nameWithExtension("a.txt", "png"))
        assertEquals("download", nameWithExtension("download", null))
    }

    @Test
    fun downloadsSplitByWhoCanReadTheAddress() {
        assertTrue(isWebDownload("https://example.com/a.zip"))
        assertTrue(isPageLocalDownload("blob:https://example.com/1"))
        assertTrue(isPageLocalDownload("data:text/plain,hi"))
        assertFalse(isWebDownload("intent://x"))
        assertFalse(isPageLocalDownload("intent://x"))
    }

    @Test
    fun aPageCannotBreakOutOfTheFileScript() {
        val script = pageFileScript("blob:x\"; alert(1); \"", "token")
        assertFalse(script.contains("blob:x\";"))
        assertTrue(script.contains("blob:x\\\""))
    }
}
