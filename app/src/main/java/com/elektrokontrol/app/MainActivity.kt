package com.elektrokontrol.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

            val currentTariff = tariff.replace(',', '.').toDoubleOrNull() ?: 0.0
            val monthly = remember(readings) { buildCalendarMonths(readings).takeLast(12) }
            val latestFirst = readings.sortedByDescending { parseDate(it.date) ?: Date(0) }
            val selectedStat = monthly.firstOrNull { it.key == selectedMonth }
            val maxUsage = monthly.maxOfOrNull { it.usage } ?: 0.0
            val forecastUsage = monthly.takeLast(3).filter { it.usage > 0 }.map { it.usage }.average().takeIf { it.isFinite() } ?: 0.0
            val forecastCost = monthly.takeLast(3).filter { it.usage > 0 }.map { it.cost }.average().takeIf { it.isFinite() } ?: 0.0
            val comparison = if (monthly.size >= 2) monthly[monthly.lastIndex] to monthly[monthly.lastIndex - 1] else null

            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B))) {
                Scaffold { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        item {
                            Text("ЭлектроКонтроль", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Учёт показаний и расходов электроэнергии")
                        }
                        item {
                            OutlinedTextField(value = tariff, onValueChange = { tariff = it; it.replace(',', '.').toFloatOrNull()?.let { v -> prefs.edit().putFloat("tariff", v).apply() } }, label = { Text("Текущий тариф, ₽ за кВт·ч") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            Text("Этот тариф будет записан только для новых показаний.", style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            OutlinedTextField(value = readingText, onValueChange = { readingText = it }, label = { Text("Новое показание счётчика, кВт·ч") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("Дата и время (дд.мм.гггг чч:мм)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                val value = readingText.replace(',', '.').toDoubleOrNull(); val date = parseDate(dateText)
                                when {
                                    value == null || value < 0 || currentTariff < 0 -> message = "Проверь показание и тариф."
                                    date == null -> message = "Неверная дата. Формат: дд.мм.гггг чч:мм."
                                    readings.any { it.date == dateText.trim() } -> message = "Запись с такой датой уже существует."
                                    else -> {
                                        val updated = recalculate(readings + Reading(value, dateText.trim(), 0.0, 0.0, currentTariff))
                                        if (updated == null) message = "Показания должны возрастать с течением времени."
                                        else { readings = updated; saveReadings(prefs, readings); readingText = ""; dateText = nowDate(); message = "Показание сохранено." }
                                    }
                                }
                            }, Modifier.fillMaxWidth()) { Text("Сохранить показание") }
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        }
                        item {
                            val last = latestFirst.firstOrNull()
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Последнее показание", fontWeight = FontWeight.SemiBold)
                                Text(last?.let { "${fmt(it.value)} кВт·ч" } ?: "Пока нет записей")
                                Text("Расход за интервал: ${fmt(last?.usage ?: 0.0)} кВт·ч")
                                Text("Стоимость интервала: ${fmt(last?.cost ?: 0.0)} ₽")
                                Text("Тариф этой записи: ${fmt(last?.tariff ?: currentTariff)} ₽/кВт·ч")
                            } }
                        }
                        item {
                            Text("Расход по календарным месяцам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Расход между показаниями распределяется по дням между календарными месяцами. Это оценка, если показания не сняты точно на границе месяца.", style = MaterialTheme.typography.bodySmall)
                            if (monthly.isEmpty()) Text("Статистика появится после двух показаний.") else MonthlyChart(monthly, maxUsage, selectedMonth) { selectedMonth = it }
                            selectedStat?.let { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(it.title, fontWeight = FontWeight.Bold); Text("Расход: ${fmt(it.usage)} кВт·ч"); Text("Стоимость: ${fmt(it.cost)} ₽") } } }
                        }
                        item {
                            Text("Сравнение последних месяцев", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                                if (comparison == null) Text("Нужны данные минимум за два месяца.") else {
                                    val (now, before) = comparison
                                    val diff = now.usage - before.usage
                                    val pct = if (before.usage > 0) diff / before.usage * 100 else null
                                    Text("${now.title} / ${before.title}", fontWeight = FontWeight.SemiBold)
                                    Text("Изменение: ${fmt(diff)} кВт·ч")
                                    Text(pct?.let { (if (it > 0) "Рост" else "Снижение") + " на ${fmt(kotlin.math.abs(it))}%" } ?: "Процентное сравнение недоступно.")
                                }
                            } }
                        }
                        item {
                            Text("Прогноз на следующий месяц", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                                Text("Средний расход последних трёх месяцев: ${fmt(forecastUsage)} кВт·ч")
                                Text("Ориентировочная стоимость: ${fmt(forecastCost)} ₽")
                                Text("Стоимость учитывает тарифы, сохранённые в интервалах.", style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        item {
                            Text("Напоминания", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Добавляй любое количество ежемесячных напоминаний. В коротких месяцах дата автоматически переносится на последний день.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = reminderLabel, onValueChange = { reminderLabel = it }, label = { Text("Текст напоминания") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = reminderDay, onValueChange = { reminderDay = it.filter(Char::isDigit).take(2) }, label = { Text("День") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                OutlinedTextField(value = reminderHour, onValueChange = { reminderHour = it.filter(Char::isDigit).take(2) }, label = { Text("Час") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                                OutlinedTextField(value = reminderMinute, onValueChange = { reminderMinute = it.filter(Char::isDigit).take(2) }, label = { Text("Мин.") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            }
                            Button(onClick = {
                                val d = reminderDay.toIntOrNull(); val h = reminderHour.toIntOrNull(); val m = reminderMinute.toIntOrNull()
                                if (d == null || d !in 1..31 || h == null || h !in 0..23 || m == null || m !in 0..59) message = "Проверь день (1–31) и время."
                                else {
                                    val id = (reminders.maxOfOrNull { it.id } ?: 0) + 1
                                    val r = MonthlyReminder(id, d, h, m, reminderLabel)
                                    reminders = reminders + r; ReminderStore.save(prefs, reminders); ReminderScheduler.schedule(this@MainActivity, r); message = "Напоминание добавлено."
                                }
                            }, Modifier.fillMaxWidth()) { Text("Добавить напоминание") }
                        }
                        items(reminders, key = { it.id }) { r ->
                            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Каждый месяц: ${r.day}-го, ${"%02d:%02d".format(r.hour, r.minute)}", fontWeight = FontWeight.SemiBold); Text(r.label) }
                                TextButton(onClick = { ReminderScheduler.cancel(this@MainActivity, r); reminders = reminders.filterNot { it.id == r.id }; ReminderStore.save(prefs, reminders) }) { Text("Удалить") }
                            } }
                        }
                        item { Text("История показаний", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        items(latestFirst, key = { it.date }) { r ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(r.date, fontWeight = FontWeight.SemiBold); Text("Показание: ${fmt(r.value)} кВт·ч")
                                Text("Расход: ${fmt(r.usage)} кВт·ч · Стоимость: ${fmt(r.cost)} ₽")
                                Text("Тариф записи: ${fmt(r.tariff)} ₽/кВт·ч")
                                OutlinedButton(onClick = { editing = r; editValue = fmtInput(r.value); editDate = r.date; editTariff = fmtInput(r.tariff) }) { Text("Исправить запись") }
                            } }
                        }
                    }
                }
            }
            editing?.let { old -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Исправить показание") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(editValue, { editValue = it }, label = { Text("Показание, кВт·ч") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(editDate, { editDate = it }, label = { Text("Дата (дд.мм.гггг чч:мм)") })
                    OutlinedTextField(editTariff, { editTariff = it }, label = { Text("Тариф этой записи, ₽/кВт·ч") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("Стоимость интервала будет рассчитана по тарифу этой записи. Тарифы остальных записей не изменятся.", style = MaterialTheme.typography.bodySmall)
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

@Composable private fun MonthlyChart(stats: List<MonthStat>, max: Double, selected: String?, onSelect: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().height(180.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        stats.forEach { s -> Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(s.key) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            val f = if (max > 0) (s.usage / max).toFloat().coerceIn(0.03f, 1f) else 0.03f
            if (selected == s.key) Text(fmt(s.usage), style = MaterialTheme.typography.labelSmall)
            Box(Modifier.fillMaxWidth(.7f).fillMaxHeight(f * .72f).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (selected == s.key) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
            Text(s.title.take(3), style = MaterialTheme.typography.labelSmall)
        } }
    } }
}

private fun recalculate(input: List<Reading>): List<Reading>? {
    val ordered = input.sortedBy { parseDate(it.date) ?: Date(Long.MIN_VALUE) }
    if (ordered.zipWithNext().any { it.second.value < it.first.value }) return null
    return ordered.mapIndexed { i, r -> val usage = if (i == 0) 0.0 else r.value - ordered[i - 1].value; r.copy(usage = usage, cost = usage * r.tariff) }.asReversed()
}

// Prorates each interval uniformly by elapsed time across calendar-month boundaries.
private fun buildCalendarMonths(readings: List<Reading>): List<MonthStat> {
    val ordered = readings.sortedBy { parseDate(it.date) ?: Date(Long.MIN_VALUE) }
    val totals = linkedMapOf<String, Pair<Double, Double>>()
    for (i in 1 until ordered.size) {
        val start = parseDate(ordered[i - 1].date) ?: continue
        val end = parseDate(ordered[i].date) ?: continue
        val duration = end.time - start.time
        if (duration <= 0) continue
        val usage = ordered[i].usage; val cost = ordered[i].cost
        var cursor = Calendar.getInstance().apply { time = start }
        while (cursor.time.before(end)) {
            val monthStart = (cursor.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val nextMonth = (monthStart.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            val segmentEnd = minOf(end.time, nextMonth.timeInMillis)
            val segmentStart = maxOf(start.time, monthStart.timeInMillis)
            val fraction = (segmentEnd - segmentStart).toDouble() / duration
            val key = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(monthStart.time)
            val old = totals[key] ?: (0.0 to 0.0)
            totals[key] = (old.first + usage * fraction) to (old.second + cost * fraction)
            cursor.timeInMillis = segmentEnd
        }
    }
    val titleFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))
    return totals.map { (key, pair) ->
        val cal = Calendar.getInstance().apply { set(key.substring(0,4).toInt(), key.substring(5,7).toInt()-1, 1) }
        MonthStat(key, titleFormat.format(cal.time).replaceFirstChar { it.titlecase(Locale("ru")) }, pair.first, pair.second)
    }.sortedBy { it.key }
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
