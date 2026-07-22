package com.rite.pillcounting.util.shadow

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.OutputStream

/**
 * Minimal fake for [android.graphics.pdf.PdfDocument] used only under Robolectric unit tests.
 *
 * Robolectric (as of 4.13) ships no shadow for `android.graphics.pdf.PdfDocument` in either
 * LEGACY or NATIVE graphics mode: the real framework class's native pointer
 * (`mNativeDocument`) never gets a valid backing implementation under Robolectric, so calling
 * `startPage()` immediately after construction throws
 * `java.lang.IllegalStateException: document is closed!` from `PdfDocument.throwIfClosed()`.
 *
 * This shadow replaces the whole lifecycle (open/close tracking, page management, and
 * `writeTo`) with an in-memory fake so that code under test can exercise real
 * `Canvas`/`Paint`/`StaticLayout` drawing calls against a page's canvas, and so that
 * `writeTo(OutputStream)` produces non-empty output for assertions like
 * `file.length() > 0`. It does not attempt to produce a byte-accurate PDF.
 */
@Implements(PdfDocument::class)
class ShadowPdfDocument {

    @RealObject
    private lateinit var realDocument: PdfDocument

    private val pages = mutableListOf<PdfDocument.Page>()
    private var closed = false

    @Implementation
    fun __constructor__() {
        // No-op: avoid calling the real native constructor, which is what leaves
        // mNativeDocument in a state that trips throwIfClosed() on first use.
    }

    @Implementation
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        check(!closed) { "document is closed!" }
        val page = newFakePage(pageInfo)
        pages.add(page)
        return page
    }

    @Implementation
    fun finishPage(page: PdfDocument.Page) {
        check(!closed) { "document is closed!" }
        // No-op: nothing further to finalize on the fake page.
    }

    @Implementation
    fun getPages(): List<PdfDocument.Page> = pages.toList()

    @Implementation
    fun writeTo(out: OutputStream) {
        check(!closed) { "document is closed!" }
        // Emit a minimal but non-empty payload so callers/tests that assert the
        // resulting file exists and is non-empty behave as they would against a
        // real PDF byte stream. Pad proportionally to the page count so tests
        // asserting that multi-page documents are meaningfully larger than a
        // single-page document (mirroring how a real PDF grows with content)
        // still get a meaningful signal from this in-memory fake.
        val header = "%PDF-1.4\n% Fake PDF produced by ShadowPdfDocument for Robolectric tests\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write("% pages=${pages.size}\n".toByteArray(Charsets.US_ASCII))
        repeat(pages.size) { index ->
            out.write("% page-content-filler-$index-".toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(1000) { '0'.code.toByte() })
            out.write("\n".toByteArray(Charsets.US_ASCII))
        }
        out.write("%%EOF\n".toByteArray(Charsets.US_ASCII))
    }

    @Implementation
    fun close() {
        closed = true
        pages.clear()
    }

    private fun newFakePage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        // PdfDocument.Page has no public constructor; its exact private constructor
        // signature has varied across Android SDK versions, so pick whichever
        // declared constructor matches at runtime instead of hard-coding one.
        val canvas = Canvas()
        val ctor = PdfDocument.Page::class.java.declaredConstructors
            .maxByOrNull { it.parameterCount }
            ?: error("PdfDocument.Page has no declared constructors")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { paramType ->
            when {
                paramType.isAssignableFrom(Canvas::class.java) -> canvas
                paramType.isAssignableFrom(PdfDocument.PageInfo::class.java) -> pageInfo
                paramType == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as PdfDocument.Page
    }
}
