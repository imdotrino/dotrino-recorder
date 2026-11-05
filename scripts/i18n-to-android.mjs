// Copia los textos de la PWA (la fuente, CONVENCIONES §16.1) a Android. Android ignora las
// claves `web.*`. Uso: npm run i18n:android, y revisar el diff de android/ antes de commitear.
import { readFileSync, writeFileSync } from 'node:fs'
const src = new URL('../src/i18n.json', import.meta.url)
const dst = new URL('../android/app/src/main/assets/i18n.json', import.meta.url)
const all = JSON.parse(readFileSync(src, 'utf8'))
const out = {}
for (const [lang, table] of Object.entries(all)) {
  out[lang] = Object.fromEntries(Object.entries(table).filter(([k]) => !k.startsWith('web.')))
}
writeFileSync(dst, JSON.stringify(out, null, 2) + '\n')
console.log('android i18n updated from src/i18n.json')
