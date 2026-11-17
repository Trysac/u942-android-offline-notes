package io.github.trysac.android_offline_notes

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals

/** Property-based tests of [Text]. */
class TextPropertiesTest {
    @Provide
    fun nonBlankStrings(): Arbitrary<String> =
        Arbitraries
            .strings()
            .alpha()
            .ofMinLength(1)
            .ofMaxLength(40)

    @Property
    fun normalizeIsIdempotent(
        @ForAll("nonBlankStrings") raw: String,
    ) {
        val once = Text.normalize(raw)
        assertEquals(once, Text.normalize(once))
    }
}
