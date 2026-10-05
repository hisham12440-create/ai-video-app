package com.montageai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** "Video options" bottom sheet: quality, highlighter color, paper, zoom and sound effects. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OptionsSheet(options: ExportOptions, onChange: (ExportOptions) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("خيارات الفيديو", style = MaterialTheme.typography.titleLarge)

            OptionSection("الجودة") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in Quality.values()) {
                        FilterChip(
                            selected = options.quality == q,
                            onClick = { onChange(options.copy(quality = q)) },
                            label = { Text(q.label) },
                        )
                    }
                }
                Text(
                    "الجودة الأعلى أبطأ وتحتاج ذاكرة أكبر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OptionSection("لون قلم التظليل") {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (c in HighlightColor.values()) {
                        val selected = options.highlight == c
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c.argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { onChange(options.copy(highlight = c)) },
                        )
                    }
                }
            }

            OptionSection("خلفية الورق") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in PaperTheme.values()) {
                        FilterChip(
                            selected = options.paper == p,
                            onClick = { onChange(options.copy(paper = p)) },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            ToggleRow("زوم سينمائي خفيف", options.zoom) { onChange(options.copy(zoom = it)) }
            ToggleRow("مؤثرات الورق والقلم", options.sfx) { onChange(options.copy(sfx = it)) }
            if (options.sfx) {
                OptionSection("مستوى المؤثرات") {
                    Slider(
                        value = options.sfxGain,
                        onValueChange = { onChange(options.copy(sfxGain = it)) },
                        valueRange = 0.05f..0.6f,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
