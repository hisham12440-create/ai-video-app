package com.montageai.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The project "conversation": greeting, audio, script, images and progress/result, with a composer at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    project: Project,
    gen: GenState,
    onMenu: () -> Unit,
    onChange: (Project) -> Unit,
    onOpenEditor: () -> Unit,
    onCreate: () -> Unit,
    onRename: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var audioError by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val a = withContext(Dispatchers.IO) { copyAudio(ctx, uri, project.id) }
                    onChange(project.copy(audioPath = a.file.path, audioName = a.name, audioMime = a.mime))
                    audioError = null
                } catch (e: Exception) {
                    audioError = "تعذّر فتح الملف الصوتي: ${e.message}"
                }
            }
        }
    }
    fun pickAudio() = audioPicker.launch(arrayOf("audio/*", "video/mp4"))

    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u -> copyImage(ctx, u)?.let { MediaItem(id = UUID.randomUUID().toString(), path = it) } }
                }
                if (added.isNotEmpty()) onChange(project.copy(media = project.media + added))
            }
        }
    }
    fun addImages() = imagesPicker.launch("image/*")

    val hasAudio = project.audioPath != null
    val here = gen.projectId == project.id
    val busyHere = gen.busy && here
    val canCreate = hasAudio && project.media.isNotEmpty() && !gen.busy

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRename() }.padding(horizontal = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "القائمة") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Composer(
                    hint = when {
                        busyHere -> gen.status
                        !hasAudio -> "ابدأ باختيار التسجيل الصوتي"
                        project.media.isEmpty() -> "أضف صورة واحدة على الأقل"
                        else -> "جاهز · ${project.media.size} صور · ${project.options.aspect.label}"
                    },
                    canCreate = canCreate,
                    onPickAudio = { pickAudio() },
                    onAddImages = { addImages() },
                    onOpenEditor = onOpenEditor,
                    onOptions = { showOptions = true },
                    onCreate = onCreate,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasAudio && project.media.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 56.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("✺", color = Clay.Terracotta, fontSize = 52.sp)
                            Text(
                                "أهلاً، لنصنع مونتاجاً",
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "أضف تسجيلك الصوتي وصورك، واكتب النص (اختياري)، وسأقص وأحرّك وأضيف الانتقالات والمؤثرات تلقائياً. بدون مفاتيح ولا إنترنت.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(4.dp))
                            AssistChip(onClick = { pickAudio() }, label = { Text("اختيار التسجيل الصوتي") })
                            AssistChip(onClick = { addImages() }, label = { Text("إضافة صور") })
                        }
                    }
                } else {
                    item {
                        Bubble(title = "التسجيل الصوتي") {
                            if (hasAudio) {
                                Text(project.audioName.ifBlank { "تسجيل صوتي" }, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { pickAudio() }) { Text("تغيير") }
                                    Button(onClick = onOpenEditor) { Text("فتح المحرر") }
                                }
                            } else {
                                Text("لم تختر ملفاً بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { pickAudio() }) { Text("اختيار التسجيل الصوتي") }
                            }
                            audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item {
                        Bubble(title = "نص التعليق الصوتي (اختياري)") {
                            Text(
                                "اكتب النص المنطوق ليُستخدم في الترجمة والمحاذاة الدقيقة للقطع. يمكن تركه فارغاً والاعتماد على التفريغ التلقائي من المحرر.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = project.script,
                                onValueChange = { onChange(project.copy(script = it)) },
                                minLines = 3,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "الصور (${project.media.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { addImages() }) { Text("＋ إضافة صور") }
                        }
                    }
                    if (project.media.isEmpty()) {
                        item {
                            Text("أضف الصور التي سيُبنى منها الفيديو.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexedMedia(project.media) { index, m ->
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
                                    onChange(project.copy(media = list))
                                }
                            },
                            onDelete = { onChange(project.copy(media = project.media.filter { it.id != m.id })) },
                        )
                    }
                }

                if (here && (gen.busy || gen.progress > 0f)) {
                    item {
                        Bubble(title = if (gen.busy) "جاري إنشاء الفيديو…" else "اكتمل") {
                            LinearProgressIndicator(progress = { gen.progress }, modifier = Modifier.fillMaxWidth())
                            if (gen.status.isNotBlank()) Text(gen.status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (here) {
                    gen.error?.let { msg ->
                        item {
                            Bubble(title = "تعذّر إنشاء الفيديو") {
                                Text(msg, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    gen.resultUri?.let { uri ->
                        item {
                            Bubble(title = "✓ الفيديو جاهز") {
                                Text("حُفظ في المعرض ضمن Movies/MontageAI.", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val i = Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        ctx.startActivity(i)
                                    }) { Text("فتح الفيديو") }
                                    OutlinedButton(onClick = onOpenEditor) { Text("تعديل التوقيت") }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showOptions) {
        OptionsSheet(
            options = project.options,
            onChange = { onChange(project.copy(options = it)) },
            onDismiss = { showOptions = false },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedMedia(
    list: List<MediaItem>,
    content: @Composable (Int, MediaItem) -> Unit,
) {
    items(list.size, key = { list[it].id }) { i -> content(i, list[i]) }
}

@Composable
private fun Bubble(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun MediaRow(
    index: Int,
    item: MediaItem,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val bmp = rememberThumb(item.path)
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("صورة ${index + 1}", style = MaterialTheme.typography.bodyLarge)
                val tags = listOfNotNull(
                    item.motion?.label,
                    item.transition?.label,
                    item.startSec?.let { "بداية ${formatTime(it)}" },
                )
                if (tags.isNotEmpty()) {
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (onEdit != null) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("تعديل") }
            }
            TextButton(enabled = canUp, onClick = { onMove(-1) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↑") }
            TextButton(enabled = canDown, onClick = { onMove(1) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↓") }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حذف", color = cs.error) }
        }
    }
}

@Composable
private fun Composer(
    hint: String,
    canCreate: Boolean,
    onPickAudio: () -> Unit,
    onAddImages: () -> Unit,
    onOpenEditor: () -> Unit,
    onOptions: () -> Unit,
    onCreate: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outline),
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 720.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, contentDescription = "إضافة") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("اختيار التسجيل الصوتي") }, onClick = {
                            menu = false
                            onPickAudio()
                        })
                        DropdownMenuItem(text = { Text("إضافة صور") }, onClick = {
                            menu = false
                            onAddImages()
                        })
                    }
                }
                AssistChip(onClick = onOpenEditor, label = { Text("المحرر") })
                AssistChip(onClick = onOptions, label = { Text("خيارات") })
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = if (canCreate) cs.primary else cs.outlineVariant,
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable(enabled = canCreate) { onCreate() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "↑",
                            color = if (canCreate) cs.onPrimary else cs.onSurfaceVariant,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
