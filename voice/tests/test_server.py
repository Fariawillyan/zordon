"""Regressões do protocolo de voz, sem carregar modelos nem captar microfone."""

import os
import sys
import pathlib
import types
import unittest
from unittest.mock import AsyncMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from zordon_voice.server import VoiceServer


class FakeModels:
    def transcribe(self, pcm):
        return "Dom Pedro foi imperador?", 0.9, 1000


class StreamTranscriptTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.server = VoiceServer(FakeModels(), None)
        self.server.send = AsyncMock()
        self.server.streams[1] = object()

    async def asyncTearDown(self):
        self.server.executor.shutdown(wait=True)

    # @AcceptanceCriteria("SPEC-013/CA-6")
    async def test_modo_aberto_preserva_o_comeco_do_comando(self):
        await self.server._stream_final(1, b"", "end", False)
        self.assertEqual(self.server.send.call_args.args[0]["text"], "Dom Pedro foi imperador?")

    # @AcceptanceCriteria("SPEC-013/CA-4")
    async def test_apenas_ativacao_pela_palavra_remove_fragmento(self):
        await self.server._stream_final(1, b"", "end", True)
        self.assertEqual(self.server.send.call_args.args[0]["text"], "Pedro foi imperador?")

    async def test_fluxo_cancelado_descarta_transcricao(self):
        self.server.streams.clear()
        await self.server._stream_final(1, b"", "end", False)
        self.server.send.assert_not_called()


class WakeThresholdTest(unittest.TestCase):
    """O limiar ajustável: o dono decide o troco entre acerto e disparo falso (SPEC-034)."""

    def _server(self, override):
        models = types.SimpleNamespace(wake_ready=True,
                                       wake_classifier=types.SimpleNamespace(threshold=0.99))
        return VoiceServer(models, pathlib.Path("/tmp/zordon-voice-test.sock"), override)

    def test_sem_ajuste_vale_o_do_modelo(self):
        self.assertAlmostEqual(self._server(None).wake_threshold, 0.99)

    def test_ajuste_valido_entra_em_vigor(self):
        self.assertAlmostEqual(self._server(0.90).wake_threshold, 0.90)

    def test_ajuste_absurdo_eh_recusado_e_volta_ao_do_modelo(self):
        # Zero dispararia com qualquer ruído; 1.0 nunca dispararia.
        self.assertAlmostEqual(self._server(0.0).wake_threshold, 0.99)
        self.assertAlmostEqual(self._server(1.0).wake_threshold, 0.99)
        self.assertAlmostEqual(self._server(-3.0).wake_threshold, 0.99)
