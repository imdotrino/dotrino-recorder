package com.dotrino.recorder

import android.content.Context
import com.dotrino.sdk.ui.DotrinoLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Los textos de la app: los MISMOS de la PWA (src/i18n.js) cuando exista; hasta entonces
 * viven aquí, en assets/i18n.json. `t` funciona como allí: `{name}` se sustituye y una
 * variable que falta es un error, no un hueco en pantalla.
 */
object I18n {
    private var dict: Map<String, Map<String, String>>? = null
    private var lang = "es"

    fun load(context: Context) {
        if (dict == null) {
            val text = context.assets.open("i18n.json").use { it.readBytes().toString(Charsets.UTF_8) }
            dict = Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) ->
                v.jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
        }
        lang = DotrinoLocale.current(context)
    }

    val code get() = lang

    fun t(key: String, vararg vars: Pair<String, Any>): String {
        val s = dict?.get(lang)?.get(key) ?: throw IllegalStateException("missing i18n key: $key ($lang)")
        if (vars.isEmpty()) return s
        val m = vars.toMap()
        return Regex("\\{(\\w+)\\}").replace(s) { r ->
            (m[r.groupValues[1]] ?: throw IllegalStateException("missing i18n var \"${r.groupValues[1]}\" for $key")).toString()
        }
    }

    /** Con cantidad: `<key>_one` cuando n es 1 («1 ronda», no «1 rondas»). */
    fun tn(key: String, n: Int, vararg vars: Pair<String, Any>): String =
        t(if (n == 1) key + "_one" else key, *(if (vars.isEmpty()) arrayOf("n" to n) else vars))
}

fun t(key: String, vararg vars: Pair<String, Any>) = I18n.t(key, *vars)
fun tn(key: String, n: Int, vararg vars: Pair<String, Any>) = I18n.tn(key, n, *vars)
