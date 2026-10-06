package com.montageai.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MatchResult(
    val sources: List<SourceCard>,
    /** Sources whose start time was found in the narration. */
    val matched: Int,
    val total: Int,
    /** Titles of the sources that were not found in the narration. */
    val missing: List<String>,
)

/**
 * Places each source on the timeline by finding where the narrator talks about it.
 *
 * No AI model is involved: the words of the source (title, author, translation, quote) are compared
 * with the transcript, and the sources are assumed to be mentioned in the order they are listed.
 */
object Matcher {
    private const val WINDOW = 14
    private const val MIN_GAP_WORDS = 5

    private val prefixes = listOf("وال", "فال", "بال", "كال", "لل", "ال")
    private val stopWords = setOf(
        "هذا", "هذه", "ذلك", "تلك", "هناك", "كان", "كانت", "يكون", "تكون", "الذي", "التي", "الذين",
        "عندما", "عند", "بين", "ايضا", "فقط", "حيث", "اذا", "لكن", "كما", "مثل", "بعض", "حتي", "انما",
        "علي", "الي", "انه", "انها", "هما", "منه", "منها", "عنه", "عنها", "فيه", "فيها", "لها", "كذلك",
    )

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
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    /** Comparable keywords of a text: normalized, without "ال"-type prefixes, short and filler words dropped. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in normalize(text).split(' ')) {
            if (raw.isEmpty()) continue
            var t = raw
            for (p in prefixes) {
                if (t.startsWith(p) && t.length - p.length >= 3) {
                    t = t.substring(p.length)
                    break
                }
            }
            if (t.length < 3 || t in stopWords) continue
            out.add(t)
        }
        return out
    }

    fun match(sources: List<SourceCard>, words: List<Word>, onlyEmpty: Boolean): MatchResult {
        if (sources.isEmpty() || words.isEmpty()) {
            return MatchResult(sources, 0, sources.size, sources.map { label(it) })
        }
        val n = words.size
        val tok = Array(n) { tokens(words[it].text).firstOrNull() }
        val counts = HashMap<String, Int>()
        for (t in tok) if (t != null) counts[t] = (counts[t] ?: 0) + 1

        val raw = sources.map { keywordWeights(it) }
        val srcFreq = HashMap<String, Int>()
        for (m in raw) for (t in m.keys) srcFreq[t] = (srcFreq[t] ?: 0) + 1
        // A word that many sources share, or that the narrator repeats all the time, says little.
        val eff = raw.map { m ->
            val e = HashMap<String, Double>()
            for ((t, w) in m) {
                val c = counts[t] ?: continue
                e[t] = w / sqrt(srcFreq.getValue(t).toDouble()) / sqrt(c.toDouble())
            }
            e
        }

        val k = sources.size
        val winLen = min(WINDOW, n)
        val m = max(1, n - winLen + 1)
        val score = Array(k) { DoubleArray(m) { -1.0 } }
        val firstIdx = Array(k) { IntArray(m) { -1 } }
        for (i in 0 until k) {
            if (eff[i].isEmpty()) continue
            val need = min(2, raw[i].size)
            for (w in 0 until m) {
                val seen = HashSet<String>()
                var sum = 0.0
                var first = -1
                for (p in w until min(n, w + winLen)) {
                    val t = tok[p] ?: continue
                    val e = eff[i][t] ?: continue
                    if (seen.add(t)) {
                        sum += e
                        if (first < 0) first = p
                    }
                }
                if (seen.size >= need && first >= 0) {
                    score[i][w] = sum
                    firstIdx[i][w] = first
                }
            }
        }

        // Best placement that keeps the listed order (dynamic programming over window positions).
        val g = Array(k) { DoubleArray(m) }
        val choice = Array(k) { ByteArray(m) }
        fun prev(i: Int, x: Int): Double = if (i == 0 || x < 0) 0.0 else g[i - 1][x]
        for (i in 0 until k) {
            for (w in 0 until m) {
                var best = prev(i, w)
                var c: Byte = 1 // skip this source
                if (w > 0 && g[i][w - 1] > best) {
                    best = g[i][w - 1]
                    c = 0 // placed (or skipped) at an earlier position
                }
                if (score[i][w] > 0.0) {
                    val v = score[i][w] + prev(i, w - MIN_GAP_WORDS)
                    if (v > best) {
                        best = v
                        c = 2
                    }
                }
                g[i][w] = best
                choice[i][w] = c
            }
        }
        val placed = IntArray(k) { -1 }
        var i = k - 1
        var w = m - 1
        while (i >= 0 && w >= 0) {
            when (choice[i][w].toInt()) {
                0 -> w -= 1
                1 -> i -= 1
                else -> {
                    placed[i] = w
                    w -= MIN_GAP_WORDS
                    i -= 1
                }
            }
        }
        // A source that lost to the ordering still gets its own best spot, if it has one.
        for (s in 0 until k) {
            if (placed[s] >= 0) continue
            var bw = -1
            for (x in 0 until m) if (score[s][x] > 0.0 && (bw < 0 || score[s][x] > score[s][bw])) bw = x
            placed[s] = bw
        }

        val foundStart = DoubleArray(k) { -1.0 }
        for (s in 0 until k) {
            if (placed[s] >= 0) foundStart[s] = max(0.0, words[firstIdx[s][placed[s]]].start - 0.5)
        }
        val finalStart = DoubleArray(k) { s ->
            val existing = sources[s].startSec
            if (onlyEmpty && existing != null) existing else if (foundStart[s] >= 0.0) foundStart[s] else existing ?: -1.0
        }

        val audioEnd = words.last().end
        val result = ArrayList<SourceCard>(k)
        val missing = ArrayList<String>()
        var matched = 0
        for (s in 0 until k) {
            val src = sources[s]
            var card = src
            if (foundStart[s] >= 0.0) {
                matched++
                if (!onlyEmpty || src.startSec == null) card = card.copy(startSec = foundStart[s])
            } else {
                missing.add(label(src))
            }

            val start = finalStart[s]
            if (start >= 0.0) {
                var end = audioEnd
                for (o in 0 until k) if (o != s && finalStart[o] > start + 0.01 && finalStart[o] < end) end = finalStart[o]
                val lo = indexAtTime(words, start)
                val hi = max(lo, indexAtTime(words, end))

                val phrase = src.highlightPhrase.ifBlank { src.quote.trim().split(Regex("\\s+")).take(5).joinToString(" ") }
                val hiAt = findPhrase(tok, words, tokens(phrase), lo, hi, preferLatest = false)
                if (hiAt != null && (!onlyEmpty || src.highlightSec == null)) {
                    card = card.copy(highlightSec = max(hiAt - 0.1, start))
                }

                if (src.conclusion.isNotBlank()) {
                    val from = card.highlightSec?.let { max(lo, indexAtTime(words, it + 0.5)) } ?: lo
                    val concAt = findPhrase(tok, words, tokens(src.conclusion), min(from, hi), hi, preferLatest = true)
                    if (concAt != null && (!onlyEmpty || src.conclusionSec == null)) {
                        card = card.copy(conclusionSec = concAt)
                    }
                }
            }
            result.add(card)
        }
        return MatchResult(result, matched, k, missing)
    }

    private fun label(s: SourceCard): String = s.title.ifBlank { s.author.ifBlank { "(بدون عنوان)" } }

    private fun keywordWeights(s: SourceCard): Map<String, Double> {
        val m = HashMap<String, Double>()
        fun add(text: String, w: Double) {
            for (t in tokens(text)) m[t] = max(m[t] ?: 0.0, w)
        }
        add(s.title, 2.0)
        add(s.author, 2.0)
        add(s.translation, 1.2)
        add(s.quote, 1.0)
        return m
    }

    private fun indexAtTime(words: List<Word>, t: Double): Int {
        for (i in words.indices) if (words[i].start >= t) return i
        return words.size
    }

    /** Start time of the narration span in [lo, hi) that best covers the words of the phrase, or null. */
    private fun findPhrase(
        tok: Array<String?>, words: List<Word>, phrase: List<String>, lo: Int, hi: Int, preferLatest: Boolean,
    ): Double? {
        val distinct = phrase.toSet()
        if (distinct.isEmpty() || hi <= lo) return null
        val need = min(2, distinct.size)
        val len = max(4, distinct.size + 3)
        var bestCount = 0
        var bestFirst = -1
        var w = lo
        while (w < hi) {
            val seen = HashSet<String>()
            var first = -1
            for (p in w until min(hi, w + len)) {
                val t = tok[p] ?: continue
                if (t in distinct && seen.add(t) && first < 0) first = p
            }
            val c = seen.size
            if (c >= need && (c > bestCount || (preferLatest && c == bestCount))) {
                bestCount = c
                bestFirst = first
            }
            w++
        }
        return if (bestFirst >= 0) words[bestFirst].start else null
    }
}
