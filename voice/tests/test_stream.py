"""Testes do fluxo contínuo (SPEC-013 CA-4, CA-5, CA-6, CA-8, CA-9).

Copyright 2026 Willyan Faria — Apache License 2.0

Só biblioteca padrão: probabilidades de fala e pontuações sintéticas.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from zordon_voice import stream  # noqa: E402

BLOCK = 512      # amostras por bloco de VAD (32 ms)


def run(s, probabilities, scores=None):
    """Alimenta blocos de 32 ms; `scores` mapeia índice do bloco → pontuação da palavra."""
    events = []
    for i, probability in enumerate(probabilities):
        s.audio(BLOCK)
        events += s.vad(probability)
        if scores and i in scores:
            events += s.score(s.samples, scores[i])
    return events


def kinds(events):
    return [e[0] for e in events]


class WakeStreamTest(unittest.TestCase):

    def test_palavra_abre_o_comando_e_a_fala_seguinte_vira_final(self):
        s = stream.Stream(stream.WAKE, 0.8)
        events = run(s, [0.9] * 20 + [0.9] * 30 + [0.05] * 25, {19: 0.95})
        self.assertEqual(kinds(events), [stream.WAKE_WORD, stream.SPEECH, stream.FINAL])
        final = events[-1]
        self.assertTrue(final[3], "o comando veio da palavra")
        self.assertEqual(final[2], 20 * BLOCK - stream.PREROLL)
        self.assertIsNone(s.command, "depois do comando, volta a dormir")

    def test_palavra_sem_comando_volta_a_dormir_calada(self):
        s = stream.Stream(stream.WAKE, 0.8)
        events = run(s, [0.9] * 20 + [0.05] * 200, {19: 0.95})
        self.assertEqual(kinds(events), [stream.WAKE_WORD, stream.SILENCE])
        self.assertIsNone(s.command)

    def test_dormindo_fala_sem_a_palavra_nao_vira_nada(self):
        s = stream.Stream(stream.WAKE, 0.8)
        self.assertEqual(run(s, [0.9] * 100 + [0.05] * 100, {50: 0.5}), [])

    def test_pontuacao_alta_sem_fala_no_vad_nao_dispara(self):
        s = stream.Stream(stream.WAKE, 0.8)
        self.assertEqual(run(s, [0.05] * 60, {40: 0.99}), [])

    def test_refratario_de_dois_segundos(self):
        s = stream.Stream(stream.WAKE, 0.8)
        events = run(s, [0.9] * 40 + [0.05] * 200, {20: 0.95, 21: 0.97, 39: 0.99})
        self.assertEqual(kinds(events).count(stream.WAKE_WORD), 1)

    def test_ouvindo_um_comando_a_palavra_nao_reabre(self):
        s = stream.Stream(stream.WAKE, 0.8)
        events = run(s, [0.9] * 150, {10: 0.95, 120: 0.95})
        self.assertEqual(kinds(events).count(stream.WAKE_WORD), 1)


class DuplexTest(unittest.TestCase):

    def test_falando_a_fala_captada_nao_vira_comando(self):
        s = stream.Stream(stream.OPEN, 0.8)
        s.duplex(speaking=True, armed=True)
        self.assertEqual(run(s, [0.9] * 40 + [0.05] * 40), [])

    def test_falando_a_palavra_interrompe_e_abre_a_escuta(self):
        s = stream.Stream(stream.WAKE, 0.8)
        s.duplex(speaking=True, armed=True)
        events = run(s, [0.9] * 20 + [0.9] * 20 + [0.05] * 25, {19: 0.95})
        self.assertEqual(kinds(events), [stream.WAKE_WORD, stream.SPEECH, stream.FINAL])
        self.assertFalse(s.speaking)

    def test_fala_do_zordon_com_a_palavra_desarma(self):
        s = stream.Stream(stream.WAKE, 0.8)
        s.duplex(speaking=True, armed=False)
        self.assertEqual(run(s, [0.9] * 40, {20: 0.99}), [])
        s.duplex(speaking=False, armed=True)
        self.assertEqual(kinds(run(s, [0.9] * 70, {60: 0.99})), [stream.WAKE_WORD, stream.SPEECH])


class OpenStreamTest(unittest.TestCase):

    def test_cada_fala_vira_comando_sem_a_palavra(self):
        s = stream.Stream(stream.OPEN, 0.8)
        events = run(s, ([0.9] * 20 + [0.05] * 25) * 2)
        self.assertEqual(kinds(events), [stream.SPEECH, stream.FINAL] * 2)
        self.assertFalse(events[1][3], "no modo aberto o comando não veio da palavra")

    def test_silencio_longo_nao_encerra_o_modo_aberto(self):
        s = stream.Stream(stream.OPEN, 0.8)
        # Mais que o teto de 15 s: silêncio não pode chegar ao Whisper como comando.
        self.assertEqual(run(s, [0.05] * 2000), [])
        self.assertIsNotNone(s.command)
        self.assertLessEqual(s.samples - s.keep_from, stream.PREROLL)
        self.assertEqual(kinds(run(s, [0.9] * 20 + [0.05] * 25)), [stream.SPEECH, stream.FINAL])

    def test_esperar_quase_quinze_segundos_nao_corta_a_fala_seguinte(self):
        s = stream.Stream(stream.OPEN, 0.8)
        events = run(s, [0.05] * 460 + [0.9] * 60 + [0.05] * 25)
        self.assertEqual(kinds(events), [stream.SPEECH, stream.FINAL])
        self.assertEqual(events[-1][1], stream.endpoint.END)

    def test_depois_de_falar_o_comando_reabre(self):
        s = stream.Stream(stream.OPEN, 0.8)
        s.duplex(speaking=True, armed=True)
        self.assertIsNone(s.command)
        s.duplex(speaking=False, armed=True)
        self.assertIsNotNone(s.command)


class StripWakeTest(unittest.TestCase):

    def test_tira_o_resto_da_palavra(self):
        self.assertEqual(stream.strip_wake("Dom, que horas são?"), "Que horas são?")
        self.assertEqual(stream.strip_wake(" Zordon, abre o navegador."), "Abre o navegador.")
        self.assertEqual(stream.strip_wake("Zórdon que horas são"), "Que horas são")
        self.assertEqual(stream.strip_wake("que horas são?"), "Que horas são?")

    def test_nao_come_palavra_de_verdade(self):
        self.assertEqual(stream.strip_wake("Corda nova, por favor"), "Corda nova, por favor")
        self.assertEqual(stream.strip_wake("Ordem alfabética"), "Ordem alfabética")
        self.assertEqual(stream.strip_wake(""), "")


if __name__ == "__main__":
    unittest.main()
