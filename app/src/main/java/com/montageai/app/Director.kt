package com.montageai.app

import kotlin.math.max
import kotlin.math.min

/**
 * Builds the edit plan from the timings the user set on each source card.
 * Nothing is invented: everything on screen comes from the user's own cards.
 * Missing start times are spread evenly; missing highlight/conclusion times get sensible defaults.
 */
object Director {

    fun manualPlan(sources: List<SourceCard>, duration: Double): Plan {
        if (sources.isEmpty()) return Plan(emptyList())
        val slice = duration / sources.size
        var prev = 0.0
        val raw = sources.mapIndexed { i, s ->
            val start = s.startSec ?: (if (i == 0) 0.0 else prev + slice)
            prev = start
            Scene(
                sourceId = s.id,
                start = start,
                end = 0.0,
                highlightPhrase = s.highlightPhrase,
                highlightAt = s.highlightSec ?: 0.0,
                conclusion = s.conclusion,
                conclusionAt = s.conclusionSec ?: 0.0,
            )
        }
        return sanitize(raw, sources, duration)
    }

    private fun sanitize(scenes: List<Scene>, sources: List<SourceCard>, duration: Double): Plan {
        val byId = sources.associateBy { it.id }
        val sorted = scenes.filter { byId.containsKey(it.sourceId) }.sortedBy { it.start }
        val kept = ArrayList<Scene>()
        val usedIds = HashSet<String>()
        for (s in sorted) {
            if (!usedIds.add(s.sourceId)) continue
            val start = s.start.coerceIn(0.0, max(0.0, duration - 2.0))
            if (kept.isNotEmpty() && start < kept.last().start + 2.5) continue
            kept.add(s.copy(start = start))
        }
        val result = ArrayList<Scene>()
        for ((i, s) in kept.withIndex()) {
            val src = byId.getValue(s.sourceId)
            val end = if (i + 1 < kept.size) kept[i + 1].start else duration
            val phrase = if (s.highlightPhrase.isNotBlank() && src.quote.contains(s.highlightPhrase)) {
                s.highlightPhrase
            } else {
                firstWords(src.quote, 5)
            }
            val hiAt = if (s.highlightAt in (s.start + 0.5)..(end - 0.5)) s.highlightAt else min(s.start + 1.2, end - 0.5)
            val concAt = if (s.conclusionAt in (hiAt + 0.5)..(end - 0.3)) s.conclusionAt else max(hiAt + 1.0, min(end - 2.0, hiAt + 4.0))
            result.add(
                s.copy(
                    end = end,
                    highlightPhrase = phrase,
                    highlightAt = hiAt,
                    conclusionAt = min(concAt, max(hiAt + 0.5, end - 0.3)),
                )
            )
        }
        return Plan(result)
    }

    /** Spreads the start times evenly over the audio (the editor's "auto-distribute" tool). */
    fun spreadEvenly(sources: List<SourceCard>, duration: Double): List<SourceCard> {
        if (sources.isEmpty()) return sources
        val slice = duration / sources.size
        return sources.mapIndexed { i, s -> s.copy(startSec = Math.round(i * slice * 10.0) / 10.0) }
    }

    private fun firstWords(text: String, n: Int): String {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val phrase = parts.take(n).joinToString(" ")
        return if (text.contains(phrase)) phrase else parts.first()
    }
}
