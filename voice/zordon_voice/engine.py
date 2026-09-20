"""Os modelos: VAD Silero, faster-whisper e Piper (SPEC-011 §3).

Copyright 2026 Willyan Faria — Apache License 2.0

Tudo local. Medido na máquina de referência (CPU, 12 núcleos): transcrição de
uma frase curta em ~1,4 s com o modelo small int8; síntese em ~0,09 s.
"""

import logging
import math
import os
import time
from pathlib import Path

import numpy as np

from . import normalize, wake

log = logging.getLogger("zordon_voice.engine")

SAMPLE_RATE = 16_000
VAD_CHUNK = 512
VAD_CONTEXT = 64
WHISPER_DIR = "faster-whisper-small"
PIPER_VOICE = "piper/pt_BR-faber-medium.onnx"
WAKE_DIR = "wake"
WAKE_MODEL = "zordon-wake-v1.npz"


class StreamingVad:
    """Silero v6 em fluxo: um estado por escuta, blocos de 512 amostras (32 ms)."""

    def __init__(self, session):
        self._session = session
        self._h = np.zeros((1, 1, 128), dtype=np.float32)
        self._c = np.zeros((1, 1, 128), dtype=np.float32)
        self._context = np.zeros(VAD_CONTEXT, dtype=np.float32)
        self._pending = np.zeros(0, dtype=np.float32)

    def feed(self, pcm: bytes):
        """PCM s16le a 16 kHz; devolve a probabilidade de fala de cada bloco completo."""
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        self._pending = np.concatenate([self._pending, samples])
        probabilities = []
        while len(self._pending) >= VAD_CHUNK:
            chunk = self._pending[:VAD_CHUNK]
            self._pending = self._pending[VAD_CHUNK:]
            window = np.concatenate([self._context, chunk])[None, :]
            output, self._h, self._c = self._session.run(
                None, {"input": window, "h": self._h, "c": self._c})
            self._context = chunk[-VAD_CONTEXT:]
            probabilities.append(float(np.asarray(output).reshape(-1)[0]))
        return probabilities


class Models:
    def __init__(self, models_dir: Path, threads: int):
        self.models_dir = Path(models_dir)
        self.threads = threads
        self.vad_session = None
        self.whisper = None
        self.voice = None
        self.wake_features = None
        self.wake_classifier = None
        self.wake_reason = "modelo da palavra ainda não carregado"

    def load(self):
        import onnxruntime
        import faster_whisper
        from faster_whisper import WhisperModel
        from piper import PiperVoice

        started = time.monotonic()
        options = onnxruntime.SessionOptions()
        options.inter_op_num_threads = 1
        options.intra_op_num_threads = 1
        options.log_severity_level = 4
        vad = Path(faster_whisper.__file__).parent / "assets" / "silero_vad_v6.onnx"
        self.vad_session = onnxruntime.InferenceSession(str(vad), providers=["CPUExecutionProvider"],
                                                        sess_options=options)
        self.whisper = WhisperModel(str(self.models_dir / WHISPER_DIR), device="cpu", compute_type="int8",
                                    cpu_threads=self.threads)
        self.voice = PiperVoice.load(str(self.models_dir / PIPER_VOICE))
        self._load_wake()
        log.info("modelos carregados em %.1f s", time.monotonic() - started)

    def _load_wake(self):
        """Sem o modelo da palavra o motor funciona por clique (SPEC-013 CA-12)."""
        folder = self.models_dir / WAKE_DIR
        try:
            self.wake_features = wake.Features(folder / "melspectrogram.onnx", folder / "embedding_model.onnx")
            self.wake_classifier = wake.Classifier.load(folder / WAKE_MODEL)
            self.wake_reason = None
            log.info("palavra de ativação carregada (limiar %.3f)", self.wake_classifier.threshold)
        except Exception as error:  # noqa: BLE001 — ausente ou inválido vira motivo, não queda
            self.wake_features = self.wake_classifier = None
            self.wake_reason = "modelo da palavra ausente ou inválido"
            log.warning("sem palavra de ativação: %s", error)

    @property
    def wake_ready(self):
        return self.wake_classifier is not None

    def wake_stream(self):
        return wake.WakeStream(self.wake_features, self.wake_classifier) if self.wake_ready else None

    def warmup(self):
        """A primeira transcrição leva ~3x mais; paga-se isso antes do usuário falar."""
        started = time.monotonic()
        self.transcribe(np.zeros(SAMPLE_RATE, dtype=np.int16).tobytes())
        list(self.synthesize("Pronto."))
        if self.wake_ready:
            self.wake_stream().feed(np.zeros(2 * SAMPLE_RATE, dtype=np.int16).tobytes())
        log.info("modelos aquecidos em %.1f s", time.monotonic() - started)

    def vad(self):
        return StreamingVad(self.vad_session)

    def transcribe(self, pcm: bytes):
        """Devolve (texto, confiança 0–1, duração do áudio em ms). O texto não vai para o log."""
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        started = time.monotonic()
        segments, _ = self.whisper.transcribe(
            audio, language="pt", beam_size=1, hotwords="Zordon", without_timestamps=True,
            condition_on_previous_text=False, vad_filter=False)
        segments = list(segments)
        text = " ".join(segment.text.strip() for segment in segments).strip()
        if segments:
            logprob = sum(segment.avg_logprob for segment in segments) / len(segments)
            confidence = max(0.0, min(1.0, math.exp(logprob)))
        else:
            confidence = 0.0
        duration_ms = int(len(audio) * 1000 / SAMPLE_RATE)
        log.info("transcrição de %d ms em %.2f s", duration_ms, time.monotonic() - started)
        return text, confidence, duration_ms

    def synthesize(self, text: str):
        """PCM s16le mono na taxa da voz (22 050 Hz), em pedaços."""
        spoken = normalize.for_speech(text)
        for chunk in self.voice.synthesize(spoken):
            yield chunk.sample_rate, chunk.audio_int16_bytes


def default_threads():
    return max(1, min(8, os.cpu_count() or 1))
