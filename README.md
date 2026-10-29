# dotrino-recorder — Grabadora

Grabadora de audio de calidad del ecosistema Dotrino. Graba en **WAV sin pérdida (24 o 16 bit)**
o en **AAC 256 kbps**, con medidor de nivel por canal, aviso de saturación, ganancia, y elección
del micrófono (el del teléfono, USB, auriculares) y de su procesado (sin procesar cuando el
aparato lo ofrece). Las grabaciones se quedan en el aparato.

## Plataformas (CONVENCIONES §16)

`package.json` declara `pwa`, `ios` y `android`.

| Plataforma | Estado |
|---|---|
| Android (`android/`) | **0.1.0**, nativa (Kotlin, vistas nativas, `dotrino-native`) |
| PWA (raíz) | pendiente |
| iOS (`ios/`) | pendiente |

**Excepción declarada por el dueño (2026-10-29):** Android va primero porque hacía falta ya. La
norma es que la PWA vaya delante; cuando exista, los textos (`android/app/src/main/assets/i18n.json`)
pasan a salir de ella.

## Cómo está hecha (Android)

- `Engine.kt` — un hilo con `AudioRecord`: ganancia, picos y escritura en una pasada. Lo mismo
  sirve para *probar el nivel* (no escribe) y para grabar.
- `Sinks.kt` — `WavSink` (PCM 16/24 bit; la cabecera se reescribe cada segundo, así que un corte
  deja un archivo reproducible) y `AacSink` (`MediaCodec` + `MediaMuxer`).
- `RecorderService.kt` — servicio en primer plano de tipo micrófono: sigue grabando en segundo
  plano y con la pantalla apagada.
- `Library.kt` — los bytes en la carpeta privada de la app; el índice (nombre, formato, duración)
  en `DotrinoStore`, hilo `recorder.recordings`. Un archivo sin ficha se recupera al abrir.
- `RecordingProvider.kt` — comparte una grabación de solo lectura, sin AndroidX.

## Desarrollo

```bash
git clone --recurse-submodules git@dotrino:imdotrino/dotrino-recorder.git
cd dotrino-recorder/android
./gradlew testDebugUnitTest assembleDebug
```

`native/` es el submódulo `dotrino-native`.

## Pendiente

- PWA e iOS.
- Respaldo cifrado de las grabaciones: el content node acepta hoy objetos de hasta 256 KB y
  `dotrino-native` no tiene cliente de content; una grabación de un minuto ya pasa de eso.
- Publicar en Google Play (`com.dotrino.recorder`).

MIT.
