package com.vpn0wn.android.widget

import android.app.Application
import android.text.InputFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class InputFilterReplacementTest {
    @Test fun fullNameAllowsSingleCharacterReplacement() {
        val text = editable(NameInputFilter(), FULL_NAME)
        text.replace(7, 8, "Z")
        assertEquals("abcdefgZijklmno", text.toString())
    }

    @Test fun fullNameAllowsSelectAllReplacement() {
        val text = editable(NameInputFilter(), FULL_NAME)
        text.replace(0, text.length, "replacement")
        assertEquals("replacement", text.toString())
    }

    @Test fun nameReplacementCountsRetainedCharactersAfterFiltering() {
        val text = editable(NameInputFilter(), FULL_NAME)
        text.replace(7, 9, "X?Y")
        assertEquals("abcdefgXYjklmno", text.toString())
    }

    @Test fun nameReplacementUsesOnlyTheSelectedSourceRange() {
        val text = editable(NameInputFilter(), FULL_NAME)
        text.replace(7, 9, "--X?Y--", 2, 5)
        assertEquals("abcdefgXYjklmno", text.toString())
    }

    @Test fun nameInsertionStillRejectsOverflowAndAllowsDeletion() {
        val text = editable(NameInputFilter(), FULL_NAME)
        text.insert(3, "Z")
        assertEquals(FULL_NAME, text.toString())
        text.delete(7, 8)
        assertEquals("abcdefgijklmno", text.toString())
    }

    @Test fun fullKeyAllowsSingleCharacterReplacement() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.replace(7, 8, "B")
        assertEquals("A".repeat(7) + "B" + "A".repeat(35) + "=", text.toString())
    }

    @Test fun fullKeyAllowsSelectAllReplacementIncludingPadding() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.replace(0, text.length, "B".repeat(43) + "=")
        assertEquals("B".repeat(43) + "=", text.toString())
    }

    @Test fun keyReplacementUsesAcceptedPositionAfterRejectedCharacters() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.replace(0, text.length, "?" + "B".repeat(43) + "=")
        assertEquals("B".repeat(43) + "=", text.toString())
    }

    @Test fun keyReplacementCountsRetainedLengthAndSelectedSourceRange() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.replace(7, 9, "!!B?C!!", 2, 5)
        assertEquals("A".repeat(7) + "BC" + "A".repeat(34) + "=", text.toString())
    }

    @Test fun keyInsertionStillRejectsOverflowAndIncorrectPadding() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.insert(7, "B")
        assertEquals(FULL_KEY, text.toString())
        val empty = editable(KeyInputFilter(), "")
        empty.append("=" + "B".repeat(43) + "=")
        assertEquals("B".repeat(43) + "=", empty.toString())
    }

    @Test fun keyDeletionAllowsAnIntermediateIncompleteValue() {
        val text = editable(KeyInputFilter(), FULL_KEY)
        text.delete(43, 44)
        assertEquals("A".repeat(43), text.toString())
    }

    @Test fun filteredReplacementPreservesAcceptedSourceSpans() {
        val source = SpannableString("B?C")
        val span = UnderlineSpan()
        source.setSpan(span, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (filter in listOf(NameInputFilter(), KeyInputFilter())) {
            val text = editable(filter, "AA")
            text.replace(0, 2, source)
            assertEquals("BC", text.toString())
            assertSame(span, text.getSpans(0, text.length, UnderlineSpan::class.java).single())
            assertEquals(0, text.getSpanStart(span))
            assertEquals(2, text.getSpanEnd(span))
        }
    }

    private fun editable(filter: InputFilter, initial: String) = SpannableStringBuilder(initial).apply {
        filters = arrayOf(filter)
    }

    companion object {
        private const val FULL_NAME = "abcdefghijklmno"
        private val FULL_KEY = "A".repeat(43) + "="
    }
}
