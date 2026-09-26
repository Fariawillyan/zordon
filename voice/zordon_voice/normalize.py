"""Normalização de texto para a fala soar natural em português (SPEC-011 §3, CA-8).

Copyright 2026 Willyan Faria — Apache License 2.0

A voz do Piper já lê números ("8" → "oito"), mas lê "15h40" como "quinze agá
quarenta". Inclui horários, siglas técnicas comuns, espaços e pontuação final.
"""

import re

_UNITS = ["zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
          "dez", "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete",
          "dezoito", "dezenove"]
_TENS = {20: "vinte", 30: "trinta", 40: "quarenta", 50: "cinquenta"}
_FEMININE = {"um": "uma", "dois": "duas"}

_TIME = re.compile(r"\b([01]?\d|2[0-3])h([0-5]\d)?\b")
_SENTENCE = re.compile(r"(?<=[.!?])\s+")
_PRONUNCIATIONS = {
    "WSL2": "dáblio ésse éle dois",
    "HTTP": "agá tê tê pê",
    "JSON": "jêison",
    "LLM": "éle éle eme",
    "MCP": "eme cê pê",
    "CPU": "cê pê u",
    "API": "á pê í",
    "STT": "ésse tê tê",
    "TTS": "tê tê ésse",
    "WSL": "dáblio ésse éle",
}
_ACRONYM = re.compile(
    r"(?<![\w])(?:" + "|".join(sorted(map(re.escape, _PRONUNCIATIONS), key=len, reverse=True))
    + r")(?![\w])",
    re.IGNORECASE,
)


def number(value, feminine=False):
    """0 a 59 por extenso."""
    if not 0 <= value <= 59:
        raise ValueError(f"fora de 0 a 59: {value}")
    if value < 20:
        word = _UNITS[value]
        return _FEMININE.get(word, word) if feminine else word
    tens, unit = divmod(value, 10)
    word = _TENS[tens * 10]
    if unit:
        last = _UNITS[unit]
        word += " e " + (_FEMININE.get(last, last) if feminine else last)
    return word


def time(hours, minutes):
    """15, 40 → "quinze horas e quarenta minutos"; 1, 0 → "uma hora"."""
    spoken = number(hours, feminine=True) + (" hora" if hours == 1 else " horas")
    if minutes:
        spoken += " e " + number(minutes) + (" minuto" if minutes == 1 else " minutos")
    return spoken


def for_speech(text):
    spoken = re.sub(r"\s+", " ", text).strip()
    spoken = _TIME.sub(lambda m: time(int(m.group(1)), int(m.group(2) or 0)), spoken)
    spoken = _ACRONYM.sub(lambda m: _PRONUNCIATIONS[m.group(0).upper()], spoken)
    # Uma frase sem pontuação final tende a receber uma queda menos natural no
    # Piper. Não toca em perguntas, exclamações ou frases já pontuadas.
    if spoken and spoken[-1].isalnum():
        spoken += "."
    return spoken


def sentences(text):
    """Divide uma fala em frases completas, preservando a pontuação."""
    spoken = for_speech(text)
    return [part for part in _SENTENCE.split(spoken) if part]
