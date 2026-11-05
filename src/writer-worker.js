// Escribe la grabación EN SU SITIO mientras se graba, en la carpeta privada del navegador
// (Origin Private File System), como la carpeta privada de la app en Android. La cabecera WAV
// se reescribe cada segundo: si la pestaña se cierra a mitad, lo grabado hasta ese segundo se
// puede reproducir y la lista lo recupera al abrir.
import { wavHeader, floatToPcm, WAV_MAX_DATA, WAV_HEADER_BYTES } from './wav.js'

let handle = null
let kind = null
let fmt = null
let pos = 0
let data = 0
let lastHeader = 0
let scratch = new Uint8Array(0)

function writeHeader () {
  handle.write(wavHeader({ ...fmt, dataBytes: data }), { at: 0 })
}

async function open ({ name, wav }) {
  const root = await navigator.storage.getDirectory()
  const dir = await root.getDirectoryHandle('recordings', { create: true })
  const fh = await dir.getFileHandle(name, { create: true })
  if (typeof fh.createSyncAccessHandle !== 'function') throw Object.assign(new Error('no sync access handle'), { code: 'no-storage' })
  handle = await fh.createSyncAccessHandle()
  handle.truncate(0)
  kind = wav ? 'wav' : 'raw'
  fmt = wav || null
  pos = 0; data = 0; lastHeader = Date.now()
  if (kind === 'wav') { writeHeader(); pos = WAV_HEADER_BYTES }
}

function pcm (samples) {
  const need = samples.length * (fmt.bits / 8)
  if (scratch.length < need) scratch = new Uint8Array(need)
  const bytes = floatToPcm(samples, fmt.bits, scratch)
  handle.write(bytes, { at: pos })
  pos += bytes.length; data += bytes.length
  const now = Date.now()
  if (now - lastHeader >= 1000) { writeHeader(); handle.flush(); lastHeader = now }
  return data >= WAV_MAX_DATA
}

function raw (buf) {
  const bytes = new Uint8Array(buf)
  handle.write(bytes, { at: pos })
  pos += bytes.length
  handle.flush()
}

function close () {
  if (!handle) return 0
  if (kind === 'wav') writeHeader()
  handle.flush()
  const size = handle.getSize()
  handle.close()
  handle = null
  return size
}

self.onmessage = async (e) => {
  const { id, op } = e.data
  try {
    let result = null
    if (op === 'open') await open(e.data)
    else if (op === 'pcm') { if (pcm(e.data.data)) self.postMessage({ type: 'full' }) ; return }
    else if (op === 'raw') { raw(e.data.data); return }
    else if (op === 'close') result = close()
    else throw new Error(`unknown op: ${op}`)
    self.postMessage({ id, ok: true, result })
  } catch (err) {
    console.error('recorder writer:', err)
    const code = err?.code === 'no-storage' ? 'no-storage' : err?.name === 'QuotaExceededError' ? 'io:quota' : 'io:' + (err?.message || err?.name || 'write failed')
    if (id != null) self.postMessage({ id, ok: false, code })
    else self.postMessage({ type: 'error', code })
  }
}
