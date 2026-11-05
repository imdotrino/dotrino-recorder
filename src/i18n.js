// Textos es/en. La fuente es `src/i18n.json`, que comparten la PWA y Android: las claves de
// Android van tal cual, y lo que en el navegador se dice distinto va con el prefijo `web.`
// (p. ej. «navegador» donde Android dice «teléfono»). `t()` busca primero la clave `web.`.
import strings from './i18n.json'

let lang = 'es'

export function detectLang () {
  let saved = null
  try { saved = localStorage.getItem('dotrino.lang') } catch { /* modo privado */ }
  const l = saved || (navigator.language || 'es').slice(0, 2)
  return l === 'en' ? 'en' : 'es'
}

export function setLang (l) { lang = l === 'en' ? 'en' : 'es' }
export const getLang = () => lang

export function t (key, vars = {}) {
  const table = strings[lang]
  const s = table['web.' + key] ?? table[key]
  if (s == null) throw new Error(`missing text: ${key}`)
  return s.replace(/\{(\w+)\}/g, (_, k) => (vars[k] ?? ''))
}

export const has = (key) => strings[lang]['web.' + key] != null || strings[lang][key] != null
