package com.montageai.app

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ClaudeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App()
                    }
                }
            }
        }
    }
}

/** State of the video that is being created (shared by the project screen). */
class GenState {
    var projectId by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var resultUri by mutableStateOf<Uri?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val projects = remember {
        mutableStateListOf<Project>().apply {
            val loaded = ProjectStore.load(ctx).sortedByDescending { it.updatedAt }
            if (loaded.isEmpty()) {
                val first = Project(id = UUID.randomUUID().toString(), name = "مشروع جديد")
                add(first)
                ProjectStore.save(ctx, listOf(first))
            } else {
                addAll(loaded)
            }
        }
    }
    var currentId by rememberSaveable { mutableStateOf(projects.first().id) }
    val current = projects.firstOrNull { it.id == currentId } ?: projects.first()

    val gen = remember { GenState() }
    val auto = remember { AutoState(ctx) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    fun persist() = ProjectStore.save(ctx, projects.toList())

    fun update(p: Project) {
        val i = projects.indexOfFirst { it.id == p.id }
        if (i >= 0) {
            projects[i] = p.copy(updatedAt = System.currentTimeMillis())
            persist()
        }
    }

    fun newProject() {
        val p = Project(id = UUID.randomUUID().toString(), name = "مشروع ${projects.size + 1}")
        projects.add(0, p)
        persist()
        currentId = p.id
        editorOpen = false
    }

    fun deleteProject(p: Project) {
        if (gen.busy && gen.projectId == p.id) return
        if (auto.transcribing && auto.projectId == p.id) return
        auto.words.remove(p.id)
        deleteProjectFiles(ctx, p)
        projects.removeAll { it.id == p.id }
        if (projects.isEmpty()) {
            projects.add(Project(id = UUID.randomUUID().toString(), name = "مشروع جديد"))
        }
        if (currentId == p.id) {
            currentId = projects.first().id
            editorOpen = false
        }
        persist()
    }

    fun generate(p: Project) {
        if (gen.busy) return
        scope.launch {
            gen.projectId = p.id
            gen.busy = true
            gen.error = null
            gen.resultUri = null
            gen.progress = 0f
            gen.status = "جاري البدء…"
            try {
                val out = Pipeline(ctx).run(p) { s, pr ->
                    gen.status = s
                    gen.progress = pr
                }
                gen.resultUri = withContext(Dispatchers.IO) { saveToGallery(ctx, out) }
                gen.status = "تم! الفيديو محفوظ في المعرض (Movies/MontageAI)."
            } catch (e: OutOfMemoryError) {
                gen.error = "الذاكرة لا تكفي. جرّب جودة أقل أو تسجيلاً أقصر."
                gen.status = ""
            } catch (e: Exception) {
                gen.error = e.message ?: e.toString()
                gen.status = ""
            }
            gen.busy = false
        }
    }

    val actions = AutoActions(
        download = { auto.download(ctx, scope) },
        cancelDownload = { auto.cancelDownload() },
        importModel = { uri -> auto.importModel(ctx, scope, uri) },
        deleteModel = { auto.deleteModel(ctx) },
        transcribe = { auto.transcribe(ctx, scope, current) },
        cancelTranscribe = { auto.cancelTranscribe() },
    )

    BackHandler(enabled = editorOpen) { editorOpen = false }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !editorOpen,
        drawerContent = {
            ProjectDrawer(
                projects = projects.sortedByDescending { it.updatedAt },
                currentId = current.id,
                onSelect = {
                    currentId = it.id
                    editorOpen = false
                    scope.launch { drawer.close() }
                },
                onNew = {
                    newProject()
                    scope.launch { drawer.close() }
                },
                onRename = { renaming = it },
                onDelete = { deleting = it },
            )
        },
    ) {
        if (editorOpen) {
            EditorScreen(
                project = current,
                auto = auto,
                actions = actions,
                onBack = { editorOpen = false },
                onSourcesChange = { update(current.copy(sources = it)) },
            )
        } else {
            ProjectScreen(
                project = current,
                gen = gen,
                onMenu = { scope.launch { drawer.open() } },
                onChange = { update(it) },
                onOpenEditor = { editorOpen = true },
                onCreate = { generate(current) },
                onRename = { renaming = current },
            )
        }
    }

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("اسم المشروع") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    update(p.copy(name = text.trim().ifBlank { p.name }))
                    renaming = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("إلغاء") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف المشروع؟") },
            text = { Text("سيُحذف «${p.name}» مع صوته وصور الأغلفة من هذا الجهاز. لا يمكن التراجع.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteProject(p)
                    deleting = null
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
fun ProjectDrawer(
    projects: List<Project>,
    currentId: String,
    onSelect: (Project) -> Unit,
    onNew: () -> Unit,
    onRename: (Project) -> Unit,
    onDelete: (Project) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 20.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✺", color = Clay.Terracotta, fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Text("مونتاج AI", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋  مشروع جديد") }
            Spacer(Modifier.height(18.dp))
            Text(
                "مشاريعك",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(projects, key = { it.id }) { p ->
                    NavigationDrawerItem(
                        label = { Text(p.name, maxLines = 1) },
                        selected = p.id == currentId,
                        onClick = { onSelect(p) },
                        badge = {
                            Row {
                                IconButton(onClick = { onRename(p) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "إعادة تسمية")
                                }
                                IconButton(onClick = { onDelete(p) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "حذف")
                                }
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }
            }
            Text(
                "يعمل بدون مفاتيح وبدون إنترنت",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
