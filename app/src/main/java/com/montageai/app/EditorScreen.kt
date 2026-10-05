package com.montageai.app

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val ColorStart = Clay.Terracotta
private val ColorHighlight = Color(0xFFE5B800)
private val ColorConclusion = Color(0xFF3E8E5A)

private class Marker(val sec: Double, val kind: Int, val selected: Boolean)

private fun markerColor(kind: Int): Color = when (kind) {
    0 -> ColorStart
    1 -> ColorHighlight
    else -> ColorConclusion
}

private fun round1(v: Double): Double = Math.round(v.coerceAtLeast(0.0) * 10.0) / 10.0

/**
 * The editing workspace: live preview of the frame at the playhead, waveform timeline with the
 * markers of every source, tap-to-mark timing, nudging, phrase picker, reorder, undo and auto tools.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    project: Project,
    onBack: () -> Unit,
    onSourcesChange: (List<SourceCard>) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val player = rememberAudioPlayer(project.audioPath)
    val wave by produceState<WaveData?>(initialValue = null, key1 = project.audioPath) {
        val p = project.audioPath
        value = if (p == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    Waveform.analyze(p)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val waveSnapshot = wave
    val duration = when {
        player.durationSec > 0.0 -> player.durationSec
        waveSnapshot != null && waveSnapshot.durationSec > 0.0 -> waveSnapshot.durationSec
        else -> 60.0
    }

    var selectedId by remember { mutableStateOf(project.sources.firstOrNull()?.id) }
    var editing by remember { mutableStateOf<SourceCard?>(null) }
    val undo = remember { mutableStateListOf<List<SourceCard>>() }

    fun commit(new: List<SourceCard>) {
        undo.add(project.sources)
        if (undo.size > 30) undo.removeAt(0)
        onSourcesChange(new)
    }

    fun setTime(id: String, kind: Int, sec: Double?) {
        val v = sec?.let { round1(it) }
        commit(
            project.sources.map { s ->
                if (s.id != id) s else when (kind) {
                    0 -> s.copy(startSec = v)
                    1 -> s.copy(highlightSec = v)
                    else -> s.copy(conclusionSec = v)
                }
            }
        )
    }

    // ---- live preview (renders the same scene the exported video will show) ----
    var renderer by remember { mutableStateOf<IpeRenderer?>(null) }
    LaunchedEffect(project.sources, project.options, duration) {
        renderer = if (project.sources.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val plan = Director.manualPlan(project.sources, duration)
                IpeRenderer(ctx, plan, project.sources.associateBy { it.id }, project.options, 360, 640)
            }
        }
    }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val bucket = player.positionMs / 80
    LaunchedEffect(renderer, bucket) {
        val r = renderer
        if (r != null) {
            val t = bucket * 0.08
            frame = withContext(Dispatchers.Default) {
                val bmp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                r.draw(AndroidCanvas(bmp), t)
                bmp.asImageBitmap()
            }
        }
    }

    val markers = buildList {
        for (s in project.sources) {
            val sel = s.id == selectedId
            s.startSec?.let { add(Marker(it, 0, sel)) }
            s.highlightSec?.let { add(Marker(it, 1, sel)) }
            s.conclusionSec?.let { add(Marker(it, 2, sel)) }
        }
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("المحرر", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    TextButton(
                        enabled = undo.isNotEmpty(),
                        onClick = {
                            val last = undo.removeAt(undo.size - 1)
                            onSourcesChange(last)
                        },
                    ) { Text("تراجع") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (player.failed) {
                Text(
                    "لا يوجد تسجيل صوتي صالح. اختر الصوت من الشاشة الرئيسية لتتمكن من التشغيل والتعليم.",
                    color = Clay.Warning,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Preview
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .height(400.dp)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, cs.outline, RoundedCornerShape(16.dp))
                        .background(cs.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val f = frame
                    if (f != null) {
                        Image(bitmap = f, contentDescription = "معاينة الإطار", modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            if (project.sources.isEmpty()) "أضف مصدراً لتظهر المعاينة" else "جاري تجهيز المعاينة…",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            // Timeline + transport (always left-to-right, like every media player)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Timeline(
                        wave = waveSnapshot,
                        duration = duration,
                        position = player.positionSec,
                        markers = markers,
                        onSeek = { sec -> player.seekTo((sec * 1000).toInt()) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(player.positionSec), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Text(formatTime(duration), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { player.seekBy(-5000) }) { Text("«5") }
                        TextButton(onClick = { player.seekBy(-100) }) { Text("-0.1") }
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = cs.primary,
                            modifier = Modifier.size(54.dp).clip(CircleShape).clickable { player.toggle() },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    if (player.playing) "❚❚" else "▶︎",
                                    color = cs.onPrimary,
                                    fontSize = 18.sp,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { player.seekBy(100) }) { Text("+0.1") }
                        TextButton(onClick = { player.seekBy(5000) }) { Text("5»") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(ColorStart, "بداية المصدر")
                LegendDot(ColorHighlight, "تظليل")
                LegendDot(ColorConclusion, "خلاصة")
            }

            // Tools
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدوات سريعة", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { commit(Director.spreadEvenly(project.sources, duration)) }) {
                            Text("توزيع متساوٍ")
                        }
                        OutlinedButton(onClick = {
                            commit(project.sources.sortedBy { it.startSec ?: Double.MAX_VALUE })
                        }) { Text("ترتيب حسب الوقت") }
                        OutlinedButton(onClick = {
                            commit(
                                project.sources.map {
                                    it.copy(startSec = null, highlightSec = null, conclusionSec = null)
                                }
                            )
                        }) { Text("مسح كل التوقيتات") }
                    }
                    Text(
                        "اختر مصدراً أدناه، شغّل الصوت، واضغط «علّم هنا» عند اللحظة المطلوبة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            Text("المصادر والتوقيت", style = MaterialTheme.typography.titleMedium)
            if (project.sources.isEmpty()) {
                Text("لا توجد مصادر بعد. أضفها من الشاشة الرئيسية.", color = cs.onSurfaceVariant)
            }
            project.sources.forEachIndexed { index, s ->
                SourceEditCard(
                    index = index,
                    s = s,
                    selected = s.id == selectedId,
                    playheadSec = player.positionSec,
                    canUp = index > 0,
                    canDown = index < project.sources.size - 1,
                    onSelect = { selectedId = s.id },
                    onSeekTo = { sec -> player.seekTo((sec * 1000).toInt()) },
                    onSet = { kind, sec -> setTime(s.id, kind, sec) },
                    onPhrase = { phrase ->
                        commit(project.sources.map { if (it.id == s.id) it.copy(highlightPhrase = phrase) else it })
                    },
                    onMove = { dir ->
                        val list = project.sources.toMutableList()
                        val j = index + dir
                        if (j in list.indices) {
                            val tmp = list[index]
                            list[index] = list[j]
                            list[j] = tmp
                            commit(list)
                        }
                    },
                    onEdit = { editing = s },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { current ->
        SourceDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { card ->
                commit(project.sources.map { if (it.id == card.id) card else it })
                editing = null
            },
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Timeline(
    wave: WaveData?,
    duration: Double,
    position: Double,
    markers: List<Marker>,
    onSeek: (Double) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val barColor = cs.onSurfaceVariant.copy(alpha = 0.4f)
    val playedColor = cs.primary
    val playheadColor = cs.onSurface
    val seek by rememberUpdatedState(onSeek)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant)
            .pointerInput(duration) {
                detectTapGestures(onTap = { o ->
                    seek((o.x / size.width).coerceIn(0f, 1f) * duration)
                })
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> seek((o.x / size.width).coerceIn(0f, 1f) * duration) },
                    onHorizontalDrag = { change, _ ->
                        seek((change.position.x / size.width).coerceIn(0f, 1f) * duration)
                    },
                )
            },
    ) {
        val cy = size.height / 2f
        val progress = (position / duration).toFloat().coerceIn(0f, 1f)
        val peaks = wave?.peaks
        if (peaks != null && peaks.isNotEmpty()) {
            val step = size.width / peaks.size
            for (i in peaks.indices) {
                val x = (i + 0.5f) * step
                val hh = max(4f, peaks[i] * size.height * 0.78f)
                drawLine(
                    color = if (x / size.width <= progress) playedColor else barColor,
                    start = Offset(x, cy - hh / 2f),
                    end = Offset(x, cy + hh / 2f),
                    strokeWidth = max(2f, step * 0.6f),
                    cap = StrokeCap.Round,
                )
            }
        } else {
            drawLine(
                color = barColor,
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = 3f,
            )
        }
        for (pass in 0..1) {
            for (m in markers) {
                if ((pass == 1) != m.selected) continue
                val x = (m.sec / duration).toFloat().coerceIn(0f, 1f) * size.width
                val c = markerColor(m.kind)
                drawLine(
                    color = c,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (m.selected) 5f else 2.5f,
                )
                drawCircle(color = c, radius = if (m.selected) 10f else 6f, center = Offset(x, 12f))
            }
        }
        val px = progress * size.width
        drawLine(
            color = playheadColor,
            start = Offset(px, 0f),
            end = Offset(px, size.height),
            strokeWidth = 3f,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceEditCard(
    index: Int,
    s: SourceCard,
    selected: Boolean,
    playheadSec: Double,
    canUp: Boolean,
    canDown: Boolean,
    onSelect: () -> Unit,
    onSeekTo: (Double) -> Unit,
    onSet: (kind: Int, sec: Double?) -> Unit,
    onPhrase: (String) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) cs.primaryContainer.copy(alpha = 0.45f) else cs.surface,
        ),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    s.title.ifBlank { "(بدون عنوان)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = canUp, onClick = { onMove(-1) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("↑")
                }
                TextButton(enabled = canDown, onClick = { onMove(1) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("↓")
                }
            }

            if (selected) {
                TimeRow(
                    label = "بداية الكلام عن المصدر",
                    color = ColorStart,
                    value = s.startSec,
                    onSeek = { s.startSec?.let(onSeekTo) },
                    onMark = { onSet(0, playheadSec) },
                    onNudge = { d -> s.startSec?.let { v -> onSet(0, v + d).also { onSeekTo(round1(v + d)) } } },
                    onClear = { onSet(0, null) },
                )
                TimeRow(
                    label = "لحظة التظليل",
                    color = ColorHighlight,
                    value = s.highlightSec,
                    onSeek = { s.highlightSec?.let(onSeekTo) },
                    onMark = { onSet(1, playheadSec) },
                    onNudge = { d -> s.highlightSec?.let { v -> onSet(1, v + d).also { onSeekTo(round1(v + d)) } } },
                    onClear = { onSet(1, null) },
                )
                TimeRow(
                    label = "ظهور الخلاصة",
                    color = ColorConclusion,
                    value = s.conclusionSec,
                    note = if (s.conclusion.isBlank()) "لا توجد خلاصة. أضفها من «تعديل البيانات»." else null,
                    onSeek = { s.conclusionSec?.let(onSeekTo) },
                    onMark = { onSet(2, playheadSec) },
                    onNudge = { d -> s.conclusionSec?.let { v -> onSet(2, v + d).also { onSeekTo(round1(v + d)) } } },
                    onClear = { onSet(2, null) },
                )

                if (s.quote.isNotBlank()) {
                    Text("العبارة المظللة", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "اضغط كلمة لتظليلها، ثم كلمة أخرى لتوسيع التظليل بينهما.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    PhrasePicker(quote = s.quote, current = s.highlightPhrase, onPick = onPhrase)
                    if (s.highlightPhrase.isNotBlank()) {
                        TextButton(onClick = { onPhrase("") }) { Text("إلغاء التظليل") }
                    }
                }
                OutlinedButton(onClick = onEdit) { Text("تعديل البيانات") }
            } else {
                val summary = listOfNotNull(
                    s.startSec?.let { "بداية " + formatTime(it) },
                    s.highlightSec?.let { "تظليل " + formatTime(it) },
                    s.conclusionSec?.let { "خلاصة " + formatTime(it) },
                ).joinToString("  ·  ")
                Text(
                    summary.ifBlank { "بلا توقيت · اضغط للتحديد" },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimeRow(
    label: String,
    color: Color,
    value: Double?,
    note: String? = null,
    onSeek: () -> Unit,
    onMark: () -> Unit,
    onNudge: (Double) -> Unit,
    onClear: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tight = PaddingValues(horizontal = 8.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                if (value != null) formatTime(value) else "تلقائي",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (value != null) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = value != null) { onSeek() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FilledTonalButton(onClick = onMark, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("علّم هنا") }
            if (value != null) {
                TextButton(onClick = { onNudge(-0.1) }, contentPadding = tight) { Text("‎-0.1") }
                TextButton(onClick = { onNudge(0.1) }, contentPadding = tight) { Text("‎+0.1") }
                TextButton(onClick = onClear, contentPadding = tight) { Text("مسح") }
            }
        }
    }
}

/** Tap a word to highlight it, tap another to stretch the highlight to cover everything in between. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhrasePicker(quote: String, current: String, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val words = remember(quote) { Regex("\\S+").findAll(quote).map { it.range }.toList() }
    var anchor by remember(quote) { mutableStateOf(-1) }
    val sel: IntRange? = remember(quote, current) {
        if (current.isBlank()) {
            null
        } else {
            val idx = quote.indexOf(current)
            if (idx < 0) {
                null
            } else {
                val a = words.indexOfFirst { it.first >= idx }
                val b = words.indexOfLast { it.last < idx + current.length }
                if (a in 0..b) a..b else null
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        words.forEachIndexed { i, r ->
            val on = sel != null && i in sel
            Text(
                quote.substring(r.first, r.last + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) Color.Black else cs.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) ColorHighlight.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable {
                        if (anchor < 0) {
                            anchor = i
                            onPick(quote.substring(r.first, r.last + 1))
                        } else {
                            val a = min(anchor, i)
                            val b = max(anchor, i)
                            onPick(quote.substring(words[a].first, words[b].last + 1))
                            anchor = -1
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
