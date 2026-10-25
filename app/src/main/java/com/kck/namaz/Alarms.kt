package com.kck.namaz

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

object Scheduler {
    const val CH_VAKIT = "vakit"
    const val CH_EZAN = "ezan"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val l = L(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CH_VAKIT, l.t("Vakit bildirimleri", "Gebetszeit-Hinweise"), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EZAN, l.t("Ezan", "Adhan"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    /** Sıradaki etkin vakit için alarm kurar. */
    fun scheduleNext(ctx: Context) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val before = prefs.before * 60_000L
        var found: Triple<Long, Int, Long>? = null
        val today = LocalDate.now(prefs.zone)
        loop@ for (d in 0..2) {
            val times = prefs.times(today.plusDays(d.toLong()))
            val events = ArrayList<Triple<Long, Int, Long>>()
            for (i in 0 until PrayerCalc.COUNT) {
                if (!prefs.notify(i)) continue
                if (before > 0) events.add(Triple(times[i] - before, i, times[i]))
                events.add(Triple(times[i], i, times[i]))
            }
            events.sortBy { it.first }
            for (e in events) if (e.first > now + 1000) { found = e; break@loop }
        }
        val intent = Intent(ctx, AlarmReceiver::class.java)
        val pi0 = PendingIntent.getBroadcast(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi0)
        val f = found ?: return
        intent.putExtra("idx", f.second).putExtra("time", f.third)
        val pi = PendingIntent.getBroadcast(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.first, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.first, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f.first, pi)
        }
    }

    fun openAppIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun message(ctx: Context, idx: Int, time: Long): Pair<String, String> {
        val l = L(ctx)
        val name = l.names[idx]
        val hm = SimpleDateFormat("HH:mm", Locale.GERMANY).apply {
            timeZone = java.util.TimeZone.getTimeZone(Prefs(ctx).zone)
        }.format(Date(time))
        val mins = ((time - System.currentTimeMillis()) / 60_000L).toInt()
        val title = if (mins >= 2) l.t("$name vaktine $mins dk kaldı", "Noch $mins Min. bis $name")
        else if (idx == 1) l.t("Güneş doğdu", "Sonnenaufgang")
        else l.t("$name vakti girdi", "Zeit für $name")
        return Pair(title, "$hm · ${Prefs(ctx).city.name}")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.channels(ctx)
        val idx = intent.getIntExtra("idx", -1)
        val time = intent.getLongExtra("time", 0L)
        val prefs = Prefs(ctx)
        if (idx in 0 until PrayerCalc.COUNT) {
            val isOnTime = time - System.currentTimeMillis() < 90_000L
            if (prefs.adhan && prefs.adhanUri != null && idx != 1 && isOnTime) {
                val s = Intent(ctx, AdhanService::class.java).putExtra("idx", idx).putExtra("time", time)
                try { ctx.startForegroundService(s) } catch (e: Exception) { show(ctx, idx, time) }
            } else show(ctx, idx, time)
        }
        Scheduler.scheduleNext(ctx)
    }

    private fun show(ctx: Context, idx: Int, time: Long) {
        val (title, text) = Scheduler.message(ctx, idx, time)
        val n = NotificationCompat.Builder(ctx, Scheduler.CH_VAKIT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(Scheduler.openAppIntent(ctx))
            .setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(100 + idx, n) } catch (e: SecurityException) { }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.channels(ctx)
        Scheduler.scheduleNext(ctx)
    }
}

/** Ezan sesini çalan ön plan servisi. */
class AdhanService : Service() {
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopAll(); return START_NOT_STICKY }
        Scheduler.channels(this)
        val idx = intent?.getIntExtra("idx", 2) ?: 2
        val time = intent?.getLongExtra("time", System.currentTimeMillis()) ?: System.currentTimeMillis()
        val l = L(this)
        val (title, text) = if (idx < 0) Pair(l.t("Ezan deneme", "Adhan-Test"), "") else Scheduler.message(this, idx, time)
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, AdhanService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Scheduler.CH_EZAN)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(Scheduler.openAppIntent(this))
            .setDeleteIntent(stopPi)
            .addAction(0, l.t("Ezanı durdur", "Adhan stoppen"), stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(200, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(200, n)

        val uri = Prefs(this).adhanUri
        if (uri == null) { stopAll(); return START_NOT_STICKY }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                setDataSource(this@AdhanService, Uri.parse(uri))
                setOnCompletionListener { stopPlayingKeepNote() }
                setOnErrorListener { _, _, _ -> stopPlayingKeepNote(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopPlayingKeepNote()
        }
        return START_NOT_STICKY
    }

    /** Ses bitince bildirimi bırakıp servisi kapatır. */
    private fun stopPlayingKeepNote() {
        player?.release(); player = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopAll() {
        player?.release(); player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
