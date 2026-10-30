package it.saldochiaro.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class Movement(val date: String, val name: String, val cents: Long, val account: String)
data class Recurring(val name: String, val cents: Long, val previous: Long)
private val navy = Color(0xFF10243A)
private val mint = Color(0xFF078E79)
private fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(cents / 100.0)
private fun demo(): List<Movement> {
    val today = LocalDate.now()
    val names = listOf(Triple("Netflix", -1549L, -1399L), Triple("Spotify", -1099L, -1099L),
        Triple("Palestra", -3900L, -3900L), Triple("Internet fibra", -2790L, -2490L),
        Triple("Cloud backup", -299L, -299L))
    return buildList {
        for (ago in 0L..4L) {
            val month = YearMonth.from(today).minusMonths(ago)
            names.forEachIndexed { index, x ->
                add(Movement(month.atDay((5 + index * 3).coerceAtMost(month.lengthOfMonth())).toString(),
                    x.first, if (ago == 0L) x.second else x.third, "Carta demo"))
            }
            add(Movement(month.atDay(3).toString(), "Stipendio", 185000, "Conto demo"))
            add(Movement(month.atDay(14).toString(), "Supermercato", -6840, "Carta demo"))
            add(Movement(month.atDay(20).toString(), "Carburante", -4890, "Carta demo"))
        }
    }.sortedByDescending { it.date }
}
private fun recurring(list: List<Movement>): List<Recurring> =
    list.filter { it.cents < 0 }
        .groupBy { it.name.trim().lowercase(Locale.ITALY) }
        .mapNotNull { (key, values) ->
            val byMonth = values.mapNotNull {
                try { YearMonth.from(LocalDate.parse(it.date)) to it } catch (_: Exception) { null }
            }.toMap()
            val now = YearMonth.now()
            val a = byMonth[now] ?: return@mapNotNull null
            val b = byMonth[now.minusMonths(1)] ?: return@mapNotNull null
            if (!byMonth.containsKey(now.minusMonths(2))) return@mapNotNull null
            if (kotlin.math.abs(a.cents) > kotlin.math.abs(b.cents) * 1.5) return@mapNotNull null
            Recurring(values.first().name, -a.cents, -b.cents)
        }.sortedByDescending { it.cents }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16,36,58)
        setContent { SaldoChiaro() }
    }
}

@Composable
fun SaldoChiaro() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("saldochiaro", 0) }
    val items = remember {
        mutableStateListOf<Movement>().apply {
            val saved = prefs.getString("data", null)
            if (saved == null) addAll(demo())
            else try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    add(Movement(j.getString("date"), j.getString("name"), j.getLong("cents"), j.getString("account")))
                }
            } catch (_: Exception) {}
        }
    }
    fun save(values: List<Movement>) {
        items.clear(); items.addAll(values.sortedByDescending { it.date })
        val a = JSONArray()
        values.forEach { a.put(JSONObject().put("date",it.date).put("name",it.name).put("cents",it.cents).put("account",it.account)) }
        prefs.edit().putString("data", a.toString()).apply()
    }
    var page by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (raw.length > 2_000_000) throw IllegalArgumentException("File troppo grande")
            val lines = raw.lines().filter { it.isNotBlank() }
            val sep = if ((lines.firstOrNull()?.count { it == ';' } ?: 0) > (lines.firstOrNull()?.count { it == ',' } ?: 0)) ';' else ','
            val header = lines.first().split(sep).map { it.trim().lowercase(Locale.ITALY) }
            fun col(vararg names: String): Int = header.indexOfFirst { it in names }
            val d = col("data", "date", "data operazione")
            val n = col("descrizione", "causale", "description", "esercente")
            val a = col("importo", "amount")
            if (d < 0 || n < 0 || a < 0) throw IllegalArgumentException("Servono colonne Data, Descrizione, Importo")
            val imported = lines.drop(1).mapNotNull { line ->
                try {
                    val v = line.split(sep)
                    val date = v[d].trim().let {
                        if ('/' in it) LocalDate.parse(it, DateTimeFormatter.ofPattern("d/M/uuuu")) else LocalDate.parse(it)
                    }
                    val amount = v[a].trim().replace(".", "").replace(",", ".").toBigDecimal().movePointRight(2).longValueExact()
                    Movement(date.toString(), v[n].trim(), amount, "CSV importato")
                } catch (_: Exception) { null }
            }
            save((items.filter { it.account != "Carta demo" && it.account != "Conto demo" } + imported)
                .distinctBy { listOf(it.date,it.name,it.cents,it.account) })
            notice = "Importati ${imported.size} movimenti. I dati restano sul telefono."
            page = 2
        } catch (e: Exception) { notice = "CSV non importato: ${e.message}" }
    }
    val rec = recurring(items)
    MaterialTheme(colorScheme = lightColorScheme(primary = mint, background = Color(0xFFF5F7FA), surface = Color.White)) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Panoramica", "Ricorrenti", "Movimenti", "Impostazioni").forEachIndexed { idx, label ->
                    NavigationBarItem(selected = page == idx, onClick = { page = idx },
                        icon = { Text(listOf("⌂","↻","≡","⚙")[idx], fontSize = 21.sp) },
                        label = { Text(label, fontSize = 10.sp, maxLines = 1) })
                }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("SaldoChiaro", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = navy)
                Text("Prototipo offline · Italia / Europa", color = Color.Gray, fontSize = 13.sp)
                when (page) {
                    0 -> {
                        val now = YearMonth.now().toString()
                        val monthly = items.filter { it.date.startsWith(now) }
                        val out = -monthly.filter { it.cents < 0 }.sumOf { it.cents }
                        Panel {
                            Text("USCITE DEL MESE", fontSize = 12.sp, color = Color.Gray)
                            Text(money(out), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = navy)
                            Text("Entrate: ${money(monthly.filter { it.cents > 0 }.sumOf { it.cents })}")
                        }
                        Panel {
                            Text("Spese ricorrenti stimate", fontWeight = FontWeight.Bold)
                            Text(money(rec.sumOf { it.cents }), fontSize = 28.sp, color = mint)
                            Text("${rec.size} possibili abbonamenti")
                        }
                        val increases = rec.filter { it.cents > it.previous }
                        if (increases.isNotEmpty()) Panel {
                            Text("Da verificare: aumenti", fontWeight = FontWeight.Bold, color = Color(0xFFB04444))
                            increases.forEach { Text("${it.name}: ${money(it.previous)} → ${money(it.cents)}") }
                        }
                        Button(onClick = { picker.launch(arrayOf("text/*", "application/csv")) }) { Text("Importa movimenti CSV") }
                    }
                    1 -> {
                        Text("Abbonamenti rilevati", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Richiedono almeno 3 mesi consecutivi di addebiti simili.", fontSize = 12.sp, color = Color.Gray)
                        rec.forEach { s -> Panel {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text("${money(s.cents)} / mese", color = navy, fontSize = 22.sp)
                            if (s.cents > s.previous) Text("Aumento: +${money(s.cents - s.previous)}", color = Color.Red)
                        } }
                        if (rec.isEmpty()) Text("Nessuna ricorrenza mensile verificata.")
                    }
                    2 -> {
                        Text("Movimenti", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { picker.launch(arrayOf("text/*", "application/csv")) }) { Text("Importa CSV") }
                        items.forEach { m -> Panel {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            Text("${m.date} · ${m.account}", fontSize = 12.sp, color = Color.Gray)
                            Text(money(m.cents), fontWeight = FontWeight.Bold,
                                color = if (m.cents < 0) navy else mint)
                        } }
                    }
                    else -> {
                        Text("Impostazioni e privacy", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Panel {
                            Text("Nessun conto bancario collegato.", fontWeight = FontWeight.Bold)
                            Text("Questa versione non ha il permesso Internet. Nessun dato viene inviato a server.")
                            Text("L'importazione CSV è sperimentale: funziona con intestazioni semplici; controlla sempre i risultati.")
                        }
                        OutlinedButton(onClick = { save(demo()); notice = "Dati dimostrativi ripristinati" }) { Text("Ripristina demo") }
                        OutlinedButton(onClick = { save(emptyList()); notice = "Movimenti cancellati dal dispositivo" }) { Text("Cancella dati") }
                    }
                }
            }
            if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text("SaldoChiaro") },
                text = { Text(notice.orEmpty()) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } })
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
