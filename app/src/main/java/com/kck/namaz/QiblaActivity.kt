package com.kck.namaz

import android.content.Context
import android.graphics.*
import android.hardware.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.min

class QiblaActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sm: SensorManager
    private lateinit var compass: CompassView
    private lateinit var info: TextView
    private lateinit var l: L
    private var qibla = 0.0
    private var declination = 0f
    private var smoothAz = Float.NaN
    private val grav = FloatArray(3); private val mag = FloatArray(3)
    private var hasGrav = false; private var hasMag = false
    private var accuracyLow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        l = L(this)
        val c = Prefs(this).city
        qibla = Prefs(this).qibla()
        declination = GeomagneticField(c.lat.toFloat(), c.lng.toFloat(), 100f, System.currentTimeMillis()).declination

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C.BG); setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(tv(l.t("Kıble Pusulası", "Qibla-Kompass"), 24f, C.TEXT, true))
        root.addView(tv("${c.name} · " + l.t("Kıble açısı", "Qibla-Richtung") + ": ${Math.round(qibla)}°", 15f, C.MUTED),
            lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        compass = CompassView(this, qibla.toFloat())
        root.addView(compass, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(16) })
        info = tv("", 18f, C.TEXT, true).apply { gravity = Gravity.CENTER }
        root.addView(info, lp())
        root.addView(tv(l.t(
            "Telefonu düz tutun, metal ve mıknatıslardan uzak durun. Gerekirse telefonu havada 8 çizerek kalibre edin.",
            "Handy flach halten, fern von Metall und Magneten. Bei Bedarf das Handy in einer 8 bewegen (Kalibrierung)."
        ), 13f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(10) })
        setContentView(root)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI)
        else {
            val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (a == null || m == null) {
                info.text = l.t("Bu telefonda pusula sensörü yok.", "Dieses Handy hat keinen Kompass-Sensor.")
                return
            }
            sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, m, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() { super.onPause(); sm.unregisterListener(this) }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR)
            accuracyLow = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9)
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(r, e.values)
            Sensor.TYPE_ACCELEROMETER -> { System.arraycopy(e.values, 0, grav, 0, 3); hasGrav = true; return }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, mag, 0, 3); hasMag = true
                if (!hasGrav || !SensorManager.getRotationMatrix(r, null, grav, mag)) return
            }
            else -> return
        }
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)
        var az = (Math.toDegrees(o[0].toDouble()).toFloat() + declination + 360f) % 360f
        if (smoothAz.isNaN()) smoothAz = az
        else {
            var d = az - smoothAz
            if (d > 180) d -= 360f; if (d < -180) d += 360f
            smoothAz = (smoothAz + d * 0.15f + 360f) % 360f
        }
        az = smoothAz
        compass.azimuth = az
        var diff = (qibla.toFloat() - az + 540f) % 360f - 180f
        val ok = abs(diff) <= 4f
        compass.onTarget = ok
        compass.invalidate()
        info.setTextColor(if (ok) C.GOLD else C.TEXT)
        info.text = when {
            ok -> "✅ " + l.t("Kıble yönündesiniz", "Sie zeigen Richtung Qibla")
            diff > 0 -> l.t("Sağa dönün", "Nach rechts drehen") + " ${Math.round(diff)}° ↻"
            else -> l.t("Sola dönün", "Nach links drehen") + " ${Math.round(-diff)}° ↺"
        } + if (accuracyLow) "\n" + l.t("(Pusula kalibrasyonu gerekli)", "(Kompass kalibrieren)") else ""
    }
}

class CompassView(ctx: Context, private val qibla: Float) : View(ctx) {
    var azimuth = 0f
    var onTarget = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.9f
        // dış halka
        p.style = Paint.Style.FILL; p.color = C.CARD
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.02f; p.color = if (onTarget) C.GOLD else C.HI
        c.drawCircle(cx, cy, r, p)

        c.save()
        c.rotate(-azimuth, cx, cy)
        // derece çizgileri
        p.color = C.MUTED
        for (d in 0 until 360 step 10) {
            c.save(); c.rotate(d.toFloat(), cx, cy)
            p.strokeWidth = if (d % 90 == 0) r * 0.02f else r * 0.008f
            c.drawLine(cx, cy - r, cx, cy - r * (if (d % 30 == 0) 0.88f else 0.93f), p)
            c.restore()
        }
        txt.textSize = r * 0.12f
        val dirs = arrayOf("N", "E", "S", "W")
        for (k in 0 until 4) {
            c.save(); c.rotate(k * 90f, cx, cy)
            txt.color = if (k == 0) 0xFFE5534B.toInt() else C.TEXT
            c.drawText(dirs[k], cx, cy - r * 0.7f, txt)
            c.restore()
        }
        // kıble oku
        c.save(); c.rotate(qibla, cx, cy)
        p.style = Paint.Style.FILL; p.color = C.GOLD
        path.reset()
        path.moveTo(cx, cy - r * 0.62f)
        path.lineTo(cx - r * 0.09f, cy - r * 0.38f)
        path.lineTo(cx + r * 0.09f, cy - r * 0.38f)
        path.close()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.035f
        c.drawLine(cx, cy, cx, cy - r * 0.4f, p)
        // Kâbe simgesi
        p.style = Paint.Style.FILL; p.color = 0xFF111111.toInt()
        val s = r * 0.08f
        c.drawRect(cx - s, cy - r * 0.62f - s * 2.6f, cx + s, cy - r * 0.62f - s * 0.6f, p)
        p.color = C.GOLD
        c.drawRect(cx - s, cy - r * 0.62f - s * 2.1f, cx + s, cy - r * 0.62f - s * 1.8f, p)
        c.restore()
        c.restore()

        // telefonun baktığı yön (sabit üst işaret)
        p.style = Paint.Style.FILL; p.color = C.TEXT
        path.reset()
        path.moveTo(cx, cy - r - r * 0.02f)
        path.lineTo(cx - r * 0.05f, cy - r - r * 0.12f)
        path.lineTo(cx + r * 0.05f, cy - r - r * 0.12f)
        path.close()
        c.drawPath(path, p)
        p.color = C.GOLD
        c.drawCircle(cx, cy, r * 0.05f, p)
    }
}
