package com.elektrokontrol.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DATE_PATTERN = "dd.MM.yyyy HH:mm"
data class Reading(val value: Double, val date: String, val usage: Double, val cost: Double, val tariff: Double)
private data class MonthStat(val key: String, val title: String, val usage: Double, val cost: Double)
private enum class AppTab { HOME, STATS, HISTORY, SETTINGS }

private val Teal = Color(0xFF087F75)
private val Ink = Color(0xFF172B2A)
private val Muted = Color(0xFF657876)
private val Canvas = Color(0xFFF3F7F6)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val prefs = getSharedPreferences("electro_control", MODE_PRIVATE)
        setContent {
            var tariff by remember { mutableStateOf(prefs.getFloat("tariff", 5.53f).toString()) }
            var readingText by remember { mutableStateOf("") }
            var dateText by remember { mutableStateOf(nowDate()) }
            var readings by remember { mutableStateOf(loadReadings(prefs, tariff.toDoubleOrNull() ?: 5.53)) }
            var message by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf<Reading?>(null) }
            var editValue by remember { mutableStateOf("") }
            var editDate by remember { mutableStateOf("") }
            var editTariff by remember { mutableStateOf("") }
            var selectedMonth by remember { mutableStateOf<String?>(null) }
            var reminders by remember { mutableStateOf(ReminderStore.load(prefs)) }
            var reminderDay by remember { mutableStateOf("1") }
            var reminderHour by remember { mutableStateOf("10") }
            var reminderMinute by remember { mutableStateOf("00") }
            var reminderLabel by remember { mutableStateOf("Пора внести показания электросчётчика") }
            var tab by remember { mutableStateOf(AppTab.HOME) }
            var showAddReading by remember { mutableStateOf(false) }

            val currentTariff = tariff.replace(',', '.').toDoubleOrNull() ?: 0.0
            val monthly = remember(readings) { buildCalendarMonths(readings).takeLast(12) }
            val latestFirst = readings.sortedByDescending { parseDate(it.date) ?: Date(0) }
            val last = latestFirst.firstOrNull()
            val selectedStat = monthly.firstOrNull { it.key == selectedMonth }
            val maxUsage = monthly.maxOfOrNull { it.usage } ?: 0.0
            val forecastMonths = monthly.takeLast(3).filter { it.usage > 0 }
            val forecastUsage = forecastMonths.map { it.usage }.average().takeIf { it.isFinite() } ?: 0.0
            val forecastCost = forecastMonths.map { it.cost }.average().takeIf { it.isFinite() } ?: 0.0
            val comparison = if (monthly.size >= 2) monthly[monthly.lastIndex] to monthly[monthly.lastIndex - 1] else null
            val thisMonth = monthly.lastOrNull()

            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Color(0xFF48A99B), background = Canvas, surface = Color.White, onSurface = Ink)) {
                Scaffold(
                    containerColor = Canvas,
                    topBar = { Surface(color = Color.White, shadowElevation = 1.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp)) {
                        Text("ЭлектроКонтроль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Ink)
                        Text(when(tab) { AppTab.HOME -> "Ваш расход электроэнергии"; AppTab.STATS -> "Аналитика и прогноз"; AppTab.HISTORY -> "Все показания счётчика"; AppTab.SETTINGS -> "Тарифы и напоминания" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    } } },
                    bottomBar = { NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(selected = tab == AppTab.HOME, onClick = { tab = AppTab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Главная") })
                        NavigationBarItem(selected = tab == AppTab.STATS, onClick = { tab = AppTab.STATS }, icon = { Icon(Icons.Default.ShowChart, null) }, label = { Text("Статистика") })
                        NavigationBarItem(selected = tab == AppTab.HISTORY, onClick = { tab = AppTab.HISTORY }, icon = { Icon(Icons.Default.History, null) }, label = { Text("История") })
                        NavigationBarItem(selected = tab == AppTab.SETTINGS, onClick = { tab = AppTab.SETTINGS }, icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("Настройки") })
                    } }
                ) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (tab) {
                            AppTab.HOME -> {
                                item { HeroCard(thisMonth, last) }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricCard("Расход за месяц", thisMonth?.let { "${fmt(it.usage)} кВт·ч" } ?: "—", Icons.Default.ShowChart, Modifier.weight(1f))
                                    MetricCard("Стоимость", thisMonth?.let { "${fmt(it.cost)} ₽" } ?: "—", Icons.Default.Add, Modifier.weight(1f))
                                } }
                                item { SectionTitle("Последнее показание") }
                                item { SurfaceCard {
                                    if (last == null) Text("Пока нет показаний. Добавьте первое значение счётчика.", color = Muted)
                                    else { Text("${fmt(last.value)} кВт·ч", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(last.date, color = Muted); Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Расход за интервал", color = Muted); Text("${fmt(last.usage)} кВт·ч", fontWeight = FontWeight.SemiBold) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Стоимость", color = Muted); Text("${fmt(last.cost)} ₽", fontWeight = FontWeight.SemiBold) } }
                                } }
                                item { Button(onClick = { showAddReading = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить показание", fontWeight = FontWeight.Bold) } }
                                item { SectionTitle("Динамика потребления") }
                                item { if (monthly.isEmpty()) SurfaceCard { Text("График появится после двух показаний.", color = Muted) } else MonthlyChart(monthly, maxUsage, selectedMonth) { selectedMonth = it } }
                                selectedStat?.let { item { SurfaceCard { Text(it.title, fontWeight = FontWeight.Bold); Text("${fmt(it.usage)} кВт·ч · ${fmt(it.cost)} ₽", color = Muted) } } }
                                item { Text("Тариф для новых показаний: ${fmt(currentTariff)} ₽/кВт·ч", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            }
                            AppTab.STATS -> {
                                item { SectionTitle("Расход по календарным месяцам") }
                                item { if (monthly.isEmpty()) SurfaceCard { Text("Статистика появится после двух показаний.", color = Muted) } else MonthlyChart(monthly, maxUsage, selectedMonth) { selectedMonth = it } }
                                selectedStat?.let { item { SurfaceCard { Text(it.title, fontWeight = FontWeight.Bold); Text("Расход: ${fmt(it.usage)} кВт·ч"); Text("Стоимость: ${fmt(it.cost)} ₽") } } }
                                item { SectionTitle("Сравнение месяцев") }
                                item { SurfaceCard { if (comparison == null) Text("Нужны данные минимум за два месяца.", color = Muted) else {
                                    val (now, before) = comparison; val diff = now.usage - before.usage; val pct = if (before.usage > 0) diff / before.usage * 100 else null
                                    Text("${now.title} / ${before.title}", fontWeight = FontWeight.SemiBold); Text("${if (diff > 0) "+" else ""}${fmt(diff)} кВт·ч", style = MaterialTheme.typography.headlineSmall, color = if (diff > 0) Color(0xFFB45A43) else Teal, fontWeight = FontWeight.Bold); Text(pct?.let { (if (it > 0) "Рост" else "Снижение") + " на ${fmt(kotlin.math.abs(it))}%" } ?: "Процентное сравнение недоступно.", color = Muted)
                                } } }
                                item { SectionTitle("Прогноз на следующий месяц") }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Средний расход", "${fmt(forecastUsage)} кВт·ч", Icons.Default.ShowChart, Modifier.weight(1f)); MetricCard("Ориентир по оплате", "${fmt(forecastCost)} ₽", Icons.Default.Add, Modifier.weight(1f)) } }
                                item { Text("Прогноз основан на среднем значении последних трёх месяцев. Расход по месяцам распределяется пропорционально времени между показаниями.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            }
                            AppTab.HISTORY -> {
                                item { SectionTitle("История показаний · ${latestFirst.size}") }
                                if (latestFirst.isEmpty()) item { SurfaceCard { Text("История пока пуста.", color = Muted) } }
                                items(latestFirst, key = { it.date }) { r -> SurfaceCard {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(r.date, color = Muted, style = MaterialTheme.typography.labelMedium); Text("${fmt(r.value)} кВт·ч", fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.height(7.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Расход", color = Muted); Text("${fmt(r.usage)} кВт·ч") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Стоимость", color = Muted); Text("${fmt(r.cost)} ₽") }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Тариф записи", color = Muted); Text("${fmt(r.tariff)} ₽/кВт·ч") }
                                    TextButton(onClick = { editing = r; editValue = fmtInput(r.value); editDate = r.date; editTariff = fmtInput(r.tariff) }) { Text("Изменить показание") }
                                } }
                            }
                            AppTab.SETTINGS -> {
                                item { SectionTitle("Текущий тариф") }
                                item { SurfaceCard {
                                    OutlinedTextField(value = tariff, onValueChange = { tariff = it; it.replace(',', '.').toFloatOrNull()?.let { v -> prefs.edit().putFloat("tariff", v).apply() } }, label = { Text("Тариф, ₽ за кВт·ч") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                                    Text("Новый тариф применяется только к новым показаниям. Исторические тарифы не меняются.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                } }
                                item { SectionTitle("Ежемесячные напоминания") }
                                item { SurfaceCard {
                                    OutlinedTextField(value = reminderLabel, onValueChange = { reminderLabel = it }, label = { Text("Текст уведомления") }, modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = reminderDay, onValueChange = { reminderDay = it.filter(Char::isDigit).take(2) }, label = { Text("День") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                        OutlinedTextField(value = reminderHour, onValueChange = { reminderHour = it.filter(Char::isDigit).take(2) }, label = { Text("Час") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                        OutlinedTextField(value = reminderMinute, onValueChange = { reminderMinute = it.filter(Char::isDigit).take(2) }, label = { Text("Мин.") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                    }
                                    Button(onClick = {
                                        val d = reminderDay.toIntOrNull(); val h = reminderHour.toIntOrNull(); val m = reminderMinute.toIntOrNull()
                                        if (d == null || d !in 1..31 || h == null || h !in 0..23 || m == null || m !in 0..59) message = "Проверь день (1–31) и время."
                                        else { val id = (reminders.maxOfOrNull { it.id } ?: 0) + 1; val r = MonthlyReminder(id, d, h, m, reminderLabel); reminders = reminders + r; ReminderStore.save(prefs, reminders); ReminderScheduler.schedule(this@MainActivity, r); message = "Напоминание добавлено." }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Добавить напоминание") }
                                } }
                                if (message.isNotBlank()) item { Text(message, color = Teal) }
                                items(reminders, key = { it.id }) { r -> SurfaceCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) { Text("Каждый месяц · ${r.day}-го", fontWeight = FontWeight.Bold); Text("%02d:%02d".format(r.hour, r.minute), color = Teal); Text(r.label, color = Muted, style = MaterialTheme.typography.bodySmall) }
                                        TextButton(onClick = { ReminderScheduler.cancel(this@MainActivity, r); reminders = reminders.filterNot { it.id == r.id }; ReminderStore.save(prefs, reminders) }) { Text("Удалить") }
                                    }
                                } }
                            }
                        }
                    }
                }
            }
            if (showAddReading) AlertDialog(onDismissRequest = { showAddReading = false }, title = { Text("Новое показание") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(readingText, { readingText = it }, label = { Text("Показание, кВт·ч") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(dateText, { dateText = it }, label = { Text("Дата и время") }, singleLine = true)
                    Text("Формат даты: дд.мм.гггг чч:мм", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = {
                val value = readingText.replace(',', '.').toDoubleOrNull(); val date = parseDate(dateText)
                when { value == null || value < 0 || currentTariff < 0 -> message = "Проверь показание и тариф."; date == null -> message = "Неверная дата."; readings.any { it.date == dateText.trim() } -> message = "Запись с такой датой уже существует."; else -> {
                    val updated = recalculate(readings + Reading(value, dateText.trim(), 0.0, 0.0, currentTariff))
                    if (updated == null) message = "Показания должны возрастать с течением времени." else { readings = updated; saveReadings(prefs, readings); readingText = ""; dateText = nowDate(); message = "Показание сохранено."; showAddReading = false }
                }
            }
            }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { showAddReading = false; message = "" }) { Text("Отмена") } })
            editing?.let { old -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Изменить показание") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(editValue, { editValue = it }, label = { Text("Показание, кВт·ч") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(editDate, { editDate = it }, label = { Text("Дата (дд.мм.гггг чч:мм)") })
                    OutlinedTextField(editTariff, { editTariff = it }, label = { Text("Тариф этой записи, ₽/кВт·ч") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("Стоимость интервала рассчитывается по тарифу этой записи.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = {
                val v = editValue.replace(',', '.').toDoubleOrNull(); val t = editTariff.replace(',', '.').toDoubleOrNull()
                if (v == null || v < 0 || t == null || t < 0 || parseDate(editDate) == null) message = "Проверь показание, тариф и дату."
                else if (readings.any { it.date == editDate.trim() && it.date != old.date }) message = "Такая дата уже занята."
                else { val revised = readings.map { if (it.date == old.date) it.copy(value = v, date = editDate.trim(), tariff = t) else it }; val result = recalculate(revised); if (result == null) message = "Показания должны возрастать по датам." else { readings = result; saveReadings(prefs, readings); editing = null; message = "Запись исправлена." } }
            }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } }) }
        }
    }
}

@Composable private fun HeroCard(month: MonthStat?, last: Reading?) {
    Surface(color = Teal, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("РАСХОД ЗА ТЕКУЩИЙ МЕСЯЦ", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(month?.let { "${fmt(it.usage)} кВт·ч" } ?: "Нет данных", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Text(month?.let { "Ориентировочная стоимость ${fmt(it.cost)} ₽" } ?: "Добавьте показания, чтобы увидеть статистику", color = Color.White.copy(alpha = .88f))
            if (last != null) Text("Последнее показание: ${fmt(last.value)} кВт·ч", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = Teal); Text(title, color = Muted, style = MaterialTheme.typography.labelMedium); Text(value, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink) }

@Composable private fun MonthlyChart(stats: List<MonthStat>, max: Double, selected: String?, onSelect: (String) -> Unit) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            stats.forEach { s -> Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(s.key) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val f = if (max > 0) (s.usage / max).toFloat().coerceIn(.04f, 1f) else .04f
                if (selected == s.key) Text(fmt(s.usage), style = MaterialTheme.typography.labelSmall, color = Teal)
                Box(Modifier.fillMaxWidth(.68f).fillMaxHeight(f * .72f).clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)).background(if (selected == s.key) Color(0xFFED9B52) else Color(0xFF42A99A)))
                Text(s.title.take(3), style = MaterialTheme.typography.labelSmall, color = Muted)
            } }
        }
        Text("Нажмите на столбец, чтобы увидеть точные значения.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun recalculate(input: List<Reading>): List<Reading>? {
    val ordered = input.sortedBy { parseDate(it.date) ?: Date(Long.MIN_VALUE) }
    if (ordered.zipWithNext().any { it.second.value < it.first.value }) return null
    return ordered.mapIndexed { i, r -> val usage = if (i == 0) 0.0 else r.value - ordered[i - 1].value; r.copy(usage = usage, cost = usage * r.tariff) }.asReversed()
}

// Распределяет расход интервала пропорционально времени по календарным месяцам.
private fun buildCalendarMonths(readings: List<Reading>): List<MonthStat> {
    val ordered = readings.sortedBy { parseDate(it.date) ?: Date(Long.MIN_VALUE) }
    val totals = linkedMapOf<String, Pair<Double, Double>>()
    for (i in 1 until ordered.size) {
        val start = parseDate(ordered[i - 1].date) ?: continue; val end = parseDate(ordered[i].date) ?: continue
        val duration = end.time - start.time; if (duration <= 0) continue
        val usage = ordered[i].usage; val cost = ordered[i].cost
        var cursor = Calendar.getInstance().apply { time = start }
        while (cursor.time.before(end)) {
            val monthStart = (cursor.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val nextMonth = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val segmentEnd = minOf(end.time, nextMonth.timeInMillis); val segmentStart = maxOf(start.time, monthStart.timeInMillis)
            val fraction = (segmentEnd - segmentStart).toDouble() / duration
            val key = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(monthStart.time); val old = totals[key] ?: (0.0 to 0.0)
            totals[key] = (old.first + usage * fraction) to (old.second + cost * fraction); cursor.timeInMillis = segmentEnd
        }
    }
    val titleFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))
    return totals.map { (key, pair) -> val cal = Calendar.getInstance().apply { set(key.substring(0,4).toInt(), key.substring(5,7).toInt()-1, 1) }; MonthStat(key, titleFormat.format(cal.time).replaceFirstChar { it.titlecase(Locale("ru")) }, pair.first, pair.second) }.sortedBy { it.key }
}

private fun parseDate(s: String): Date? = try { SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).apply { isLenient = false }.parse(s) } catch (_: Exception) { null }
private fun nowDate() = SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(Date())
private fun fmt(v: Double) = String.format(Locale.getDefault(), "%.2f", v)
private fun fmtInput(v: Double) = String.format(Locale.US, "%.2f", v)
private fun loadReadings(prefs: android.content.SharedPreferences, fallbackTariff: Double): List<Reading> = try {
    val a = JSONArray(prefs.getString("readings", "[]")); (0 until a.length()).map { i -> val o = a.getJSONObject(i); Reading(o.getDouble("value"), o.getString("date"), o.getDouble("usage"), o.getDouble("cost"), o.optDouble("tariff", if (o.getDouble("usage") > 0) o.getDouble("cost") / o.getDouble("usage") else fallbackTariff)) }
} catch (_: Exception) { emptyList() }
private fun saveReadings(prefs: android.content.SharedPreferences, list: List<Reading>) {
    val a = JSONArray(); list.forEach { a.put(JSONObject().put("value", it.value).put("date", it.date).put("usage", it.usage).put("cost", it.cost).put("tariff", it.tariff)) }; prefs.edit().putString("readings", a.toString()).apply()
}
