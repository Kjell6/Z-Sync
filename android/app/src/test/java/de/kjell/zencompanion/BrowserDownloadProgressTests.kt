package de.kjell.zencompanion

import de.kjell.zencompanion.ui.browser.BrowserDownload
import de.kjell.zencompanion.ui.browser.DownloadFailure
import de.kjell.zencompanion.ui.browser.DownloadState
import de.kjell.zencompanion.ui.browser.DownloadTask
import de.kjell.zencompanion.ui.browser.combinedDownloadFraction
import de.kjell.zencompanion.ui.browser.downloadFailure
import de.kjell.zencompanion.ui.browser.downloadMimeType
import de.kjell.zencompanion.ui.browser.downloadPercent
import de.kjell.zencompanion.ui.browser.downloadSummary
import de.kjell.zencompanion.ui.browser.isPageLocalDownload
import de.kjell.zencompanion.ui.browser.safeDownloadName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download capsule's pure side: which file a line speaks for, how far the
 * bytes have come, and what a failure is told from. The system's own
 * downloader and save screen need a device; these do not.
 */
class BrowserDownloadProgressTests {

    private fun task(
        id: Long = 1,
        name: String = "report.pdf",
        state: DownloadState = DownloadState.Downloading,
        bytes: Long = 0,
        total: Long = -1,
    ) = DownloadTask(id = id, name = name, state = state, bytes = bytes, total = total)

    @Test
    fun oneFileShowsItsOwnArrival() {
        val summary = downloadSummary(listOf(task(bytes = 500, total = 1_000)))
        assertEquals(DownloadState.Downloading, summary?.state)
        assertEquals(1, summary?.count)
        assertEquals("report.pdf", summary?.name)
        assertEquals(0.5f, summary?.fraction)
    }

    @Test
    fun aSizeTheServerNeverNamedLeavesTheBarUnknown() {
        val summary = downloadSummary(listOf(task(bytes = 500, total = -1)))
        assertEquals(DownloadState.Downloading, summary?.state)
        assertNull(summary?.fraction)
        // More bytes than promised reads as done, never as past the end.
        assertEquals(1f, task(bytes = 1_200, total = 1_000).fraction)
    }

    @Test
    fun severalFilesShareTheBytesTheyHaveBetweenThem() {
        val tasks = listOf(
            task(id = 1, bytes = 1, total = 4),
            task(id = 2, bytes = 2, total = 4),
        )
        assertEquals(3f / 8f, combinedDownloadFraction(tasks))
        assertEquals(2, downloadSummary(tasks)?.count)
    }

    @Test
    fun oneUnknownSizeLeavesTheWholeLineUnknown() {
        val tasks = listOf(
            task(id = 1, bytes = 3, total = 10),
            task(id = 2, bytes = 1, total = -1),
        )
        assertNull(combinedDownloadFraction(tasks))
    }

    @Test
    fun workInFlightWinsOverAResultThatIsFading() {
        val tasks = listOf(
            task(id = 1, name = "done.pdf", state = DownloadState.Saved),
            task(id = 2, name = "running.pdf"),
        )
        val summary = downloadSummary(tasks)
        assertEquals(DownloadState.Downloading, summary?.state)
        assertEquals(listOf(2L), summary?.ids)
    }

    @Test
    fun theSaveScreenWinsOverAFailure() {
        val tasks = listOf(
            task(id = 1, name = "failed.pdf", state = DownloadState.Failed(DownloadFailure.NETWORK)),
            task(id = 2, name = "waiting.pdf", state = DownloadState.ChoosingLocation),
        )
        val summary = downloadSummary(tasks)
        assertEquals(DownloadState.ChoosingLocation, summary?.state)
        assertEquals("waiting.pdf", summary?.name)
    }

    @Test
    fun aFailureIsNamedWithItsReason() {
        val tasks = listOf(task(name = "report.pdf", state = DownloadState.Failed(DownloadFailure.STORAGE)))
        val summary = downloadSummary(tasks)
        assertTrue(summary?.state is DownloadState.Failed)
        assertEquals(DownloadFailure.STORAGE, summary?.reason)
        assertEquals("report.pdf", summary?.name)
    }

    @Test
    fun nothingRunningShowsNoCapsule() {
        assertNull(downloadSummary(emptyList()))
        assertNull(combinedDownloadFraction(emptyList()))
    }

    @Test
    fun percentagesRound() {
        assertEquals(0, downloadPercent(0f))
        assertEquals(43, downloadPercent(0.426f))
        assertEquals(100, downloadPercent(1f))
        // A report that overshoots never says more than all of it.
        assertEquals(100, downloadPercent(1.4f))
        assertNull(downloadPercent(null))
    }

    @Test
    fun aFailureReasonComesFromTheDownloader() {
        // DownloadManager's own error codes, as COLUMN_REASON carries them.
        assertEquals(DownloadFailure.STORAGE, downloadFailure(198)) // ERROR_INSUFFICIENT_SPACE
        assertEquals(DownloadFailure.NETWORK, downloadFailure(404)) // an HTTP status
        assertEquals(DownloadFailure.NETWORK, downloadFailure(1006)) // ERROR_CANNOT_RESUME
        assertEquals(DownloadFailure.SAVE, downloadFailure(1001)) // ERROR_FILE_ERROR
    }

    @Test
    fun theSaveScreenIsAskedForTheFilesOwnType() {
        val pdf = BrowserDownload(
            url = "https://example.com/report.pdf",
            userAgent = null,
            contentDisposition = null,
            mimeType = "application/pdf",
            referer = null,
        )
        assertEquals("application/pdf", downloadMimeType(pdf))
        // A page-local file with no type is a stream of bytes.
        val blob = BrowserDownload(
            url = "blob:https://example.com/1",
            userAgent = null,
            contentDisposition = null,
            mimeType = null,
            referer = null,
        )
        assertTrue(isPageLocalDownload(blob.url))
        assertEquals("application/octet-stream", downloadMimeType(blob))
    }

    @Test
    fun aNameFromThePageStaysInsideTheFolder() {
        assertEquals("_.._etc_passwd", safeDownloadName("../../etc/passwd"))
        assertEquals("download", safeDownloadName("..."))
        assertEquals("download", safeDownloadName(null))
        assertEquals("report.pdf", safeDownloadName("report.pdf"))
        assertEquals("a_b_.pdf", safeDownloadName("a\nb\u0007.pdf"))
    }
}
