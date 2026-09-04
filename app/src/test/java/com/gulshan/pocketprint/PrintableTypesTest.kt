package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.SourceDocument
import com.gulshan.pocketprint.render.RenderPipeline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported share target refuses anything [RenderPipeline.canRender] rejects,
 * so this is the gate on what an arbitrary app can hand the pipeline. It is
 * worth pinning both directions: a false negative makes a printable file
 * unprintable, and a false positive puts an unbounded stream from an untrusted
 * caller back into the render path.
 */
class PrintableTypesTest {

    private fun canRender(mime: String, name: String) =
        RenderPipeline.canRender(mime, name.substringAfterLast('.', "").lowercase())

    @Test
    fun `accepts the formats the pipeline has a branch for`() {
        assertTrue(canRender("application/pdf", "label.pdf"))
        assertTrue(canRender("image/jpeg", "photo.jpg"))
        assertTrue(canRender("image/heic", "photo.heic"))
        assertTrue(canRender("text/plain", "notes.txt"))
        assertTrue(canRender("text/html", "page.html"))
        assertTrue(canRender("text/csv", "rows.csv"))
        assertTrue(canRender(SourceDocument.MIME_URL, "https://example.invalid"))
        assertTrue(
            canRender(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "report.docx",
            ),
        )
        assertTrue(canRender("application/msword", "report.doc"))
        assertTrue(canRender("application/vnd.oasis.opendocument.text", "report.odt"))
    }

    @Test
    fun `accepts raw command files, which go to the printer untouched`() {
        for (extension in RenderPipeline.RAW_LABEL_EXTENSIONS) {
            assertTrue(extension, canRender("application/octet-stream", "job.$extension"))
        }
    }

    @Test
    fun `refuses what it cannot print`() {
        assertFalse(canRender("application/vnd.android.package-archive", "app.apk"))
        assertFalse(canRender("application/zip", "archive.zip"))
        assertFalse(canRender("application/octet-stream", "payload.exe"))
        assertFalse(canRender("audio/mpeg", "song.mp3"))
        assertFalse(canRender("video/mp4", "clip.mp4"))
        assertFalse(canRender("application/x-sqlite3", "contacts.db"))
    }

    @Test
    fun `a bare octet-stream with no useful extension is refused`() {
        // The default when a provider declines to say what it is holding.
        assertFalse(canRender("application/octet-stream", "download"))
    }
}
