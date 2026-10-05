package com.montageai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SourceStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "sources.json")

    fun load(ctx: Context): List<SourceCard> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<SourceCard>) {
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
            )
        }
        file(ctx).writeText(arr.toString())
    }
}
