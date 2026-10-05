package com.montageai.app

import java.io.File

data class Word(val text: String, val start: Double, val end: Double)

data class SourceCard(
    val id: String,
    val author: String = "",
    val title: String = "",
    val publisher: String = "",
    val location: String = "",
    val quote: String = "",
    val translation: String = "",
    val imagePath: String? = null,
    // Manual timing (used when no transcription key is set, or when the user fills it in)
    val startSec: Double? = null,
    val highlightPhrase: String = "",
    val highlightSec: Double? = null,
    val conclusion: String = "",
    val conclusionSec: Double? = null,
)

data class Scene(
    val sourceId: String,
    val start: Double,
    val end: Double,
    val highlightPhrase: String,
    val highlightAt: Double,
    val conclusion: String,
    val conclusionAt: Double,
)

data class Plan(val scenes: List<Scene>)

data class PickedAudio(val file: File, val name: String, val mime: String)
