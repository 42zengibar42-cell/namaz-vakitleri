package com.kck.namaz

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Namaz vaktinde telefonu titreşime / sessize alır, süre bitince eski haline döndürür. */
object Silent {
    private const val CH = "sessiz"
    private const val NOTE_ID = 400

    private fun sp(ctx: Context) = ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE)

    // --- Ayarlar ---
    fun enabled(ctx: Context) = sp(ctx).getBoolean("silOn", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("silOn", v).apply()
    /** true = tam sessiz, false = titreşim */
    fun full(ctx: Context) = sp(ctx).getBoolean("silFull", false)
    fun setFull(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("silFull", v).apply()
    fun minutes(ctx: Context) = sp(ctx).getInt("silMin", 20)
    fun setMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("silMin", v).apply()
    fun fridayMinutes(ctx: Context) = sp(ctx).getInt("silFri", 45)
    fun setFridayMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("silFri", v).apply()
    fun prayer(ctx: Context, i: Int) = sp(ctx).getBoolean("sil$i", i != 0 && i != 1)
    fun setPrayer(ctx: Context, i: Int, v: Boolean) = sp(ctx).edit().putBoolean("sil$i", v).apply()

    fun hasPolicyAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun pi(ctx: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, rc, Intent(ctx, SilentReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(ctx: Context, at: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** Sıradaki sessize alma zamanını kurar. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val start = pi(ctx, "start", 8)
        am.cancel(start)
        if (!enabled(ctx)) { if (activeUntil(ctx) > 0) restore(ctx); return }
        val u = activeUntil(ctx)
        if (u in 1 until System.currentTimeMillis()) restore(ctx)
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val today = LocalDate.now(prefs.zone)
        for (d in 0..2) {
            val t = prefs.times(today.plusDays(d.toLong()))
            for (i in 0 until PrayerCalc.COUNT) {
                if (i == 1 || !prayer(ctx, i)) continue
                if (t[i] > now + 1000) {
                    sp(ctx).edit().putInt("silNextIdx", i).apply()
                    setAlarm(ctx, t[i], start)
                    return
                }
            }
        }
    }

    fun activeUntil(ctx: Context) = sp(ctx).getLong("silUntil", 0L)

    fun start(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val prefs = Prefs(ctx)
        val idx = sp(ctx).getInt("silNextIdx", 2)
        val zone = prefs.zone
        val isFriday = Instant.now().atZone(zone).dayOfWeek == DayOfWeek.FRIDAY && idx == 2
        val mins = if (isFriday) fridayMinutes(ctx) else minutes(ctx)
        val until = System.currentTimeMillis() + mins * 60_000L
        val e = sp(ctx).edit()
        if (activeUntil(ctx) == 0L) {
            // Kullanıcı zaten sessizdeyse dokunma ve sonra da geri açma
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) { schedule(ctx); return }
            e.putInt("silPrev", am.ringerMode)
        }
        var target = if (full(ctx)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_VIBRATE
        try { am.ringerMode = target } catch (ex: SecurityException) {
            target = AudioManager.RINGER_MODE_VIBRATE
            try { am.ringerMode = target } catch (ex2: Exception) { schedule(ctx); return }
        }
        e.putInt("silSet", target).putLong("silUntil", until).apply()
        setAlarm(ctx, until, pi(ctx, "end", 9))
        note(ctx, idx, until, target)
        schedule(ctx)
    }

    fun restore(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val prev = sp(ctx).getInt("silPrev", AudioManager.RINGER_MODE_NORMAL)
        val set = sp(ctx).getInt("silSet", -1)
        // Kullanıcı bu arada kendisi değiştirdiyse ona dokunma
        if (am.ringerMode == set) try { am.ringerMode = prev } catch (e: Exception) { }
        sp(ctx).edit().putLong("silUntil", 0L).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, "end", 9))
        NotificationManagerCompat.from(ctx).cancel(NOTE_ID)
    }

    private fun note(ctx: Context, idx: Int, until: Long, mode: Int) {
        val l = L(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, l.t("Namazda sessiz", "Stumm beim Gebet"), NotificationManager.IMPORTANCE_LOW))
        val f = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone(Prefs(ctx).zone) }
        val what = if (mode == AudioManager.RINGER_MODE_SILENT) l.t("sessize", "stumm") else l.t("titreşime", "auf Vibration")
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(l.t("🤫 ${l.names[idx]} namazı için $what alındı", "🤫 ${l.names[idx]}: Handy $what geschaltet"))
            .setContentText(l.t("Saat ${f.format(Date(until))}'te ses geri açılacak", "Ton wieder an um ${f.format(Date(until))}"))
            .setOngoing(true)
            .addAction(0, l.t("Sesi şimdi aç", "Ton jetzt an"), pi(ctx, "end", 10))
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        try { NotificationManagerCompat.from(ctx).notify(NOTE_ID, n) } catch (e: SecurityException) { }
    }
}

class SilentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "start" -> if (Silent.enabled(ctx)) Silent.start(ctx)
            "end" -> Silent.restore(ctx)
        }
    }
}
