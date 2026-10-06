package com.montageai.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class SfxKind { SOFT_SWELL, SWISH, WHOOSH, DEEP_WHOOSH, WHIP, FLASH, THUD, TICK }

class SfxEvent(val time: Double, val kind: SfxKind, val gain: Float, val variant: Int)

/** How natural a cut point is: a cut at a sentence end feels calmer than one in the middle of a phrase. */
enum class CutKind { SENTENCE, CLAUSE, PAUSE, ARBITRARY }

class Beat(val start: Double, val end: Double, val firstWord: Int, val lastWord: Int, val endKind: CutKind)

class Shot(
    val index: Int,
    val mediaIndex: Int,
    val mediaId: String,
    val start: Double,
    val end: Double,
    val motion: Motion,
    val transition: Transition,
    /** True for the 2nd, 3rd… shot of the same image: the camera starts tighter (a "punch-in" jump cut). */
    val tight: Boolean,
    /** Alternates left/right (or up/down) so consecutive transitions do not all go the same way. */
    val reverse: Boolean,
)

class CaptionChunk(val words: List<Word>, val start: Double, val end: Double, val showFrom: Double, val showTo: Double)

class EditPlan(
    val duration: Double,
    val shots: List<Shot>,
    val captions: List<CaptionChunk>,
    val sfx: List<SfxEvent>,
    val words: List<Word>,
    /** Share of the written script found in the speech, 0..1 (1.0 when there is no script). */
    val scriptMatch: Double,
) {
    companion object {
        val EMPTY = EditPlan(0.0, emptyList(), emptyList(), emptyList(), emptyList(), 1.0)
    }
}

/**
 * Turns the narration timing, the script and the images into an edit decision list:
 * where each image starts, how the camera moves, how shots are joined, what the captions say and which
 * sound effect goes with every transition. Pure Kotlin; no Android classes.
 */
object Planner {
    private const val MIN_SHOT = 1.4
    private const val SPLIT_ABOVE = 8.5
    private const val SPLIT_TARGET = 6.0

    // ------------------------------------------------------------ words and beats

    /** The words to caption and cut on: aligned script if there is one, otherwise the transcript. */
    fun timedWords(script: String, transcript: List<Word>?, duration: Double): Pair<List<Word>, Double> {
        val scriptWords = TextNorm.splitWords(script)
        if (scriptWords.isNotEmpty()) {
            val a = ScriptAligner.align(scriptWords, transcript ?: emptyList(), duration)
            return Pair(a.words, if (transcript.isNullOrEmpty()) 1.0 else a.matchedRatio)
        }
        return Pair(transcript ?: emptyList(), 1.0)
    }

    fun beats(words: List<Word>): List<Beat> {
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Beat>()
        var first = 0
        for (i in words.indices) {
            val w = words[i]
            val len = w.end - words[first].start
            val gapNext = if (i + 1 < words.size) words[i + 1].start - w.end else 99.0
            val last = i == words.size - 1
            val sentence = TextNorm.endsSentence(w.text)
            val clause = TextNorm.endsClause(w.text)
            val kind = when {
                sentence -> CutKind.SENTENCE
                clause -> CutKind.CLAUSE
                gapNext >= 0.35 -> CutKind.PAUSE
                else -> CutKind.ARBITRARY
            }
            val brk = last ||
                gapNext >= 0.7 ||
                (sentence && len >= 1.2) ||
                ((clause || gapNext >= 0.35) && len >= 2.6) ||
                len >= 7.0
            if (brk) {
                out.add(Beat(words[first].start, w.end, first, i, kind))
                first = i + 1
            }
        }
        // A tiny last beat is merged into the one before it.
        if (out.size >= 2 && out.last().end - out.last().start < 1.0) {
            val a = out[out.size - 2]
            val b = out.removeAt(out.size - 1)
            out[out.size - 1] = Beat(a.start, b.end, a.firstWord, b.lastWord, b.endKind)
        }
        return out
    }

    private class Cand(val time: Double, val kind: CutKind)

    /** Cut times between beats, placed in the silence before the next phrase. */
    private fun candidates(words: List<Word>, beats: List<Beat>): List<Cand> {
        val out = ArrayList<Cand>()
        for (i in 1 until beats.size) {
            val prevEnd = beats[i - 1].end
            val start = beats[i].start
            val gap = max(0.0, start - prevEnd)
            out.add(Cand(start - min(0.12, gap / 2.0), beats[i - 1].endKind))
        }
        return out
    }

    // ------------------------------------------------------------ cut points

    private fun fillRun(a: Double, b: Double, k: Int, cands: List<Cand>): List<Double> {
        val slice = (b - a) / (k + 1)
        val even = List(k) { a + slice * (it + 1) }
        if (slice < MIN_SHOT * 0.9) return even
        val out = ArrayList<Double>(k)
        var prev = a
        for (j in 1..k) {
            val ideal = a + slice * j
            val lo = prev + MIN_SHOT
            val hi = b - MIN_SHOT * (k - j + 1)
            if (lo > hi) return even
            var best: Cand? = null
            for (c in cands) {
                if (c.time < lo || c.time > hi) continue
                if (best == null || abs(c.time - ideal) < abs(best.time - ideal)) best = c
            }
            val pick = if (best != null && abs(best.time - ideal) <= slice * 0.45) best.time else ideal.coerceIn(lo, hi)
            out.add(pick)
            prev = pick
        }
        return out
    }

    private fun kindAt(t: Double, cands: List<Cand>): CutKind {
        var best: Cand? = null
        for (c in cands) if (abs(c.time - t) <= 0.3 && (best == null || abs(c.time - t) < abs(best.time - t))) best = c
        return best?.kind ?: CutKind.ARBITRARY
    }

    // ------------------------------------------------------------ main entry

    fun plan(project: Project, transcript: List<Word>?, duration: Double): EditPlan {
        val media = project.media
        val opts = project.options
        if (media.isEmpty() || duration <= 0.5) return EditPlan.EMPTY

        val (words, match) = timedWords(project.script, transcript, duration)
        val beats = beats(words)
        val cands = candidates(words, beats)

        // 1. one start time per image: pinned by the user or chosen automatically
        val n = media.size
        val starts = DoubleArray(n) { -1.0 }
        starts[0] = 0.0
        var lastPinned = 0.0
        for (i in 1 until n) {
            val p = media[i].startSec
            val room = duration - MIN_SHOT * (n - i)
            if (p != null && p >= lastPinned + MIN_SHOT && p <= room) {
                starts[i] = p
                lastPinned = p
            }
        }
        var i = 1
        while (i < n) {
            if (starts[i] >= 0.0) {
                i++
                continue
            }
            var e = i
            while (e + 1 < n && starts[e + 1] < 0.0) e++
            val a = starts[i - 1]
            val b = if (e + 1 < n) starts[e + 1] else duration
            val cuts = fillRun(a, b, e - i + 1, cands)
            for (x in i..e) starts[x] = cuts[x - i]
            i = e + 1
        }

        // 2. long images are split into several shots at natural cut points
        class Raw(val mediaIndex: Int, val start: Double, val end: Double, val sub: Int)
        val raws = ArrayList<Raw>()
        for (m in 0 until n) {
            val s = starts[m]
            val e = if (m + 1 < n) starts[m + 1] else duration
            val len = e - s
            if (len > SPLIT_ABOVE) {
                val parts = ceil(len / SPLIT_TARGET).toInt()
                val inner = cands.filter { it.time > s + 2.5 && it.time < e - 2.5 }
                val cuts = fillRun(s, e, parts - 1, inner)
                var from = s
                for ((idx, c) in cuts.withIndex()) {
                    raws.add(Raw(m, from, c, idx))
                    from = c
                }
                raws.add(Raw(m, from, e, cuts.size))
            } else {
                raws.add(Raw(m, s, e, 0))
            }
        }

        // 3. motion and transition for every shot
        val shots = ArrayList<Shot>()
        val sfx = ArrayList<SfxEvent>()
        val motionCycle = listOf(Motion.ZOOM_IN, Motion.PAN_RIGHT, Motion.ZOOM_OUT, Motion.PAN_LEFT, Motion.ZOOM_IN, Motion.PAN_LEFT, Motion.ZOOM_OUT, Motion.PAN_RIGHT)
        var prevTr: Transition? = null
        var trCount = 0
        for ((idx, r) in raws.withIndex()) {
            val item = media[r.mediaIndex]
            val sameImage = r.sub > 0
            val motion = when {
                !opts.motion -> Motion.STILL
                item.motion != null && !sameImage -> item.motion
                sameImage -> if (idx % 2 == 0) Motion.ZOOM_IN else Motion.PAN_RIGHT
                else -> motionCycle[idx % motionCycle.size]
            }
            var tr: Transition = when {
                idx == 0 -> Transition.CUT
                sameImage -> Transition.PUNCH
                item.transition != null -> item.transition
                else -> autoTransition(opts.transitions, kindAt(r.start, cands), prevTr, trCount)
            }
            if (idx > 0) {
                val prevLen = r.start - raws[idx - 1].start
                val curLen = r.end - r.start
                val d = tr.seconds
                if (d > 0.0 && (prevLen < d * 1.6 || curLen < d * 1.6)) tr = Transition.CUT
            }
            shots.add(Shot(idx, r.mediaIndex, item.id, r.start, r.end, motion, tr, sameImage, idx % 2 == 1))
            if (idx > 0) {
                sfxFor(tr, r.start, trCount)?.let { sfx.add(it) }
                trCount++
                prevTr = tr
            }
        }

        return EditPlan(duration, shots, captionChunks(words, opts.aspect), sfx, words, match)
    }

    private fun autoTransition(pack: TransitionPack, kind: CutKind, prev: Transition?, count: Int): Transition {
        val calm = listOf(Transition.FADE, Transition.SLIDE, Transition.FADE, Transition.ZOOM)
        val energetic = listOf(Transition.PUSH, Transition.WHIP, Transition.ZOOM, Transition.SLIDE, Transition.FLASH, Transition.WHIP)
        val list = when (pack) {
            TransitionPack.SMOOTH -> listOf(Transition.FADE, Transition.FADE, Transition.SLIDE, Transition.FADE, Transition.FADE, Transition.PUSH)
            TransitionPack.DYNAMIC -> listOf(Transition.WHIP, Transition.PUSH, Transition.ZOOM, Transition.SLIDE, Transition.FLASH, Transition.WHIP, Transition.PUSH, Transition.ZOOM)
            TransitionPack.AUTO -> if (kind == CutKind.SENTENCE || kind == CutKind.PAUSE) calm else energetic
        }
        var t = list[count % list.size]
        if (t == prev) t = list[(count + 1) % list.size]
        if (t == prev) t = Transition.FADE
        return t
    }

    private fun sfxFor(tr: Transition, cut: Double, count: Int): SfxEvent? {
        val v = count % 3
        return when (tr) {
            Transition.FADE -> SfxEvent(cut, SfxKind.SOFT_SWELL, 0.55f, v)
            Transition.SLIDE -> SfxEvent(cut, SfxKind.SWISH, 0.8f, v)
            Transition.PUSH -> SfxEvent(cut, SfxKind.WHOOSH, 0.9f, v)
            Transition.ZOOM -> SfxEvent(cut, SfxKind.DEEP_WHOOSH, 0.9f, v)
            Transition.WHIP -> SfxEvent(cut, SfxKind.WHIP, 0.85f, v)
            Transition.FLASH -> SfxEvent(cut, SfxKind.FLASH, 0.8f, v)
            Transition.PUNCH -> SfxEvent(cut, SfxKind.THUD, 0.7f, v)
            Transition.CUT -> SfxEvent(cut, SfxKind.TICK, 0.5f, v)
        }
    }

    // ------------------------------------------------------------ captions

    fun captionChunks(words: List<Word>, aspect: AspectRatio): List<CaptionChunk> {
        if (words.isEmpty()) return emptyList()
        val maxWords = if (aspect == AspectRatio.PORTRAIT) 4 else 7
        val maxChars = if (aspect == AspectRatio.PORTRAIT) 24 else 46
        val groups = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        var chars = 0
        for ((i, w) in words.withIndex()) {
            cur.add(w)
            chars += w.text.length + 1
            val gapNext = if (i + 1 < words.size) words[i + 1].start - w.end else 99.0
            val nextLen = if (i + 1 < words.size) words[i + 1].text.length + 1 else 0
            val brk = i == words.size - 1 ||
                cur.size >= maxWords ||
                chars + nextLen > maxChars ||
                TextNorm.endsSentence(w.text) ||
                (TextNorm.endsClause(w.text) && cur.size >= 2) ||
                gapNext >= 0.4
            if (brk) {
                groups.add(cur)
                cur = ArrayList()
                chars = 0
            }
        }
        val out = ArrayList<CaptionChunk>(groups.size)
        for ((gi, g) in groups.withIndex()) {
            val s = g.first().start
            val e = g.last().end
            val nextStart = if (gi + 1 < groups.size) groups[gi + 1].first().start else Double.MAX_VALUE
            val from = max(0.0, s - 0.06)
            val to = min(e + 0.30, nextStart)
            out.add(CaptionChunk(g, s, e, from, max(to, from + 0.2)))
        }
        return out
    }
}
