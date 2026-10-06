package com.montageai.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Things the editor can ask for; the work itself runs in the app-level scope so leaving the editor does not stop it. */
class AutoActions(
    val download: () -> Unit,
    val cancelDownload: () -> Unit,
    val importModel: (Uri) -> Unit,
    val deleteModel: () -> Unit,
    val transcribe: () -> Unit,
    val cancelTranscribe: () -> Unit,
)

/** Model download/import and transcription state, shared by every screen. */
class AutoState(ctx: Context) {
    val supported: Boolean = WhisperLib.available

    var modelReady by mutableStateOf(ModelStore.isReady(ctx))
    var downloading by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var downloadDone by mutableLongStateOf(0L)
    var downloadTotal by mutableLongStateOf(-1L)

    var transcribing by mutableStateOf(false)
    var projectId by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)

    /** Bumped each time a transcription finishes; [doneProject] says for which project. */
    var doneTick by mutableIntStateOf(0)
    var doneProject: String? = null

    val words = mutableStateMapOf<String, List<Word>>()

    private var downloadJob: Job? = null
    private var transcribeJob: Job? = null

    val busy: Boolean get() = downloading || importing || transcribing

    fun download(ctx: Context, scope: CoroutineScope) {
        if (busy) return
        downloadJob = scope.launch {
            downloading = true
            error = null
            downloadDone = ModelStore.partialBytes(ctx)
            downloadTotal = -1L
            try {
                withContext(Dispatchers.IO) {
                    ModelStore.download(ctx) { done, total ->
                        downloadDone = done
                        downloadTotal = total
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                modelReady = ModelStore.isReady(ctx)
                downloading = false
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun importModel(ctx: Context, scope: CoroutineScope, uri: Uri) {
        if (busy) return
        scope.launch {
            importing = true
            error = null
            try {
                withContext(Dispatchers.IO) { ModelStore.importFrom(ctx, uri) }
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                modelReady = ModelStore.isReady(ctx)
                importing = false
            }
        }
    }

    fun deleteModel(ctx: Context) {
        if (busy) return
        ModelStore.delete(ctx)
        modelReady = false
    }

    /** Loads a saved transcript of the project's current audio, if there is one. */
    suspend fun loadTranscript(ctx: Context, project: Project) {
        val path = project.audioPath
        val loaded = if (path == null) null else withContext(Dispatchers.IO) { TranscriptStore.load(ctx, project.id, path) }
        if (loaded != null) words[project.id] = loaded else words.remove(project.id)
    }

    fun transcribe(ctx: Context, scope: CoroutineScope, project: Project) {
        if (busy) return
        val path = project.audioPath ?: return
        transcribeJob = scope.launch {
            transcribing = true
            projectId = project.id
            error = null
            progress = 0f
            status = "جاري التجهيز…"
            try {
                val result = withContext(Dispatchers.Default) {
                    Transcriber.run(ctx, path) { s, p ->
                        status = s
                        progress = p
                    }
                }
                if (result == null) {
                    status = "أُلغي التفريغ."
                } else if (result.isEmpty()) {
                    status = ""
                    error = "لم أسمع كلاماً في هذا التسجيل."
                } else {
                    withContext(Dispatchers.IO) { TranscriptStore.save(ctx, project.id, path, result) }
                    words[project.id] = result
                    status = "تم تفريغ ${result.size} كلمة."
                    doneProject = project.id
                    doneTick++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                error = "الذاكرة لا تكفي لهذا التسجيل. جرّب تسجيلاً أقصر."
                status = ""
            } catch (e: Throwable) {
                error = describe(e)
                status = ""
            } finally {
                transcribing = false
            }
        }
    }

    fun cancelTranscribe() {
        Transcriber.cancel()
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is SocketException ->
            "تعذّر الاتصال بالإنترنت. ما نُزِّل يُحفظ، وتكمل من حيث توقفت."
        is IOException -> e.message ?: "حدث خطأ في الملفات."
        else -> e.message ?: e.toString()
    }
}
