package com.example.emit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

/** Pure helpers (unit-tested). Dictation only ever fills the field; it never sends. */
object VoiceText {
    /** Append dictated [spoken] to existing [base] with a single space; capitalizes after sentence end / empty. */
    fun merge(base: String, spoken: String): String {
        val s = spoken.trim()
        if (s.isEmpty()) return base
        if (base.isBlank()) return s.replaceFirstChar { it.uppercase() }
        val b = base.trimEnd()
        val cap = b.endsWith('.') || b.endsWith('!') || b.endsWith('?')
        return b + " " + if (cap) s.replaceFirstChar { it.uppercase() } else s
    }
    fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "didn't catch that - tap mic and try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "voice needs a connection (or offline speech pack)"
        SpeechRecognizer.ERROR_AUDIO -> "microphone busy or unavailable"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "voice engine busy - try again"
        else -> "voice input error ($code)"
    }
}

class VoiceState internal constructor() {
    var listening by mutableStateOf(false); internal set
    var partial by mutableStateOf(""); internal set
    var level by mutableFloatStateOf(0f); internal set
    internal var toggle: () -> Unit = {}
    fun tap() = toggle()
}

/** Mic controller: asks RECORD_AUDIO only on first tap; SpeechRecognizer, falling back to RecognizerIntent. */
@Composable
fun rememberVoiceInput(onText: (String) -> Unit): VoiceState {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = remember { VoiceState() }
    val cb by rememberUpdatedState(onText)
    var rec by remember { mutableStateOf<SpeechRecognizer?>(null) }
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "speak - text goes into the box, not sent")
    }
    val fallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { cb(it) }
    }
    fun launchFallback() {
        try { fallback.launch(intent()) } catch (_: Exception) { toast("no speech recognizer on this phone - install/enable Google speech services") }
    }
    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { launchFallback(); return }
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { rec = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { st.listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { st.level = ((v + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { st.listening = false; st.partial = ""; if (e != SpeechRecognizer.ERROR_CLIENT) toast(VoiceText.errorMessage(e)) }
            override fun onResults(b: Bundle?) {
                st.listening = false; st.partial = ""
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { cb(it) }
            }
            override fun onPartialResults(b: Bundle?) { st.partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "" }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        st.listening = true
        try { r.startListening(intent()) } catch (_: Exception) { st.listening = false; launchFallback() }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else toast("mic permission denied - enable it in Settings > Apps > Emit to dictate")
    }
    st.toggle = {
        if (st.listening) { rec?.stopListening(); st.listening = false }
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { rec?.destroy(); rec = null } }
    return st
}
