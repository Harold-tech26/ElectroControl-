package com.elektrokontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reading(val value: Double, val date: String, val usage: Double, val cost: Double)
private data class MonthStat(val key: String, val title: String, val usage: Double, val cost: Double)

private const val DATE_PATTERN = "dd.MM.yyyy HH:mm"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("electro_control", MODE_PRIVATE)

        setContent {
            var tariff by remember { mutableStateOf(prefs.getFloat("tariff", 5.53f).toString()) }
            var readingText by remember { mutableStateOf("") }
            var dateText by remember { mutableStateOf(nowDate()) }
            var readings by remember { mutableStateOf(loadReadings(prefs)) }
            var message by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf<Reading?>(null) }
            var editValue by remember { mutableStateOf("") }
            var editDate by remember { mutableStateOf("") }
            var selectedMonth by remember { mutableStateOf<String?>(null) }

            val tariffValue = tariff.replace(',', '.').toDoubleOrNull() ?: 0.0
            val monthly = remember(readings) { buildMonthlyStats(readings).takeLast(12) }
            val latestFirst = readings.sortedByDescending { parseDate(it.date) ?: Date(0) }
            val maxUsage = monthly.maxOfOrNull { it.usage } ?: 0.0
            val selectedStat = monthly.firstOrNull { it.key == selectedMonth }
            val comparison = monthly.takeIf { it.size >= 2 }?.let {
                val previous = it[it.lastIndex - 1]
                val current = it.last()
                val percent = if (previous.usage == 0.0) null
                    else ((current.usage - previous.usage) / previous.usage) * 100.0
                Triple(current, previous, percent)
            }
            val forecastUsage = monthly.takeLast(3).takeIf { it.isNotEmpty() }
                ?.map { it.usage }?.average() ?: 0.0
            val forecastCost = forecastUsage * tariffValue

            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B))) {
                Scaffold { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        item {
                            Text("ЭлектроКонтроль", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Учёт показаний и расходов электроэнергии")
                        }

                        item {
                            OutlinedTextField(
                                value = tariff,
                                onValueChange = {
                                    tariff = it
                                    it.replace(',', '.').toFloatOrNull()?.let { v ->
                                        prefs.edit().putFloat("tariff", v).apply()
                                    }
                                },
                                label = { Text("Тариф, ₽ за кВт·ч") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = readingText,
                                onValueChange = { readingText = it },
                                label = { Text("Новое показание счётчика, кВт·ч") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = dateText,
                                onValueChange = { dateText = it },
                                label = { Text("Дата и время (дд.мм.гггг чч:мм)") },
                                supportingText = { Text("Можно изменить дату вручную") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    val value = readingText.replace(',', '.').toDoubleOrNull()
                                    val date = parseDate(dateText)
                                    if (value == null || value < 0 || tariffValue < 0) {
                                        message = "Проверь показание и тариф."
                                    } else if (date == null) {
                                        message = "Неверная дата. Используй формат дд.мм.гггг чч:мм."
                                    } else if (readings.any { it.date == dateText.trim() }) {
                                        message = "Запись с такой датой и временем уже существует."
                                    } else {
                                        val candidate = readings + Reading(value, dateText.trim(), 0.0, 0.0)
                                        val recalculated = recalculate(candidate, tariffValue)
                                        if (recalculated == null) {
                                            message = "Показания должны возрастать с течением времени."
                                        } else {
                                            readings = recalculated
                                            saveReadings(prefs, readings)
                                            readingText = ""
                                            dateText = nowDate()
                                            message = "Показание сохранено."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                            ) { Text("Сохранить показание") }
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        }

                        item {
                            val last = latestFirst.firstOrNull()
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Последнее показание", fontWeight = FontWeight.SemiBold)
                                    Text(last?.let { "${fmt(it.value)} кВт·ч" } ?: "Пока нет записей")
                                    Text("Расход за последний интервал: ${fmt(last?.usage ?: 0.0)} кВт·ч")
                                    Text("Стоимость интервала: ${fmt(last?.cost ?: 0.0)} ₽")
                                }
                            }
                        }

                        item {
                            Text("Расход по месяцам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Выбери столбик, чтобы посмотреть месяц. Интервал относится к месяцу нового показания.",
                                style = MaterialTheme.typography.bodySmall)
                            if (monthly.isEmpty()) {
                                Text("Статистика появится после второго показания.")
                            } else {
                                MonthlyBarChart(
                                    stats = monthly,
                                    maxUsage = maxUsage,
                                    selectedKey = selectedMonth,
                                    onSelect = { selectedMonth = it }
                                )
                            }
                            selectedStat?.let { stat ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(stat.title, fontWeight = FontWeight.Bold)
                                        Text("Расход: ${fmt(stat.usage)} кВт·ч")
                                        Text("Стоимость: ${fmt(stat.cost)} ₽")
                                    }
                                }
                            }
                        }

                        item {
                            Text("Сравнение месяцев", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (comparison == null) {
                                        Text("Для сравнения нужны данные минимум за два месяца.")
                                    } else {
                                        val (current, previous, percent) = comparison
                                        Text("${current.title} / ${previous.title}", fontWeight = FontWeight.SemiBold)
                                        Text("Изменение: ${fmt(current.usage - previous.usage)} кВт·ч")
                                        Text(
                                            percent?.let {
                                                val direction = if (it > 0) "рост" else if (it < 0) "снижение" else "без изменений"
                                                "$direction на ${fmt(kotlin.math.abs(it))}%"
                                            } ?: "Нельзя рассчитать процент: в прошлом месяце расход был нулевым."
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text("Прогноз на следующий месяц", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Ориентир по среднему расходу последних трёх месяцев")
                                    Text("${fmt(forecastUsage)} кВт·ч", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("Примерная стоимость: ${fmt(forecastCost)} ₽")
                                    Text("Прогноз приблизительный и использует текущий тариф.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        item {
                            Text("История показаний", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        if (latestFirst.isEmpty()) item { Text("Добавь первое показание, чтобы начать учёт.") }
                        items(latestFirst, key = { it.date }) { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(r.date, fontWeight = FontWeight.SemiBold)
                                    Text("Показание: ${fmt(r.value)} кВт·ч")
                                    Text("Расход: ${fmt(r.usage)} кВт·ч · Стоимость: ${fmt(r.cost)} ₽")
                                    OutlinedButton(onClick = {
                                        editing = r
                                        editValue = fmtInput(r.value)
                                        editDate = r.date
                                        message = ""
                                    }) { Text("Исправить запись") }
                                }
                            }
                        }
                    }
                }
            }

            editing?.let { original ->
                AlertDialog(
                    onDismissRequest = { editing = null },
                    title = { Text("Исправить показание") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = editValue,
                                onValueChange = { editValue = it },
                                label = { Text("Показание, кВт·ч") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = editDate,
                                onValueChange = { editDate = it },
                                label = { Text("Дата и время (дд.мм.гггг чч:мм)") },
                                singleLine = true
                            )
                            Text("После сохранения расход и стоимость интервалов будут пересчитаны по текущему тарифу.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val value = editValue.replace(',', '.').toDoubleOrNull()
                            val date = parseDate(editDate)
                            if (value == null || value < 0 || date == null) {
                                message = "Проверь показание и формат даты."
                            } else if (readings.any { it.date == editDate.trim() && it.date != original.date }) {
                                message = "Такая дата и время уже заняты другой записью."
                            } else {
                                val changed = readings.map {
                                    if (it.date == original.date) Reading(value, editDate.trim(), 0.0, 0.0) else it
                                }
                                val recalculated = recalculate(changed, tariffValue)
                                if (recalculated == null) {
                                    message = "Нельзя сохранить: показания должны возрастать с течением времени."
                                } else {
                                    readings = recalculated
                                    saveReadings(prefs, readings)
                                    editing = null
                                    message = "Запись исправлена, расчёты обновлены."
                                }
                            }
                        }) { Text("Сохранить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { editing = null }) { Text("Отмена") }
                    }
                )
            }
        }
    }
}

@Composable
private fun MonthlyBarChart(
    stats: List<MonthStat>,
    maxUsage: Double,
    selectedKey: String?,
    onSelect: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            stats.forEach { stat ->
                val ratio = if (maxUsage > 0) (stat.usage / maxUsage).toFloat().coerceIn(0.04f, 1f) else 0.04f
                val selected = selectedKey == stat.key
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelect(stat.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(if (selected) fmt(stat.usage) else "", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .fillMaxHeight(ratio * 0.72f)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(stat.title.take(3), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

private fun recalculate(input: List<Reading>, tariff: Double): List<Reading>? {
    val chronological = input.sortedBy { parseDate(it.date) ?: Date(Long.MIN_VALUE) }
    for (i in 1 until chronological.size) {
        if (chronological[i].value < chronological[i - 1].value) return null
    }
    val calculated = chronological.mapIndexed { index, r ->
        val usage = if (index == 0) 0.0 else r.value - chronological[index - 1].value
        r.copy(usage = usage, cost = usage * tariff)
    }
    return calculated.asReversed()
}

private fun buildMonthlyStats(readings: List<Reading>): List<MonthStat> {
    val keyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val titleFormat = SimpleDateFormat("LLLL yy", Locale("ru"))
    val grouped = linkedMapOf<String, MonthStat>()
    readings.sortedBy { parseDate(it.date) ?: Date(0) }.forEach { r ->
        val date = parseDate(r.date) ?: return@forEach
        val key = keyFormat.format(date)
        val title = titleFormat.format(date).replaceFirstChar { it.titlecase(Locale("ru")) }
        val old = grouped[key]
        grouped[key] = if (old == null) MonthStat(key, title, r.usage, r.cost)
        else old.copy(usage = old.usage + r.usage, cost = old.cost + r.cost)
    }
    return grouped.values.sortedBy { it.key }
}

private fun parseDate(value: String): Date? = try {
    SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).apply { isLenient = false }.parse(value)
} catch (_: Exception) { null }

private fun nowDate(): String = SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(Date())
private fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
private fun fmtInput(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun loadReadings(prefs: android.content.SharedPreferences): List<Reading> {
    return try {
        val arr = JSONArray(prefs.getString("readings", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reading(o.getDouble("value"), o.getString("date"), o.getDouble("usage"), o.getDouble("cost"))
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveReadings(prefs: android.content.SharedPreferences, readings: List<Reading>) {
    val arr = JSONArray()
    readings.forEach { r ->
        arr.put(JSONObject().put("value", r.value).put("date", r.date).put("usage", r.usage).put("cost", r.cost))
    }
    prefs.edit().putString("readings", arr.toString()).apply()
}
