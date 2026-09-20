"""O mínimo para a fala soar certa em português (SPEC-011 §3, CA-8).

Copyright 2026 Willyan Faria — Apache License 2.0

A voz do Piper já lê números ("8" → "oito"), mas lê "15h40" como "quinze agá
quarenta". Aqui só os horários; a normalização completa tem item próprio no
roadmap.
"""

import re

_UNITS = ["zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
          "dez", "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete",
          "dezoito", "dezenove"]
_TENS = {20: "vinte", 30: "trinta", 40: "quarenta", 50: "cinquenta"}
_FEMININE = {"um": "uma", "dois": "duas"}

_TIME = re.compile(r"\b([01]?\d|2[0-3])h([0-5]\d)?\b")


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
    return _TIME.sub(lambda m: time(int(m.group(1)), int(m.group(2) or 0)), text)
