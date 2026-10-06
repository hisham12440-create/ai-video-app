package com.montageai.app

import java.io.File

/** One spoken word with its start and end time (seconds) on the voice-over timeline. */
data class Word(val text: String, val start: Double, val end: Double)

/** Output frame shape. Both are 1080p (1080 on the short side). */
enum class AspectRatio(val label: String, val width: Int, val height: Int) {
    PORTRAIT("9:16 عمودي", 1080, 1920),
    LANDSCAPE("16:9 أفقي", 1920, 1080),
}

/** Camera movement applied to a still image while it is on screen. */
enum class Motion(val label: String) {
    ZOOM_IN("تكبير"),
    ZOOM_OUT("تصغير"),
    PAN_LEFT("تحريك لليسار"),
    PAN_RIGHT("تحريك لليمين"),
    PAN_UP("تحريك لأعلى"),
    PAN_DOWN("تحريك لأسفل"),
    STILL("ثابت"),
}

/** How one shot enters from the previous one. */
enum class Transition(val label: String, val seconds: Double) {
    CUT("قطع مباشر", 0.0),
    PUNCH("قطع بتكبير", 0.0),
    FADE("تلاشي", 0.55),
    SLIDE("انزلاق", 0.45),
    PUSH("دفع", 0.40),
    ZOOM("عبور بالتكبير", 0.50),
    WHIP("سحب سريع", 0.32),
    FLASH("وميض", 0.36),
}

/** How varied the automatic choice of transitions is. */
enum class TransitionPack(val label: String) {
    AUTO("تلقائي ذكي"),
    SMOOTH("ناعم"),
    DYNAMIC("ديناميكي"),
}

enum class CaptionStyle(val label: String) {
    KARAOKE("كلمة بكلمة"),
    SIMPLE("عادي"),
    OFF("بدون ترجمة"),
}

enum class HighlightColor(val label: String, val argb: Int) {
    YELLOW("أصفر", 0xFFFFE600.toInt()),
    GREEN("أخضر", 0xFF8CF29B.toInt()),
    PINK("وردي", 0xFFFF9AD5.toInt()),
    BLUE("أزرق", 0xFF80D8FF.toInt()),
}

/** One still image on the timeline. Everything except [path] is optional: null means "decide automatically". */
data class MediaItem(
    val id: String,
    val path: String,
    val startSec: Double? = null,
    val motion: Motion? = null,
    val transition: Transition? = null,
)

data class ExportOptions(
    val aspect: AspectRatio = AspectRatio.PORTRAIT,
    val captions: CaptionStyle = CaptionStyle.KARAOKE,
    val highlight: HighlightColor = HighlightColor.YELLOW,
    val transitions: TransitionPack = TransitionPack.AUTO,
    val motion: Boolean = true,
    val grade: Boolean = true,
    val sfx: Boolean = true,
    val sfxGain: Float = 0.30f,
    val fps: Int = 30,
)

data class PickedAudio(val file: File, val name: String, val mime: String)

data class Project(
    val id: String,
    val name: String,
    val audioPath: String? = null,
    val audioName: String = "",
    val audioMime: String = "",
    /** Optional narration text. Used for the captions and to cut at the right sentences. */
    val script: String = "",
    val media: List<MediaItem> = emptyList(),
    val options: ExportOptions = ExportOptions(),
    val updatedAt: Long = System.currentTimeMillis(),
)
