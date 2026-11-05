// El micrófono, en el hilo de audio: ganancia, recorte y picos en una pasada (como Engine.kt).
// Sale por su salida (para el codificador comprimido) y, si se está grabando en WAV, manda
// las muestras intercaladas al hilo principal en tandas. Sin imports: es un worklet.
const SILENCE = -90

class RecorderCapture extends AudioWorkletProcessor {
  constructor (opts) {
    super()
    const o = opts.processorOptions || {}
    this.ch = o.channels === 1 ? 1 : 2
    this.gain = 1
    this.pcm = false        // mandar muestras (grabando en WAV y sin pausa)
    this.counting = false   // contar el tiempo (grabando y sin pausa)
    this.frames = 0
    this.clip = false
    this.peaks = [0, 0]
    this.sincePeaks = 0
    this.peakEvery = Math.round(sampleRate * 0.05)   // cada 50 ms, como Android
    this.batchFrames = 4096
    this.batch = new Float32Array(this.batchFrames * this.ch)
    this.fill = 0
    this.port.onmessage = (e) => {
      const m = e.data
      if (m.gain != null) this.gain = Math.pow(10, m.gain / 20)
      if (m.pcm != null) this.pcm = m.pcm
      if (m.counting != null) this.counting = m.counting
      if (m.clearClip) this.clip = false
      if (m.flush) { this.flushBatch(); this.port.postMessage({ type: 'flushed', frames: this.frames }) }
    }
  }

  flushBatch () {
    if (this.fill === 0) return
    const out = this.batch.slice(0, this.fill * this.ch)
    this.port.postMessage({ type: 'pcm', data: out }, [out.buffer])
    this.fill = 0
  }

  process (inputs, outputs) {
    const input = inputs[0]
    const output = outputs[0]
    const n = (output[0] || input[0] || []).length
    if (!n) return true
    const ch = this.ch
    const g = this.gain
    for (let i = 0; i < n; i++) {
      for (let c = 0; c < ch; c++) {
        const src = input[c] || input[0]
        let v = src ? src[i] * g : 0
        if (v >= 1) { v = 1; this.clip = true } else if (v <= -1) { v = -1; this.clip = true }
        if (output[c]) output[c][i] = v
        const a = v < 0 ? -v : v
        if (a > this.peaks[c]) this.peaks[c] = a
        if (this.pcm) this.batch[this.fill * ch + c] = v
      }
      if (this.pcm && ++this.fill === this.batchFrames) this.flushBatch()
    }
    if (this.counting) this.frames += n
    this.sincePeaks += n
    if (this.sincePeaks >= this.peakEvery) {
      const db = this.peaks.map((p) => (p > 0 ? Math.max(SILENCE, 20 * Math.log10(p)) : SILENCE))
      if (ch === 1) db[1] = db[0]
      this.port.postMessage({ type: 'level', db, clipped: this.clip, frames: this.frames })
      this.peaks[0] = 0; this.peaks[1] = 0
      this.sincePeaks = 0
    }
    return true
  }
}

registerProcessor('recorder-capture', RecorderCapture)
