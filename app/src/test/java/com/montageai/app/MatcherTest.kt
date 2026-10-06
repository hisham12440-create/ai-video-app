package com.montageai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MatcherTest {

    private val narration =
        "اليوم سنتحدث عن مسألة السببية في الفلسفة " +
            "يقول ديفيد هيوم في كتابه تحقيق في الفهم البشري إن السببية مجرد عادة ذهنية " +
            "وخلاصة ذلك أن السببية عند هيوم ليست سوى عادة " +
            "ثم ننتقل إلى كانط في كتابه نقد العقل المحض حيث يرى أن السببية مقولة قبلية " +
            "وأخيرا نصل إلى لايبنتز وكتابه المونادولوجيا ومبدأ العلة الكافية"

    private val words: List<Word> = narration.split(' ').mapIndexed { i, t -> Word(t, i * 0.5, i * 0.5 + 0.4) }

    private fun timeOf(word: String, occurrence: Int = 0): Double {
        var seen = 0
        for (w in words) {
            if (w.text == word) {
                if (seen == occurrence) return w.start
                seen++
            }
        }
        throw IllegalArgumentException("word not found: $word")
    }

    private val hume = SourceCard(
        id = "hume", title = "تحقيق في الفهم البشري", author = "ديفيد هيوم",
        quote = "السببية مجرد عادة ذهنية", conclusion = "السببية عند هيوم عادة",
    )
    private val kant = SourceCard(id = "kant", title = "نقد العقل المحض", author = "إيمانويل كانط")
    private val leibniz = SourceCard(id = "leibniz", title = "المونادولوجيا", author = "لايبنتز")

    @Test
    fun normalizeUnifiesArabicLetterVariants() {
        assertEquals("ايمانويل", Matcher.normalize("إيمانويل").trim())
        assertEquals("مدرسه", Matcher.normalize("مَدْرَسَة").trim())
        assertEquals("علي", Matcher.normalize("على").trim())
        assertEquals("123", Matcher.normalize("١٢٣").trim())
    }

    @Test
    fun tokensDropPrefixesShortAndFillerWords() {
        assertEquals(listOf("كتاب"), Matcher.tokens("الكتاب"))
        assertEquals(listOf("فهم", "بشري"), Matcher.tokens("في الفهم البشري"))
        assertEquals(emptyList<String>(), Matcher.tokens("هذا الذي"))
    }

    @Test
    fun placesEverySourceWhereItIsMentionedAndInOrder() {
        val r = Matcher.match(listOf(hume, kant, leibniz), words, onlyEmpty = true)
        assertEquals(3, r.matched)
        assertTrue(r.missing.isEmpty())
        val s = r.sources.map { it.startSec }
        assertTrue(s.all { it != null })
        assertTrue(s[0]!! < s[1]!! && s[1]!! < s[2]!!)

        assertTrue("hume start ${s[0]}", s[0]!! <= timeOf("ذهنية"))
        assertTrue("hume start ${s[0]}", s[0]!! >= timeOf("السببية") - 0.6)
        assertTrue("kant start ${s[1]}", abs(s[1]!! - timeOf("كانط")) <= 3.0)
        assertTrue("leibniz start ${s[2]}", abs(s[2]!! - timeOf("لايبنتز")) <= 3.0)
    }

    @Test
    fun highlightAndConclusionAreFoundInsideTheScene() {
        val r = Matcher.match(listOf(hume, kant, leibniz), words, onlyEmpty = true)
        val h = r.sources[0]
        assertNotNull(h.highlightSec)
        assertTrue("highlight ${h.highlightSec}", h.highlightSec!! >= timeOf("مجرد") - 1.5)
        assertTrue("highlight ${h.highlightSec}", h.highlightSec!! <= timeOf("ذهنية"))

        assertNotNull(h.conclusionSec)
        assertTrue("conclusion ${h.conclusionSec}", h.conclusionSec!! > h.highlightSec!!)
        assertTrue("conclusion ${h.conclusionSec}", h.conclusionSec!! < r.sources[1].startSec!!)
        // The conclusion is said after "وخلاصة ذلك", at or after the second mention of "السببية".
        assertTrue(h.conclusionSec!! >= timeOf("السببية", 1))
    }

    @Test
    fun existingManualTimingsSurviveWhenFillingOnlyEmpty() {
        val manual = kant.copy(startSec = 1.0)
        val r = Matcher.match(listOf(hume, manual, leibniz), words, onlyEmpty = true)
        assertEquals(1.0, r.sources[1].startSec!!, 0.0001)

        val replaced = Matcher.match(listOf(hume, manual, leibniz), words, onlyEmpty = false)
        assertTrue(abs(replaced.sources[1].startSec!! - timeOf("كانط")) <= 3.0)
    }

    @Test
    fun aSourceThatIsNeverMentionedIsReportedAndLeftEmpty() {
        val absent = SourceCard(id = "x", title = "الجمهورية", author = "أفلاطون")
        val r = Matcher.match(listOf(hume, absent, leibniz), words, onlyEmpty = true)
        assertEquals(2, r.matched)
        assertEquals(listOf("الجمهورية"), r.missing)
        assertNull(r.sources[1].startSec)
        assertNotNull(r.sources[0].startSec)
        assertNotNull(r.sources[2].startSec)
    }

    @Test
    fun emptyInputsAreHandled() {
        val none = Matcher.match(emptyList(), words, onlyEmpty = true)
        assertEquals(0, none.total)
        val noWords = Matcher.match(listOf(hume), emptyList(), onlyEmpty = true)
        assertEquals(0, noWords.matched)
        assertNull(noWords.sources[0].startSec)
    }
}
