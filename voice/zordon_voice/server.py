"""O servidor do motor no socket Unix (SPEC-011 §5, SPEC-013 §7).

Copyright 2026 Willyan Faria — Apache License 2.0

Um cliente por vez: o núcleo. Se outro se conecta, ele substitui o anterior.
Modelos carregam numa thread; até lá o estado é "starting".

Dois jeitos de ouvir: `listen` (uma escuta, por clique) e `stream` (contínuo,
pela palavra ou no modo aberto). As decisões do fluxo moram em `stream.py`.
"""

import asyncio
import logging
import os
from concurrent.futures import ThreadPoolExecutor

from . import endpoint, framing, stream

log = logging.getLogger("zordon_voice.server")

TTS_CHUNK_SECONDS = 0.1


class Listening:
    def __init__(self, vad):
        self.vad = vad
        self.endpointer = endpoint.Endpointer()
        self.audio = bytearray()


class StreamSession:
    """Um fluxo contínuo: VAD, detector da palavra e o áudio que o comando ainda pode pedir."""

    def __init__(self, logic, vad, detector):
        self.logic = logic
        self.vad = vad
        self.detector = detector
        self.buffer = bytearray()
        self.base = 0                  # amostra do primeiro byte guardado

    def analyze(self, pcm):
        """Na thread do executor: probabilidades de fala e pontuações da palavra."""
        return self.vad.feed(pcm), (self.detector.feed(pcm) if self.detector is not None else [])

    def since(self, start):
        return bytes(self.buffer[max(0, start - self.base) * 2:])

    def trim(self):
        """Só RAM: guarda os últimos 2 s dormindo, ou desde o começo do comando."""
        drop = self.logic.keep_from - self.base
        if drop > 0:
            del self.buffer[:drop * 2]
            self.base += drop


class VoiceServer:
    def __init__(self, models, socket_path):
        self.models = models
        self.socket_path = socket_path
        self.state = "starting"
        self.reason = None
        self.writer = None
        self.listening = {}
        self.streams = {}
        self.executor = ThreadPoolExecutor(max_workers=3, thread_name_prefix="zordon-voice")
        self._write_lock = asyncio.Lock()

    async def serve(self):
        self._loop = asyncio.get_running_loop()
        server = await asyncio.start_unix_server(self._client, path=str(self.socket_path))
        os.chmod(self.socket_path, 0o600)
        log.info("ouvindo em %s", self.socket_path)
        asyncio.get_running_loop().run_in_executor(self.executor, self._load)
        async with server:
            await server.serve_forever()

    def _load(self):
        try:
            self.models.load()
            self.models.warmup()
            self._set_state("ready")
        except Exception as error:  # noqa: BLE001 — qualquer falha vira estado, não queda
            log.exception("falha ao carregar os modelos")
            self._set_state("failed", str(error))

    def _set_state(self, state, reason=None):
        self.state, self.reason = state, reason
        loop = self._loop
        if loop:
            asyncio.run_coroutine_threadsafe(self._send_state(), loop)

    async def _send_state(self):
        header = {"ev": "state", "state": self.state}
        if self.state == "ready":
            header["wake"] = self.models.wake_ready
        if self.reason:
            header["reason"] = self.reason
        await self.send(header)

    async def send(self, header, payload=b""):
        writer = self.writer
        if writer is None:
            return
        async with self._write_lock:
            try:
                writer.write(framing.encode(header, payload))
                await writer.drain()
            except (ConnectionError, RuntimeError) as error:
                log.debug("cliente saiu: %s", error)

    async def _client(self, reader, writer):
        previous, self.writer = self.writer, writer
        if previous is not None:
            previous.close()
        self.listening.clear()
        self.streams.clear()
        log.info("núcleo conectado")
        await self._send_state()
        try:
            while True:
                header, payload = await framing.read(reader)
                await self._dispatch(header, payload)
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        except framing.FramingError as error:
            log.warning("mensagem inválida do núcleo: %s", error)
        finally:
            if self.writer is writer:
                self.writer = None
                self.listening.clear()
                self.streams.clear()
            writer.close()
            log.info("núcleo desconectado")

    async def _dispatch(self, header, payload):
        op = header.get("op")
        ident = header.get("id")
        if op == "listen":
            if self.state != "ready":
                await self.send({"ev": "final", "id": ident, "text": "", "confidence": 0.0,
                                 "reason": "not_ready"})
                return
            self.listening[ident] = Listening(self.models.vad())
        elif op == "stream":
            await self._open_stream(ident, str(header.get("mode", "")))
        elif op == "audio" and ident in self.streams:
            await self._stream_audio(ident, self.streams[ident], payload)
        elif op == "audio":
            await self._audio(ident, payload)
        elif op == "duplex":
            session = self.streams.get(ident)
            if session is not None:
                session.logic.duplex(bool(header.get("speaking")), bool(header.get("armed", True)))
        elif op == "listen_now":
            session = self.streams.get(ident)
            if session is not None:
                session.logic.listen_now()
        elif op == "stop":
            session = self.listening.pop(ident, None)
            if session is not None:
                await self._finish(ident, session, "stopped")
        elif op == "cancel":
            self.listening.pop(ident, None)
            self.streams.pop(ident, None)
        elif op == "speak":
            asyncio.create_task(self._speak(ident, str(header.get("text", ""))))
        else:
            log.debug("operação desconhecida: %s", op)

    async def _open_stream(self, ident, mode):
        reason = None
        if self.state != "ready":
            reason = "not_ready"
        elif mode not in (stream.WAKE, stream.OPEN):
            reason = "invalid_mode"
        elif mode == stream.WAKE and not self.models.wake_ready:
            reason = "no_wake_word"
        if reason:
            await self.send({"ev": "stream_end", "id": ident, "reason": reason})
            return
        threshold = self.models.wake_classifier.threshold if self.models.wake_ready else 1.1
        self.streams[ident] = StreamSession(stream.Stream(mode, threshold), self.models.vad(),
                                            self.models.wake_stream())
        log.info("fluxo %s aberto (%s)", ident, mode)

    async def _stream_audio(self, ident, session, pcm):
        session.buffer.extend(pcm)
        session.logic.audio(len(pcm) // 2)
        probabilities, scores = await asyncio.get_running_loop().run_in_executor(self.executor, session.analyze, pcm)
        events = []
        for probability in probabilities:
            events += session.logic.vad(probability)
        for score in scores:
            events += session.logic.score(score.end, score.score)
        for event in events:
            kind = event[0]
            if kind == stream.WAKE_WORD:
                log.info("palavra detectada no fluxo %s (%.3f)", ident, event[1])
                await self.send({"ev": "wake", "id": ident, "score": round(event[1], 3)})
            elif kind == stream.SPEECH:
                await self.send({"ev": "speech", "id": ident})
            elif kind == stream.SILENCE:
                await self.send({"ev": "end", "id": ident})
                await self.send({"ev": "final", "id": ident, "text": "", "confidence": 0.0, "reason": "silence"})
            elif kind == stream.FINAL:
                await self.send({"ev": "end", "id": ident})
                asyncio.create_task(self._stream_final(ident, session.since(event[2]), event[1], event[3]))
        session.trim()

    async def _stream_final(self, ident, pcm, reason, woken):
        """Transcreve o comando sem parar o fluxo; o resto da palavra sai do começo."""
        text, confidence, duration = await asyncio.get_running_loop().run_in_executor(
            self.executor, self.models.transcribe, pcm)
        if ident not in self.streams:
            return
        await self.send({"ev": "final", "id": ident, "text": stream.strip_wake(text) if woken else text,
                         "confidence": round(confidence, 3), "durationMs": duration, "reason": reason,
                         "woken": woken})

    async def _audio(self, ident, pcm):
        session = self.listening.get(ident)
        if session is None:
            return
        session.audio.extend(pcm)
        probabilities = await asyncio.get_running_loop().run_in_executor(self.executor, session.vad.feed, pcm)
        for probability in probabilities:
            for event in session.endpointer.feed(probability):
                if event == endpoint.SPEECH:
                    await self.send({"ev": "speech", "id": ident})
                elif event in (endpoint.END, endpoint.MAX):
                    self.listening.pop(ident, None)
                    await self._finish(ident, session, event)
                    return
                elif event == endpoint.NO_SPEECH:
                    self.listening.pop(ident, None)
                    await self.send({"ev": "end", "id": ident})
                    await self.send({"ev": "final", "id": ident, "text": "", "confidence": 0.0,
                                     "reason": "silence"})
                    return

    async def _finish(self, ident, session, reason):
        """Fim da fala: avisa já (o núcleo desliga o microfone) e depois transcreve."""
        await self.send({"ev": "end", "id": ident})
        if not session.endpointer.speaking:
            await self.send({"ev": "final", "id": ident, "text": "", "confidence": 0.0, "reason": "silence"})
            return
        text, confidence, duration = await asyncio.get_running_loop().run_in_executor(
            self.executor, self.models.transcribe, bytes(session.audio))
        await self.send({"ev": "final", "id": ident, "text": text, "confidence": round(confidence, 3),
                         "durationMs": duration, "reason": reason})

    async def _speak(self, ident, text):
        if self.state != "ready" or not text.strip():
            await self.send({"ev": "tts_end", "id": ident, "reason": "not_ready" if text.strip() else "empty"})
            return
        try:
            chunks = await asyncio.get_running_loop().run_in_executor(
                self.executor, lambda: list(self.models.synthesize(text)))
        except Exception as error:  # noqa: BLE001
            log.warning("síntese falhou: %s", error)
            await self.send({"ev": "tts_end", "id": ident, "reason": "failed"})
            return
        for rate, pcm in chunks:
            step = int(rate * TTS_CHUNK_SECONDS) * 2
            for start in range(0, len(pcm), step):
                await self.send({"ev": "tts", "id": ident, "rate": rate}, pcm[start:start + step])
        await self.send({"ev": "tts_end", "id": ident})

    _loop = None
