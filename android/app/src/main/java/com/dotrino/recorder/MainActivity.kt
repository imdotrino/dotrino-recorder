package com.dotrino.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.dotrino.sdk.R as SR
import com.dotrino.sdk.ui.DotrinoLocale
import com.dotrino.sdk.ui.DotrinoTopbar
import java.util.Locale

/**
 * La app es UNA pantalla administrativa (CONVENCIONES §5.1): el nivel, los botones de grabar y
 * la lista de grabaciones. Cómo se graba (formato, micrófono) va en la hoja de Ajustes.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_MIC = 1
        private const val REQ_SAVE = 2
        private const val PENDING_KEY = "pending-action"
    }

    private lateinit var lib: Library
    private var settings = Settings()
    private val main = Handler(Looper.getMainLooper())
    private var off: (() -> Unit)? = null
    private var lastState: Engine.State? = null

    private lateinit var timeView: TextView
    private lateinit var modeView: TextView
    private lateinit var meter: MeterView
    private lateinit var clipView: TextView
    private lateinit var summary: TextView
    private lateinit var gainLabel: TextView
    private lateinit var gainBar: SeekBar
    private lateinit var controls: LinearLayout
    private lateinit var list: LinearLayout

    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private val positions = mutableMapOf<String, TextView>()
    /** Lo que se pidió antes de tener el micrófono: "monitor" o "record". */
    private var pending: String? = null
    private var saving: Library.Recording? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(DotrinoLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        lib = Library(this)
        settings = Settings.load(this)
        pending = savedInstanceState?.getString(PENDING_KEY)
        setContentView(layout())
        renderSettings()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        pending?.let { out.putString(PENDING_KEY, it) }
    }

    override fun onStart() {
        super.onStart()
        if (Engine.state.mode == Engine.Mode.IDLE) lib.recoverOrphans(null)
        renderList()
        off = Engine.listen { onState(it) }
    }

    override fun onStop() {
        off?.invoke(); off = null
        // Probar el nivel es solo con la pantalla delante: el micrófono no se queda abierto.
        if (Engine.state.mode == Engine.Mode.MONITOR) Engine.stop()
        stopPlayback()
        super.onStop()
    }

    // ---------- pantalla ----------

    private fun layout(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(24)) }
        timeView = label("0:00:00", 52f, col(SR.color.dotrino_fg), bold = true).apply {
            gravity = Gravity.CENTER; tag = "time"
            fontFeatureSettings = "tnum"   // cifras del mismo ancho: el reloj no baila
        }
        modeView = label("", 14f, col(SR.color.dotrino_muted), bold = true).apply { gravity = Gravity.CENTER; tag = "mode" }
        meter = MeterView(this).apply { tag = "meter" }
        clipView = label(t("clipped"), 13f, col(R.color.rec_red), bold = true).apply {
            visibility = View.INVISIBLE; tag = "clip"
            setPadding(0, px(4), 0, px(4))
            setOnClickListener { Engine.clearClip() }
        }
        gainLabel = label("", 14f, col(SR.color.dotrino_fg), bold = true)
        gainBar = SeekBar(this).apply {
            max = Settings.GAIN_MAX - Settings.GAIN_MIN
            tag = "gain"
            contentDescription = t("gain")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings = settings.copy(gainDb = p + Settings.GAIN_MIN)
                    Engine.setGain(settings.gainDb)
                    renderGain()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { Settings.save(this@MainActivity, settings) }
            })
        }
        summary = label("", 13f, col(SR.color.dotrino_muted)).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6)); tag = "summary" }
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(18)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "list" }

        page.addView(timeView)
        page.addView(modeView)
        page.addView(meter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60)).apply { topMargin = px(16) })
        page.addView(clipView)
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(gainLabel, LinearLayout.LayoutParams(px(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(gainBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        page.addView(summary)
        page.addView(controls)
        page.addView(label(t("recordings").uppercase(), 13f, col(SR.color.dotrino_muted), bold = true).apply { setPadding(0, 0, 0, px(8)) })
        page.addView(list)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(SR.color.dotrino_bg))
            fitsSystemWindows = true
            addView(topbar())
            addView(ScrollView(this@MainActivity).apply { addView(page); isFillViewport = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun topbar(): View {
        val settingsBtn = label(t("settings").uppercase(), 14f, col(SR.color.dotrino_muted), bold = true).apply {
            setPadding(px(8), px(10), px(8), px(10))
            tag = "settings-btn"
            setOnClickListener { openSettings() }
        }
        return DotrinoTopbar(
            this,
            repo = "imdotrino/dotrino-recorder",
            brand = DotrinoTopbar.Brand(t("appName"), R.drawable.recorder_brand),
            actions = listOf(settingsBtn),
        ) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dotrino.com/"))) }.view
    }

    private fun onState(s: Engine.State) {
        val prev = lastState
        lastState = s
        timeView.text = clock(s.elapsedMs)
        modeView.text = when (s.mode) {
            Engine.Mode.IDLE -> t("modeIdle")
            Engine.Mode.MONITOR -> t("modeMonitor")
            Engine.Mode.RECORDING -> t("modeRecording", "size" to size(s.bytes))
            Engine.Mode.PAUSED -> t("modePaused", "size" to size(s.bytes))
        }
        modeView.setTextColor(col(if (s.mode == Engine.Mode.RECORDING) R.color.rec_red else SR.color.dotrino_muted))
        meter.channels = settings.channels
        meter.set(s.peakDb)
        clipView.visibility = if (s.clipped) View.VISIBLE else View.INVISIBLE
        if (prev?.mode != s.mode) {
            renderControls(s.mode)
            renderSettings()
            val active = s.mode == Engine.Mode.RECORDING || s.mode == Engine.Mode.PAUSED
            if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (s.mode == Engine.Mode.IDLE && prev != null) renderList()
        }
        if (s.error != null && prev?.error != s.error) toast(errorText(s.error))
    }

    private fun errorText(code: String): String {
        val key = code.substringBefore(':')
        val detail = code.substringAfter(':', "")
        return t("err.$key", "detail" to detail)
    }

    private fun renderControls(mode: Engine.Mode) {
        controls.removeAllViews()
        fun add(v: View) = controls.addView(v, LinearLayout.LayoutParams(0, px(56), 1f).apply { marginStart = px(4); marginEnd = px(4) })
        when (mode) {
            Engine.Mode.IDLE, Engine.Mode.MONITOR -> {
                add(button(t(if (mode == Engine.Mode.MONITOR) "monitorStop" else "monitor")) { toggleMonitor() }.apply { tag = "monitor-btn" })
                add(button("● " + t("record"), danger = true) { withMic("record") }.apply { tag = "record-btn" })
            }
            Engine.Mode.RECORDING, Engine.Mode.PAUSED -> {
                val paused = mode == Engine.Mode.PAUSED
                add(button(t(if (paused) "resume" else "pause")) {
                    RecorderService.send(this, if (paused) RecorderService.RESUME else RecorderService.PAUSE)
                }.apply { tag = "pause-btn" })
                add(button("■ " + t("stop"), filled = true) { RecorderService.send(this, RecorderService.STOP) }.apply { tag = "stop-btn" })
            }
        }
    }

    private fun renderGain() {
        gainBar.progress = settings.gainDb - Settings.GAIN_MIN
        gainLabel.text = t("gainValue", "db" to (if (settings.gainDb > 0) "+" else "") + settings.gainDb)
    }

    private fun renderSettings() {
        renderGain()
        val free = Library.dir(this).usableSpace / settings.bytesPerSecond
        summary.text = describe(settings.format.name, settings.sampleRate, settings.channels) + " · " + sourceName(settings.source) +
            "\n" + t("freeSpace", "time" to longTime(free))
    }

    // ---------- grabar ----------

    private fun toggleMonitor() {
        if (Engine.state.mode == Engine.Mode.MONITOR) Engine.stop() else withMic("monitor")
    }

    private fun withMic(action: String) {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        // El aviso de «grabando» en la barra: se pide con la primera grabación, y si se niega se graba igual.
        if (action == "record" && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.contains(Manifest.permission.RECORD_AUDIO)) {
            pending = action
            requestPermissions(need.toTypedArray(), REQ_MIC)
            return
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 0)
        run(action)
    }

    private fun run(action: String) = when (action) {
        "monitor" -> Engine.monitor(this, settings)
        "record" -> RecorderService.start(this)
        else -> error("unknown action: $action")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_MIC) return
        val action = pending ?: return
        pending = null
        val i = perms.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0 && results.getOrNull(i) == PackageManager.PERMISSION_GRANTED) run(action) else toast(t("micDenied"))
    }

    // ---------- ajustes ----------

    private fun openSettings() {
        val (dialog, body) = sheet(t("settings"))
        fun paint() {
            body.removeAllViews()
            val busy = Engine.state.mode == Engine.Mode.RECORDING || Engine.state.mode == Engine.Mode.PAUSED
            if (busy) body.addView(label(t("settingsBusy"), 14f, col(R.color.rec_amber)).apply { setPadding(0, 0, 0, px(10)) })
            fun section(title: String, v: View) {
                body.addView(label(title, 13f, col(SR.color.dotrino_muted), bold = true).apply { setPadding(0, px(12), 0, px(6)) })
                v.isEnabled = !busy
                if (busy) { v.alpha = 0.5f; disableTree(v) }
                body.addView(v)
            }
            fun change(s: Settings) {
                settings = s
                Settings.save(this, s)
                // Probando el nivel, se vuelve a abrir el micrófono con lo nuevo.
                if (Engine.state.mode == Engine.Mode.MONITOR) Engine.monitor(this, s)
                renderSettings(); paint()
            }
            section(t("format"), segmented(listOf(
                Settings.Format.WAV24 to "WAV 24 bit",
                Settings.Format.WAV16 to "WAV 16 bit",
                Settings.Format.AAC to "AAC 256k",
            ), settings.format) { change(settings.copy(format = it)) })
            body.addView(label(t("formatHint." + settings.format.name), 13f, col(SR.color.dotrino_muted)).apply { setPadding(0, px(6), 0, 0) })
            section(t("rate"), segmented(Settings.RATES.map { it to "%.1f kHz".format(Locale.ROOT, it / 1000.0).replace(".0 ", " ") }, settings.sampleRate) { change(settings.copy(sampleRate = it)) })
            section(t("channels"), segmented(listOf(1 to t("mono"), 2 to t("stereo")), settings.channels) { change(settings.copy(channels = it)) })
            val sources = buildList {
                if (Settings.unprocessedSupported(this@MainActivity)) add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.MIC)
                add(MediaRecorder.AudioSource.CAMCORDER)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
            section(t("source"), choices(sources.map { it to sourceName(it) }, settings.source) { change(settings.copy(source = it)) })
            val devices = Engine.inputs(this)
            section(t("device"), choices(listOf(0 to t("devAuto")) + devices.map { it.id to deviceName(it) }, settings.deviceId) { change(settings.copy(deviceId = it)) })
            if (settings.deviceId != 0 && devices.none { it.id == settings.deviceId })
                body.addView(label(t("deviceGone"), 13f, col(R.color.rec_amber)).apply { setPadding(0, px(6), 0, 0) })
        }
        paint()
        dialog.show()
    }

    private fun disableTree(v: View) {
        v.isEnabled = false; v.isClickable = false
        if (v is ViewGroup) for (i in 0 until v.childCount) disableTree(v.getChildAt(i))
    }

    /** Una lista vertical de opciones excluyentes (para las que no caben en una fila). */
    private fun <T> choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for ((v, text) in options) {
            val on = v == selected
            addView(label((if (on) "● " else "○ ") + text, 15f, col(if (on) SR.color.dotrino_fg else SR.color.dotrino_muted), bold = on).apply {
                setPadding(px(4), px(10), px(4), px(10))
                isSelected = on
                setOnClickListener { onPick(v) }
            })
        }
    }

    private fun sourceName(src: Int) = t(when (src) {
        MediaRecorder.AudioSource.UNPROCESSED -> "srcUnprocessed"
        MediaRecorder.AudioSource.MIC -> "srcMic"
        MediaRecorder.AudioSource.CAMCORDER -> "srcCamcorder"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "srcVoice"
        else -> error("unknown audio source: $src")
    })

    private fun deviceName(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> t("devBuiltin")
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> t("devUsb")
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> t("devWired")
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> t("devBluetooth")
            else -> t("devOther")
        }
        val extra = listOf(d.productName?.toString(), d.address).filter { !it.isNullOrBlank() }.distinct().joinToString(" · ")
        return if (extra.isEmpty()) kind else "$kind ($extra)"
    }

    // ---------- grabaciones ----------

    private fun renderList() {
        list.removeAllViews()
        positions.clear()
        val all = lib.list()
        if (all.isEmpty()) {
            list.addView(label(t("empty"), 14f, col(SR.color.dotrino_muted)).apply { setPadding(0, px(8), 0, px(8)) })
            return
        }
        for (r in all) list.addView(row(r))
    }

    private fun row(r: Library.Recording): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(col(SR.color.dotrino_card), px(12))
        setPadding(px(14), px(12), px(14), px(8))
        tag = "rec-${r.id}"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(10) }
        addView(label(r.name, 16f, col(SR.color.dotrino_fg), bold = true))
        val dur = if (r.durationMs >= 0) clock(r.durationMs) else t("unknownLength")
        val meta = listOf(dur, size(r.bytes), describe(r.format, r.sampleRate, r.channels)).joinToString(" · ") +
            if (r.recovered) " · " + t("recovered") else ""
        addView(label(meta, 13f, col(SR.color.dotrino_muted)).apply { setPadding(0, px(2), 0, px(6)) })
        val pos = label("", 13f, col(SR.color.dotrino_accent), bold = true)
        positions[r.id] = pos
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            fun act(text: String, id: String, run: () -> Unit) = addView(label(text, 14f, col(SR.color.dotrino_accent), bold = true).apply {
                setPadding(px(4), px(8), px(14), px(8)); tag = "$id-${r.id}"; setOnClickListener { run() }
            })
            act(if (playingId == r.id) t("stopPlay") else t("play"), "play") { togglePlay(r) }
            act(t("share"), "share") { share(r) }
            act(t("saveCopy"), "save") { saveCopy(r) }
            act(t("more"), "more") { more(r) }
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pos)
        })
    }

    private fun togglePlay(r: Library.Recording) {
        if (playingId == r.id) { stopPlayback(); renderList(); return }
        stopPlayback()
        val f = lib.file(r)
        if (!f.exists()) return toast(t("fileMissing"))
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { stopPlayback(); renderList() }
                prepare(); start()
            }
            playingId = r.id
            renderList()
            tickPlayback()
        } catch (e: Exception) {
            Log.e("recorder", "could not play ${f.name}", e)
            stopPlayback()
            toast(t("playFailed"))
        }
    }

    private fun tickPlayback() {
        val p = player ?: return
        val id = playingId ?: return
        positions[id]?.text = clock(p.currentPosition.toLong())
        main.postDelayed({ tickPlayback() }, 250)
    }

    private fun stopPlayback() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playingId = null
    }

    private fun share(r: Library.Recording) {
        val f = lib.file(r)
        if (!f.exists()) return toast(t("fileMissing"))
        val uri = RecordingProvider.uri(f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
            .setType(RecordingProvider.mime(f))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, r.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("share")))
    }

    /** Una copia donde el usuario elija (Descargas, una carpeta, una nube): el sistema pregunta. */
    @Suppress("DEPRECATION")
    private fun saveCopy(r: Library.Recording) {
        val f = lib.file(r)
        if (!f.exists()) return toast(t("fileMissing"))
        saving = r
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(RecordingProvider.mime(f))
            .putExtra(Intent.EXTRA_TITLE, safeName(r.name) + "." + f.extension), REQ_SAVE)
    }

    @Deprecated("Activity without AndroidX")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, result, data)
        if (code != REQ_SAVE) return
        val r = saving ?: return
        saving = null
        val dest = data?.data
        if (result != RESULT_OK || dest == null) return
        val src = lib.file(r)
        Thread({
            val ok = try {
                val out = contentResolver.openOutputStream(dest) ?: throw java.io.IOException("no output stream for $dest")
                out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
                true
            } catch (e: Exception) {
                Log.e("recorder", "could not save a copy of ${src.name}", e)
                false
            }
            main.post { toast(t(if (ok) "saved" else "saveFailed")) }
        }, "recorder-save").start()
    }

    private fun more(r: Library.Recording) {
        val (dialog, body) = sheet(r.name)
        val input = EditText(this).apply {
            setText(r.name); setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(col(SR.color.dotrino_fg)); tag = "rename-input"
        }
        body.addView(label(t("rename"), 13f, col(SR.color.dotrino_muted), bold = true))
        body.addView(input)
        body.addView(button(t("saveName"), filled = true) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@button toast(t("nameEmpty"))
            lib.rename(r, name); dialog.dismiss(); renderList()
        }.apply { tag = "rename-ok" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        body.addView(button(t("delete"), danger = true) {
            dialog.dismiss()
            ask(t("deleteTitle"), t("deleteText", "name" to r.name), t("delete"), danger = true) {
                if (playingId == r.id) stopPlayback()
                try { lib.delete(r) } catch (e: java.io.IOException) { Log.e("recorder", "delete failed", e); toast(t("deleteFailed")) }
                renderList()
            }
        }.apply { tag = "delete-btn" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })
        dialog.show()
    }

    // ---------- formatos ----------

    private fun describe(format: String, rate: Int, ch: Int): String {
        val f = when (format) { "WAV24" -> "WAV 24 bit"; "WAV16" -> "WAV 16 bit"; "AAC" -> "AAC 256 kbps"; else -> format }
        val r = if (rate > 0) " · " + "%.1f kHz".format(Locale.ROOT, rate / 1000.0).replace(".0 ", " ") else ""
        val c = when (ch) { 1 -> " · " + t("mono"); 2 -> " · " + t("stereo"); else -> "" }
        return f + r + c
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private fun longTime(seconds: Long): String {
        val h = seconds / 3600; val m = seconds / 60 % 60
        return if (h > 0) t("hoursMinutes", "h" to h, "m" to m) else t("minutes", "m" to m)
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(Locale.ROOT, b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, b / (1L shl 20).toDouble())
        else -> "%d KB".format(b / 1024)
    }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "-")
}
