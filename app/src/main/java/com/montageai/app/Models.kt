package com.montageai.app

import java.io.File

data class SourceCard(
    val id: String,
    val author: String = "",
    val title: String = "",
    val publisher: String = "",
    val location: String = "",
    val quote: String = "",
    val translation: String = "",
    val imagePath: String? = null,
    // Manual timing, in seconds on the voice-over timeline
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

enum class HighlightColor(val label: String, val argb: Int) {
    YELLOW("أصفر", 0xFFFFE600.toInt()),
    GREEN("أخضر", 0xFF8CF29B.toInt()),
    PINK("وردي", 0xFFFF9AD5.toInt()),
    BLUE("أزرق", 0xFF80D8FF.toInt()),
}

enum class PaperTheme(val label: String, val bg: Int, val card: Int, val cover: Int, val ink: Int) {
    PARCHMENT("ورق قديم", 0xFFF4F1EA.toInt(), 0xFFFBF9F3.toInt(), 0xFFE9E3D3.toInt(), 0xFF1A1A1A.toInt()),
    KRAFT("ورق كرافت", 0xFFE6D5B8.toInt(), 0xFFF3E8D2.toInt(), 0xFFD9C6A3.toInt(), 0xFF2A1F14.toInt()),
    WHITE("أبيض نظيف", 0xFFFFFFFF.toInt(), 0xFFFAFAFA.toInt(), 0xFFEDEDED.toInt(), 0xFF111111.toInt()),
}

enum class Quality(val label: String, val width: Int, val height: Int, val fps: Int) {
    FAST("سريع · 540p", 540, 960, 24),
    STANDARD("قياسي · 720p", 720, 1280, 30),
    HIGH("عالٍ · 1080p", 1080, 1920, 30),
}

data class ExportOptions(
    val quality: Quality = Quality.STANDARD,
    val highlight: HighlightColor = HighlightColor.YELLOW,
    val paper: PaperTheme = PaperTheme.PARCHMENT,
    val zoom: Boolean = true,
    val sfx: Boolean = true,
    val sfxGain: Float = 0.28f,
)

data class Project(
    val id: String,
    val name: String,
    val audioPath: String? = null,
    val audioName: String = "",
    val audioMime: String = "",
    val sources: List<SourceCard> = emptyList(),
    val options: ExportOptions = ExportOptions(),
    val updatedAt: Long = System.currentTimeMillis(),
)
