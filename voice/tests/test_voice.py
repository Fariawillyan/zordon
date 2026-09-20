"""Testes das partes puras do motor de voz (SPEC-011 CA-8, CA-9).

Copyright 2026 Willyan Faria — Apache License 2.0

Só biblioteca padrão: roda no verifyAll, sem o venv de 480 MB.
"""

import asyncio
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from zordon_voice import endpoint, framing, normalize  # noqa: E402


def feed(endpointer, probabilities):
    events = []
    for probability in probabilities:
        events += [(event, endpointer.elapsed_ms) for event in endpointer.feed(probability)]
    return events


class FramingTest(unittest.TestCase):
    """SPEC-011/CA-9: a moldura faz ida e volta."""

    @staticmethod
    def parse(raw):
        async def go():
            reader = asyncio.StreamReader()
            reader.feed_data(raw)
            reader.feed_eof()
            return await framing.read(reader)
        return asyncio.run(go())

    def roundtrip(self, header, payload=b""):
        return self.parse(framing.encode(header, payload))

    def test_ida_e_volta_com_e_sem_carga(self):
        self.assertEqual(self.roundtrip({"op": "listen", "id": 1}), ({"op": "listen", "id": 1}, b""))
        header, payload = self.roundtrip({"op": "audio", "id": 1}, b"\x01\x02" * 320)
        self.assertEqual(header, {"op": "audio", "id": 1, "bytes": 640})
        self.assertEqual(len(payload), 640)
        self.assertEqual(self.roundtrip({"ev": "final", "text": "que horas são"})[0]["text"], "que horas são")

    def test_cabecalho_comeca_pelo_tamanho_big_endian(self):
        raw = framing.encode({"op": "stop", "id": 7})
        size = struct.unpack(">I", raw[:4])[0]
        self.assertEqual(size, len(raw) - 4)

    def test_mensagem_fora_do_formato_e_recusada(self):
        with self.assertRaises(framing.FramingError):
            self.parse(struct.pack(">I", 3) + b"nao")


class EndpointTest(unittest.TestCase):
    """SPEC-011/CA-9 e CA-2: fim de fala em 0,6 s, 6 s sem fala, teto de 15 s."""

    def test_fala_e_depois_silencio_termina_em_seiscentos_ms(self):
        events = feed(endpoint.Endpointer(), [0.05] * 5 + [0.9] * 30 + [0.05] * 30)
        self.assertEqual([e for e, _ in events], [endpoint.SPEECH, endpoint.END])
        speech_at = events[0][1]
        end_at = events[1][1]
        # 30 blocos de fala a partir do 6.º; o fim vem 19 blocos (≥ 600 ms) depois.
        self.assertGreaterEqual(end_at - (5 + 30) * endpoint.CHUNK_MS, 600)
        self.assertLess(end_at - (5 + 30) * endpoint.CHUNK_MS, 600 + endpoint.CHUNK_MS)
        self.assertLess(speech_at, end_at)

    def test_ruido_curto_nao_vira_fala(self):
        events = feed(endpoint.Endpointer(), ([0.9, 0.9, 0.1] * 100))
        self.assertNotIn(endpoint.SPEECH, [e for e, _ in events])

    def test_sem_fala_em_seis_segundos_encerra_sem_nada(self):
        events = feed(endpoint.Endpointer(), [0.1] * 400)
        self.assertEqual(events[-1][0], endpoint.NO_SPEECH)
        self.assertLessEqual(events[-1][1], 6000 + endpoint.CHUNK_MS)

    def test_fala_sem_fim_para_no_teto_de_quinze_segundos(self):
        events = feed(endpoint.Endpointer(), [0.9] * 600)
        self.assertEqual([e for e, _ in events], [endpoint.SPEECH, endpoint.MAX])
        self.assertLessEqual(events[-1][1], 15000 + endpoint.CHUNK_MS)

    def test_pausa_curta_no_meio_da_frase_nao_encerra(self):
        events = feed(endpoint.Endpointer(), [0.9] * 20 + [0.1] * 10 + [0.9] * 20 + [0.1] * 25)
        self.assertEqual([e for e, _ in events], [endpoint.SPEECH, endpoint.END])


class NormalizeTest(unittest.TestCase):
    """SPEC-011/CA-8: horários falados em português."""

    # @AcceptanceCriteria("SPEC-011/CA-8")
    def test_horarios(self):
        self.assertEqual(normalize.for_speech("São 15h40."), "São quinze horas e quarenta minutos.")
        self.assertEqual(normalize.for_speech("São 21h."), "São vinte e uma horas.")
        self.assertEqual(normalize.for_speech("São 01h01."), "São uma hora e um minuto.")
        self.assertEqual(normalize.for_speech("São 22h02."), "São vinte e duas horas e dois minutos.")
        self.assertEqual(normalize.for_speech("São 00h00."), "São zero horas.")

    def test_o_resto_fica_como_esta(self):
        self.assertEqual(normalize.for_speech("Você tem 8 containers."), "Você tem 8 containers.")
        self.assertEqual(normalize.for_speech("h2o e 25h"), "h2o e 25h")


if __name__ == "__main__":
    unittest.main()
