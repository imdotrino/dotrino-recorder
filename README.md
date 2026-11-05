# dotrino-recorder — Grabadora

Grabadora de audio de calidad del ecosistema Dotrino ([`recorder.dotrino.com`](https://recorder.dotrino.com/),
uso en el [wiki](https://wiki.dotrino.com/apps/grabadora/)). Graba en **WAV sin pérdida (24 o 16 bit)**
o comprimido (**AAC 256 kbps**; en la web, Opus si el navegador no tiene AAC), con medidor de nivel por canal, aviso de saturación, ganancia, y elección
del micrófono (el del teléfono, USB, auriculares) y de su procesado (sin procesar cuando el
aparato lo ofrece). Las grabaciones se quedan en el aparato.

## Plataformas (CONVENCIONES §16)

`package.json` declara `pwa`, `ios` y `android`.

| Plataforma | Estado |
|---|---|
| PWA (raíz) | **0.1.0**, Vite sin framework, a la par de Android 0.1.0 salvo lo de abajo |
| Android (`android/`) | **0.1.0**, nativa (Kotlin, vistas nativas, `dotrino-native`) |
| iOS (`ios/`) | pendiente |

Android fue primero (excepción declarada por el dueño el 2026-10-29). Desde la PWA 0.1.0 la norma
vuelve a aplicar: la PWA va delante.

**Lo que la PWA 0.1.0 no tiene de Android 0.1.0** (el navegador no lo permite o falta la pieza):

- **Seguir grabando con la pestaña cerrada o en segundo plano en el móvil.** No hay servicio en
  primer plano: la pestaña tiene que quedar abierta (lo dice en pantalla) y se pide que la pantalla
  no se apague (Wake Lock).
- **Copia al content node** (`Sync.kt`). En la web falta el transporte de archivos grandes al node
  (`@dotrino/content-client` llega a 256 KB por objeto).
- **Elegir la fuente de Android** (sin procesar / estándar / vídeo / voz): en la web son dos,
  *sin procesar* (sin cancelación de eco, supresión de ruido ni control de ganancia) y *voz* (con
  los tres).
- **El formato comprimido depende del navegador**: AAC 256 kbps en `.m4a` donde `MediaRecorder` lo
  sabe hacer (Safari, Chrome reciente); si no, Opus 192 kbps en `.webm`. Ajustes dice cuál.
- **Compartir** sale solo donde el navegador comparte archivos (Web Share); **Descargar** está siempre.

### Textos (i18n)

La fuente es **`src/i18n.json`** (de la PWA). Las claves de Android van tal cual; lo que en el
navegador se dice distinto lleva el prefijo `web.` (p. ej. «navegador» donde Android dice
«teléfono») y `t()` lo prefiere. Para llevar un cambio a Android: `npm run i18n:android` (copia
el archivo a `android/app/src/main/assets/i18n.json` sin las claves `web.*`) y revisar el diff.

## Cómo está hecha (PWA)

- `src/engine.js` + `src/capture-worklet.js` — el micrófono (`getUserMedia`) entra a un
  `AudioWorklet` que aplica ganancia, recorte y picos en una pasada, como `Engine.kt`. Lo mismo
  sirve para *probar el nivel* y para grabar. WAV: las muestras van al worker que escribe. Comprimido:
  la salida del worklet va a `MediaRecorder`.
- `src/writer-worker.js` + `src/wav.js` — escribe en sitio con `createSyncAccessHandle` y reescribe
  la cabecera WAV cada segundo (como `WavSink`): si la pestaña se cierra a mitad, lo grabado se
  recupera al abrir.
- `src/library.js` — dónde vive cada cosa (ver abajo).
- `npm test` prueba lo puro (cabecera WAV, PCM, picos y dBFS).

### Dónde viven los bytes (decisión, 2026-11-04)

- **El índice** (nombre, formato, duración) va a **`@dotrino/store`**, abierto con identidad, hilo
  `recorder.recordings`, con la misma forma `{ id, ts, doc }` que Android. Si el almacén no abre, la
  lista se guarda en memoria y **se dice en pantalla**; los archivos quedan y se recuperan al abrir.
- **Los bytes del audio** van a la **carpeta privada del navegador** (Origin Private File System,
  `recordings/`), que es lo que en Android es la carpeta privada de la app. No van al store: el store
  guarda entradas JSON por mensaje y no respalda una de más de ~550 KB, y un minuto de WAV son
  17 MB. Tampoco a `dotrino-content`: la app tiene que funcionar sin node (CONVENCIONES §4), y desde
  la web aún no hay cómo subir archivos grandes al node. Cuando exista, la copia al node se suma igual
  que en Android, sin quitar la local.
- Consecuencia: si el índice se respalda en la bóveda y se abre en otro navegador, la grabación
  aparece con «el archivo no está en este navegador».

## Cómo está hecha (Android)

- `Engine.kt` — un hilo con `AudioRecord`: ganancia, picos y escritura en una pasada. Lo mismo
  sirve para *probar el nivel* (no escribe) y para grabar.
- `Sinks.kt` — `WavSink` (PCM 16/24 bit; la cabecera se reescribe cada segundo, así que un corte
  deja un archivo reproducible) y `AacSink` (`MediaCodec` + `MediaMuxer`).
- `RecorderService.kt` — servicio en primer plano de tipo micrófono: sigue grabando en segundo
  plano y con la pantalla apagada.
- `Library.kt` — los bytes en la carpeta privada de la app; el índice (nombre, formato, duración)
  en `DotrinoStore`, hilo `recorder.recordings`. Un archivo sin ficha se recupera al abrir.
- `Sync.kt` — copia las grabaciones al content node de la cuenta (`ContentNodeClient`). Por WebRTC
  directo, sola. Por TURN o proxio propios, queda a la vista y pregunta. Por los de Dotrino no sube
  (`dotrino-content/docs/DISENO.md` §7.1). En la PC se abren con `dotrino-content gui`.
- `RecordingProvider.kt` — comparte una grabación de solo lectura, sin AndroidX.

## Desarrollo

```bash
git clone --recurse-submodules git@dotrino:imdotrino/dotrino-recorder.git
cd dotrino-recorder
npm install && npm test && npm run dev      # PWA
cd android && ./gradlew testDebugUnitTest assembleDebug
```

La PWA se despliega sola a GitHub Pages con cada push a `main` (`.github/workflows/deploy.yml`).

`native/` es el submódulo `dotrino-native`.

## Pendiente

- iOS.
- PWA: copia al content node y grabar en segundo plano (ver arriba).
- Grabar video, en esta misma app: otro motor de captura sobre la misma lista y la misma copia (dueño, 2026-11-01).
- Publicar en Google Play (`com.dotrino.recorder`).

MIT.
