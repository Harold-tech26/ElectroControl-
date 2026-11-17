package com.elektrokontrol.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("electro_control", Context.MODE_PRIVATE)
        val id = intent?.getIntExtra("reminder_id", -1) ?: -1
        val reminder = ReminderStore.load(prefs).firstOrNull { it.id == id } ?: return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    "meter_reminders",
                    "Напоминания счётчика",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val open = PendingIntent.getActivity(
            context,
            9000 + id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "meter_reminders")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ЭлектроКонтроль")
            .setContentText(reminder.label.ifBlank { "Пора внести показания электросчётчика" })
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        manager.notify(9000 + id, notification)
        // После срабатывания сразу назначаем следующее ежемесячное напоминание.
        ReminderScheduler.schedule(context, reminder)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("electro_control", Context.MODE_PRIVATE)
        ReminderStore.load(prefs).forEach { ReminderScheduler.schedule(context, it) }
    }
}

data class MonthlyReminder(
    val id: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val label: String
)

object ReminderStore {
    fun load(prefs: android.content.SharedPreferences): List<MonthlyReminder> = try {
        val array = JSONArray(prefs.getString("monthly_reminders", "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            MonthlyReminder(
                item.getInt("id"),
                item.getInt("day"),
                item.getInt("hour"),
                item.getInt("minute"),
                item.optString("label", "")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(prefs: android.content.SharedPreferences, list: List<MonthlyReminder>) {
        val array = JSONArray()
        list.forEach { reminder ->
            array.put(
                JSONObject()
                    .put("id", reminder.id)
                    .put("day", reminder.day)
                    .put("hour", reminder.hour)
                    .put("minute", reminder.minute)
                    .put("label", reminder.label)
            )
        }
        prefs.edit().putString("monthly_reminders", array.toString()).apply()
    }
}

object ReminderScheduler {
    fun schedule(context: Context, reminder: MonthlyReminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("reminder_id", reminder.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            // Сначала выставляем выбранные день и время в текущем месяце.
            val lastDay = getActualMaximum(Calendar.DAY_OF_MONTH)
            set(Calendar.DAY_OF_MONTH, minOf(reminder.day, lastDay))
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // Если выбранное время уже прошло — переходим на следующий месяц.
            if (!after(now)) {
                add(Calendar.MONTH, 1)
                val nextMonthLastDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                set(Calendar.DAY_OF_MONTH, minOf(reminder.day, nextMonthLastDay))
            }
        }

        // Точное срабатывание, если Android разрешил точные будильники.
        // Иначе используем разрешённый системой неточный вариант.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.timeInMillis,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.timeInMillis,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context, reminder: MonthlyReminder) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("reminder_id", reminder.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
    }
}
