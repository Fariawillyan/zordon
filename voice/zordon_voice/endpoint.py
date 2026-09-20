"""Quando a fala começou e quando acabou (SPEC-011 §3, CA-2).

Copyright 2026 Willyan Faria — Apache License 2.0

Recebe a probabilidade de fala de cada bloco de 32 ms (512 amostras a 16 kHz,
o bloco do Silero) e decide. Puro: testado com probabilidades sintéticas.
"""

CHUNK_MS = 32

SPEECH = "speech"          # fala confirmada: o usuário começou a falar
END = "end"                # fala terminou: 0,6 s de silêncio depois dela
NO_SPEECH = "no_speech"    # 6 s sem fala: nada a transcrever
MAX = "max"                # 15 s de escuta: transcreve o que houver


class Endpointer:
    def __init__(self, start_threshold=0.5, end_threshold=0.35, min_speech_ms=96,
                 end_silence_ms=600, no_speech_ms=6000, max_ms=15000):
        self.start_threshold = start_threshold
        self.end_threshold = end_threshold
        self.min_speech_ms = min_speech_ms
        self.end_silence_ms = end_silence_ms
        self.no_speech_ms = no_speech_ms
        self.max_ms = max_ms
        self.elapsed_ms = 0
        self.speaking = False
        self.done = False
        self._voiced_ms = 0
        self._silence_ms = 0

    def feed(self, probability):
        """Um bloco de 32 ms. Devolve os acontecimentos deste bloco, em ordem."""
        if self.done:
            return []
        events = []
        self.elapsed_ms += CHUNK_MS
        if not self.speaking:
            self._voiced_ms = self._voiced_ms + CHUNK_MS if probability >= self.start_threshold else 0
            if self._voiced_ms >= self.min_speech_ms:
                self.speaking = True
                events.append(SPEECH)
            elif self.elapsed_ms >= self.no_speech_ms:
                return self._finish(events, NO_SPEECH)
        else:
            self._silence_ms = self._silence_ms + CHUNK_MS if probability < self.end_threshold else 0
            if self._silence_ms >= self.end_silence_ms:
                return self._finish(events, END)
        if self.elapsed_ms >= self.max_ms:
            return self._finish(events, MAX)
        return events

    def _finish(self, events, reason):
        self.done = True
        events.append(reason)
        return events
