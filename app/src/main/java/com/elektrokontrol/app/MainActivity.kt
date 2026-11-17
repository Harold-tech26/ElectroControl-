package com.elektrokontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private data class MonthlyStat(
    val key: String,
    val title: String,
    val usage: Double,
    val cost: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("electro_control", MODE_PRIVATE)

        setContent {
            var tariff by remember { mutableStateOf(prefs.getFloat("tariff", 6.50f).toString()) }
            var readingText by remember { mutableStateOf("") }
            var readings by remember { mutableStateOf(loadReadings(prefs)) }
            var message by remember { mutableStateOf("") }

            val monthlyStats = remember(readings) {
                buildMonthlyStats(readings).takeLast(12).reversed()
            }
            val maxMonthlyUsage = monthlyStats.maxOfOrNull { it.usage } ?: 0.0

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF176B5B)
                )
            ) {
                Scaffold { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text(
                                "ЭлектроКонтроль",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
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
                            Button(
                                onClick = {
                                    val v = readingText.replace(',', '.').toDoubleOrNull()
                                    val t = tariff.replace(',', '.').toDoubleOrNull()

                                    if (v == null || t == null || v < 0 || t < 0) {
                                        message = "Проверь показание и тариф."
                                    } else if (readings.isNotEmpty() && v < readings.first().value) {
                                        message = "Новое показание не может быть меньше предыдущего."
                                    } else {
                                        val previous = readings.firstOrNull()?.value ?: v
                                        val usage = v - previous
                                        val date = SimpleDateFormat(
                                            "dd.MM.yyyy HH:mm",
                                            Locale.getDefault()
                                        ).format(Date())
                                        val newReading = Reading(v, date, usage, usage * t)

                                        readings = listOf(newReading) + readings
                                        saveReadings(prefs, readings)
                                        readingText = ""
                                        message = "Показание сохранено."
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text("Сохранить показание")
                            }

                            if (message.isNotBlank()) {
                                Text(message, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        item {
                            val last = readings.firstOrNull()
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Последнее показание", fontWeight = FontWeight.SemiBold)
                                    Text(last?.let { "${formatNumber(it.value)} кВт·ч" } ?: "Пока нет записей")
                                    Text(
                                        "Расход за последний интервал: " +
                                            "${formatNumber(last?.usage ?: 0.0)} кВт·ч"
                                    )
                                    Text(
                                        "Стоимость интервала: " +
                                            "${formatNumber(last?.cost ?: 0.0)} ₽"
                                    )
                                }
                            }
                        }

                        item {
                            Text(
                                "Расход по месяцам",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "За последние 12 месяцев. Интервал относится к месяцу, " +
                                    "в котором внесено новое показание.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (monthlyStats.isEmpty()) {
                            item {
                                Text("Статистика появится после второго показания счётчика.")
                            }
                        } else {
                            items(monthlyStats) { stat ->
                                MonthlyStatRow(
                                    stat = stat,
                                    maxUsage = maxMonthlyUsage
                                )
                            }
                        }

                        item {
                            Text(
                                "История показаний",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (readings.isEmpty()) {
                            item { Text("Добавь первое показание, чтобы начать учёт.") }
                        }

                        items(readings) { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(r.date, fontWeight = FontWeight.SemiBold)
                                    Text("Показание: ${formatNumber(r.value)} кВт·ч")
                                    Text(
                                        "Расход: ${formatNumber(r.usage)} кВт·ч · " +
                                            "Стоимость: ${formatNumber(r.cost)} ₽"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyStatRow(stat: MonthlyStat, maxUsage: Double) {
    val fraction = if (maxUsage > 0.0) {
        (stat.usage / maxUsage).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stat.title, fontWeight = FontWeight.SemiBold)
                Text("${formatNumber(stat.usage)} кВт·ч")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Text(
                "Стоимость: ${formatNumber(stat.cost)} ₽",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun buildMonthlyStats(readings: List<Reading>): List<MonthlyStat> {
    val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val monthTitleFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))
    val grouped = linkedMapOf<String, MonthlyStat>()

    // Readings are stored newest first. Each interval is assigned to the
    // month in which its newer reading was entered.
    readings.asReversed().forEach { reading ->
        val parsedDate = try {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).parse(reading.date)
        } catch (_: Exception) {
            null
        } ?: return@forEach

        val key = monthKeyFormat.format(parsedDate)
        val title = monthTitleFormat.format(parsedDate)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }

        val previous = grouped[key]
        grouped[key] = if (previous == null) {
            MonthlyStat(key, title, reading.usage, reading.cost)
        } else {
            previous.copy(
                usage = previous.usage + reading.usage,
                cost = previous.cost + reading.cost
            )
        }
    }

    return grouped.values.sortedBy { it.key }
}

private fun formatNumber(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)

private fun loadReadings(prefs: android.content.SharedPreferences): List<Reading> {
    return try {
        val arr = JSONArray(prefs.getString("readings", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reading(
                o.getDouble("value"),
                o.getString("date"),
                o.getDouble("usage"),
                o.getDouble("cost")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveReadings(
    prefs: android.content.SharedPreferences,
    readings: List<Reading>
) {
    val arr = JSONArray()
    readings.forEach { r ->
        arr.put(
            JSONObject()
                .put("value", r.value)
                .put("date", r.date)
                .put("usage", r.usage)
                .put("cost", r.cost)
        )
    }
    prefs.edit().putString("readings", arr.toString()).apply()
}
