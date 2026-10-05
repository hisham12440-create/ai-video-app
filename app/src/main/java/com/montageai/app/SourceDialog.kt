package com.montageai.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID

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
    var startText by remember { mutableStateOf(initial?.startSec?.let { formatTime(it) } ?: "") }
    var phrase by remember { mutableStateOf(initial?.highlightPhrase ?: "") }
    var hiText by remember { mutableStateOf(initial?.highlightSec?.let { formatTime(it) } ?: "") }
    var conclusion by remember { mutableStateOf(initial?.conclusion ?: "") }
    var concText by remember { mutableStateOf(initial?.conclusionSec?.let { formatTime(it) } ?: "") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imagePath = copyImage(ctx, uri)
    }

    val full = Modifier.fillMaxWidth()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "إضافة مصدر" else "تعديل المصدر") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("عنوان الكتاب أو الورقة") }, modifier = full)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("المؤلف / الباحث") }, modifier = full)
                OutlinedTextField(value = publisher, onValueChange = { publisher = it }, label = { Text("الناشر والطبعة") }, modifier = full)
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("المجلد / الفصل / الصفحة") }, modifier = full)
                OutlinedTextField(
                    value = quote, onValueChange = { quote = it },
                    label = { Text("النص المقتبس (بلغته الأصلية)") }, minLines = 3, modifier = full,
                )
                OutlinedTextField(
                    value = translation, onValueChange = { translation = it },
                    label = { Text("التفريغ / الترجمة (اختياري)") }, minLines = 2, modifier = full,
                )
                Text(
                    "التوقيت (اختياري، والأسهل ضبطه من المحرر)",
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = startText, onValueChange = { startText = it },
                    label = { Text("وقت بداية الكلام عن هذا المصدر (مثال 0:35)") },
                    singleLine = true, modifier = full,
                )
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it },
                    label = { Text("العبارة المراد تظليلها (منسوخة حرفياً من النص)") },
                    supportingText = {
                        if (phrase.isNotBlank() && !quote.contains(phrase.trim())) {
                            Text("هذه العبارة غير موجودة في النص المقتبس")
                        }
                    },
                    modifier = full,
                )
                OutlinedTextField(
                    value = hiText, onValueChange = { hiText = it },
                    label = { Text("وقت التظليل (اختياري)") }, singleLine = true, modifier = full,
                )
                OutlinedTextField(
                    value = conclusion, onValueChange = { conclusion = it },
                    label = { Text("الخلاصة بخط اليد (اختياري)") }, modifier = full,
                )
                OutlinedTextField(
                    value = concText, onValueChange = { concText = it },
                    label = { Text("وقت ظهور الخلاصة (اختياري)") }, singleLine = true, modifier = full,
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
                        startSec = parseTime(startText),
                        highlightPhrase = phrase.trim(),
                        highlightSec = parseTime(hiText),
                        conclusion = conclusion.trim(),
                        conclusionSec = parseTime(concText),
                    )
                )
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
