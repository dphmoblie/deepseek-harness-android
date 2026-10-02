package io.deepseekharness.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OverlayFileChooserPolicyTest {
    @Test
    fun acceptsOnlyBoundedMimeTypes() {
        assertArrayEquals(
            arrayOf("image/*", "application/pdf"),
            OverlayFileChooserPolicy.mimeTypes(
                arrayOf("image/*", ".png", "application/pdf", "image/*", "text/plain\ninvalid"),
            ),
        )
        assertArrayEquals(arrayOf("*/*"), OverlayFileChooserPolicy.mimeTypes(arrayOf(".png")))
        assertArrayEquals(arrayOf("*/*"), OverlayFileChooserPolicy.mimeTypes(null))
    }
}
