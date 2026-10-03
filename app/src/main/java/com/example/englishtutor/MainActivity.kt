package com.example.englishtutor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Si el modelo deja de funcionar, cambia este nombre por otro modelo Flash de Gemini.
const val MODEL = "gemini-flash-latest"
const val SYSTEM = "You are a friendly English tutor for a Spanish speaker. " +
    "Chat in simple English and keep replies short, ending with a question. " +
    "After your reply, add a new line starting with 'Corrección:' written in Spanish, " +
    "listing the mistakes in the user's last message and the corrected sentence. " +
    "If there are no mistakes, write 'Corrección: ¡Perfecto!'."

data class Msg(val fromUser: Boolean, val text: String)

fun askGemini(key: String, history: List<Msg>): String {
    val contents = JSONArray()
    history.forEach {
        contents.put(
            JSONObject().put("role", if (it.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
        )
    }
    val body = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
        .put("contents", contents)
    val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) throw Exception("Error ${c.responseCode}: " + txt.take(200))
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    } finally {
        c.disconnect()
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Chat(
                    savedKey = prefs.getString("key", "") ?: "",
                    saveKey = { prefs.edit().putString("key", it).apply() },
                    speak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, null) }
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun Chat(savedKey: String, saveKey: (String) -> Unit, speak: (String) -> Unit) {
    var key by remember { mutableStateOf(savedKey) }
    var editKey by remember { mutableStateOf(savedKey.isEmpty()) }
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || loading) return
        msgs.add(Msg(true, t)); input = ""; loading = true; error = ""
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { askGemini(key, msgs.toList()) }
                msgs.add(Msg(false, r))
            } catch (e: Exception) {
                error = e.message ?: "Error"
            }
            loading = false
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && t != null) { input = t; send() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        if (editKey) {
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { key = key.trim(); saveKey(key); editKey = false }) { Text("Guardar") }
        } else {
            TextButton(onClick = { editKey = true }) { Text("Cambiar API key") }
            if (msgs.isEmpty()) Text("Escribe algo en inglés para empezar. Toca una respuesta para escucharla.")
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp).then(
                            if (m.fromUser) Modifier
                            else Modifier.clickable { speak(m.text.substringBefore("Corrección:")) }
                        )
                    ) { Text(m.text, Modifier.padding(10.dp)) }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Write in English…") }
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in English")
                    }
                    try { mic.launch(i) } catch (e: Exception) {
                        error = "No hay reconocimiento de voz. Usa el micrófono del teclado."
                    }
                },
                enabled = key.isNotEmpty() && !loading,
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("🎤") }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { send() }, enabled = key.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("➤") }
        }
    }
}
