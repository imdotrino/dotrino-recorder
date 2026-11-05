import { defineConfig } from 'vite'
import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import prerender from '@dotrino/prerender/vite'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))

// <meta name="commit"> con el hash del commit del build (CONVENCIONES §3).
function commitMeta () {
  let hash = 'dev'
  try { hash = execSync('git rev-parse --short HEAD').toString().trim() } catch { /* sin git */ }
  return {
    name: 'commit-meta',
    transformIndexHtml: (html) => html.replace('</head>', `  <meta name="commit" content="${hash}" />\n</head>`),
  }
}

// base './' → rutas relativas, para servir bajo recorder.dotrino.com. Los assets PWA viven en
// public/ y se copian tal cual a la raíz de dist/. android/ e ios/ no entran en el build.
export default defineConfig({
  base: './',
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [
    // El HTML llega con contenido para quien lo lee sin ejecutar JavaScript (§7).
    prerender({ links: [{ href: 'https://wiki.dotrino.com/apps/grabadora/', text: 'Cómo se usa la Grabadora' }] }),
    commitMeta(),
  ],
  worker: { format: 'es' },
  // El worklet se carga por URL (audioWorklet.addModule): que no se incruste como data: URI.
  build: { assetsInlineLimit: (file) => (file.endsWith('.js') ? false : undefined) },
  server: { port: 3260, host: true },
})
