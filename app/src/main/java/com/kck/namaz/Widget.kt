package com.kck.namaz

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ana ekran widget'ı: sıradaki vakit, geri sayım ve günün 6 vakti. */
class PrayerWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx)
    }

    override fun onEnabled(ctx: Context) { update(ctx) }

    companion object {
        private val cellIds = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
        private val nameIds = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5)
        private val timeIds = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4, R.id.t5)

        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrayerWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = Prefs(ctx); val l = L(ctx)
            val now = System.currentTimeMillis()
            val today = LocalDate.now(prefs.zone)
            val t0 = prefs.times(today)
            var nextIdx = -1; var nextTime = 0L
            for (i in 0 until PrayerCalc.COUNT) if (t0[i] > now) { nextIdx = i; nextTime = t0[i]; break }
            val showDay = if (nextIdx == -1) today.plusDays(1) else today
            val times = if (nextIdx == -1) prefs.times(showDay) else t0
            if (nextIdx == -1) { nextIdx = 0; nextTime = times[0] }
            var cur = -1
            if (showDay == today) for (i in 0 until PrayerCalc.COUNT) if (t0[i] <= now) cur = i

            val f = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone(prefs.zone) }
            val v = RemoteViews(ctx.packageName, R.layout.widget_prayer)
            v.setTextViewText(R.id.city, "📍 " + prefs.city.name + if (showDay != today) " · " + l.t("yarın", "morgen") else "")
            val imsakDay = if (t0[0] > now) today else today.plusDays(1)
            val label = when {
                nextIdx == 0 && ReligiousDays.inRamadan(imsakDay) -> l.t("Sahur bitimine", "bis Sahur-Ende")
                nextIdx == 4 && ReligiousDays.inRamadan(today) -> l.t("İftara", "bis Iftar")
                else -> l.t("kalan", "verbleibend")
            }
            v.setTextViewText(R.id.label, label)
            v.setTextViewText(R.id.next, l.shortNames[nextIdx] + "  " + f.format(Date(nextTime)))
            v.setChronometer(R.id.cd, SystemClock.elapsedRealtime() + (nextTime - now), null, true)
            v.setChronometerCountDown(R.id.cd, true)
            for (i in 0 until PrayerCalc.COUNT) {
                v.setTextViewText(nameIds[i], l.shortNames[i])
                v.setTextViewText(timeIds[i], f.format(Date(times[i])))
                val hi = i == cur
                v.setInt(cellIds[i], "setBackgroundResource", if (hi) R.drawable.widget_cell_hi else 0)
                v.setTextColor(timeIds[i], if (hi || i == nextIdx && showDay != today) C.GOLD else C.TEXT)
            }
            val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.root, open)
            mgr.updateAppWidget(ids, v)
            scheduleNextUpdate(ctx, nextTime)
        }

        /** Vakit girdiği an widget'ı yenile. */
        private fun scheduleNextUpdate(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 6, Intent(ctx, WidgetTick::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val t = at + 1500
            try {
                if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        }
    }
}

class WidgetTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try { PrayerWidget.update(ctx) } catch (e: Exception) { }
    }
}
