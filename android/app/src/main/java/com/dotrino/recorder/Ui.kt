package com.dotrino.recorder

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dotrino.sdk.R as SR

// Piezas de pantalla pequeñas y comunes a toda la app: vistas nativas simples, sin AppCompat
// ni Material (arranque en frío y tamaño, CONVENCIONES §16.2).

fun Context.px(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()
fun Context.col(id: Int) = getColor(id)

fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    cornerRadius = radius.toFloat(); setColor(color)
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

/** Un botón plano de la app. `filled` = el principal (acento). */
fun Context.button(text: String, filled: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTypeface(typeface, Typeface.BOLD)
    stateListAnimator = null
    val bg = when {
        danger -> col(R.color.rec_red)
        filled -> col(SR.color.dotrino_accent)
        else -> col(R.color.rec_surface2)
    }
    setTextColor(if (filled) col(SR.color.dotrino_bg) else col(SR.color.dotrino_fg))
    background = rounded(bg, px(10))
    setPadding(px(16), px(10), px(16), px(10))
    setOnClickListener { onClick() }
}

/**
 * Una hoja que sube desde abajo, a lo ancho, con título y ✕: la forma de los modales de la PWA
 * en un teléfono. Devuelve el diálogo y la columna donde va el contenido.
 */
fun Activity.sheet(title: String): Pair<Dialog, LinearLayout> {
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(4), px(20), px(24)) }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), px(14), px(8), px(6))
        addView(label(title.uppercase(), 16f, col(SR.color.dotrino_fg), bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@sheet).apply {
            text = "✕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(col(SR.color.dotrino_fg))
            setPadding(px(12), px(4), px(12), px(4))
            contentDescription = t("close")
            setOnClickListener { dialog.dismiss() }
        })
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            val r = px(20).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(col(SR.color.dotrino_card))
        }
        addView(head)
        addView(ScrollView(this@sheet).apply { addView(body) })
    }
    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawable(GradientDrawable().apply { setColor(0) })
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setWindowAnimations(android.R.style.Animation_InputMethod)
    }
    return dialog to body
}

/** Confirmación propia (título, texto, aceptar/cancelar). */
fun Activity.ask(title: String, text: String, ok: String, danger: Boolean = false, cancel: String = t("cancel"), onYes: () -> Unit) {
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(22), px(20), px(22), px(16))
        background = rounded(col(SR.color.dotrino_card), px(14))
        addView(label(title, 18f, col(SR.color.dotrino_fg), bold = true))
        if (text.isNotEmpty()) addView(label(text, 15f, col(SR.color.dotrino_muted)).apply { setPadding(0, px(8), 0, px(8)) })
        addView(LinearLayout(this@ask).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(12), 0, 0)
            addView(button(cancel) { dialog.dismiss() }.apply { tag = "dialog-cancel" })
            addView(button(ok, filled = !danger, danger = danger) { dialog.dismiss(); onYes() }.apply { tag = "dialog-ok" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        })
    }
    dialog.setContentView(box)
    dialog.window?.apply {
        setBackgroundDrawable(GradientDrawable().apply { setColor(0) })
        setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}

fun Activity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

/** Un grupo de opciones excluyentes (el `.seg` de la PWA). */
fun <T> Context.segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((v, text) in options) {
            val on = v == selected
            addView(TextView(this@segmented).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (on) col(SR.color.dotrino_bg) else col(SR.color.dotrino_fg))
                background = rounded(if (on) col(SR.color.dotrino_accent) else col(SR.color.dotrino_bg), px(8), px(1), col(SR.color.dotrino_muted))
                setPadding(px(6), px(10), px(6), px(10))
                isSelected = on
                setOnClickListener { onPick(v) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(6) })
        }
    }
