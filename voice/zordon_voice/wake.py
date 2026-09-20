"""A palavra "Zordon" (SPEC-013, ADR-0038).

Copyright 2026 Willyan Faria — Apache License 2.0

Features do openWakeWord (https://github.com/dscripka/openWakeWord, Apache 2.0):
o `melspectrogram.onnx` e o `embedding_model.onnx` (o speech embedding do Google,
Apache 2.0). A lógica de janelas foi portada de `openwakeword/utils.py` v0.6.0.
O classificador é nosso: um MLP pequeno, com pesos num `.npz`, rodado em numpy.

O mel é local por quadro (512 amostras, passo de 160): calcular em fluxo ou no
clipe inteiro dá o mesmo resultado. Por isso o treino usa `clip_embeddings` e a
execução usa `WakeStream`, e os dois veem as mesmas features.
"""

from dataclasses import dataclass
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000
MEL_FRAME = 512            # amostras por quadro de mel (32 ms)
MEL_HOP = 160              # 10 ms
MEL_WINDOW = 76            # quadros de mel por embedding
MEL_STEP = 8               # 80 ms entre embeddings
EMBEDDINGS = 16            # embeddings na entrada do classificador
EMBEDDING_SIZE = 96
EMBEDDING_STRIDE = MEL_HOP * MEL_STEP                        # 1280 amostras
EMBEDDING_SPAN = MEL_HOP * (MEL_WINDOW - 1) + MEL_FRAME      # 12 512 amostras


def embedding_end(index):
    """Amostra (exclusiva) onde termina o áudio visto pelo embedding `index`."""
    return index * EMBEDDING_STRIDE + EMBEDDING_SPAN


class Features:
    """As duas redes de features, compartilhadas por todos os fluxos."""

    def __init__(self, melspectrogram: Path, embedding: Path):
        import onnxruntime

        options = onnxruntime.SessionOptions()
        options.inter_op_num_threads = 1
        options.intra_op_num_threads = 1
        options.log_severity_level = 4
        providers = ["CPUExecutionProvider"]
        self._mel = onnxruntime.InferenceSession(str(melspectrogram), sess_options=options, providers=providers)
        self._embedding = onnxruntime.InferenceSession(str(embedding), sess_options=options, providers=providers)

    def mel(self, samples: np.ndarray) -> np.ndarray:
        """PCM int16 → (quadros, 32). Precisa de ao menos 512 amostras."""
        spec = self._mel.run(None, {"input": samples.astype(np.float32)[None, :]})[0]
        return spec.reshape(-1, 32) / 10 + 2   # a mesma transformação do openWakeWord

    def embed(self, windows: np.ndarray) -> np.ndarray:
        """(n, 76, 32) → (n, 96)."""
        if len(windows) == 0:
            return np.zeros((0, EMBEDDING_SIZE), dtype=np.float32)
        out = self._embedding.run(None, {"input_1": windows.astype(np.float32)[..., None]})[0]
        return out.reshape(len(windows), EMBEDDING_SIZE)

    def clip_embeddings(self, samples: np.ndarray, batch=512) -> np.ndarray:
        """Todos os embeddings de um clipe; o `i`-ésimo termina em `embedding_end(i)`."""
        if len(samples) < EMBEDDING_SPAN:
            return np.zeros((0, EMBEDDING_SIZE), dtype=np.float32)
        spec = self.mel(samples)
        count = (len(spec) - MEL_WINDOW) // MEL_STEP + 1
        out = []
        for start in range(0, count, batch):
            stop = min(count, start + batch)
            windows = np.stack([spec[i * MEL_STEP:i * MEL_STEP + MEL_WINDOW] for i in range(start, stop)])
            out.append(self.embed(windows))
        return np.concatenate(out)


class Classifier:
    """MLP 1536 → 64 → 64 → 1 sobre 16 embeddings. Pesos sem pickle."""

    KEYS = ("mean", "std", "w1", "b1", "w2", "b2", "w3", "b3", "threshold")

    def __init__(self, params):
        missing = [key for key in self.KEYS if key not in params]
        if missing:
            raise ValueError(f"modelo da palavra incompleto: {missing}")
        self.p = {key: np.asarray(params[key], dtype=np.float32) for key in self.KEYS}
        hidden = self.p["w1"].shape[1] if self.p["w1"].ndim == 2 else -1
        shapes = ((1536,), (1536,), (1536, hidden), (hidden,), (hidden, hidden), (hidden,), (hidden, 1), (1,), ())
        for key, shape in zip(self.KEYS, shapes):
            if hidden < 1 or self.p[key].shape != shape or not np.isfinite(self.p[key]).all():
                raise ValueError(f"modelo da palavra inválido: {key}")
        self.threshold = float(self.p["threshold"])
        if not 0 < self.threshold < 1 or not (self.p["std"] > 0).all():
            raise ValueError("modelo da palavra com limiar ou escala inválidos")

    @classmethod
    def load(cls, path: Path):
        with np.load(path, allow_pickle=False) as data:
            return cls({key: data[key] for key in data.files})

    def scores(self, windows: np.ndarray) -> np.ndarray:
        """(n, 16, 96) → (n,) em 0–1."""
        p = self.p
        x = (windows.reshape(len(windows), -1) - p["mean"]) / p["std"]
        h = np.maximum(x @ p["w1"] + p["b1"], 0)
        h = np.maximum(h @ p["w2"] + p["b2"], 0)
        z = (h @ p["w3"] + p["b3"]).reshape(-1)
        return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


@dataclass(frozen=True)
class Score:
    end: int        # amostra (exclusiva) onde termina o trecho avaliado, desde o início do fluxo
    score: float


class WakeStream:
    """Um fluxo de áudio: recebe PCM em qualquer tamanho, devolve uma pontuação a cada 80 ms."""

    def __init__(self, features: Features, classifier: Classifier):
        self._features = features
        self._classifier = classifier
        self._pending = np.zeros(0, dtype=np.int16)   # começa na amostra do próximo quadro de mel
        self._mel = np.zeros((0, 32), dtype=np.float32)
        self._mel_first = 0                           # índice do primeiro quadro guardado
        self._next_mel = 0
        self._next_embedding = 0
        self._embeddings = np.zeros((0, EMBEDDING_SIZE), dtype=np.float32)

    def feed(self, pcm: bytes) -> list:
        samples = np.frombuffer(pcm, dtype="<i2")
        self._pending = np.concatenate([self._pending, samples])
        frames = (len(self._pending) - MEL_FRAME) // MEL_HOP + 1 if len(self._pending) >= MEL_FRAME else 0
        if frames <= 0:
            return []
        spec = self._features.mel(self._pending[:MEL_HOP * (frames - 1) + MEL_FRAME])
        self._pending = self._pending[MEL_HOP * frames:]
        self._next_mel += frames
        self._mel = np.concatenate([self._mel, spec])

        scores = []
        new = []
        while (self._next_embedding * MEL_STEP + MEL_WINDOW) <= self._next_mel:
            start = self._next_embedding * MEL_STEP - self._mel_first
            new.append(self._mel[start:start + MEL_WINDOW])
            self._next_embedding += 1
        if new:
            first = self._next_embedding - len(new)
            self._embeddings = np.concatenate([self._embeddings, self._features.embed(np.stack(new))])
            ready = []
            for i in range(first, self._next_embedding):
                offset = len(self._embeddings) - (self._next_embedding - i)
                if offset + 1 >= EMBEDDINGS:
                    ready.append((i, self._embeddings[offset + 1 - EMBEDDINGS:offset + 1]))
            if ready:
                values = self._classifier.scores(np.stack([window for _, window in ready]))
                scores = [Score(embedding_end(i), float(v)) for (i, _), v in zip(ready, values)]
            self._embeddings = self._embeddings[-EMBEDDINGS:]
        # Guarda só os quadros que o próximo embedding ainda vai usar.
        keep_from = self._next_embedding * MEL_STEP
        if keep_from > self._mel_first:
            self._mel = self._mel[keep_from - self._mel_first:]
            self._mel_first = keep_from
        return scores


class WakeGate:
    """Decide o disparo: limiar, fala confirmada pelo VAD e 2 s de refratário. Puro."""

    def __init__(self, threshold: float, refractory_samples=2 * SAMPLE_RATE):
        self.threshold = threshold
        self.refractory = refractory_samples
        self._last = None

    def decide(self, score: Score, speech_recently: bool) -> bool:
        if score.score < self.threshold or not speech_recently:
            return False
        if self._last is not None and score.end - self._last < self.refractory:
            return False
        self._last = score.end
        return True
