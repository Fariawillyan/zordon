"""O fluxo contínuo do motor (SPEC-013): dormindo pela palavra, ouvindo o comando.

Copyright 2026 Willyan Faria — Apache License 2.0

Puro, só biblioteca padrão: recebe a probabilidade de fala de cada bloco de 32 ms
(VAD) e a pontuação da palavra a cada 80 ms, e devolve o que aconteceu. O servidor
faz o áudio e os modelos; a decisão mora aqui e é testada sem modelo nenhum.

Estados de um fluxo `wake`: dormindo (só procura a palavra) → ouvindo o comando
(endpointer da SPEC-011) → dormindo. Num fluxo `open`, o comando fica sempre
aberto. Enquanto o Zordon fala (`speaking`), nada vira comando; só a palavra
interrompe, e só se estiver armada.
"""

import unicodedata
from collections import deque

from . import endpoint

WAKE = "wake"
OPEN = "open"
SAMPLE_RATE = 16_000
PREROLL = SAMPLE_RATE // 4          # 250 ms antes do disparo entram no comando: não corta o começo
REFRACTORY = 2 * SAMPLE_RATE        # 2 s sem disparar de novo
SPEECH_PROBABILITY = 0.5            # o VAD precisa ter visto fala no último ~1 s
VAD_RECENT = 32                     # blocos de 32 ms
OPEN_NO_SPEECH_MS = 10 ** 9         # em `open`, esperar fala não tem prazo

SPEECH = "speech"      # a fala do comando começou
FINAL = "final"        # a fala do comando acabou: transcrever desde `start`
SILENCE = "silence"    # a palavra veio e o comando não: volta a dormir
WAKE_WORD = "wake"     # a palavra foi dita


class Stream:
    def __init__(self, mode, threshold):
        if mode not in (WAKE, OPEN):
            raise ValueError(f"modo de fluxo inválido: {mode}")
        self.mode = mode
        self.threshold = threshold
        self.speaking = False
        self.armed = True
        self.samples = 0            # amostras recebidas desde o início do fluxo
        self.command = None         # endpointer do comando em curso
        self.start = None           # amostra onde o áudio do comando começa
        self.woken = False          # o comando em curso veio da palavra
        self._recent = deque(maxlen=VAD_RECENT)
        self._last_wake = None
        if mode == OPEN:
            self._open(0, woken=False)

    @property
    def keep_from(self):
        """Primeira amostra que o servidor ainda precisa guardar."""
        if self.command is not None:
            return self.start
        return max(0, self.samples - 2 * SAMPLE_RATE)

    def audio(self, samples):
        self.samples += samples

    def vad(self, probability):
        """Um bloco de 32 ms. Devolve os acontecimentos, em ordem."""
        self._recent.append(probability)
        if self.command is None:
            return []
        if self.mode == OPEN and not self.woken and not self.command.speaking:
            # Esperar no modo aberto não consome os 15 s do comando e não acumula
            # silêncio para o transcritor. Conserva o começo da fala (96 ms de VAD).
            self.command.elapsed_ms = 0
            self.start = max(0, self.samples - PREROLL)
        out = []
        for event in self.command.feed(probability):
            if event == endpoint.SPEECH:
                out.append((SPEECH,))
            elif event in (endpoint.END, endpoint.MAX):
                if self.command.speaking:
                    out.append((FINAL, event, self.start, self.woken))
                elif self.mode != OPEN:
                    out.append((SILENCE,))
                self._after_command()
            elif event == endpoint.NO_SPEECH:
                out.append((SILENCE,))
                self._after_command()
        return out

    def score(self, end, value):
        """Pontuação da palavra no trecho que termina na amostra `end`."""
        if not self.armed or value < self.threshold:
            return []
        if max(self._recent, default=0.0) < SPEECH_PROBABILITY:
            return []
        if self._last_wake is not None and end - self._last_wake < REFRACTORY:
            return []
        if not (self.speaking or (self.mode == WAKE and self.command is None)):
            return []           # já ouvindo um comando: a palavra não reabre
        self._last_wake = end
        self.speaking = False   # barge-in: o núcleo corta a fala ao receber o evento
        self._open(max(0, end - PREROLL), woken=True)
        return [(WAKE_WORD, value, end)]

    def duplex(self, speaking, armed):
        """O Zordon começou ou parou de falar; `armed` diz se a palavra pode interromper."""
        self.armed = armed
        if speaking:
            self.command = None
            self.start = None
        elif self.speaking and self.mode == OPEN and self.command is None:
            self._open(self.samples, woken=False)
        self.speaking = speaking

    def listen_now(self):
        """O usuário pediu para ouvir (clique): abre o comando já, sem a palavra."""
        if self.command is None or self.speaking:
            self.speaking = False
            self._open(self.samples, woken=False)

    def _open(self, start, woken):
        no_speech = OPEN_NO_SPEECH_MS if self.mode == OPEN and not woken else endpoint.Endpointer().no_speech_ms
        self.command = endpoint.Endpointer(no_speech_ms=no_speech)
        self.start = start
        self.woken = woken

    def _after_command(self):
        self.command = None
        self.start = None
        if self.mode == OPEN and not self.speaking:
            self._open(self.samples, woken=False)


_FRAGMENTS = {"zordon", "zordom", "zordao", "dom", "don", "dao", "om", "on"}


def _plain(word):
    text = unicodedata.normalize("NFKD", word.lower())
    return "".join(c for c in text if c.isalpha() and not unicodedata.combining(c))


def _distance(a, b):
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, cb in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (ca != cb))
    return row[-1]


def strip_wake(text):
    """Tira o resto da palavra do começo do comando ("Dom, que horas são?" → "Que horas são?")."""
    words = text.strip().split()
    removed = 0
    while words and removed < 2:
        token = _plain(words[0])
        if token in _FRAGMENTS or (len(token) >= 5 and _distance(token, "zordon") <= (2 if len(token) >= 6 else 1)):
            words.pop(0)
            removed += 1
        else:
            break
    result = " ".join(words).lstrip(",.;:!?-– ")
    return result[:1].upper() + result[1:]
