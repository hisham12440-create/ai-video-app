package com.montageai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Saves every project (audio, script, images, export options) in one JSON file on the phone. */
object ProjectStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "projects.json")

    fun load(ctx: Context): List<Project> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { projectFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
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
        .put("script", p.script)
        .put("updatedAt", p.updatedAt)
        .put("media", mediaToJson(p.media))
        .put(
            "options",
            JSONObject()
                .put("aspect", p.options.aspect.name)
                .put("captions", p.options.captions.name)
                .put("highlight", p.options.highlight.name)
                .put("transitions", p.options.transitions.name)
                .put("motion", p.options.motion)
                .put("grade", p.options.grade)
                .put("sfx", p.options.sfx)
                .put("sfxGain", p.options.sfxGain.toDouble())
                .put("fps", p.options.fps)
        )

    private fun projectFromJson(o: JSONObject): Project {
        val opts = o.optJSONObject("options")
        val options = if (opts == null) ExportOptions() else ExportOptions(
            aspect = enumOr(opts.optString("aspect"), AspectRatio.PORTRAIT),
            captions = enumOr(opts.optString("captions"), CaptionStyle.KARAOKE),
            highlight = enumOr(opts.optString("highlight"), HighlightColor.YELLOW),
            transitions = enumOr(opts.optString("transitions"), TransitionPack.AUTO),
            motion = opts.optBoolean("motion", true),
            grade = opts.optBoolean("grade", true),
            sfx = opts.optBoolean("sfx", true),
            sfxGain = opts.optDouble("sfxGain", 0.30).toFloat(),
            fps = opts.optInt("fps", 30),
        )
        return Project(
            id = o.getString("id"),
            name = o.optString("name", "مشروع"),
            audioPath = if (o.isNull("audioPath")) null else o.optString("audioPath"),
            audioName = o.optString("audioName"),
            audioMime = o.optString("audioMime"),
            script = o.optString("script"),
            media = mediaFromJson(o.optJSONArray("media") ?: JSONArray()),
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

    private fun mediaToJson(list: List<MediaItem>): JSONArray {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("path", m.path)
                    .put("startSec", m.startSec ?: JSONObject.NULL)
                    .put("motion", m.motion?.name ?: JSONObject.NULL)
                    .put("transition", m.transition?.name ?: JSONObject.NULL)
            )
        }
        return arr
    }

    private fun mediaFromJson(arr: JSONArray): List<MediaItem> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val path = o.optString("path")
            if (path.isNullOrBlank() || !File(path).exists()) return@mapNotNull null
            MediaItem(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                path = path,
                startSec = if (o.isNull("startSec")) null else o.optDouble("startSec"),
                motion = if (o.isNull("motion")) null else enumOrNull<Motion>(o.optString("motion")),
                transition = if (o.isNull("transition")) null else enumOrNull<Transition>(o.optString("transition")),
            )
        }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        try {
            enumValueOf<T>(name)
        } catch (e: Exception) {
            null
        }
}
