#!/usr/bin/env python3
"""Receives MIDI 1.0 from MIDI Performance Controller Pro and sends it to a DAW."""

import argparse
import hmac
import secrets
import socket
import sys

PORT = 31459
PORT_NAME = "MIDI Performance Controller Pro"


def midi_messages(frame):
    """Split a frame of complete MIDI messages before passing it to RtMidi."""
    messages = []
    offset = 0
    while offset < len(frame):
        status = frame[offset]
        if 0x80 <= status <= 0xEF:
            end = offset + (2 if status >> 4 in (0xC, 0xD) else 3)
        elif status == 0xF0:
            end = frame.index(0xF7, offset + 1) + 1
        elif status in (0xF8, 0xFA, 0xFB, 0xFC):
            end = offset + 1
        else:
            raise ValueError(f"Estado MIDI no admitido: {status:#x}")
        data_end = end - 1 if status == 0xF0 else end
        if end > len(frame) or any(value >= 0x80 for value in frame[offset + 1:data_end]):
            raise ValueError("Mensaje MIDI incompleto o inválido")
        messages.append(list(frame[offset:end]))
        offset = end
    return messages


class MidiSession:
    def __init__(self, send):
        self.send = send
        self.notes = set()
        self.holds = set()
        self.playing = False

    def forward(self, message):
        self.send(message)
        status = message[0]
        kind, channel = status >> 4, status & 15
        if kind == 9 and message[2] > 0:
            self.notes.add((channel, message[1]))
        elif kind in (8, 9):
            self.notes.discard((channel, message[1]))
        elif kind == 11 and message[1] in (64, 66, 69):
            key = channel, message[1]
            if message[2] >= 64:
                self.holds.add(key)
            else:
                self.holds.discard(key)
        if status in (0xFA, 0xFB, 0xFC):
            self.playing = status != 0xFC

    def release(self):
        if self.playing:
            self.send([0xFC])
        for channel, controller in sorted(self.holds):
            self.send([0xB0 | channel, controller, 0])
        for channel, note in sorted(self.notes):
            self.send([0x80 | channel, note, 0])


def receive(client, code, send, peer, log=print):
    with client, client.makefile("rb") as stream:
        client.settimeout(7)
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        if not hmac.compare_digest(stream.readline(32), b"MPM1 " + code + b"\n"):
            client.sendall(b"\x00")
            return
        client.sendall(b"\x01")
        log(f"Teléfono conectado: {peer}")
        session = MidiSession(send)
        try:
            while header := stream.read(1):
                length = header[0]
                if length == 0:
                    client.sendall(b"\x01")
                    continue
                if length > 32:
                    raise ValueError("Trama MIDI demasiado larga")
                frame = stream.read(length)
                if len(frame) != length:
                    raise ValueError("Trama MIDI incompleta")
                for message in midi_messages(frame):
                    session.forward(message)
        finally:
            session.release()
            log("Teléfono desconectado.")


def open_output(name, list_ports):
    try:
        import rtmidi
    except ImportError as error:
        raise ValueError("Instala python-rtmidi: python -m pip install -r requirements.txt") from error

    output = rtmidi.MidiOut(name=PORT_NAME)
    ports = output.get_ports()
    if list_ports:
        print("\n".join(ports) or "Sin salidas MIDI.")
        return None

    if name is None and output.get_current_api() != rtmidi.API_WINDOWS_MM:
        output.open_virtual_port(PORT_NAME)
    else:
        name = name or PORT_NAME
        matches = [index for index, port in enumerate(ports) if port == name]
        if not matches:
            matches = [index for index, port in enumerate(ports) if port.startswith(name + " ")]
        if len(matches) != 1:
            raise ValueError(f"Crea o selecciona una salida MIDI llamada «{name}». "
                             "En Windows, crea ese puerto en loopMIDI. Usa --list-ports para ver los nombres.")
        output.open_port(matches[0])
    return output


def local_addresses():
    addresses = set()
    try:
        entries = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
    except socket.gaierror:
        entries = []
    for entry in entries:
        address = entry[4][0]
        if not address.startswith("127."):
            addresses.add(address)
    # UDP connect selects a local interface without transmitting packets.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            addresses.add(probe.getsockname()[0])
    except OSError:
        pass
    return sorted(addresses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0", help="Dirección local donde escuchar.")
    parser.add_argument("--midi-port", help="Nombre de una salida MIDI existente.")
    parser.add_argument("--list-ports", action="store_true")
    parser.add_argument("--monitor", action="store_true", help="Muestra mensajes sin abrir un puerto MIDI.")
    args = parser.parse_args()

    try:
        output = None if args.monitor else open_output(args.midi_port, args.list_ports)
        if args.list_ports:
            return
        send = output.send_message if output is not None else lambda message: print(bytes(message).hex(" "))
        code = f"{secrets.randbelow(1_000_000):06d}".encode("ascii")
        with socket.create_server((args.bind, PORT)) as server:
            addresses = local_addresses() if args.bind == "0.0.0.0" else [args.bind]
            print("IP para introducir en Android: " + (", ".join(addresses) or "consulta ipconfig o ip addr"))
            print(f"Puerto TCP {PORT} · Código {code.decode()} · Ctrl+C para cerrar", flush=True)
            while True:
                client, peer = server.accept()
                try:
                    receive(client, code, send, peer[0], lambda message: print(message, flush=True))
                except (OSError, ValueError) as error:
                    print(f"Conexión cerrada: {error}", file=sys.stderr)
    except KeyboardInterrupt:
        print("\nPuente cerrado.")
    except (OSError, RuntimeError, ValueError) as error:
        raise SystemExit(f"Error: {error}") from error


if __name__ == "__main__":
    main()
