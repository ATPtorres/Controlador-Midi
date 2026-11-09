# Puente MIDI para MIDI Performance Controller Pro 1.1.0

El puente recibe MIDI 1.0 desde el teléfono y lo entrega a una entrada estándar de tu DAW. Deja abierto el puente mientras tocas.

## Windows y Waveform Free

1. Instala [Python 3](https://www.python.org/downloads/windows/) y [loopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html). Abre loopMIDI y crea un puerto llamado exactamente **MIDI Performance Controller Pro**.
2. Extrae esta carpeta. Ábrela en el Explorador, escribe `cmd` en la barra de dirección y pulsa Enter.
3. Ejecuta estos comandos en esa ventana.

   ```bat
   py -3 -m pip install -r requirements.txt
   py -3 midi_bridge.py
   ```

4. Conecta el teléfono y la PC a la misma red local. Si Windows pide permiso de red para Python, usa solo la red privada. El puente muestra la IP local y un código de seis dígitos.
5. En Android, abre **MIDI → Conectar por Wi-Fi o USB con puente**. Introduce la IP y el código. Pulsa **Conectar puente** y vuelve a **Piano**.
6. En Waveform, habilita **MIDI Performance Controller Pro** en **Settings → MIDI Devices**, selecciónalo como entrada de una pista con sintetizador y activa la monitorización. Toca una nota y verifica que la pista reciba MIDI.

## macOS y Linux

Instala Python 3 y ejecuta `python3 -m pip install -r requirements.txt` en un entorno virtual si el sistema lo requiere. Abre el puente con `python3 midi_bridge.py`. Crea un puerto virtual de salida con CoreMIDI o ALSA, que el DAW verá como fuente. En Linux, el sistema necesita ALSA Sequencer. Si quieres usar un puerto ya creado, consulta `--list-ports` y pasa `--midi-port "Nombre del puerto"`.

## USB con el puente

Para evitar depender del modo USB MIDI del teléfono y de la red local, instala [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools), habilita la depuración USB y autoriza únicamente tu PC de confianza. Conecta el teléfono, ejecuta `adb devices` y, cuando figure como `device`, ejecuta:

```sh
adb reverse tcp:31459 tcp:31459
```

En la PC, inicia el puente con `python midi_bridge.py --bind 127.0.0.1`. En Android, usa **127.0.0.1** como IP y el código de la consola. En Windows, si usas el lanzador Python, reemplaza `python` por `py -3`.

## Diagnóstico

- **El puente no encuentra el puerto:** abre loopMIDI y comprueba el nombre, o usa `--list-ports`.
- **Android no conecta:** comprueba el código, la IP de la PC y que ambos equipos estén en la misma red privada. Algunas redes de invitados aíslan a los dispositivos entre sí. El puente usa TCP 31459.
- **Quieres confirmar que llegan eventos sin abrir el DAW:** ejecuta el puente con `--monitor`. Toca y suelta una tecla y verás dos mensajes MIDI en la consola.
- **El DAW recibe eventos, pero no suena:** activa la monitorización de la pista y carga un instrumento virtual.

El código cambia al reiniciar el puente. Es un transporte local sin cifrado. Usa una red de confianza o la conexión USB por ADB.
