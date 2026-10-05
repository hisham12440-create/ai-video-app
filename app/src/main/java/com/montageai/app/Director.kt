package com.montageai.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The "director": reads the transcript + the sources the user supplied and
 * produces an edit plan. It never invents sources: everything on screen comes
 * from the user's own source cards.
 */
object Director {

    private const val SYSTEM = """You are the editing director for an Arabic academic video in the "Interactive Pyramid Editing" style: parchment background, documentation cards for cited sources, and live yellow highlighting synchronized with the narrator's words.

You receive (1) the narrator's transcript with timestamps in seconds and (2) a list of source cards supplied by the user.

Produce an edit plan as JSON only. Rules:
- Use ONLY the supplied sources. Never invent sources, quotes, authors, pages or claims.
- Create one scene for each moment where the narrator discusses a supplied source. Scenes must be in ascending order of start time. Use each source at most once. Skip sources the narrator never discusses.
- "start": the time in seconds when the narrator begins discussing that source.
- "highlightPhrase": 2 to 8 consecutive words copied EXACTLY (same characters) from that source's quote - the part the narrator is talking about.
- "highlightAt": the time in seconds when the narrator says the idea that matches the phrase.
- "conclusion": one short Arabic sentence (at most 80 characters) restating what the narrator concluded, using only the narrator's own claims.
- "conclusionAt": the time in seconds when the narrator states that conclusion.

Output format, nothing else (no prose, no markdown):
{"scenes":[{"sourceId":"...","start":0.0,"highlightPhrase":"...","highlightAt":0.0,"conclusion":"...","conclusionAt":0.0}]}"""

    fun plan(settings: Settings, words: List<Word>, sources: List<SourceCard>, duration: Double): Plan {
        if (sources.isEmpty()) return Plan(emptyList())
        if (settings.anthropicKey.isBlank()) return fallbackPlan(sources, duration)

        val raw = callClaude(settings, words, sources, duration)
        val plan = sanitize(parse(raw), sources, duration)
        return if (plan.scenes.isEmpty()) fallbackPlan(sources, duration) else plan
    }

    private fun callClaude(settings: Settings, words: List<Word>, sources: List<SourceCard>, duration: Double): String {
        val transcript = StringBuilder()
        var i = 0
        while (i < words.size) {
            val chunk = words.subList(i, min(i + 6, words.size))
            transcript.append(String.format(Locale.US, "[%.1f] ", chunk.first().start))
            transcript.append(chunk.joinToString(" ") { it.text }).append('\n')
            i += 6
        }
        val src = JSONArray()
        for (s in sources) {
            src.put(
                JSONObject()
                    .put("id", s.id)
                    .put("author", s.author)
                    .put("title", s.title)
                    .put("location", s.location)
                    .put("quote", s.quote)
            )
        }
        val user = "TRANSCRIPT:\n$transcript\nSOURCES:\n$src\n\nTOTAL_DURATION_SECONDS: " +
            String.format(Locale.US, "%.1f", duration)

        val body = JSONObject()
            .put("model", settings.anthropicModel)
            .put("max_tokens", 4096)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))

        val resp = Http.postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to settings.anthropicKey, "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
        val content = JSONObject(resp).getJSONArray("content")
        for (k in 0 until content.length()) {
            val block = content.getJSONObject(k)
            if (block.optString("type") == "text") return block.getString("text")
        }
        throw IOException("رد المخرج لم يحتوِ نصاً.")
    }

    private fun parse(raw: String): List<Scene> {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("رد المخرج ليس بصيغة JSON صالحة.")
        val arr = JSONObject(raw.substring(a, b + 1)).optJSONArray("scenes") ?: return emptyList()
        val out = ArrayList<Scene>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Scene(
                    sourceId = o.optString("sourceId"),
                    start = o.optDouble("start", 0.0),
                    end = 0.0,
                    highlightPhrase = o.optString("highlightPhrase"),
                    highlightAt = o.optDouble("highlightAt", 0.0),
                    conclusion = o.optString("conclusion"),
                    conclusionAt = o.optDouble("conclusionAt", 0.0),
                )
            )
        }
        return out
    }

    /**
     * No keys needed: builds the plan from the timings the user typed on each source card.
     * Missing start times are spread evenly; missing highlight/conclusion times get sensible defaults.
     */
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

    /** No Claude key: spread the sources evenly over the audio, with no conclusions. */
    fun fallbackPlan(sources: List<SourceCard>, duration: Double): Plan {
        val n = sources.size
        val slice = duration / n
        val scenes = sources.mapIndexed { i, s ->
            val start = i * slice
            val end = if (i == n - 1) duration else (i + 1) * slice
            Scene(
                sourceId = s.id,
                start = start,
                end = end,
                highlightPhrase = firstWords(s.quote, 5),
                highlightAt = min(start + 1.2, end - 0.5),
                conclusion = "",
                conclusionAt = end,
            )
        }
        return Plan(scenes)
    }

    private fun firstWords(text: String, n: Int): String {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val phrase = parts.take(n).joinToString(" ")
        return if (text.contains(phrase)) phrase else parts.first()
    }
}
