// La app es UNA pantalla administrativa (CONVENCIONES §5.1), como en Android: el nivel, los
// botones de grabar y la lista de grabaciones. Cómo se graba (formato, micrófono) va en Ajustes.
import '@dotrino/theme/theme.css'
import './style.css'
import '@dotrino/topbar'
import '@dotrino/install'
import { createBackNav } from '@dotrino/nav'
import { declare } from '@dotrino/compat'
import { t, setLang, getLang, detectLang } from './i18n.js'
import { Engine, compressedCodec, storageSupported } from './engine.js'
import { Library, newFileName, mimeOf } from './library.js'
import { getIdentity } from './services/identity.js'
import { getReputation } from './services/reputation.js'
import { GAIN_MIN, GAIN_MAX, METER_FLOOR, SILENCE, smooth, meterPos, clock, size, bytesPerSecond, kHz } from './levels.js'

/** Qué versión soy (§14). La Grabadora no habla con nadie todavía: se enseña en Ajustes. */
export const MINE = declare({ product: 'recorder', version: __APP_VERSION__, protocol: 1, speaks: [1] })

setLang(detectLang())
const nav = createBackNav()
const engine = new Engine()
const codec = compressedCodec()
const lib = new Library({ defaultName: (ts) => t('defaultName', { date: dateText(ts) }) })

// ---------- ajustes (del aparato, no del usuario: localStorage, como SharedPreferences) ----------

const RATES = [44100, 48000]
const DEFAULTS = { format: 'WAV24', sampleRate: 48000, channels: 2, processing: 'raw', deviceId: '', gainDb: 0 }
function loadSettings () {
  let s = {}
  try { s = JSON.parse(localStorage.getItem('recorder.settings') || '{}') } catch { s = {} }
  const out = { ...DEFAULTS, ...s }
  if (out.format === 'COMPRESSED' && !codec) out.format = 'WAV24'
  return out
}
let settings = loadSettings()
const saveSettings = () => { try { localStorage.setItem('recorder.settings', JSON.stringify(settings)) } catch { /* modo privado */ } }
engine.setGain(settings.gainDb)

// ---------- piezas de DOM ----------

function h (tag, attrs = {}, ...kids) {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
    else if (k === 'text') el.textContent = v
    else if (k === 'class') el.className = v
    else el.setAttribute(k, v === true ? '' : v)
  }
  for (const k of kids.flat()) if (k != null) el.append(k)
  return el
}

const toastEl = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite', 'data-testid': 'toast' })
let toastTimer = 0
function toast (msg) {
  toastEl.textContent = msg
  toastEl.classList.add('on')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => toastEl.classList.remove('on'), 4500)
}

/** Una hoja modal propia (nada de alert/confirm/prompt). Volver la cierra. */
function sheet (title, testid) {
  const body = h('div', { class: 'sheet-body' })
  const dlg = h('dialog', { class: 'sheet', 'data-testid': testid },
    h('div', { class: 'sheet-head' },
      h('h2', { text: title }),
      h('button', { class: 'btn ghost', 'data-testid': 'sheet-close', onclick: () => close(), text: t('close') })),
    body)
  let layer = null
  const close = () => { if (layer) { const l = layer; layer = null; l.close() } dlg.close(); dlg.remove() }
  dlg.addEventListener('cancel', (e) => { e.preventDefault(); close() })
  dlg.addEventListener('click', (e) => { if (e.target === dlg) close() })
  document.body.append(dlg)
  dlg.showModal()
  layer = nav.open(() => { layer = null; dlg.close(); dlg.remove() })
  return { body, close }
}

function ask (title, text, ok, run, testid) {
  const { body, close } = sheet(title, testid)
  body.append(
    h('p', { class: 'muted', text }),
    h('div', { class: 'row end' },
      h('button', { class: 'btn ghost', onclick: () => close(), text: t('cancel') }),
      h('button', { class: 'btn danger', 'data-testid': 'ask-ok', onclick: () => { close(); run() }, text: ok })))
}

// ---------- topbar ----------

const settingsBtn = h('button', { class: 'btn ghost', 'data-testid': 'settings-btn', onclick: () => openSettings() })
const installBtn = h('dotrino-install', { slot: 'end', class: 'cc-install sm', 'data-testid': 'install-btn' })
const topbar = h('dotrino-topbar', {
  class: 'topbar',
  brand: 'Grabadora',
  icon: 'icon.svg',
  'brand-href': './',
  profile: true,
  theme: true,
  'support-href': 'https://ko-fi.com/dotrino',
  'support-repo': 'imdotrino/dotrino-recorder',
  'support-discord': 'https://discord.gg/D648uq7cth',
}, settingsBtn, installBtn)

// ---------- pantalla ----------

const timeEl = h('div', { class: 'time', 'data-testid': 'time', text: '0:00:00' })
const modeEl = h('div', { class: 'mode', 'data-testid': 'mode' })
const meter = h('canvas', { class: 'meter', 'data-testid': 'meter', role: 'img' })
const clipEl = h('button', { class: 'clip', 'data-testid': 'clip', onclick: () => engine.clearClip() })
const gainLabel = h('label', { class: 'gain-label', for: 'gain' })
const gainEl = h('input', { id: 'gain', type: 'range', min: GAIN_MIN, max: GAIN_MAX, step: 1, 'data-testid': 'gain' })
const summaryEl = h('div', { class: 'summary muted', 'data-testid': 'summary' })
const banner = h('div', { class: 'banner', 'data-testid': 'store-problem', hidden: true })
const controls = h('div', { class: 'controls' })
const listTitle = h('h2', { class: 'list-title' })
const listEl = h('div', { class: 'list', 'data-testid': 'list' })
const footEl = h('div', { class: 'foot muted' })

gainEl.value = settings.gainDb
gainEl.addEventListener('input', () => {
  settings.gainDb = Number(gainEl.value)
  engine.setGain(settings.gainDb)
  renderGain()
})
gainEl.addEventListener('change', saveSettings)

const page = h('main', { class: 'page' },
  banner, timeEl, modeEl, meter, clipEl,
  h('div', { class: 'gain-row' }, gainLabel, gainEl),
  summaryEl, controls, footEl, listTitle, listEl)
document.getElementById('app').replaceChildren(topbar, page, toastEl)

function renderTexts () {
  document.documentElement.lang = getLang()
  document.title = t('appName')
  topbar.setAttribute('brand', t('appName'))
  settingsBtn.textContent = t('settings')
  installBtn.setAttribute('lang', getLang())
  clipEl.textContent = t('clipped')
  listTitle.textContent = t('recordings')
  gainEl.setAttribute('aria-label', t('gain'))
  meter.setAttribute('aria-label', t('modeMonitor'))
  banner.textContent = t('storeProblem')
  renderGain(); renderSummary(); renderControls(engine.state.mode); onState(engine.state, true); renderList()
}

topbar.addEventListener('dotrino-lang', (e) => { setLang(e.detail.lang); renderTexts() })

function renderGain () {
  gainEl.value = settings.gainDb
  gainLabel.textContent = t('gainValue', { db: (settings.gainDb > 0 ? '+' : '') + settings.gainDb })
}

function formatLabel (format) {
  if (format === 'WAV24') return 'WAV 24 bit'
  if (format === 'WAV16') return 'WAV 16 bit'
  if (format === 'AAC') return 'AAC 256 kbps'
  if (format === 'OPUS') return 'Opus 192 kbps'
  if (format === 'COMPRESSED') return codec ? codec.label : '—'
  return format
}

function describe (format, rate, ch) {
  return [formatLabel(format), rate > 0 ? kHz(rate) : null, ch === 1 ? t('mono') : ch === 2 ? t('stereo') : null]
    .filter(Boolean).join(' · ')
}

const processingName = (p) => t(p === 'voice' ? 'srcVoice' : 'srcUnprocessed')

async function renderSummary () {
  const base = describe(settings.format, settings.sampleRate, settings.channels) + ' · ' + processingName(settings.processing)
  summaryEl.textContent = base
  const free = await lib.free().catch(() => null)
  if (free == null) return
  const secs = Math.floor(free / bytesPerSecond(settings, codec?.bps || 0))
  const hh = Math.floor(secs / 3600), mm = Math.floor(secs / 60) % 60
  const time = hh > 0 ? t('hoursMinutes', { h: hh, m: mm }) : t('minutes', { m: mm })
  summaryEl.textContent = base + '\n' + t('freeSpace', { time })
}

function button (text, cls, testid, run) {
  return h('button', { class: 'btn ' + cls, 'data-testid': testid, onclick: run, text })
}

function renderControls (mode) {
  controls.replaceChildren()
  if (mode === 'idle' || mode === 'monitor') {
    controls.append(
      button(t(mode === 'monitor' ? 'monitorStop' : 'monitor'), 'outline', 'monitor-btn', toggleMonitor),
      button('● ' + t('record'), 'danger', 'record-btn', record))
  } else {
    const paused = mode === 'paused'
    controls.append(
      button(t(paused ? 'resume' : 'pause'), 'outline', 'pause-btn', () => (paused ? engine.resume() : engine.pause())),
      button('■ ' + t('stop'), 'filled', 'stop-btn', () => engine.stop()))
  }
}

function errorText (code) {
  const [key, ...rest] = code.split(':')
  return t('err.' + key, { detail: rest.join(':') })
}

let lastMode = null
let lastError = null
function onState (s, force = false) {
  timeEl.textContent = clock(s.elapsedMs)
  modeEl.textContent = s.mode === 'idle' ? t('modeIdle')
    : s.mode === 'monitor' ? t('modeMonitor')
      : t(s.mode === 'recording' ? 'modeRecording' : 'modePaused', { size: size(s.bytes) })
  modeEl.classList.toggle('rec', s.mode === 'recording')
  footEl.textContent = s.mode === 'recording' || s.mode === 'paused' ? t('keepOpen') : ''
  levels.target = s.peakDb
  clipEl.classList.toggle('on', s.clipped)
  if (force || s.mode !== lastMode) {
    const was = lastMode
    lastMode = s.mode
    renderControls(s.mode)
    const active = s.mode === 'recording' || s.mode === 'paused'
    document.body.classList.toggle('busy', active)
    keepAwake(active)
    if (s.mode === 'idle' && was && was !== 'idle') { renderList(); renderSummary() }
  }
  if (s.error && s.error !== lastError) toast(errorText(s.error))
  lastError = s.error
}

// ---------- medidor (como MeterView: verde hasta -12, ámbar hasta -3, rojo después) ----------

const levels = { target: [SILENCE, SILENCE], now: [METER_FLOOR, METER_FLOOR], hold: [METER_FLOOR, METER_FLOOR], holdAt: [0, 0] }
// Los tokens del tema usan light-dark(), que el canvas no entiende: se resuelven en un elemento.
const probe = h('span', { hidden: true })
let palette = null, paletteAt = 0
function colors () {
  const now = performance.now()
  if (palette && now - paletteAt < 1000) return palette
  if (!probe.isConnected) document.body.append(probe)
  const resolve = (v) => { probe.style.color = `var(${v})`; return getComputedStyle(probe).color }
  palette = { track: resolve('--dt-surface-3'), green: resolve('--dt-ok'), amber: resolve('--dt-gold'), red: resolve('--dt-error'), tick: resolve('--dt-text-faint'),
    font: getComputedStyle(document.documentElement).getPropertyValue('--dt-font').trim() || 'sans-serif' }
  paletteAt = now
  return palette
}
function drawMeter () {
  const dpr = window.devicePixelRatio || 1
  const w = meter.clientWidth, hgt = meter.clientHeight
  if (meter.width !== Math.round(w * dpr) || meter.height !== Math.round(hgt * dpr)) { meter.width = Math.round(w * dpr); meter.height = Math.round(hgt * dpr) }
  const c = meter.getContext('2d')
  c.setTransform(dpr, 0, 0, dpr, 0, 0)
  c.clearRect(0, 0, w, hgt)
  const ch = settings.channels
  const labelH = 14, gap = 6
  const barH = (hgt - labelH - gap * (ch - 1)) / ch
  const col = colors()
  const now = performance.now()
  const g = meterPos(-12) * w, a = meterPos(-3) * w
  for (let i = 0; i < ch; i++) {
    const db = levels.target[i]
    levels.now[i] = smooth(levels.now[i], db)
    if (db >= levels.hold[i] || now - levels.holdAt[i] > 1500) { levels.hold[i] = db; levels.holdAt[i] = now }
    const top = i * (barH + gap)
    c.fillStyle = col.track; c.beginPath(); c.roundRect(0, top, w, barH, 4); c.fill()
    const lv = meterPos(levels.now[i]) * w
    if (lv > 0) { c.fillStyle = col.green; c.fillRect(0, top, Math.min(lv, g), barH) }
    if (lv > g) { c.fillStyle = col.amber; c.fillRect(g, top, Math.min(lv, a) - g, barH) }
    if (lv > a) { c.fillStyle = col.red; c.fillRect(a, top, lv - a, barH) }
    const hx = meterPos(levels.hold[i]) * w
    if (hx > 0) { c.fillStyle = levels.hold[i] > -3 ? col.red : levels.hold[i] > -12 ? col.amber : col.green; c.fillRect(hx - 2, top, 2, barH) }
  }
  c.fillStyle = col.tick; c.font = '10px ' + col.font
  for (const db of [-60, -48, -36, -24, -12, -6, -3, 0]) {
    const s = String(db), tw = c.measureText(s).width
    c.fillText(s, Math.min(Math.max(0, meterPos(db) * w - tw / 2), w - tw), hgt - 2)
  }
  requestAnimationFrame(drawMeter)
}
requestAnimationFrame(drawMeter)

// ---------- grabar ----------

function toggleMonitor () {
  if (engine.state.mode === 'monitor') engine.stop()
  else engine.monitor(settings)
}

async function record () {
  if (!storageSupported()) return toast(t('noStorage'))
  const c = settings.format === 'COMPRESSED' ? codec : null
  if (settings.format === 'COMPRESSED' && !c) return toast(t('compressedNone'))
  navigator.storage.persist?.().catch(() => {})
  await engine.record(settings, { name: newFileName(settings.format, c), codec: c })
  // Ya se conocen los nombres de los micrófonos (hay permiso).
  devicesCache = null
}

engine.onDone = async (done) => { await lib.add(done) }

// Probar el nivel es solo con la pantalla delante: el micrófono no se queda abierto.
document.addEventListener('visibilitychange', () => {
  if (document.hidden && engine.state.mode === 'monitor') engine.stop()
  if (!document.hidden && wakeWanted) keepAwake(true)
})

// La pantalla no se apaga mientras se graba (FLAG_KEEP_SCREEN_ON en Android).
let wake = null
let wakeWanted = false
async function keepAwake (on) {
  wakeWanted = on
  try {
    if (on && !wake && navigator.wakeLock) { wake = await navigator.wakeLock.request('screen'); wake.addEventListener('release', () => { wake = null }) }
    if (!on && wake) { await wake.release(); wake = null }
  } catch (e) { console.warn('[recorder] wake lock refused:', e?.message || e) }
}

// Cerrar la pestaña a mitad de una grabación: el navegador pregunta (lo grabado igual queda).
window.addEventListener('beforeunload', (e) => {
  if (engine.state.mode === 'recording' || engine.state.mode === 'paused') { e.preventDefault(); e.returnValue = '' }
})

// ---------- ajustes ----------

let devicesCache = null
async function inputs () {
  if (devicesCache) return devicesCache
  const all = await navigator.mediaDevices?.enumerateDevices?.() || []
  devicesCache = all.filter((d) => d.kind === 'audioinput' && d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications')
  return devicesCache
}
navigator.mediaDevices?.addEventListener?.('devicechange', () => { devicesCache = null })

function segmented (options, selected, onPick, testid, disabled) {
  return h('div', { class: 'seg', role: 'radiogroup', 'data-testid': testid },
    options.map(([v, text, off]) => h('button', {
      class: v === selected ? 'on' : '', role: 'radio', 'aria-checked': String(v === selected),
      'data-testid': `${testid}-${v}`, disabled: disabled || off, onclick: () => onPick(v), text,
    })))
}

function choices (options, selected, onPick, testid, disabled) {
  return h('div', { class: 'choices', role: 'radiogroup', 'data-testid': testid },
    options.map(([v, text], i) => h('button', {
      class: v === selected ? 'on' : '', role: 'radio', 'aria-checked': String(v === selected),
      'data-testid': `${testid}-${i}`, disabled, onclick: () => onPick(v),
    }, (v === selected ? '● ' : '○ ') + text)))
}

function openSettings () {
  const { body } = sheet(t('settings'), 'settings')
  async function paint () {
    const busy = engine.state.mode === 'recording' || engine.state.mode === 'paused'
    const devices = await inputs()
    const parts = []
    if (busy) parts.push(h('p', { class: 'warn', text: t('settingsBusy') }))
    const section = (title, v) => parts.push(h('h3', { text: title }), v)
    const change = (patch) => {
      settings = { ...settings, ...patch }
      saveSettings()
      // Probando el nivel, se vuelve a abrir el micrófono con lo nuevo.
      if (engine.state.mode === 'monitor') engine.monitor(settings)
      renderSummary(); paint()
    }
    section(t('format'), segmented([
      ['WAV24', 'WAV 24 bit'], ['WAV16', 'WAV 16 bit'],
      ['COMPRESSED', codec ? codec.label : 'AAC', !codec],
    ], settings.format, (format) => change({ format }), 'format', busy))
    const hint = settings.format === 'COMPRESSED' ? 'formatHint.' + codec.format : 'formatHint.' + settings.format
    parts.push(h('p', { class: 'muted small', 'data-testid': 'format-hint', text: t(hint) }))
    if (!codec) parts.push(h('p', { class: 'muted small', 'data-testid': 'no-compressed', text: t('compressedNone') }))
    section(t('rate'), segmented(RATES.map((r) => [r, kHz(r)]), settings.sampleRate, (sampleRate) => change({ sampleRate }), 'rate', busy))
    section(t('channels'), segmented([[1, t('mono')], [2, t('stereo')]], settings.channels, (channels) => change({ channels }), 'channels', busy))
    section(t('source'), choices([['raw', t('srcUnprocessed')], ['voice', t('srcVoice')]], settings.processing, (processing) => change({ processing }), 'processing', busy))
    parts.push(h('p', { class: 'muted small', text: t(settings.processing === 'voice' ? 'srcHintVoice' : 'srcHintUnprocessed') }))
    const named = devices.map((d, i) => [d.deviceId, d.label || t('devUnnamed', { n: i + 1 })])
    section(t('device'), choices([['', t('devAuto')], ...named], settings.deviceId, (deviceId) => change({ deviceId }), 'device', busy))
    if (devices.length && devices.every((d) => !d.label)) parts.push(h('p', { class: 'muted small', text: t('devNeedPermission') }))
    if (settings.deviceId && !devices.some((d) => d.deviceId === settings.deviceId)) parts.push(h('p', { class: 'warn', text: t('deviceGone') }))
    parts.push(h('p', { class: 'muted small version', 'data-testid': 'version', text: t('version', { version: MINE.version }) }))
    body.replaceChildren(...parts)
  }
  paint()
}

// ---------- grabaciones ----------

let player = null
let playingId = null
let playUrl = null
let posTimer = 0
const positions = new Map()

function dateText (ts) {
  return new Intl.DateTimeFormat(getLang() === 'en' ? 'en' : 'es', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(ts))
}

const safeName = (n) => n.replace(/[\\/:*?"<>|]/g, '-')

let renderSeq = 0
async function renderList () {
  const seq = ++renderSeq
  let all
  try { all = await lib.list() } catch (e) { console.error('[recorder] could not read the list', e); return }
  banner.hidden = !lib.problem
  const rows = await Promise.all(all.map(async (r) => ({ r, file: await lib.file(r).catch(() => null) })))
  if (seq !== renderSeq) return
  positions.clear()
  if (!rows.length) { listEl.replaceChildren(h('p', { class: 'muted', 'data-testid': 'empty', text: t('empty') })); return }
  listEl.replaceChildren(...rows.map(({ r, file }) => row(r, file)))
}

function row (r, file) {
  const dur = r.durationMs >= 0 ? clock(r.durationMs) : t('unknownLength')
  const meta = [dur, size(file ? file.size : r.bytes), describe(r.format, r.sampleRate, r.channels)]
  if (r.recovered) meta.push(t('recovered'))
  if (!file) meta.push(t('otherDevice'))
  const pos = h('span', { class: 'pos' })
  positions.set(r.id, pos)
  const act = (text, id, run, off) => h('button', { class: 'link', 'data-testid': `${id}-btn`, disabled: off, onclick: run, text })
  const canShare = file && navigator.canShare?.({ files: [new File([], r.file, { type: mimeOf(r.file) })] })
  return h('div', { class: 'card rec', 'data-testid': 'recording', 'data-rec-id': r.id },
    h('div', { class: 'rec-name', text: r.name }),
    h('div', { class: 'muted small', text: meta.join(' · ') }),
    h('div', { class: 'row' },
      act(playingId === r.id ? t('stopPlay') : t('play'), 'play', () => togglePlay(r), !file),
      canShare ? act(t('share'), 'share', () => share(r)) : null,
      act(t('download'), 'download', () => download(r), !file),
      act(t('more'), 'more', () => more(r)),
      h('span', { class: 'grow' }), pos))
}

function stopPlayback () {
  clearInterval(posTimer)
  if (player) { player.pause(); player.src = '' }
  if (playUrl) URL.revokeObjectURL(playUrl)
  player = null; playingId = null; playUrl = null
}

async function togglePlay (r) {
  if (playingId === r.id) { stopPlayback(); return renderList() }
  stopPlayback()
  const f = await lib.file(r)
  if (!f) return toast(t('fileMissing'))
  try {
    playUrl = URL.createObjectURL(f)
    player = new Audio(playUrl)
    player.addEventListener('ended', () => { stopPlayback(); renderList() })
    await player.play()
    playingId = r.id
    await renderList()
    posTimer = setInterval(() => { const p = positions.get(r.id); if (p && player) p.textContent = clock(player.currentTime * 1000) }, 250)
  } catch (e) {
    console.error('[recorder] could not play', r.file, e)
    stopPlayback()
    toast(t('playFailed'))
  }
}

async function download (r) {
  const f = await lib.file(r)
  if (!f) return toast(t('fileMissing'))
  const url = URL.createObjectURL(f)
  const a = h('a', { href: url, download: safeName(r.name) + '.' + r.file.split('.').pop() })
  document.body.append(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

async function share (r) {
  const f = await lib.file(r)
  if (!f) return toast(t('fileMissing'))
  const file = new File([f], safeName(r.name) + '.' + r.file.split('.').pop(), { type: mimeOf(r.file) })
  try { await navigator.share({ files: [file], title: r.name }) } catch (e) {
    if (e?.name !== 'AbortError') { console.error('[recorder] share failed', e); toast(t('shareFailed')) }
  }
}

function more (r) {
  const { body, close } = sheet(r.name, 'more')
  const input = h('input', { type: 'text', value: r.name, 'data-testid': 'rename-input', 'aria-label': t('rename') })
  const save = async () => {
    const name = input.value.trim()
    if (!name) return toast(t('nameEmpty'))
    await lib.rename(r, name); close(); renderList()
  }
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save() })
  body.append(
    h('h3', { text: t('rename') }), input,
    h('div', { class: 'row end' }, button(t('saveName'), 'filled', 'rename-ok', save)),
    h('div', { class: 'row end gap' }, button(t('delete'), 'danger', 'delete-btn', () => {
      close()
      ask(t('deleteTitle'), t('deleteText', { name: r.name }), t('delete'), async () => {
        if (playingId === r.id) stopPlayback()
        try { await lib.delete(r) } catch (e) { console.error('[recorder] delete failed', e); toast(t('deleteFailed')) }
        renderList(); renderSummary()
      }, 'delete-confirm')
    })))
  input.focus(); input.select()
}

// ---------- arranque ----------

renderTexts()
engine.listen((s) => onState(s))
lib.opened.then(async () => {
  banner.hidden = !lib.problem
  if (storageSupported() && engine.state.mode === 'idle') await lib.recoverOrphans(null).catch((e) => console.error('[recorder] recovery failed', e))
  renderList()
})
if (!storageSupported()) toast(t('noStorage'))

// Identidad (§6.1): el botón de perfil del topbar, el respaldo del almacén y la reputación.
getIdentity().then(async (identity) => {
  if (!identity) return
  topbar.identity = identity
  const store = await lib.opened
  if (store) topbar.store = store
  topbar.reputation = await getReputation()
})

// Service worker (§3): registro tras `load` y re-chequeo cada 30 min. Si llega uno nuevo, la
// página se recarga, pero NUNCA a mitad de una grabación: espera a que se detenga.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', async () => {
    try {
      const reg = await navigator.serviceWorker.register('sw.js')
      setInterval(() => reg.update().catch(() => {}), 30 * 60 * 1000)
    } catch (e) { console.error('[recorder] service worker failed', e) }
  })
  let hadController = !!navigator.serviceWorker.controller
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (!hadController) { hadController = true; return }
    const reload = () => { if (engine.state.mode === 'idle') location.reload(); else setTimeout(reload, 5000) }
    reload()
  })
}
