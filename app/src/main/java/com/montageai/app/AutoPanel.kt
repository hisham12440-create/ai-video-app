package com.montageai.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / 1048576.0)

/** Speech model + automatic timing: download, transcribe. The transcript then feeds [Planner] automatically
 *  every time the plan is built — there is no separate "matching" step for the user to run. */
@Composable
fun AutoCard(
    auto: AutoState,
    project: Project,
    hasTranscript: Boolean,
    actions: AutoActions,
) {
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.importModel(uri)
    }
    val here = auto.projectId == project.id
    val hasAudio = project.audioPath != null

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("التفريغ والتوقيت التلقائي", style = MaterialTheme.typography.titleMedium)

            when {
                !auto.supported -> {
                    Text(
                        "التفريغ التلقائي غير مدعوم على معالج هذا الجهاز. بدون نص أو تفريغ، تُوزَّع الصور بالتساوي على مدة الصوت.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Clay.Warning,
                    )
                }

                auto.downloading -> {
                    val total = auto.downloadTotal
                    LinearProgressIndicator(
                        progress = { if (total > 0) (auto.downloadDone.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (total > 0) "جاري التنزيل… ${mb(auto.downloadDone)} / ${mb(total)} ميغابايت"
                        else "جاري التنزيل… ${mb(auto.downloadDone)} ميغابايت",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = actions.cancelDownload) { Text("إيقاف (يُحفظ ما نُزِّل)") }
                }

                auto.importing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("جاري نسخ الموديل…", style = MaterialTheme.typography.bodyMedium)
                }

                !auto.modelReady -> {
                    Text(
                        "لتفعيل التفريغ التلقائي نزّل موديل Whisper (small) مرة واحدة، حجمه نحو ${ModelStore.APPROX_MB} ميغابايت. " +
                            "بعدها يعمل التفريغ على جوالك بدون إنترنت وبدون مفاتيح.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    val partial = ModelStore.partialBytes(androidx.compose.ui.platform.LocalContext.current)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.download) {
                            Text(if (partial > 0L) "إكمال التنزيل (${mb(partial)} م.ب)" else "تنزيل الموديل")
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("استيراد ملف") }
                    }
                }

                auto.transcribing -> {
                    LinearProgressIndicator(progress = { auto.progress }, modifier = Modifier.fillMaxWidth())
                    Text(auto.status, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (here) "أبقِ التطبيق مفتوحاً، قد يستغرق عدة دقائق حسب طول التسجيل." else "يجري تفريغ مشروع آخر.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = actions.cancelTranscribe) { Text("إلغاء") }
                }

                !hasAudio -> {
                    Text(
                        "اختر التسجيل الصوتي من الشاشة الرئيسية ليعمل التفريغ.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        if (hasTranscript) {
                            "النص المفرّغ جاهز، ويُستخدم تلقائياً لمحاذاة القطع والترجمة مع ما قاله المتحدث."
                        } else {
                            "يفرّغ الصوت بتوقيت كل كلمة، ثم يُستخدم تلقائياً مع النص المكتوب (إن وُجد) لضبط القطع والترجمة."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    if (!hasTranscript) {
                        Button(onClick = actions.transcribe) { Text("تفريغ تلقائي") }
                    } else {
                        TextButton(onClick = actions.transcribe) { Text("إعادة التفريغ") }
                    }
                }
            }

            if (!auto.transcribing && auto.status.isNotBlank() && here) {
                Text(auto.status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            auto.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }

            if (auto.supported && auto.modelReady && !auto.busy) {
                TextButton(onClick = actions.deleteModel) {
                    Text("حذف الموديل لتوفير المساحة", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun indexAt(words: List<Word>, t: Double): Int {
    var lo = 0
    var hi = words.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (words[mid].start <= t) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/** The words around the playhead; tap a word to jump there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptCard(words: List<Word>, positionSec: Double, onSeek: (Double) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("النص المفرّغ (${words.size} كلمة)", style = MaterialTheme.typography.titleMedium)
                Text(if (open) "إخفاء" else "إظهار", color = cs.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (open && words.isNotEmpty()) {
                val cur = indexAt(words, positionSec)
                val from = (cur - 25).coerceAtLeast(0)
                val to = (cur + 60).coerceAtMost(words.size - 1)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (i in from..to) {
                        val w = words[i]
                        val on = i == cur
                        Text(
                            w.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (on) cs.onPrimary else cs.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) cs.primary else Color.Transparent)
                                .clickable { onSeek(w.start) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
