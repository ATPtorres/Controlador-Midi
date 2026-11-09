import concurrent.futures
import socket
import unittest

from midi_bridge import midi_messages, receive


class BridgeTest(unittest.TestCase):
    def test_forwarding_and_disconnect_release(self):
        events = []
        with socket.create_server(("127.0.0.1", 0)) as server:
            with concurrent.futures.ThreadPoolExecutor(1) as executor:
                def accept():
                    client, peer = server.accept()
                    receive(client, b"123456", events.append, peer[0], lambda _: None)

                result = executor.submit(accept)
                with socket.create_connection(server.getsockname(), timeout=2) as phone:
                    phone.sendall(b"MPM1 123456\n")
                    self.assertEqual(phone.recv(1), b"\x01")
                    phone.sendall(b"\x00")
                    self.assertEqual(phone.recv(1), b"\x01")
                    phone.sendall(bytes([3, 0x92, 60, 100, 3, 0xB2, 64, 127]))
                    phone.sendall(bytes([8, 0xB2, 0, 1, 0xB2, 32, 2, 0xC2, 5]))
                    phone.sendall(bytes([1, 0xFA]))
                result.result(timeout=3)
        self.assertEqual(events, [
            [0x92, 60, 100], [0xB2, 64, 127],
            [0xB2, 0, 1], [0xB2, 32, 2], [0xC2, 5],
            [0xFA], [0xFC], [0xB2, 64, 0], [0x82, 60, 0],
        ])

    def test_rejects_malformed_message_and_preserves_sysex(self):
        with self.assertRaises(ValueError):
            midi_messages(bytes([0x90, 60, 0x80]))
        with self.assertRaises(ValueError):
            midi_messages(bytes([0xC0]))
        self.assertEqual(midi_messages(bytes([0xF0, 0x7F, 0x7F, 6, 6, 0xF7])),
                         [[0xF0, 0x7F, 0x7F, 6, 6, 0xF7]])

    def test_rejects_wrong_pairing_code(self):
        with socket.create_server(("127.0.0.1", 0)) as server:
            with concurrent.futures.ThreadPoolExecutor(1) as executor:
                def accept():
                    client, peer = server.accept()
                    receive(client, b"123456", self.fail, peer[0], lambda _: None)

                result = executor.submit(accept)
                with socket.create_connection(server.getsockname(), timeout=2) as phone:
                    phone.sendall(b"MPM1 654321\n")
                    self.assertEqual(phone.recv(1), b"\x00")
                result.result(timeout=3)


if __name__ == "__main__":
    unittest.main()
