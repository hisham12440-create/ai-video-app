package com.montageai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Saves every project (audio path, sources, export options) in one JSON file on the phone. */
object ProjectStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "projects.json")

    fun load(ctx: Context): List<Project> {
        val f = file(ctx)
        if (f.exists()) {
            return try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { projectFromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        // Migrate the single source list of the first app version into a project.
        val legacy = File(ctx.filesDir, "sources.json")
        if (legacy.exists()) {
            try {
                val sources = sourcesFromJson(JSONArray(legacy.readText()))
                if (sources.isNotEmpty()) {
                    val p = Project(id = UUID.randomUUID().toString(), name = "مشروعي الأول", sources = sources)
                    save(ctx, listOf(p))
                    return listOf(p)
                }
            } catch (e: Exception) {
                // ignore a corrupt legacy file
            }
        }
        return emptyList()
    }

    fun save(ctx: Context, list: List<Project>) {
        val arr = JSONArray()
        for (p in list) arr.put(projectToJson(p))
        file(ctx).writeText(arr.toString())
    }

    private fun projectToJson(p: Project): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("audioPath", p.audioPath ?: JSONObject.NULL)
        .put("audioName", p.audioName)
        .put("audioMime", p.audioMime)
        .put("updatedAt", p.updatedAt)
        .put("sources", sourcesToJson(p.sources))
        .put(
            "options",
            JSONObject()
                .put("quality", p.options.quality.name)
                .put("highlight", p.options.highlight.name)
                .put("paper", p.options.paper.name)
                .put("zoom", p.options.zoom)
                .put("sfx", p.options.sfx)
                .put("sfxGain", p.options.sfxGain.toDouble())
        )

    private fun projectFromJson(o: JSONObject): Project {
        val opts = o.optJSONObject("options")
        val options = if (opts == null) ExportOptions() else ExportOptions(
            quality = enumOr(opts.optString("quality"), Quality.STANDARD),
            highlight = enumOr(opts.optString("highlight"), HighlightColor.YELLOW),
            paper = enumOr(opts.optString("paper"), PaperTheme.PARCHMENT),
            zoom = opts.optBoolean("zoom", true),
            sfx = opts.optBoolean("sfx", true),
            sfxGain = opts.optDouble("sfxGain", 0.28).toFloat(),
        )
        return Project(
            id = o.getString("id"),
            name = o.optString("name", "مشروع"),
            audioPath = if (o.isNull("audioPath")) null else o.optString("audioPath"),
            audioName = o.optString("audioName"),
            audioMime = o.optString("audioMime"),
            sources = sourcesFromJson(o.optJSONArray("sources") ?: JSONArray()),
            options = options,
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        try {
            enumValueOf<T>(name)
        } catch (e: Exception) {
            default
        }

    private fun sourcesToJson(list: List<SourceCard>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("author", s.author)
                    .put("title", s.title)
                    .put("publisher", s.publisher)
                    .put("location", s.location)
                    .put("quote", s.quote)
                    .put("translation", s.translation)
                    .put("imagePath", s.imagePath ?: JSONObject.NULL)
                    .put("startSec", s.startSec ?: JSONObject.NULL)
                    .put("highlightPhrase", s.highlightPhrase)
                    .put("highlightSec", s.highlightSec ?: JSONObject.NULL)
                    .put("conclusion", s.conclusion)
                    .put("conclusionSec", s.conclusionSec ?: JSONObject.NULL)
            )
        }
        return arr
    }

    private fun sourcesFromJson(arr: JSONArray): List<SourceCard> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SourceCard(
                id = o.getString("id"),
                author = o.optString("author"),
                title = o.optString("title"),
                publisher = o.optString("publisher"),
                location = o.optString("location"),
                quote = o.optString("quote"),
                translation = o.optString("translation"),
                imagePath = if (o.isNull("imagePath")) null else o.optString("imagePath"),
                startSec = if (o.isNull("startSec")) null else o.optDouble("startSec"),
                highlightPhrase = o.optString("highlightPhrase"),
                highlightSec = if (o.isNull("highlightSec")) null else o.optDouble("highlightSec"),
                conclusion = o.optString("conclusion"),
                conclusionSec = if (o.isNull("conclusionSec")) null else o.optDouble("conclusionSec"),
            )
        }
}
