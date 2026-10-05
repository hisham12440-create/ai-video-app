package com.montageai.app

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var openAiKey: String
        get() = p.getString("openai", "") ?: ""
        set(v) {
            p.edit().putString("openai", v.trim()).apply()
        }

    var anthropicKey: String
        get() = p.getString("anthropic", "") ?: ""
        set(v) {
            p.edit().putString("anthropic", v.trim()).apply()
        }

    var anthropicModel: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) {
            p.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
