package com.montageai.app

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The editing workspace: live preview of the plan Pipeline will render, waveform timeline with the
 * cut points, transport controls, automatic transcription, and the list of images (reorder, delete,
 * per-image motion/transition/start overrides).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    project: Project,
    auto: AutoState,
    actions: AutoActions,
    onBack: () -> Unit,
    onProjectChange: (Project) -> Unit,
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

    var editing by remember { mutableStateOf<MediaItem?>(null) }

    // ---- automatic transcription (feeds the planner; no separate "matching" step) ----
    val transcript = auto.words[project.id]
    LaunchedEffect(project.id, project.audioPath) { auto.loadTranscript(ctx, project) }

    // ---- the actual edit plan the exported video will follow ----
    var plan by remember { mutableStateOf(EditPlan.EMPTY) }
    LaunchedEffect(project.media, project.script, project.options, transcript, duration) {
        plan = if (project.media.isEmpty()) {
            EditPlan.EMPTY
        } else {
            withContext(Dispatchers.Default) { Planner.plan(project, transcript, duration) }
        }
    }

    // ---- live preview (renders the same scene the exported video will show, at a small size) ----
    val ratio = project.options.aspect.width.toFloat() / project.options.aspect.height.toFloat()
    val previewH = 420
    val previewW = max(1, (previewH * ratio).toInt())
    var renderer by remember { mutableStateOf<Renderer?>(null) }
    LaunchedEffect(project, plan) {
        renderer = if (plan.shots.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) { Renderer(ctx, project, plan, previewW, previewH) }
        }
    }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val bucket = player.positionMs / 80
    LaunchedEffect(renderer, bucket) {
        val r = renderer
        if (r != null) {
            val t = bucket * 0.08
            frame = withContext(Dispatchers.Default) {
                val bmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
                r.draw(AndroidCanvas(bmp), t)
                bmp.asImageBitmap()
            }
        }
    }

    val cutMarkers = plan.shots.map { it.start }

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
                    "لا يوجد تسجيل صوتي صالح. اختر الصوت من الشاشة الرئيسية لتتمكن من التشغيل.",
                    color = Clay.Warning,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Preview
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .height(380.dp)
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, cs.outline, RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val f = frame
                    if (f != null) {
                        Image(bitmap = f, contentDescription = "معاينة الإطار", modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            if (project.media.isEmpty()) "أضف صوراً لتظهر المعاينة" else "جاري تجهيز المعاينة…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
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
                        cuts = cutMarkers,
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
                            modifier = Modifier.size(54.dp).clip(CircleShape)
                                .pointerInput(Unit) { detectTapGestures(onTap = { player.toggle() }) },
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

            Text(
                if (plan.shots.isEmpty()) "لا توجد لقطات بعد." else "${plan.shots.size} لقطة · خط أحمر = نقطة قطع",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            AutoCard(auto = auto, project = project, hasTranscript = transcript != null, actions = actions)
            if (transcript != null && transcript.isNotEmpty()) {
                TranscriptCard(
                    words = transcript,
                    positionSec = player.positionSec,
                    onSeek = { sec -> player.seekTo((sec * 1000).toInt()) },
                )
            }

            Text("الصور (${project.media.size})", style = MaterialTheme.typography.titleMedium)
            if (project.media.isEmpty()) {
                Text("لا توجد صور بعد. أضفها من الشاشة الرئيسية.", color = cs.onSurfaceVariant)
            }
            project.media.forEachIndexed { index, m ->
                MediaRow(
                    index = index,
                    item = m,
                    canUp = index > 0,
                    canDown = index < project.media.size - 1,
                    onMove = { dir ->
                        val list = project.media.toMutableList()
                        val j = index + dir
                        if (j in list.indices) {
                            val tmp = list[index]
                            list[index] = list[j]
                            list[j] = tmp
                            onProjectChange(project.copy(media = list))
                        }
                    },
                    onDelete = { onProjectChange(project.copy(media = project.media.filter { it.id != m.id })) },
                    onEdit = { editing = m },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { current ->
        MediaOptionsDialog(
            item = current,
            playheadSec = player.positionSec,
            onDismiss = { editing = null },
            onSave = { updated ->
                onProjectChange(project.copy(media = project.media.map { if (it.id == updated.id) updated else it }))
                editing = null
            },
        )
    }
}

@Composable
private fun Timeline(
    wave: WaveData?,
    duration: Double,
    position: Double,
    cuts: List<Double>,
    onSeek: (Double) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val barColor = cs.onSurfaceVariant.copy(alpha = 0.4f)
    val playedColor = cs.primary
    val playheadColor = cs.onSurface
    val cutColor = Color(0xFFB3412E)
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
            drawLine(color = barColor, start = Offset(0f, cy), end = Offset(size.width, cy), strokeWidth = 3f)
        }
        for (cutT in cuts) {
            val x = (cutT / duration).toFloat().coerceIn(0f, 1f) * size.width
            drawLine(color = cutColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
        }
        val px = progress * size.width
        drawLine(color = playheadColor, start = Offset(px, 0f), end = Offset(px, size.height), strokeWidth = 3f)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MediaOptionsDialog(
    item: MediaItem,
    playheadSec: Double,
    onDismiss: () -> Unit,
    onSave: (MediaItem) -> Unit,
) {
    var motion by remember(item.id) { mutableStateOf(item.motion) }
    var transition by remember(item.id) { mutableStateOf(item.transition) }
    var startText by remember(item.id) { mutableStateOf(item.startSec?.let { formatTime(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("خيارات الصورة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("وقت البداية (اختياري، فارغ = تلقائي)", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        placeholder = { Text("مثال 0:35") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { startText = formatTime(playheadSec) }) { Text("علّم هنا") }
                }

                Text("الحركة", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = motion == null, onClick = { motion = null }, label = { Text("تلقائي") })
                    for (m in Motion.values()) {
                        FilterChip(selected = motion == m, onClick = { motion = m }, label = { Text(m.label) })
                    }
                }

                Text("الانتقال (عند الدخول)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = transition == null, onClick = { transition = null }, label = { Text("تلقائي") })
                    for (tr in Transition.values()) {
                        FilterChip(selected = transition == tr, onClick = { transition = tr }, label = { Text(tr.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(item.copy(startSec = parseTime(startText), motion = motion, transition = transition))
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
