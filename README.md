# MIDI Performance Controller Pro

Controlador MIDI Android para interpretación en vivo. La app envía eventos MIDI a un instrumento externo, DAW o sintetizador Android.

## Instalar y compilar

- Android 13 (API 33) o posterior. Instala el APK firmado desde el artefacto de la versión en **Actions → Android APK**.
- Para compilar, usa JDK 17, Android SDK Platform 35, Build Tools 35.0.0 y Gradle 8.9. También puedes abrir este directorio como proyecto en Android Studio.

```bash
./gradlew :midi:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

El APK aparece en `app/build/outputs/apk/debug/app-debug.apk`.

## Conectar

- **Puente MIDI para PC.** Usa el [puente de escritorio](desktop/LEEME.md) por Wi-Fi o por USB con ADB si el ordenador no detecta el modo USB MIDI del teléfono. El puente reenvía MIDI 1.0 a un puerto estándar del DAW.

- **DAW en computadora por USB.** Conecta el teléfono mediante un cable de datos, selecciona **MIDI** en las opciones USB de Android si el dispositivo ofrece ese modo, abre **MIDI** en la app y elige la entrada USB de Android. En el DAW, habilita el puerto MIDI que expone el teléfono y arma una pista de instrumento.
- **Sintetizador o interfaz USB MIDI.** Conecta un dispositivo class-compliant con un cable o adaptador OTG compatible y selecciónalo en **MIDI → Puertos del sistema**. Algunos dispositivos requieren alimentación propia.
- **Bluetooth LE.** En **MIDI → Buscar dispositivos**, concede el permiso de dispositivos cercanos y elige un periférico BLE que anuncie el servicio MIDI. La app actúa como cliente BLE MIDI 1.0. Para recibir en una computadora vía BLE, la computadora necesita un receptor que se anuncie como periférico BLE MIDI.
- **MIDI virtual.** En el sintetizador o DAW de Android, abre el puerto de salida `Controller Out` de **MIDI Performance Controller Pro**. En Android 15 o posterior existe además `Controller UMP` para aplicaciones MIDI 2.0.
- **USB UMP.** Android 13 introdujo la API para puertos USB UMP. La disponibilidad depende del puerto que expongan el dispositivo y la versión de Android del fabricante. El modo automático usa el protocolo predeterminado anunciado y, si no está especificado, usa UMP MIDI 1.0. Selecciona UMP MIDI 2.0 manualmente si el equipo ya está configurado para ese protocolo. El proyecto no implementa la negociación MIDI-CI.

## Transporte maestro en Waveform

1. Conecta el teléfono por USB MIDI y habilita su entrada en **Settings → MIDI Devices**.
2. En **Settings → Control Surfaces**, crea una superficie personalizada de tipo MIDI e importa [MIDI-Performance-Waveform.trkctrlr](desktop/MIDI-Performance-Waveform.trkctrlr) con **Import Settings**.
3. Selecciona como entrada de la superficie el puerto del teléfono. Para que se iluminen los estados de reproducción, grabación y metrónomo, selecciona también el puerto del teléfono como salida de la superficie.
4. En el proyecto, desactiva **Return to start on stop** en **Options** para que **Pausa** conserve la posición. Arma las pistas que quieras grabar.

La app viene con **Transporte maestro del DAW** activado. Los botones actúan sobre el proyecto activo. **Detener** envía un Stop y luego un segundo Stop mediante un CC distinto. En Waveform, el segundo Stop mueve el cursor a 0:00 cuando el transporte ya está detenido.

| Botón | Canal | CC | Asignación Waveform |
| --- | --- | --- | --- |
| Reproducir | 16 | 110 | Transport → Play |
| Pausa | 16 | 111 | Transport → Stop |
| Grabar | 16 | 112 | Transport → Record |
| Detener | 16 | 111 y 113 | Transport → Stop en ambos |
| Metrónomo | 16 | 114 | Options → Toggle click |

En otros DAWs, asigna **CC 113** a la acción que lleva el cursor al inicio. El puente de escritorio existente permite enviar estos comandos sin indicadores de retorno. Si quieres volver al transporte y metrónomo locales, desactiva **Transporte maestro del DAW** en ajustes.

## Superficies

| Superficie | Uso |
| --- | --- |
| Piano | Toca cualquier tecla. Los puntos azules indican la escala. Arrastra o pellizca sobre la banda superior del teclado. |
| Pads | Ocho pads 4×2. Usa **Editar** para cambiar nota, color, icono, velocity y modo. |
| Escala | Toca las notas ordenadas por grado, con sólo las notas de la escala seleccionada. |
| Acordes | Matriz 7×3. En **Editar**, toca para cambiar el voicing o arrastra una celda para intercambiarla. Las escalas de menos de siete notas dejan grados disponibles para voicings personalizados. |
| Grid | Cuadrícula 4×6 en vertical y 8×3 en horizontal. La afinación asciende de izquierda a derecha y de abajo hacia arriba. |
| Faders | Seis CC asignables con nombres guardados en el preset. Cada uno admite curvas, color y alta resolución. Ajusta **Stream CC** para enviar al mover o sólo al soltar. |

El protocolo MIDI 1.0 permite pares CC de 14 bits para los controladores 0–31. CC74 y los demás controladores fuera de ese intervalo usan 7 bits en MIDI 1.0. MIDI 2.0 usa su campo de 32 bits y lo alimenta con el valor calculado de hasta 14 bits. La presión táctil y el número de toques simultáneos dependen del hardware.

La pantalla **Presets** guarda y recupera la configuración completa, y exporta o importa JSON mediante el selector de archivos de Android. La configuración de trabajo se conserva localmente.

## Arquitectura

- `:app` contiene la interfaz Compose/Material 3, las superficies multitáctiles, la lógica musical, persistencia de presets y el ViewModel Hilt/StateFlow.
- `:midi` contiene el códec MIDI 1.0/UMP y el motor Android. Un hilo serial dedicado ordena mensajes y rastrea Note Off aunque se superpongan acordes o contactos. El motor opera sin dependencia de Compose.
- GitHub Actions compila el APK, ejecuta pruebas de codificación MIDI y escalas y verifica la firma.
