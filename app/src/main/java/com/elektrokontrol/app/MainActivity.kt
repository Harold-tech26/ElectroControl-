package com.elektrokontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reading(val value: Double, val date: String, val usage: Double, val cost: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("electro_control", MODE_PRIVATE)
        setContent {
            var tariff by remember { mutableStateOf(prefs.getFloat("tariff", 6.50f).toString()) }
            var readingText by remember { mutableStateOf("") }
            var readings by remember { mutableStateOf(loadReadings(prefs)) }
            var message by remember { mutableStateOf("") }

            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B5B))) {
                Scaffold { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("ЭлектроКонтроль", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Учёт показаний и расходов электроэнергии")
                        }
                        item {
                            OutlinedTextField(
                                value = tariff, onValueChange = { tariff = it; it.toFloatOrNull()?.let { v -> prefs.edit().putFloat("tariff", v).apply() } },
                                label = { Text("Тариф, ₽ за кВт·ч") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = readingText, onValueChange = { readingText = it },
                                label = { Text("Новое показание счётчика, кВт·ч") }, singleLine = true,
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
                                        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                                        val item = Reading(v, date, usage, usage * t)
                                        readings = listOf(item) + readings
                                        saveReadings(prefs, readings)
                                        readingText = ""
                                        message = "Показание сохранено."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) { Text("Сохранить показание") }
                            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        }
                        item {
                            val last = readings.firstOrNull()
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Последнее показание", fontWeight = FontWeight.SemiBold)
                                    Text(last?.let { "${it.value} кВт·ч" } ?: "Пока нет записей")
                                    Text("Расход за последний интервал: ${"%.2f".format(last?.usage ?: 0.0)} кВт·ч")
                                    Text("Стоимость интервала: ${"%.2f".format(last?.cost ?: 0.0)} ₽")
                                }
                            }
                        }
                        item { Text("История показаний", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        if (readings.isEmpty()) item { Text("Добавь первое показание, чтобы начать учёт.") }
                        items(readings) { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(r.date, fontWeight = FontWeight.SemiBold)
                                    Text("Показание: ${r.value} кВт·ч")
                                    Text("Расход: ${"%.2f".format(r.usage)} кВт·ч · Стоимость: ${"%.2f".format(r.cost)} ₽")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
