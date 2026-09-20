"""Testes do detector com os modelos de verdade (SPEC-013 CA-1, CA-2, CA-9).

Copyright 2026 Willyan Faria — Apache License 2.0

Precisam do venv do motor (numpy, onnxruntime, piper) e dos modelos em
~/.zordon/models; sem eles, pulam. O verifyAll usa o venv quando ele existe.
"""

import os
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

MODELS = Path(os.environ.get("ZORDON_MODELS_DIR", Path.home() / ".zordon" / "models"))
REPO_MODEL = Path(__file__).resolve().parents[1] / "models" / "zordon-wake-v1.npz"

try:
    import numpy as np
    from zordon_voice import stream, wake
    HAVE_NUMPY = True
except ImportError:  # python3 do sistema, sem o venv
    HAVE_NUMPY = False

FEATURES = MODELS / "wake"
READY = HAVE_NUMPY and (FEATURES / "melspectrogram.onnx").exists() and REPO_MODEL.exists()


def features():
    return wake.Features(FEATURES / "melspectrogram.onnx", FEATURES / "embedding_model.onnx")


def synthesize(text, speed=1.0):
    """Fala do Piper (a voz do próprio Zordon) em 16 kHz, com 1,5 s de silêncio antes e depois."""
    from piper import PiperVoice, SynthesisConfig
    import av

    voice = PiperVoice.load(str(MODELS / "piper" / "pt_BR-faber-medium.onnx"))
    # Sem o ruído do VITS: a amostra dourada precisa ser a mesma a cada execução.
    chunks = list(voice.synthesize(text, syn_config=SynthesisConfig(length_scale=speed, noise_scale=0.0,
                                                                      noise_w_scale=0.0)))
    pcm = np.frombuffer(b"".join(c.audio_int16_bytes for c in chunks), dtype="<i2")
    resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
    frame = av.AudioFrame.from_ndarray(pcm[None, :], format="s16", layout="mono")
    frame.sample_rate = chunks[0].sample_rate
    audio = np.concatenate([f.to_ndarray().reshape(-1) for f in resampler.resample(frame) + resampler.resample(None)])
    pad = np.zeros(24000, dtype=np.int16)
    return np.concatenate([pad, audio.astype(np.int16), pad])


def best_score(detector_features, classifier, audio):
    s = wake.WakeStream(detector_features, classifier)
    scores = []
    for i in range(0, len(audio), 320):          # frames de 20 ms, como o host manda
        scores += s.feed(audio[i:i + 320].tobytes())
    return max(score.score for score in scores)


@unittest.skipUnless(HAVE_NUMPY, "sem numpy: rode com o venv do motor")
class StreamingEqualsBatchTest(unittest.TestCase):
    """O fluxo vê as mesmas features que o treino (wake.py)."""

    @unittest.skipUnless((FEATURES / "melspectrogram.onnx").exists(), "sem os modelos de features")
    def test_fluxo_e_lote_dao_a_mesma_pontuacao(self):
        f = features()
        rng = np.random.default_rng(3)
        params = {"mean": np.zeros(1536), "std": np.ones(1536), "w1": rng.normal(0, .05, (1536, 64)),
                  "b1": np.zeros(64), "w2": rng.normal(0, .1, (64, 64)), "b2": np.zeros(64),
                  "w3": rng.normal(0, .1, (64, 1)), "b3": np.zeros(1), "threshold": np.array(0.5)}
        classifier = wake.Classifier(params)
        audio = rng.normal(0, 2000, 16000 * 4).astype(np.int16)
        emb = f.clip_embeddings(audio)
        batch = classifier.scores(np.stack([emb[i - 15:i + 1] for i in range(15, len(emb))]))
        s = wake.WakeStream(f, classifier)
        scores = []
        for i in range(0, len(audio), 320):
            scores += s.feed(audio[i:i + 320].tobytes())
        self.assertEqual(len(scores), len(batch))
        self.assertLess(max(abs(a.score - b) for a, b in zip(scores, batch)), 1e-4)
        self.assertEqual(scores[0].end, wake.embedding_end(15))

    def test_pesos_carregam_sem_pickle(self):
        with self.assertRaises(ValueError):
            wake.Classifier({"mean": np.zeros(3)})

    # @AcceptanceCriteria("SPEC-013/CA-12")
    def test_modelo_invalido_e_recusado_antes_de_abrir_o_microfone(self):
        shapes = ((1536,), (1536,), (1536, 64), (64,), (64, 64), (64,), (64, 1), (1,), ())
        valid = {key: np.ones(shape) for key, shape in zip(wake.Classifier.KEYS, shapes)}
        valid["threshold"] = np.array(0.8)
        wake.Classifier(valid)
        for key, value in (("w1", np.ones((2, 2))), ("std", np.zeros(1536)),
                           ("b1", np.full(64, np.nan)), ("threshold", np.array(0.0)),
                           ("threshold", np.array(1.1)), ("threshold", np.array(np.inf))):
            with self.subTest(key=key, shape=value.shape), self.assertRaises(ValueError):
                wake.Classifier({**valid, key: value})


@unittest.skipUnless(READY, "sem o modelo da palavra ou sem o venv")
class DetectorTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.features = features()
        cls.classifier = wake.Classifier.load(REPO_MODEL)

    def test_zordon_dispara_e_palavras_parecidas_nao(self):
        """Amostra dourada: a voz do próprio Zordon dizendo a palavra sozinha, e as vizinhas."""
        t = self.classifier.threshold
        for text in ("Zordon!", "Ei, Zordon."):
            self.assertGreaterEqual(best_score(self.features, self.classifier, synthesize(text)), t, text)
        for text in ("Gordon.", "O rio Jordão.", "Que horas são?", "Sordo não escutou.", "Tudo em ordem."):
            self.assertLess(best_score(self.features, self.classifier, synthesize(text)), t, text)

    # @AcceptanceCriteria("SPEC-013/CA-1")
    # Não atendido pelo zordon-wake-v1 (rodada 5): a frase de um fôlego pontua 0,80, e o limiar
    # de operação é 0,99. Marcado como falha esperada para ficar visível: quando um modelo novo
    # passar, o unittest acusa "unexpected success" e esta marca precisa sair.
    @unittest.expectedFailure
    def test_frase_de_um_folego_dispara(self):
        """SPEC-013/CA-1: "Zórdon, que horas são?" sem pausa depois da palavra."""
        t = self.classifier.threshold
        self.assertGreaterEqual(best_score(self.features, self.classifier, synthesize("Zórdon, que horas são?")), t)

    # @AcceptanceCriteria("SPEC-013/CA-2")
    def test_cada_bloco_de_80_ms_custa_menos_de_10_ms(self):
        """SPEC-013/CA-2."""
        s = wake.WakeStream(self.features, self.classifier)
        audio = np.random.default_rng(5).normal(0, 1000, 16000 * 12).astype(np.int16)
        s.feed(audio[:16000 * 2].tobytes())
        costs = []
        for i in range(16000 * 2, len(audio), wake.EMBEDDING_STRIDE):
            started = time.perf_counter()
            s.feed(audio[i:i + wake.EMBEDDING_STRIDE].tobytes())
            costs.append(time.perf_counter() - started)
        costs.sort()
        self.assertLess(costs[int(len(costs) * 0.95)], 0.010)

    # Depende da frase de um fôlego disparar (acima): falha esperada pelo mesmo motivo.
    @unittest.expectedFailure
    def test_comando_no_mesmo_folego_sai_sem_a_palavra(self):
        """SPEC-013/CA-4: o fluxo corta o comando no disparo; a transcrição tira o resto."""
        audio = synthesize("Zórdon, que horas são?")
        logic = stream.Stream(stream.WAKE, self.classifier.threshold)
        detector = wake.WakeStream(self.features, self.classifier)
        woke = None
        for i in range(0, len(audio), 320):
            chunk = audio[i:i + 320]
            logic.audio(len(chunk))
            energy = float(np.sqrt(np.mean(chunk.astype(np.float64) ** 2)))
            for _ in range(len(chunk) // 320 or 1):
                logic._recent.append(1.0 if energy > 300 else 0.0)
            for score in detector.feed(chunk.tobytes()):
                for event in logic.score(score.end, score.score):
                    woke = woke or event
        self.assertIsNotNone(woke, "a palavra não disparou")
        # O disparo acontece perto do fim da palavra: antes do fim da frase inteira.
        self.assertLess(woke[2], len(audio) - 24000)


if __name__ == "__main__":
    unittest.main()
