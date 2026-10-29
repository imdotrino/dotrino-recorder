package com.dotrino.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.dotrino.sdk.R as SR

/**
 * El medidor de nivel: una barra por canal, de -60 a 0 dBFS, con la marca del pico que se
 * queda un momento. Verde hasta -12, ámbar hasta -3, rojo después (cerca de saturar).
 */
class MeterView(ctx: Context) : View(ctx) {
    private val floor = -60f
    private var levels = floatArrayOf(floor, floor)
    private var holds = floatArrayOf(floor, floor)
    private var holdAt = longArrayOf(0, 0)
    var channels = 2
        set(v) { field = v; invalidate() }

    private val track = Paint().apply { color = ctx.getColor(R.color.rec_surface2) }
    private val green = Paint().apply { color = ctx.getColor(R.color.rec_green) }
    private val amber = Paint().apply { color = ctx.getColor(R.color.rec_amber) }
    private val red = Paint().apply { color = ctx.getColor(R.color.rec_red) }
    private val tick = Paint().apply { color = ctx.getColor(SR.color.dotrino_muted); textSize = ctx.px(10).toFloat(); isAntiAlias = true }
    private val r = RectF()

    fun set(db: FloatArray) {
        val now = System.currentTimeMillis()
        for (c in 0..1) {
            // Sube al instante y baja suave, para que se pueda leer.
            levels[c] = if (db[c] >= levels[c]) db[c] else maxOf(db[c], levels[c] - 1.5f)
            if (db[c] >= holds[c] || now - holdAt[c] > 1500) { holds[c] = db[c]; holdAt[c] = now }
        }
        invalidate()
    }

    private fun x(db: Float, w: Float) = ((db.coerceIn(floor, 0f) - floor) / -floor) * w

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val labelH = context.px(14).toFloat()
        val gap = context.px(6).toFloat()
        val barH = (height - labelH - gap * (channels - 1)) / channels
        val rad = context.px(4).toFloat()
        for (ch in 0 until channels) {
            val top = ch * (barH + gap)
            r.set(0f, top, w, top + barH); c.drawRoundRect(r, rad, rad, track)
            val lv = x(levels[ch], w)
            val g = x(-12f, w); val a = x(-3f, w)
            if (lv > 0) { r.set(0f, top, minOf(lv, g), top + barH); c.drawRect(r, green) }
            if (lv > g) { r.set(g, top, minOf(lv, a), top + barH); c.drawRect(r, amber) }
            if (lv > a) { r.set(a, top, lv, top + barH); c.drawRect(r, red) }
            val hx = x(holds[ch], w)
            if (hx > 0) {
                val p = if (holds[ch] > -3f) red else if (holds[ch] > -12f) amber else green
                r.set(hx - context.px(2), top, hx, top + barH); c.drawRect(r, p)
            }
        }
        val base = height.toFloat() - context.px(2)
        for (db in listOf(-60, -48, -36, -24, -12, -6, -3, 0)) {
            val s = db.toString()
            val tx = (x(db.toFloat(), w) - tick.measureText(s) / 2).coerceIn(0f, w - tick.measureText(s))
            c.drawText(s, tx, base, tick)
        }
    }
}
