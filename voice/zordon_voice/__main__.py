"""python -m zordon_voice — o motor de voz do Zordon (SPEC-011).

Copyright 2026 Willyan Faria — Apache License 2.0

Configuração por ambiente, com os padrões da unit zordon-voice.service:
  ZORDON_VOICE_SOCKET  socket Unix (padrão /run/zordon-voice/voice.sock)
  ZORDON_HOME          dados do Zordon; modelos em $ZORDON_HOME/models
  ZORDON_VOICE_THREADS threads da transcrição (padrão: até 8)
"""

import asyncio
import logging
import os
from pathlib import Path

from .engine import Models, default_threads
from .server import VoiceServer


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s - %(message)s")
    home = Path(os.environ.get("ZORDON_HOME", Path.home() / ".zordon"))
    socket_path = Path(os.environ.get("ZORDON_VOICE_SOCKET", "/run/zordon-voice/voice.sock"))
    threads = int(os.environ.get("ZORDON_VOICE_THREADS", default_threads()))
    # Limiar da palavra de ativação: sem isto vale o gravado no modelo. Baixar
    # aumenta o acerto e os disparos falsos — a curva está na SPEC-034 §3.
    wake = os.environ.get("ZORDON_WAKE_THRESHOLD")
    models = Models(home / "models", threads)
    asyncio.run(VoiceServer(models, socket_path, float(wake) if wake else None).serve())


if __name__ == "__main__":
    main()
