// El micrófono para toda la página, como Engine.kt: lo mismo sirve para PROBAR el nivel (lee y
// mide, no escribe) y para GRABAR, así que lo que marca el medidor al probar es lo que se graba.
//
//   micrófono → [worklet: ganancia, recorte, picos] → WAV: muestras al worker que escribe
//                                                   → comprimido: MediaRecorder → worker
import workletUrl from './capture-worklet.js?url'
import { SILENCE } from './levels.js'

/** Lo que el navegador sabe comprimir, en orden de preferencia (AAC como Android; si no, Opus). */
const COMPRESSED = [
  { format: 'AAC', mime: 'audio/mp4;codecs=mp4a.40.2', ext: 'm4a', bps: 256000, label: 'AAC 256 kbps' },
  { format: 'AAC', mime: 'audio/mp4; codecs="mp4a.40.2"', ext: 'm4a', bps: 256000, label: 'AAC 256 kbps' },
  { format: 'OPUS', mime: 'audio/webm;codecs=opus', ext: 'webm', bps: 192000, label: 'Opus 192 kbps' },
  { format: 'OPUS', mime: 'audio/ogg;codecs=opus', ext: 'ogg', bps: 192000, label: 'Opus 192 kbps' },
]

/** El formato comprimido de ESTE navegador, o `null` si no tiene ninguno. */
export function compressedCodec () {
  if (typeof MediaRecorder === 'undefined' || !MediaRecorder.isTypeSupported) return null
  return COMPRESSED.find((c) => MediaRecorder.isTypeSupported(c.mime)) || null
}

// La escritura en sitio (createSyncAccessHandle) solo existe dentro de un worker: aquí se mira
// la carpeta privada, y el worker dice `no-storage` si le falta lo suyo.
export const storageSupported = () => typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory

/** Un fallo con un código estable: la pantalla lo traduce (`err.<code>`). */
export class RecorderError extends Error {
  constructor (code, cause) { super(code); this.code = code; this.cause = cause }
}

const idle = (error = null) => ({ mode: 'idle', elapsedMs: 0, peakDb: [SILENCE, SILENCE], clipped: false, bytes: 0, error })

class Writer {
  constructor () {
    this.w = new Worker(new URL('./writer-worker.js', import.meta.url), { type: 'module' })
    this.seq = 0
    this.wait = new Map()
    this.onFull = null
    this.onError = null
    this.w.onmessage = (e) => {
      const m = e.data
      if (m.type === 'full') return this.onFull?.()
      if (m.type === 'error') return this.onError?.(m.code)
      const p = this.wait.get(m.id); if (!p) return
      this.wait.delete(m.id)
      m.ok ? p.resolve(m.result) : p.reject(new RecorderError(m.code))
    }
  }

  call (msg) {
    const id = ++this.seq
    return new Promise((resolve, reject) => { this.wait.set(id, { resolve, reject }); this.w.postMessage({ ...msg, id }) })
  }

  pcm (data) { this.w.postMessage({ op: 'pcm', data }, [data.buffer]) }
  raw (buf) { this.w.postMessage({ op: 'raw', data: buf }, [buf]) }
  terminate () { this.w.terminate() }
}

export class Engine {
  constructor () {
    this.state = idle()
    this.listeners = new Set()
    this.run = null
    this.onDone = null       // ({ file, settings, codec, durationMs, bytes, startedAt }) => Promise
    this.gainDb = 0
  }

  listen (fn) { this.listeners.add(fn); fn(this.state); return () => this.listeners.delete(fn) }
  publish (s) { this.state = s; for (const fn of [...this.listeners]) fn(s) }

  setGain (db) { this.gainDb = db; this.run?.node.port.postMessage({ gain: db }) }
  clearClip () { this.run?.node.port.postMessage({ clearClip: true }) }

  monitor (settings) { return this.start(settings, null) }
  record (settings, file) { return this.start(settings, file) }

  pause () {
    const r = this.run; if (!r || this.state.mode !== 'recording') return
    r.paused = true
    r.node.port.postMessage({ pcm: false, counting: false })
    r.media?.pause()
    this.publish({ ...this.state, mode: 'paused' })
  }

  resume () {
    const r = this.run; if (!r || this.state.mode !== 'paused') return
    r.paused = false
    r.node.port.postMessage({ pcm: !r.media, counting: true })
    r.media?.resume()
    this.publish({ ...this.state, mode: 'recording' })
  }

  /** Abre el micrófono con los ajustes. Lanza `RecorderError` con un código que la pantalla traduce. */
  async openMic (s) {
    const proc = s.processing === 'voice'
    const audio = {
      channelCount: { ideal: s.channels },
      sampleRate: { ideal: s.sampleRate },
      echoCancellation: proc, noiseSuppression: proc, autoGainControl: proc,
    }
    if (s.deviceId) audio.deviceId = { exact: s.deviceId }
    try {
      return await navigator.mediaDevices.getUserMedia({ audio })
    } catch (e) {
      const n = e?.name
      if (n === 'NotAllowedError' || n === 'SecurityError') throw new RecorderError('mic-denied', e)
      if ((n === 'OverconstrainedError' || n === 'NotFoundError') && s.deviceId) throw new RecorderError('device-gone', e)
      if (n === 'NotReadableError' || n === 'AbortError') throw new RecorderError('mic-busy', e)
      throw new RecorderError('mic-unavailable', e)
    }
  }

  async start (s, file) {
    await this.stop()
    const startedAt = Date.now()
    let stream = null
    let ctx = null
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new RecorderError('mic-unavailable')
      if (file && !storageSupported()) throw new RecorderError('no-storage')
      stream = await this.openMic(s)
      try {
        ctx = new AudioContext({ sampleRate: s.sampleRate, latencyHint: 'playback' })
      } catch (e) { throw new RecorderError('unsupported-format', e) }
      await ctx.audioWorklet.addModule(workletUrl)
      let src
      try { src = ctx.createMediaStreamSource(stream) } catch (e) { throw new RecorderError('unsupported-format', e) }
      const node = new AudioWorkletNode(ctx, 'recorder-capture', {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [s.channels],
        channelCount: s.channels, channelCountMode: 'explicit', channelInterpretation: 'speakers',
        processorOptions: { channels: s.channels },
      })
      src.connect(node)
      // El grafo solo corre si llega a la salida: se conecta en silencio (ganancia 0).
      const mute = ctx.createGain(); mute.gain.value = 0
      node.connect(mute).connect(ctx.destination)
      node.port.postMessage({ gain: this.gainDb })

      const run = { ctx, stream, node, file, settings: s, startedAt, paused: false, frames: 0, bytes: 0, writer: null, media: null, error: null }
      this.run = run
      const bytesPerFrame = file && !file.codec ? s.channels * (s.format === 'WAV24' ? 3 : 2) : 0
      node.port.onmessage = (e) => {
        const m = e.data
        if (m.type === 'pcm') { run.bytes += m.data.length / s.channels * bytesPerFrame; run.writer?.pcm(m.data); return }
        if (m.type === 'flushed') { run.frames = m.frames; run.flushed?.(); return }
        if (m.type === 'level' && this.run === run) {
          run.frames = m.frames
          const mode = !file ? 'monitor' : run.paused ? 'paused' : 'recording'
          this.publish({ mode, elapsedMs: Math.floor(m.frames * 1000 / ctx.sampleRate), peakDb: m.db, clipped: m.clipped,
            bytes: bytesPerFrame ? 44 + run.bytes : run.bytes, error: null })
        }
      }
      const track = stream.getAudioTracks()[0]
      track.addEventListener('ended', () => { if (this.run === run) { run.error = run.error || 'read-failed:ended'; this.stop() } })

      if (file) {
        run.writer = new Writer()
        run.writer.onFull = () => { run.error = 'wav-full'; this.stop() }
        run.writer.onError = (code) => { run.error = run.error || code; this.stop() }
        const wav = file.codec ? null : { sampleRate: ctx.sampleRate, channels: s.channels, bits: s.format === 'WAV24' ? 24 : 16 }
        await run.writer.call({ op: 'open', name: file.name, wav })
        if (wav) {
          node.port.postMessage({ pcm: true, counting: true })
        } else {
          const dest = ctx.createMediaStreamDestination()
          dest.channelCount = s.channels
          node.connect(dest)
          const mr = new MediaRecorder(dest.stream, { mimeType: file.codec.mime, audioBitsPerSecond: file.codec.bps })
          run.media = mr
          run.mediaDone = new Promise((resolve) => { mr.onstop = resolve })
          // En cadena: cada trozo se escribe en orden, y cerrar espera al último.
          run.chain = Promise.resolve()
          mr.ondataavailable = (e) => {
            if (!e.data?.size) return
            run.bytes += e.data.size
            const blob = e.data
            run.chain = run.chain.then(async () => run.writer.raw(await blob.arrayBuffer()))
          }
          mr.onerror = (e) => { console.error('recorder: MediaRecorder failed', e.error || e); run.error = run.error || 'io:encoder'; this.stop() }
          mr.start(1000)
          node.port.postMessage({ counting: true })
        }
      }

      if (ctx.state === 'suspended') await ctx.resume()
      this.publish({ ...idle(), mode: file ? 'recording' : 'monitor' })
    } catch (e) {
      const code = e instanceof RecorderError ? e.code : 'mic-unavailable'
      console.error('recorder: capture failed to start:', code, e.cause || e)
      if (this.run) { this.run.error = code; await this.stop() } else {
        stream?.getTracks().forEach((t) => t.stop())
        ctx?.close().catch(() => {})
        this.publish(idle(code))
      }
    }
  }

  /** Para lo que esté corriendo y espera a que el archivo quede cerrado. */
  async stop () {
    const r = this.run
    if (!r) return
    if (r.stopping) return r.stopping
    r.stopping = (async () => {
      let size = 0
      try {
        if (r.media) {
          if (r.media.state !== 'inactive') r.media.stop()
          await r.mediaDone
          // El último trozo llega en `dataavailable` antes de `stop`; la cadena lo escribe en orden.
          await r.chain
        } else if (r.writer) {
          await new Promise((res) => { r.flushed = res; r.node.port.postMessage({ pcm: false, counting: false, flush: true }) })
        }
        if (r.writer) size = await r.writer.call({ op: 'close' })
      } catch (e) {
        console.error('recorder: could not close the recording', e)
        r.error = r.error || 'close-failed'
      } finally {
        r.writer?.terminate()
        r.stream.getTracks().forEach((t) => t.stop())
        await r.ctx.close().catch(() => {})
        this.run = null
      }
      const durationMs = Math.floor(r.frames * 1000 / r.ctx.sampleRate)
      // Lo que se alcanzó a escribir se entrega aunque haya habido un error: es del usuario.
      if (r.file) {
        try {
          await this.onDone?.({ file: r.file, settings: r.settings, sampleRate: r.ctx.sampleRate, durationMs, bytes: size, startedAt: r.startedAt, empty: r.frames === 0 })
        } catch (e) { console.error('recorder: could not list the recording', e); r.error = r.error || 'io:index' }
      }
      this.publish(idle(r.error))
    })()
    return r.stopping
  }
}
