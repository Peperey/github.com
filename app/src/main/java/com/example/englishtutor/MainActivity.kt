package com.example.englishtutor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            MaterialTheme(colorScheme = lightColorScheme(primary = Green)) {
                App(
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
fun App(savedKey: String, saveKey: (String) -> Unit, speak: (String) -> Unit) {
    var screen by remember { mutableStateOf("home") }
    var lessonIdx by remember { mutableIntStateOf(0) }
    BackHandler(enabled = screen != "home") { screen = "home" }
    Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        when (screen) {
            "chat" -> Chat(savedKey, saveKey, speak) { screen = "home" }
            "lesson" -> LessonScreen(LESSONS[lessonIdx], speak) { screen = "home" }
            else -> Home({ screen = "chat" }) { lessonIdx = it; screen = "lesson" }
        }
    }
}

@Composable
fun BigCard(emoji: String, title: String, sub: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(2.dp, Gray, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 32.sp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(sub, color = Color(0xFF777777))
        }
    }
}

@Composable
fun Home(openChat: () -> Unit, openLesson: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Speak Up", color = Green, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text("Practica inglés en lecciones cortas", color = Ink, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        BigCard("💬", "Chat con tutor IA", "Conversa y recibe correcciones", openChat)
        Text("Lecciones", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        LESSONS.forEachIndexed { n, l ->
            BigCard(l.emoji, l.title, "${l.qs.size} ejercicios") { openLesson(n) }
        }
    }
}

@Composable
fun Chat(savedKey: String, saveKey: (String) -> Unit, speak: (String) -> Unit, back: () -> Unit) {
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

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←", fontSize = 26.sp, color = Ink,
                modifier = Modifier.clickable { back() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp)
            )
            Text(
                "Chat con tutor IA", color = Green, fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { editKey = !editKey }) { Text("API key") }
        }
        if (editKey) {
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Btn("Guardar", Green, GreenDark, Modifier.fillMaxWidth()) {
                key = key.trim(); saveKey(key); editKey = false
            }
        } else if (msgs.isEmpty()) {
            Text("Escribe o habla en inglés. Toca una respuesta para escucharla.", color = Ink)
        }
        LazyColumn(
            Modifier.weight(1f).padding(top = 8.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(msgs) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        m.text,
                        color = if (m.fromUser) Color.White else Ink,
                        modifier = Modifier.widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (m.fromUser) Green else Color(0xFFF0F0F0))
                            .then(
                                if (m.fromUser) Modifier
                                else Modifier.clickable { speak(m.text.substringBefore("Corrección:")) }
                            )
                            .padding(12.dp)
                    )
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
        if (error.isNotEmpty()) Text(error, color = RedDark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Write in English…") }
            )
            Spacer(Modifier.width(6.dp))
            Btn("🎤", Blue, BlueDark, Modifier.width(56.dp), enabled = key.isNotEmpty() && !loading) {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in English")
                }
                try { mic.launch(i) } catch (e: Exception) {
                    error = "No hay reconocimiento de voz. Usa el micrófono del teclado."
                }
            }
            Spacer(Modifier.width(6.dp))
            Btn("➤", Green, GreenDark, Modifier.width(56.dp), enabled = key.isNotEmpty()) { send() }
        }
    }
}
