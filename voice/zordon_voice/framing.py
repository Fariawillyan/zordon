"""Moldura das mensagens no socket entre o núcleo e o motor (SPEC-011 §5).

Copyright 2026 Willyan Faria — Apache License 2.0

Cada mensagem: tamanho do cabeçalho (u32 big-endian), cabeçalho JSON em UTF-8 e,
se o cabeçalho tiver "bytes", essa quantidade de carga binária. Só biblioteca
padrão: é testado sem as dependências pesadas.
"""

import json
import struct

MAX_HEADER = 64 * 1024
MAX_PAYLOAD = 4 * 1024 * 1024


class FramingError(ValueError):
    """Mensagem fora do formato: o outro lado não fala este protocolo."""


def encode(header: dict, payload: bytes = b"") -> bytes:
    header = dict(header)
    if payload:
        header["bytes"] = len(payload)
    else:
        header.pop("bytes", None)
    raw = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > MAX_HEADER:
        raise FramingError(f"cabeçalho de {len(raw)} bytes passa do limite de {MAX_HEADER}")
    if len(payload) > MAX_PAYLOAD:
        raise FramingError(f"carga de {len(payload)} bytes passa do limite de {MAX_PAYLOAD}")
    return struct.pack(">I", len(raw)) + raw + payload


async def read(reader):
    """Lê uma mensagem de um asyncio.StreamReader: (cabeçalho, carga)."""
    size = struct.unpack(">I", await reader.readexactly(4))[0]
    if size == 0 or size > MAX_HEADER:
        raise FramingError(f"cabeçalho de {size} bytes")
    try:
        header = json.loads((await reader.readexactly(size)).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise FramingError(f"cabeçalho não é JSON: {error}") from error
    if not isinstance(header, dict):
        raise FramingError("cabeçalho precisa ser um objeto JSON")
    length = header.get("bytes", 0)
    if not isinstance(length, int) or length < 0 or length > MAX_PAYLOAD:
        raise FramingError(f"carga de {length!r} bytes")
    payload = await reader.readexactly(length) if length else b""
    return header, payload
