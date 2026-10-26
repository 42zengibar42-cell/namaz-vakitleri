package com.kck.namaz

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Zikirmatik: dokundukça sayar, hedefe gelince uzun titrer. Ses tuşlarıyla da sayılabilir. */
class ZikirActivity : AppCompatActivity() {
    data class Zikir(val tr: String, val de: String, val target: Int)

    private val list = listOf(
        Zikir("Sübhânallah", "Subhanallah", 33),
        Zikir("Elhamdülillah", "Alhamdulillah", 33),
        Zikir("Allâhu Ekber", "Allahu Akbar", 33),
        Zikir("Lâ ilâhe illallah", "La ilaha illallah", 100),
        Zikir("Estağfirullah", "Astaghfirullah", 100),
        Zikir("Salavat", "Salawat", 100),
        Zikir("Serbest", "Frei", 0),
    )

    private lateinit var l: L
    private lateinit var ring: RingView
    private lateinit var countTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var tabs: LinearLayout
    private var sel = 0
    private val sp by lazy { getSharedPreferences("zikir", Context.MODE_PRIVATE) }

    private fun count(i: Int) = sp.getInt("c$i", 0)
    private fun target(i: Int) = sp.getInt("t$i", list[i].target)
    private fun total(i: Int) = sp.getInt("all$i", 0)
    private fun vibOn() = sp.getBoolean("vib", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        l = L(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sel = sp.getInt("sel", 0).coerceIn(0, list.size - 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG)
            setPadding(dp(12), dp(18), dp(12), dp(14))
        }
        root.addView(tv("📿 " + l.t("Zikirmatik", "Zikr-Zähler"), 22f, C.TEXT, true).apply { setPadding(dp(4), 0, 0, 0) })

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        root.addView(hs, lp().apply { topMargin = dp(10) })

        ring = RingView(this)
        countTv = tv("0", 64f, C.GOLD, true).apply { gravity = Gravity.CENTER }
        infoTv = tv("", 15f, C.MUTED).apply { gravity = Gravity.CENTER }
        val tapArea = android.widget.FrameLayout(this).apply {
            addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
            val inner = LinearLayout(this@ZikirActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(countTv); addView(infoTv)
            }
            addView(inner, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) { tap(); v.performClick() }
                true
            }
        }
        root.addView(tapArea, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(10) })

        root.addView(tv(l.t("Ekrana dokunun veya ses tuşlarına basın", "Bildschirm antippen oder Lautstärketasten drücken"), 12f, C.MUTED).apply {
            gravity = Gravity.CENTER }, lp())

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(ghostButton("↺ " + l.t("Sıfırla", "Zurücksetzen")) { reset() },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        bar.addView(ghostButton("− 1") { undo() },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        bar.addView(ghostButton("🎯 " + l.t("Hedef", "Ziel")) { chooseTarget() },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        val vibBtn = ghostButton(if (vibOn()) "📳" else "🔇") {}
        vibBtn.setOnClickListener { sp.edit().putBoolean("vib", !vibOn()).apply(); vibBtn.text = if (vibOn()) "📳" else "🔇" }
        bar.addView(vibBtn, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f).apply { leftMargin = dp(4) })
        root.addView(bar, lp().apply { topMargin = dp(10) })

        setContentView(root)
        buildTabs(); refresh()
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        list.forEachIndexed { i, z ->
            val name = if (l.tr) z.tr else z.de
            val b = if (i == sel) button(name) {} else ghostButton(name) { sel = i; sp.edit().putInt("sel", i).apply(); buildTabs(); refresh() }
            b.textSize = 13f; b.setPadding(dp(12), dp(8), dp(12), dp(8))
            tabs.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
    }

    private fun refresh() {
        val c = count(sel); val t = target(sel)
        countTv.text = "$c"
        val rounds = if (t > 0) c / t else 0
        val parts = ArrayList<String>()
        if (t > 0) parts.add(l.t("Hedef $t", "Ziel $t") + if (rounds > 0) l.t(" · $rounds tur", " · $rounds Runden") else "")
        parts.add(l.t("Toplam ${total(sel)}", "Gesamt ${total(sel)}"))
        infoTv.text = parts.joinToString("\n")
        ring.progress = if (t > 0) (c % t).toFloat() / t else 0f
        ring.done = t > 0 && c > 0 && c % t == 0
        ring.invalidate()
    }

    private fun vibrate(ms: Long, strong: Boolean) {
        if (!vibOn()) return
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try {
            if (strong) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 220), -1))
            else v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    private fun tap() {
        val c = count(sel) + 1
        sp.edit().putInt("c$sel", c).putInt("all$sel", total(sel) + 1).apply()
        val t = target(sel)
        if (t > 0 && c % t == 0) vibrate(0, true) else vibrate(25, false)
        refresh()
    }

    private fun undo() {
        val c = count(sel)
        if (c <= 0) return
        sp.edit().putInt("c$sel", c - 1).putInt("all$sel", maxOf(0, total(sel) - 1)).apply()
        refresh()
    }

    private fun reset() {
        AlertDialog.Builder(this).setMessage(l.t("Sayaç sıfırlansın mı?", "Zähler zurücksetzen?"))
            .setPositiveButton(l.t("Sıfırla", "Ja")) { _, _ -> sp.edit().putInt("c$sel", 0).apply(); refresh() }
            .setNegativeButton(l.t("Vazgeç", "Abbrechen"), null).show()
    }

    private fun chooseTarget() {
        val opts = intArrayOf(33, 99, 100, 500, 1000, 0, -1)
        val labels = opts.map {
            when (it) { 0 -> l.t("Hedefsiz", "Ohne Ziel"); -1 -> l.t("Başka sayı…", "Andere Zahl…"); else -> "$it" }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(l.t("Hedef", "Ziel")).setItems(labels) { _, w ->
            if (opts[w] == -1) {
                val e = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setView(e).setPositiveButton("OK") { _, _ ->
                    e.text.toString().toIntOrNull()?.takeIf { it in 1..100000 }?.let { sp.edit().putInt("t$sel", it).apply(); refresh() }
                }.show()
            } else { sp.edit().putInt("t$sel", opts[w]).apply(); refresh() }
        }.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) tap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    class RingView(ctx: Context) : View(ctx) {
        var progress = 0f
        var done = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f * 0.88f
            val cx = width / 2f; val cy = height / 2f
            fill.color = C.CARD
            c.drawCircle(cx, cy, r, fill)
            p.strokeWidth = r * 0.07f
            p.color = C.HI
            c.drawCircle(cx, cy, r, p)
            p.color = C.GOLD
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, -90f, if (done) 360f else 360f * progress, false, p)
        }
    }
}
