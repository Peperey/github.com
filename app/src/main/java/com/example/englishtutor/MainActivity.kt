package com.example.englishtutor

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
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

fun geminiPost(key: String, body: JSONObject): String {
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
    return geminiPost(key, body)
}

fun generateLesson(key: String, topic: String, level: String): Lesson {
    val levelEn = when (level) {
        "Intermedio" -> "intermediate (B1-B2)"
        "Avanzado" -> "advanced (C1)"
        else -> "beginner (A1-A2)"
    }
    val prompt = """
Create 6 multiple-choice English exercises at $levelEn level for a Spanish speaker about the topic: $topic.
Mix two types:
1) translation: the prompt is in Spanish like ¿Cómo se dice «...»? with 3 English options, or an English word with 3 Spanish options;
2) fill in the blank: an English sentence with ___ and 3 English options.
Return ONLY a JSON object with this shape:
{"emoji":"one emoji","title":"short title in Spanish","qs":[{"prompt":"...","options":["a","b","c"],"answer":0,"say":"the English text to pronounce"}]}
"answer" is the 0-based index of the correct option. Vary the position of the correct option.
""".trimIndent()
    val body = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
    val raw = geminiPost(key, body).replace("```json", "").replace("```", "").trim()
    val o = JSONObject(raw)
    val qs = parseQs(o.getJSONArray("qs")).filter { it.options.size >= 2 && it.answer in it.options.indices }
    if (qs.size < 3) throw Exception("Lección inválida")
    return Lesson(o.optString("title", topic), o.optString("emoji", "✨"), qs, level)
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green)) {
                App(prefs, speak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, null) })
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun App(prefs: SharedPreferences, speak: (String) -> Unit) {
    var screen by remember { mutableStateOf("home") }
    var lessonIdx by remember { mutableIntStateOf(0) }
    var xp by remember { mutableIntStateOf(prefs.getInt("xp", 0)) }
    var streak by remember { mutableIntStateOf(prefs.getInt("streak", 0)) }
    var lastDay by remember { mutableLongStateOf(prefs.getLong("lastDay", -1L)) }
    val custom = remember {
        mutableStateListOf<Lesson>().apply {
            try {
                val arr = JSONArray(prefs.getString("lessons", "[]"))
                for (n in 0 until arr.length()) add(lessonFromJson(arr.getJSONObject(n)))
            } catch (e: Exception) {
            }
        }
    }
    fun persist() {
        prefs.edit().putString("lessons", JSONArray(custom.map { it.toJson() }).toString()).apply()
    }
    fun finishLesson(good: Int) {
        val today = java.time.LocalDate.now().toEpochDay()
        if (lastDay != today) {
            streak = if (lastDay == today - 1) streak + 1 else 1
            lastDay = today
        }
        xp += good * 10
        prefs.edit().putInt("xp", xp).putInt("streak", streak).putLong("lastDay", lastDay).apply()
    }
    val all = LESSONS + custom
    val key = prefs.getString("key", "") ?: ""
    val shownStreak = if (lastDay >= java.time.LocalDate.now().toEpochDay() - 1) streak else 0
    BackHandler(enabled = screen != "home") { screen = "home" }
    Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        when (screen) {
            "chat" -> Chat(key, { prefs.edit().putString("key", it).apply() }, speak) { screen = "home" }
            "create" -> CreateScreen(key, { l ->
                custom.add(l); persist()
                lessonIdx = LESSONS.size + custom.size - 1
                screen = "lesson"
            }) { screen = "home" }
            "lesson" -> LessonScreen(all[lessonIdx], speak, { finishLesson(it) }) { screen = "home" }
            else -> Home(
                all, LESSONS.size, shownStreak, xp,
                { screen = "chat" }, { screen = "create" },
                { n -> lessonIdx = n; screen = "lesson" },
                { n -> custom.removeAt(n - LESSONS.size); persist() }
            )
        }
    }
}

@Composable
fun BigCard(emoji: String, title: String, sub: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(2.dp, Gray, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 32.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(sub, color = Color(0xFF777777))
        }
        if (onDelete != null) {
            Text("🗑", fontSize = 22.sp, modifier = Modifier.clickable { onDelete() }.padding(8.dp))
        }
    }
}

@Composable
fun Home(
    all: List<Lesson>, builtIn: Int, streak: Int, xp: Int,
    openChat: () -> Unit, create: () -> Unit,
    openLesson: (Int) -> Unit, delete: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Pepe English", color = Green, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text("Practica inglés en lecciones cortas", color = Ink, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("🔥", "$streak", if (streak == 1) "día" else "días", Modifier.weight(1f))
            Stat("⭐", "$xp", "XP", Modifier.weight(1f))
        }
        BigCard("💬", "Chat con tutor IA", "Conversa y recibe correcciones", onClick = openChat)
        BigCard("✨", "Crear lección con IA", "Elige tema y nivel", onClick = create)
        Text("Lecciones", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        all.forEachIndexed { n, l ->
            BigCard(
                l.emoji, l.title,
                "${l.qs.size} ejercicios" + if (l.level.isNotEmpty()) " · ${l.level}" else "",
                onClick = { openLesson(n) },
                onDelete = if (n >= builtIn) ({ delete(n) }) else null
            )
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
