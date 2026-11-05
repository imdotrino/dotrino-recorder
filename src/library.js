// Las grabaciones (CONVENCIONES §4), igual que Library.kt en Android:
//   - los BYTES son archivos en la carpeta privada del navegador (Origin Private File System,
//     carpeta `recordings/`), que es lo que en Android es la carpeta privada de la app;
//   - el ÍNDICE (nombre, formato, duración) es del usuario y va al almacén `@dotrino/store`,
//     hilo `recorder.recordings`, con la misma forma que en Android: `{ id, ts, doc }`.
// Un archivo sin ficha (la pestaña se cerró a mitad de una grabación) se recupera al abrir.
import { Store } from '@dotrino/store'
import { getIdentity } from './services/identity.js'
import { parseWavHeader, wavDurationMs, WAV_HEADER_BYTES } from './wav.js'

export const THREAD = 'recorder.recordings'
const EXT = { WAV24: 'wav', WAV16: 'wav', AAC: 'm4a', OPUS: 'webm' }
const MIME = { wav: 'audio/wav', m4a: 'audio/mp4', webm: 'audio/webm', ogg: 'audio/ogg' }

export const mimeOf = (file) => MIME[file.split('.').pop()] || 'application/octet-stream'

function stamp (ts) {
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

/** El archivo de una grabación nueva: `rec-AAAAMMDD-HHMMSS.<ext>`, como en Android. */
export function newFileName (format, codec, ts = Date.now()) {
  const ext = codec ? codec.ext : EXT[format]
  return `rec-${stamp(ts)}.${ext}`
}

export class Library {
  constructor ({ defaultName }) {
    this.defaultName = defaultName
    this.store = null
    this.problem = null
    this.memory = new Map()   // sin almacén: la lista vive aquí y se DICE en pantalla
    this.opened = this.openStore()
  }

  async openStore () {
    try {
      const identity = await getIdentity()
      if (!identity) throw Object.assign(new Error('identity not available'), { code: 'no-identity' })
      const store = await Store.connect({ identity, adoptCommon: ['recorder.'] })
      if (typeof store.ready === 'function') await store.ready()
      this.store = store
    } catch (e) {
      console.error('[recorder] store unavailable: the list of recordings is NOT being saved', e)
      this.problem = e
    }
    return this.store
  }

  async dir () {
    const root = await navigator.storage.getDirectory()
    return root.getDirectoryHandle('recordings', { create: true })
  }

  /** El `File` de una grabación, o `null` si no está en este navegador. */
  async file (r) {
    try { return await (await (await this.dir()).getFileHandle(r.file)).getFile() } catch (e) {
      if (e?.name === 'NotFoundError') return null
      throw e
    }
  }

  async list () {
    await this.opened
    let docs
    if (this.store) {
      docs = (await this.store.listThread(THREAD, {})).map((e) => {
        if (!e.doc) throw new Error(`entry ${e.id} in ${THREAD} has no document`)
        return e.doc
      })
    } else docs = [...this.memory.values()]
    return docs.sort((a, b) => b.createdAt - a.createdAt)
  }

  async save (r) {
    await this.opened
    if (this.store) await this.store.appendMessage(THREAD, { id: r.id, ts: Date.now(), doc: r })
    else this.memory.set(r.id, r)
  }

  /** Apunta una grabación terminada. Una vacía (no llegó a grabar nada) se borra. */
  async add ({ file, settings, sampleRate, durationMs, bytes, startedAt, empty }) {
    if (empty) { await this.removeFile(file.name); return null }
    const r = {
      id: file.name.replace(/\.[^.]+$/, ''),
      name: this.defaultName(startedAt),
      file: file.name,
      format: file.codec ? file.codec.format : settings.format,
      sampleRate,
      channels: settings.channels,
      durationMs,
      bytes,
      createdAt: startedAt,
      recovered: false,
    }
    await this.save(r)
    return r
  }

  rename (r, name) { return this.save({ ...r, name }) }

  async removeFile (name) {
    try { await (await this.dir()).removeEntry(name) } catch (e) { if (e?.name !== 'NotFoundError') throw e }
  }

  async delete (r) {
    await this.removeFile(r.file)
    await this.opened
    if (this.store) await this.store.removeMessage(THREAD, r.id)
    else this.memory.delete(r.id)
  }

  /** Los archivos de la carpeta que no tienen ficha: se apuntan como recuperados. */
  async recoverOrphans (skip) {
    const known = new Set((await this.list()).map((r) => r.file))
    const dir = await this.dir()
    for await (const [name, h] of dir.entries()) {
      if (h.kind !== 'file' || known.has(name) || name === skip) continue
      const f = await h.getFile()
      const ext = name.split('.').pop()
      if (f.size <= WAV_HEADER_BYTES) { await dir.removeEntry(name); continue }
      let format, rate = 0, ch = 0, dur = -1
      if (ext === 'wav') {
        const head = parseWavHeader(new Uint8Array(await f.slice(0, WAV_HEADER_BYTES).arrayBuffer()))
        if (!head) continue
        format = head.bits === 24 ? 'WAV24' : 'WAV16'
        rate = head.sampleRate; ch = head.channels; dur = wavDurationMs(head, f.size)
      } else if (ext === 'm4a') format = 'AAC'
      else if (ext === 'webm' || ext === 'ogg') format = 'OPUS'
      else continue
      await this.save({ id: name.replace(/\.[^.]+$/, ''), name: this.defaultName(f.lastModified), file: name, format,
        sampleRate: rate, channels: ch, durationMs: dur, bytes: f.size, createdAt: f.lastModified, recovered: true })
    }
  }

  /** Lo que queda libre, en bytes (estimado por el navegador). */
  async free () {
    const e = await navigator.storage?.estimate?.()
    if (!e || e.quota == null) return null
    return Math.max(0, e.quota - (e.usage || 0))
  }
}
