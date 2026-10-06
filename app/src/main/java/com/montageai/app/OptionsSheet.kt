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

/** "Video options" bottom sheet: aspect, captions, highlight color, transitions, motion, grade and SFX. */
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

            OptionSection("نسبة الإطار") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in AspectRatio.values()) {
                        FilterChip(
                            selected = options.aspect == a,
                            onClick = { onChange(options.copy(aspect = a)) },
                            label = { Text(a.label) },
                        )
                    }
                }
            }

            OptionSection("الترجمة") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in CaptionStyle.values()) {
                        FilterChip(
                            selected = options.captions == c,
                            onClick = { onChange(options.copy(captions = c)) },
                            label = { Text(c.label) },
                        )
                    }
                }
            }

            OptionSection("لون تظليل الترجمة") {
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

            OptionSection("أسلوب الانتقالات") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in TransitionPack.values()) {
                        FilterChip(
                            selected = options.transitions == p,
                            onClick = { onChange(options.copy(transitions = p)) },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            OptionSection("سرعة الإطارات (FPS)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in listOf(24, 30, 60)) {
                        FilterChip(
                            selected = options.fps == f,
                            onClick = { onChange(options.copy(fps = f)) },
                            label = { Text("$f") },
                        )
                    }
                }
            }

            ToggleRow("حركة كاميرا سينمائية (Ken Burns)", options.motion) { onChange(options.copy(motion = it)) }
            ToggleRow("درجة ألوان وتظليل خفيف", options.grade) { onChange(options.copy(grade = it)) }
            ToggleRow("مؤثرات صوتية عند الانتقالات", options.sfx) { onChange(options.copy(sfx = it)) }
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
