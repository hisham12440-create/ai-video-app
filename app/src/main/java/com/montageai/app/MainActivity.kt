package com.montageai.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(ctx) }
    val sources = remember { mutableStateListOf<SourceCard>().apply { addAll(SourceStore.load(ctx)) } }

    var audio by remember { mutableStateOf<PickedAudio?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceCard?>(null) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    audio = withContext(Dispatchers.IO) { copyAudio(ctx, uri) }
                    error = null
                } catch (e: Exception) {
                    error = "تعذّر فتح الملف الصوتي: ${e.message}"
                }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("مونتاج AI", style = MaterialTheme.typography.headlineMedium)
            Text("ستايل المونتاج الهرمي التفاعلي (IPE)", style = MaterialTheme.typography.bodyMedium)

            OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("إعدادات المفاتيح")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1) الصوت", style = MaterialTheme.typography.titleMedium)
                    Text(audio?.name ?: "لم تختر ملفاً بعد")
                    Button(onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4")) }) {
                        Text("اختيار التسجيل الصوتي")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2) المصادر والصور", style = MaterialTheme.typography.titleMedium)
                    if (sources.isEmpty()) Text("أضف المصادر التي ذكرتها في كلامك.")
                    for (s in sources.toList()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.title.ifBlank { "(بدون عنوان)" }, style = MaterialTheme.typography.titleSmall)
                                Text(listOf(s.author, s.location).filter { it.isNotBlank() }.joinToString(" — "))
                                val missing = buildList {
                                    if (s.author.isBlank()) add("المؤلف")
                                    if (s.publisher.isBlank()) add("الناشر والطبعة")
                                    if (s.location.isBlank()) add("المجلد/الصفحة")
                                    if (s.quote.isBlank()) add("النص المقتبس")
                                }
                                if (missing.isNotEmpty()) {
                                    Text("ناقص: " + missing.joinToString("، "), color = Color(0xFFFFB74D))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editing = s; showSourceDialog = true }) { Text("تعديل") }
                                    TextButton(onClick = {
                                        sources.remove(s)
                                        SourceStore.save(ctx, sources.toList())
                                    }) { Text("حذف") }
                                }
                            }
                        }
                    }
                    Button(onClick = { editing = null; showSourceDialog = true }) { Text("إضافة مصدر") }
                }
            }

            Button(
                onClick = {
                    val a = audio ?: return@Button
                    scope.launch {
                        busy = true
                        error = null
                        resultUri = null
                        progress = 0f
                        try {
                            val out = Pipeline(ctx).run(a, settings, sources.toList()) { s, p ->
                                status = s
                                progress = p
                            }
                            resultUri = withContext(Dispatchers.IO) { saveToGallery(ctx, out) }
                            status = "تم! الفيديو محفوظ في المعرض (Movies/MontageAI)."
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                            status = ""
                        }
                        busy = false
                    }
                },
                enabled = audio != null && sources.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "جاري العمل…" else "إنشاء الفيديو")
            }

            if (busy || progress > 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            if (status.isNotBlank()) Text(status)
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            resultUri?.let { uri ->
                Button(
                    onClick = {
                        val i = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(i)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("فتح الفيديو") }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(settings) { showSettings = false }
    }
    if (showSourceDialog) {
        SourceDialog(
            initial = editing,
            onDismiss = { showSourceDialog = false },
            onSave = { card ->
                val idx = sources.indexOfFirst { it.id == card.id }
                if (idx >= 0) sources[idx] = card else sources.add(card)
                SourceStore.save(ctx, sources.toList())
                showSourceDialog = false
            },
        )
    }
}

@Composable
fun SettingsDialog(settings: Settings, onClose: () -> Unit) {
    var openAi by remember { mutableStateOf(settings.openAiKey) }
    var anthropic by remember { mutableStateOf(settings.anthropicKey) }
    var model by remember { mutableStateOf(settings.anthropicModel) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("إعدادات المفاتيح") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المفاتيح تُحفظ على جوالك فقط.")
                OutlinedTextField(
                    value = openAi, onValueChange = { openAi = it },
                    label = { Text("مفتاح OpenAI (تفريغ الصوت)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                )
                OutlinedTextField(
                    value = anthropic, onValueChange = { anthropic = it },
                    label = { Text("مفتاح Claude (المخرج) - اختياري") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("نموذج Claude") }, singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.openAiKey = openAi
                settings.anthropicKey = anthropic
                settings.anthropicModel = model
                onClose()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}

@Composable
fun SourceDialog(initial: SourceCard?, onDismiss: () -> Unit, onSave: (SourceCard) -> Unit) {
    val ctx = LocalContext.current
    var author by remember { mutableStateOf(initial?.author ?: "") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var publisher by remember { mutableStateOf(initial?.publisher ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var quote by remember { mutableStateOf(initial?.quote ?: "") }
    var translation by remember { mutableStateOf(initial?.translation ?: "") }
    var imagePath by remember { mutableStateOf(initial?.imagePath) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imagePath = copyImage(ctx, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "إضافة مصدر" else "تعديل المصدر") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("عنوان الكتاب أو الورقة") })
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("المؤلف / الباحث") })
                OutlinedTextField(value = publisher, onValueChange = { publisher = it }, label = { Text("الناشر والطبعة") })
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("المجلد / الفصل / الصفحة") })
                OutlinedTextField(
                    value = quote, onValueChange = { quote = it },
                    label = { Text("النص المقتبس (بلغته الأصلية)") }, minLines = 3,
                )
                OutlinedTextField(
                    value = translation, onValueChange = { translation = it },
                    label = { Text("التفريغ / الترجمة (اختياري)") }, minLines = 2,
                )
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                    Text(if (imagePath == null) "اختيار صورة الغلاف" else "تغيير صورة الغلاف")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    SourceCard(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        author = author.trim(), title = title.trim(), publisher = publisher.trim(),
                        location = location.trim(), quote = quote.trim(), translation = translation.trim(),
                        imagePath = imagePath,
                    )
                )
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
