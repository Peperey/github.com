package com.example.englishtutor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Green = Color(0xFF58CC02)
val GreenDark = Color(0xFF58A700)
val Blue = Color(0xFF1CB0F6)
val BlueDark = Color(0xFF1899D6)
val Red = Color(0xFFFF4B4B)
val RedDark = Color(0xFFEA2B2B)
val Gray = Color(0xFFE5E5E5)
val Ink = Color(0xFF4B4B4B)

data class Q(val prompt: String, val options: List<String>, val answer: Int, val say: String)
data class Lesson(val title: String, val emoji: String, val qs: List<Q>)

val LESSONS = listOf(
    Lesson("Saludos", "👋", listOf(
        Q("¿Cómo se dice «Buenos días»?", listOf("Good morning", "Good night", "Goodbye"), 0, "Good morning"),
        Q("¿Cómo se dice «Mucho gusto»?", listOf("See you", "Nice to meet you", "Thank you"), 1, "Nice to meet you"),
        Q("Hello! How ___ you?", listOf("is", "are", "am"), 1, "Hello! How are you?"),
        Q("¿Qué significa «Good night»?", listOf("Buenos días", "Buenas noches", "Buenas tardes"), 1, "Good night"),
        Q("¿Cómo se dice «Gracias»?", listOf("Please", "Sorry", "Thank you"), 2, "Thank you"),
        Q("My name ___ Ana.", listOf("are", "is", "am"), 1, "My name is Ana.")
    )),
    Lesson("Comida", "🍎", listOf(
        Q("¿Qué significa «water»?", listOf("leche", "agua", "jugo"), 1, "water"),
        Q("¿Cómo se dice «pan»?", listOf("bread", "rice", "cheese"), 0, "bread"),
        Q("I ___ an apple.", listOf("eat", "eats", "eating"), 0, "I eat an apple."),
        Q("¿Qué significa «chicken»?", listOf("pescado", "pollo", "huevo"), 1, "chicken"),
        Q("She ___ milk every day.", listOf("drink", "drinks", "drinking"), 1, "She drinks milk every day."),
        Q("¿Cómo se dice «Tengo hambre»?", listOf("I am hungry", "I am tired", "I am happy"), 0, "I am hungry")
    )),
    Lesson("Viajes", "✈️", listOf(
        Q("¿Cómo se dice «¿Dónde está el baño?»", listOf("Where is the bathroom?", "What time is it?", "How much is it?"), 0, "Where is the bathroom?"),
        Q("¿Qué significa «How much is it?»", listOf("¿Cuánto cuesta?", "¿Dónde está?", "¿Qué hora es?"), 0, "How much is it?"),
        Q("I want to ___ a ticket.", listOf("buy", "buys", "buying"), 0, "I want to buy a ticket."),
        Q("¿Qué significa «airport»?", listOf("estación", "aeropuerto", "hotel"), 1, "airport"),
        Q("The train ___ at eight o'clock.", listOf("leave", "leaves", "leaving"), 1, "The train leaves at eight o'clock."),
        Q("¿Cómo se dice «Necesito ayuda»?", listOf("I need help", "I need food", "I need money"), 0, "I need help")
    )),
    Lesson("Verbo to be", "📚", listOf(
        Q("I ___ happy.", listOf("am", "is", "are"), 0, "I am happy."),
        Q("They ___ friends.", listOf("is", "am", "are"), 2, "They are friends."),
        Q("He ___ a doctor.", listOf("are", "is", "am"), 1, "He is a doctor."),
        Q("We ___ at home.", listOf("am", "is", "are"), 2, "We are at home."),
        Q("You ___ my friend.", listOf("are", "is", "am"), 0, "You are my friend."),
        Q("It ___ cold today.", listOf("are", "am", "is"), 2, "It is cold today.")
    ))
)

@Composable
fun Btn(
    text: String, color: Color, shadow: Color, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit
) {
    val c = if (enabled) color else Gray
    val s = if (enabled) shadow else Color(0xFFCECECE)
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(s)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(c),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(), color = if (enabled) Color.White else Color(0xFFAFAFAF),
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
            )
        }
    }
}

@Composable
fun LessonScreen(lesson: Lesson, speak: (String) -> Unit, close: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(-1) }
    var checked by remember { mutableStateOf(false) }
    var good by remember { mutableIntStateOf(0) }

    if (i >= lesson.qs.size) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            Arrangement.Center, Alignment.CenterHorizontally
        ) {
            Text("🎉", fontSize = 64.sp)
            Text("¡Lección completada!", color = Green, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Acertaste $good de ${lesson.qs.size}", color = Ink, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            Btn("Volver", Green, GreenDark, Modifier.fillMaxWidth(), onClick = close)
        }
        return
    }

    val q = lesson.qs[i]
    val ok = sel == q.answer
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✕", color = Color.Gray, fontSize = 22.sp,
                modifier = Modifier.clickable { close() }.padding(end = 12.dp)
            )
            LinearProgressIndicator(
                progress = { i / lesson.qs.size.toFloat() },
                modifier = Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)),
                color = Green, trackColor = Gray
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                q.prompt, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            q.options.forEachIndexed { n, o ->
                val shape = RoundedCornerShape(16.dp)
                val (border, bg) = when {
                    checked && n == q.answer -> Green to Color(0xFFD7FFB8)
                    checked && n == sel -> Red to Color(0xFFFFDFE0)
                    n == sel -> Blue to Color(0xFFDDF4FF)
                    else -> Gray to Color.White
                }
                Text(
                    o, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clip(shape).background(bg)
                        .border(2.dp, border, shape)
                        .clickable(enabled = !checked) { sel = n }.padding(16.dp)
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(
                if (!checked) Color.White else if (ok) Color(0xFFD7FFB8) else Color(0xFFFFDFE0)
            ).padding(16.dp)
        ) {
            if (checked) {
                Text(
                    if (ok) "¡Correcto!" else "Respuesta correcta:",
                    color = if (ok) GreenDark else RedDark, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp
                )
                if (!ok) Text(q.options[q.answer], color = RedDark, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Btn("Continuar", if (ok) Green else Red, if (ok) GreenDark else RedDark, Modifier.fillMaxWidth()) {
                    i++; sel = -1; checked = false
                }
            } else {
                Btn("Comprobar", Green, GreenDark, Modifier.fillMaxWidth(), enabled = sel >= 0) {
                    checked = true
                    if (ok) good++
                    speak(q.say)
                }
            }
        }
    }
}
