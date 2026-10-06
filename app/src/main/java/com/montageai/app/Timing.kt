package com.montageai.app

import kotlin.math.max
import kotlin.math.min

/** Arabic-aware text helpers (pure Kotlin, no Android classes, so they are unit-testable). */
object TextNorm {
    private val prefixes = listOf("وال", "فال", "بال", "كال", "لل", "ال")

    /** Removes diacritics and unifies letter variants so spoken and written Arabic compare equal. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in 'ً'..'ٟ' || ch == 'ٰ' || ch == 'ـ' -> Unit
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
                ch == 'ى' || ch == 'ئ' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch == 'ؤ' -> sb.append('و')
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** A comparable form of one word: normalized and without "ال"-type prefixes. */
    fun key(raw: String): String {
        val n = normalize(raw)
        for (p in prefixes) {
            if (n.startsWith(p) && n.length - p.length >= 2) return n.substring(p.length)
        }
        return n
    }

    fun splitWords(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun endsSentence(w: String): Boolean {
        val t = w.trimEnd('"', '\'', '»', '”', ')', ' ')
        return t.isNotEmpty() && t.last() in ".!?؟…"
    }

    fun endsClause(w: String): Boolean {
        val t = w.trimEnd('"', '\'', '»', '”', ')', ' ')
        return t.isNotEmpty() && t.last() in "،,;؛:-–—"
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

class Aligned(val words: List<Word>, val matchedRatio: Double)

/**
 * Gives every word of the written script a time on the voice-over.
 *
 * With a transcript (from Whisper) the script is aligned to what was actually said, so the captions
 * show the exact written text at the exact spoken moment. Without a transcript the words are spread over
 * the audio in proportion to their length, with longer pauses after punctuation.
 */
object ScriptAligner {
    private const val MAX_CELLS = 24_000_000L

    private fun score(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return -2
        if (a == b) return 3
        val sa = TextNorm.key(a)
        val sb = TextNorm.key(b)
        if (sa.isNotEmpty() && sa == sb) return 2
        if (min(sa.length, sb.length) >= 4 && TextNorm.editDistance(sa, sb) <= 1) return 1
        return -2
    }

    fun align(scriptWords: List<String>, transcript: List<Word>, duration: Double): Aligned {
        if (scriptWords.isEmpty()) return Aligned(emptyList(), 0.0)
        if (transcript.isEmpty()) return Aligned(spread(scriptWords, 0.0, duration), 0.0)

        val n = scriptWords.size
        val m = transcript.size
        val speechStart = transcript.first().start
        val speechEnd = max(transcript.last().end, speechStart + 0.5)
        if (n.toLong() * m > MAX_CELLS) return Aligned(spread(scriptWords, speechStart, speechEnd), 0.0)

        val sk = scriptWords.map { TextNorm.normalize(it) }
        val tk = transcript.map { TextNorm.normalize(it.text) }
        val gap = -1
        // trace: 0 = diagonal, 1 = up (skip script word), 2 = left (skip transcript word)
        val trace = ByteArray((n + 1) * (m + 1))
        var prev = IntArray(m + 1) { it * gap }
        var cur = IntArray(m + 1)
        for (j in 1..m) trace[j] = 2
        for (i in 1..n) {
            cur[0] = i * gap
            trace[i * (m + 1)] = 1
            for (j in 1..m) {
                val diag = prev[j - 1] + score(sk[i - 1], tk[j - 1])
                val up = prev[j] + gap
                val left = cur[j - 1] + gap
                var best = diag
                var dir: Byte = 0
                if (up > best) {
                    best = up
                    dir = 1
                }
                if (left > best) {
                    best = left
                    dir = 2
                }
                cur[j] = best
                trace[i * (m + 1) + j] = dir
            }
            val t = prev
            prev = cur
            cur = t
        }

        val matchOf = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val dir = if (i == 0) 2 else if (j == 0) 1 else trace[i * (m + 1) + j].toInt()
            when (dir) {
                0 -> {
                    if (score(sk[i - 1], tk[j - 1]) > 0) matchOf[i - 1] = j - 1
                    i--
                    j--
                }
                1 -> i--
                else -> j--
            }
        }

        val matched = matchOf.count { it >= 0 }
        val ratio = matched.toDouble() / n
        if (ratio < 0.25) return Aligned(spread(scriptWords, speechStart, speechEnd), ratio)

        val starts = DoubleArray(n)
        val ends = DoubleArray(n)
        val known = BooleanArray(n)
        for (k in 0 until n) {
            if (matchOf[k] >= 0) {
                starts[k] = transcript[matchOf[k]].start
                ends[k] = transcript[matchOf[k]].end
                known[k] = true
            }
        }
        var k = 0
        while (k < n) {
            if (known[k]) {
                k++
                continue
            }
            var e = k
            while (e + 1 < n && !known[e + 1]) e++
            val left = if (k > 0) ends[k - 1] else speechStart
            var right = if (e + 1 < n) starts[e + 1] else speechEnd
            val count = e - k + 1
            if (right < left + 0.08 * count) right = left + 0.12 * count
            var totalW = 0.0
            for (x in k..e) totalW += weight(scriptWords[x])
            var t = left
            for (x in k..e) {
                val span = (right - left) * weight(scriptWords[x]) / totalW
                starts[x] = t
                ends[x] = t + span
                t += span
            }
            k = e + 1
        }

        val out = ArrayList<Word>(n)
        var lastStart = 0.0
        for (x in 0 until n) {
            val s = max(starts[x], lastStart)
            var e = max(ends[x], s + 0.06)
            if (x + 1 < n) {
                val nextStart = max(starts[x + 1], s)
                if (nextStart > s + 0.06) e = min(e, nextStart)
            }
            out.add(Word(scriptWords[x], s, e))
            lastStart = s
        }
        return Aligned(out, ratio)
    }

    private fun weight(w: String): Double {
        var v = max(2, TextNorm.normalize(w).length).toDouble()
        if (TextNorm.endsSentence(w)) v += 5.0 else if (TextNorm.endsClause(w)) v += 2.5
        return v
    }

    /** Spreads the words over [from, to] in proportion to their length. */
    fun spread(scriptWords: List<String>, from: Double, to: Double): List<Word> {
        if (scriptWords.isEmpty()) return emptyList()
        val lead = if (from <= 0.0) 0.15 else 0.0
        val tail = if (to > from + 1.0) 0.25 else 0.0
        val a = from + lead
        val b = max(a + 0.2 * scriptWords.size, to - tail)
        var totalW = 0.0
        for (w in scriptWords) totalW += weight(w)
        var t = a
        val out = ArrayList<Word>(scriptWords.size)
        for (w in scriptWords) {
            val span = (b - a) * weight(w) / totalW
            out.add(Word(w, t, t + span * 0.92))
            t += span
        }
        return out
    }
}
