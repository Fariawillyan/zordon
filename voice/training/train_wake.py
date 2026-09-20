#!/usr/bin/env python3
"""Treino do detector da palavra "Zordon" (SPEC-013, ADR-0038).

Copyright 2026 Willyan Faria — Apache License 2.0

Tarefa de desenvolvimento, fora da instalação. Roda no venv do motor (nenhum
pacote a mais) e escreve em ~/.zordon/training. Nada é apagado: cada etapa grava
num arquivo novo e a seguinte reaproveita o que já está em disco.

    ~/.zordon/venv/bin/python voice/training/train_wake.py all

Etapas:
  fetch     fontes do training.lock, verificadas por SHA-256
  mls       fala do MLS português, lida em fluxo e dividida por locutor
  synth     positivos e negativos sintetizados com as vozes do Piper
  features  embeddings de tudo, com as mesmas features da execução (wake.py)
  train     MLP em numpy; limiar escolhido na validação, números medidos no teste

Separação: a voz cadu, 10% dos locutores LibriTTS e locutores do MLS que o
treino não viu ficam só no teste. O ruído interno do VITS do Piper não aceita
semente, então a síntese é reprodutível em distribuição, não bit a bit; o que se
fixa é o modelo gerado, pelo SHA-256 no models.lock.
"""

import argparse
import hashlib
import json
import math
import os
import sys
import tarfile
import time
import urllib.request
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

# Cada processo de features já ocupa um núcleo; BLAS não deve multiplicar isso.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "voice"))
from zordon_voice import wake  # noqa: E402

ROOT = Path(os.environ.get("ZORDON_TRAINING_DIR", Path.home() / ".zordon" / "training"))
SOURCES = ROOT / "sources"
MLS_DIR = ROOT / "mls-pt"
WORK = ROOT / "work"
MODELS = Path(os.environ.get("ZORDON_MODELS_DIR", Path.home() / ".zordon" / "models"))
LOCK = Path(__file__).with_name("training.lock")
OUTPUT = REPO / "voice" / "models" / "zordon-wake-v1.npz"
REPORT = Path(__file__).with_name("report-zordon-wake-v1.md")
METRICS = Path(__file__).with_name("report-zordon-wake-v1.json")

SEED = 20260918
SR = wake.SAMPLE_RATE
QUOTA_HOURS = {"test": 10.5, "val": 5.0, "train": 30.0}
SPEAKER_CAP_HOURS = 7.0
WORKERS = max(1, min(10, (os.cpu_count() or 2) - 2))
MLS_ARCHIVE = "mls/mls_portuguese_opus.tar.gz"
REFRACTORY = 25                     # embeddings: 2 s
POSITIVE_AFTER = 3200               # janela positiva termina até 200 ms depois da palavra
DETECTION_AFTER = 9600              # no teste, vale detectar até 600 ms depois

PT_VOICES = {
    "faber": (MODELS / "piper" / "pt_BR-faber-medium.onnx", "train"),
    "jeff": (SOURCES / "piper" / "pt_BR-jeff-medium.onnx", "train"),
    "edresson": (SOURCES / "piper" / "pt_BR-edresson-low.onnx", "train"),
    "cadu": (SOURCES / "piper" / "pt_BR-cadu-medium.onnx", "test"),
}
LIBRITTS = SOURCES / "piper" / "en_US-libritts_r-medium.onnx"
LIBRITTS_SPEAKERS = 904

WORDS_PT = ["Zordon", "Zórdon", "Zôrdon"]
FORMS_PT = ["{w}.", "{w}!", "{w}?", "{w},", "Ei, {w}.", "Ô {w}!", "Oi, {w}.", "Ok, {w}.", "E aí, {w}?"]
FORMS_EN = ["Zordon.", "Zordon!", "Zordon?", "Hey, Zordon.", "Okay, Zordon."]
TAILS_PT = ["que horas são?", "abre o navegador.", "como está o tempo?", "desliga o microfone.",
            "quantos containers estão rodando?", "para.", "toca uma música.", "me ajuda aqui."]
CONFUSABLES_PT = [
    "Gordon.", "O Gordon ligou ontem.", "Jordão.", "O rio Jordão é longo.", "Cordão.", "Comprei um cordão.",
    "Zorro.", "O Zorro voltou.", "Sordo.", "Condor.", "O condor voou alto.", "Ordem.", "Tudo em ordem.",
    "Dom.", "Tudo bom.", "Zona.", "Zoom.", "Sorte.", "Tordo.", "Bordão.", "Gordão.", "Jordana.",
    "Sardinha.", "Dormindo.", "Acordou.", "Zé Gordo.", "Corda.", "Zerar.", "Sorvete.", "Gordo e magro.",
    "Sorteio.", "Recordação.", "Gordura.", "Portão.", "Sermão.", "Zangão.", "Zorba.", "Power Rangers.",
    "Sordão.", "Transformers.", "Zé Rodão.", "Por favor.", "Todo dom.", "Do dom.", "Sol e som.",
]
CONFUSABLES_EN = ["Gordon.", "Jordan.", "Warden.", "Garden.", "Border.", "Order.", "Sorting.", "Zoran.",
                  "Sort it on.", "Hold on.", "Zorro.", "Soldier."]
NARRATION = ["Microfone funcionando.", "São 19h21.", "São dez e meia.", "Não entendi.", "Pronto.",
             "Certo, estou verificando.", "Abrindo o navegador.", "Terminei.", "Preciso da sua autorização.",
             "Não consegui acessar o arquivo.", "Você tem oito containers rodando.", "Conversa aberta.",
             "Até mais.", "Estou ouvindo.", "Tudo certo por aqui."]


# ───────────────────────── utilidades ─────────────────────────

def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_atomic(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part")
    part.write_bytes(data)
    os.replace(part, path)


def save_npz(path: Path, **arrays):
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part.npz")
    np.savez(part, **arrays)
    os.replace(part, path)


def load_npz(path: Path):
    with np.load(path, allow_pickle=False) as data:
        return {key: data[key] for key in data.files}


def resample(samples: np.ndarray, rate: int) -> np.ndarray:
    if rate == SR:
        return samples.astype(np.int16)
    import av
    resampler = av.AudioResampler(format="s16", layout="mono", rate=SR)
    frame = av.AudioFrame.from_ndarray(samples.astype(np.int16)[None, :], format="s16", layout="mono")
    frame.sample_rate = rate
    out = resampler.resample(frame) + resampler.resample(None)
    return np.concatenate([f.to_ndarray().reshape(-1) for f in out]).astype(np.int16)


def decode(path: Path) -> np.ndarray:
    import av
    chunks = []
    # Algumas tags do MLS vêm em latin-1; o áudio é o que importa.
    with av.open(str(path), metadata_errors="ignore") as container:
        resampler = av.AudioResampler(format="s16", layout="mono", rate=SR)
        for frame in container.decode(audio=0):
            chunks.extend(out.to_ndarray().reshape(-1) for out in resampler.resample(frame))
        chunks.extend(out.to_ndarray().reshape(-1) for out in resampler.resample(None))
    return np.concatenate(chunks).astype(np.int16) if chunks else np.zeros(0, dtype=np.int16)


def word_end_in_phrase(audio: np.ndarray):
    """Início da fala e fim da primeira palavra numa frase "Zordon, ...": a pausa da vírgula.

    Devolve (início, fim) em amostras ou None se não houver pausa clara entre 250 ms e 1,3 s.
    """
    frame = 160
    n = len(audio) // frame
    if n < 20:
        return None
    rms = np.sqrt(np.mean(audio[:n * frame].astype(np.float32).reshape(n, frame) ** 2, axis=1))
    floor = max(rms.max() * 0.1, 30.0)
    voiced = np.nonzero(rms > floor)[0]
    if len(voiced) == 0:
        return None
    onset = int(voiced[0])
    quiet_run = 0
    for i in range(onset + 25, min(n, onset + 130)):
        quiet_run = quiet_run + 1 if rms[i] < floor else 0
        if quiet_run >= 5:
            return onset * frame, (i - 4) * frame
    return None


def trim(audio: np.ndarray):
    """Corta o silêncio; devolve (clipe, início da voz, fim da voz) em amostras do clipe."""
    frame = 160
    n = len(audio) // frame
    if n == 0:
        return audio, 0, len(audio)
    rms = np.sqrt(np.mean(audio[:n * frame].astype(np.float32).reshape(n, frame) ** 2, axis=1))
    voiced = np.nonzero(rms > max(rms.max() * 0.05, 30.0))[0]
    if len(voiced) == 0:
        return audio, 0, len(audio)
    first, last = int(voiced[0]) * frame, (int(voiced[-1]) + 1) * frame
    start, end = max(0, first - 480), min(len(audio), last + 480)
    return audio[start:end], first - start, last - start


# ───────────────────────── fetch ─────────────────────────

def lock_entries():
    for line in LOCK.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            target, url, sha, size = line.split("\t")
            yield target, url, sha, int(size)


def download(url, path: Path, size: int) -> str:
    """Baixa em fluxo para `.part`, confere o tamanho e só então dá o nome final."""
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part")
    digest = hashlib.sha256()
    received = 0
    with urllib.request.urlopen(url) as response, open(part, "wb") as out:
        for block in iter(lambda: response.read(1 << 20), b""):
            out.write(block)
            digest.update(block)
            received += len(block)
            if size > 50_000_000 and received % (256 << 20) < (1 << 20):
                log(f"  {received / 1e9:.2f} de {size / 1e9:.2f} GB")
    if received != size:
        raise SystemExit(f"download incompleto: {path.name} ({received} de {size} bytes)")
    os.replace(part, path)
    return digest.hexdigest()


def cmd_fetch(_):
    for target, url, sha, size in lock_entries():
        path = SOURCES / target
        if path.exists() and path.stat().st_size == size and (sha == "-" or sha256_of(path) == sha):
            continue
        log(f"baixando {target} ({size / 1e6:.1f} MB)")
        got = download(url, path, size)
        if sha == "-":
            log(f"  {target}: sha256 {got} — fixe no training.lock")
        elif got != sha:
            raise SystemExit(f"hash não bate: {target}")
    for name, (path, _) in PT_VOICES.items():
        if not path.exists():
            raise SystemExit(f"voz ausente: {name} ({path})")
    log("fontes conferidas")


# ───────────────────────── mls ─────────────────────────

def cmd_mls(_):
    manifest = MLS_DIR / "manifest.tsv"
    if manifest.exists() and all(sum(float(r[3]) for r in mls_rows(s)) >= QUOTA_HOURS[s] * 3600 - 120
                                 for s in QUOTA_HOURS):
        log("mls: manifesto já existe")
        return
    archive = SOURCES / MLS_ARCHIVE
    durations, transcripts = {}, {}
    # Primeira passada: durações e transcrições (no tar elas vêm depois do áudio).
    with tarfile.open(archive, mode="r|gz") as tar:
        for member in tar:
            parts = member.name.split("/")
            if member.isfile() and len(parts) == 3 and parts[2] in ("segments.txt", "transcripts.txt"):
                for line in tar.extractfile(member).read().decode("utf-8").splitlines():
                    fields = line.split("\t")
                    if parts[2] == "segments.txt" and len(fields) >= 4:
                        durations[fields[0]] = float(fields[3]) - float(fields[2])
                    elif parts[2] == "transcripts.txt" and len(fields) >= 2:
                        transcripts[fields[0]] = fields[1]
    log(f"mls: {len(durations)} durações, {len(transcripts)} transcrições")
    totals = Counter()
    per_speaker = Counter()
    assigned = {}
    rows = []
    started = time.monotonic()
    with tarfile.open(archive, mode="r|gz") as tar:
        for member in tar:
            parts = member.name.split("/")
            if not member.isfile() or not member.name.endswith(".opus"):
                continue
            origin, speaker = parts[1], int(parts[3])
            ident = Path(parts[-1]).stem
            seconds = durations.get(ident, member.size / 5800)
            if origin in ("dev", "test"):
                split = "test"
            else:
                if speaker not in assigned:
                    # Locutor novo vai inteiro para o primeiro conjunto que ainda precisa de horas.
                    assigned[speaker] = next((s for s in ("test", "val") if totals[s] < QUOTA_HOURS[s] * 3600 - 60),
                                             "train")
                split = assigned[speaker]
            if totals[split] + seconds > QUOTA_HOURS[split] * 3600:
                continue
            if split == "train" and per_speaker[speaker] + seconds > SPEAKER_CAP_HOURS * 3600:
                continue
            data = tar.extractfile(member).read()
            write_atomic(MLS_DIR / split / f"{ident}.opus", data)
            rows.append((split, ident, str(speaker), f"{seconds:.2f}", hashlib.sha256(data).hexdigest(),
                         transcripts.get(ident, "").replace("\t", " ")))
            totals[split] += seconds
            per_speaker[speaker] += seconds
            if len(rows) % 500 == 0:
                log("mls: " + ", ".join(f"{s} {totals[s] / 3600:.1f} h" for s in QUOTA_HOURS))
            if all(totals[s] >= QUOTA_HOURS[s] * 3600 - 60 for s in QUOTA_HOURS):
                break
    header = "# split\tid\tlocutor\tsegundos\tsha256\ttranscrição (MLS português, CC BY 4.0)\n"
    write_atomic(manifest, (header + "".join("\t".join(r) + "\n" for r in rows)).encode("utf-8"))
    log(f"mls: {len(rows)} arquivos em {time.monotonic() - started:.0f} s — "
        + ", ".join(f"{s} {totals[s] / 3600:.2f} h" for s in QUOTA_HOURS))


EXTRA_DIR = ROOT / "extra"
EXTRA_SOURCES = {
    # nome: (arquivo no training.lock, horas, teto por locutor em horas, extensão)
    "it": ("mls/mls_italian_opus.tar.gz", 30.0, 1.0, ".opus"),
    "en": ("librispeech/train-clean-100.tar.gz", 40.0, 0.2, ".flac"),
}


def cmd_extra(_):
    """Tentativa 3: fala real de muitos locutores (italiano do MLS, inglês do LibriSpeech), só como negativo."""
    for name, (archive, quota_hours, cap_hours, suffix) in EXTRA_SOURCES.items():
        manifest = EXTRA_DIR / name / "manifest.tsv"
        if manifest.exists():
            continue
        rows, total, per_speaker = [], 0.0, Counter()
        with tarfile.open(SOURCES / archive, mode="r|gz") as tar:
            for member in tar:
                if not member.isfile() or not member.name.endswith(suffix):
                    continue
                parts = member.name.split("/")
                if "train" not in parts and "train-clean-100" not in parts:
                    continue
                speaker = parts[3] if name == "it" else parts[2]
                seconds = member.size / (5800 if suffix == ".opus" else 20000)   # flac 16 kHz ≈ 20 KB/s
                if per_speaker[speaker] + seconds > cap_hours * 3600:
                    continue
                data = tar.extractfile(member).read()
                ident = Path(parts[-1]).stem
                write_atomic(EXTRA_DIR / name / f"{ident}{suffix}", data)
                rows.append((ident + suffix, speaker, f"{seconds:.1f}", hashlib.sha256(data).hexdigest()))
                total += seconds
                per_speaker[speaker] += seconds
                if total >= quota_hours * 3600:
                    break
        header = "# arquivo\tlocutor\tsegundos (estimado)\tsha256 — CC BY 4.0\n"
        write_atomic(manifest, (header + "".join("\t".join(r) + "\n" for r in rows)).encode("utf-8"))
        log(f"extra {name}: {len(rows)} arquivos, {len(per_speaker)} locutores, ~{total / 3600:.1f} h")

    target = WORK / "features" / "extra_train.npz"
    if target.exists():
        return
    files = [str(EXTRA_DIR / name / line.split("\t")[0])
             for name in EXTRA_SOURCES
             for line in (EXTRA_DIR / name / "manifest.tsv").read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("#")]
    log(f"features: extra, {len(files)} arquivos")
    with ProcessPoolExecutor(WORKERS, initializer=_init_features) as pool:
        parts = [e for result in pool.map(_mls_job, [files[i:i + 40] for i in range(0, len(files), 40)])
                 for e in result]
    data, offsets = _sequences(parts)
    save_npz(target, data=data, offsets=offsets)
    log("features: extra prontas")


def mls_rows(split=None):
    rows = []
    for line in (MLS_DIR / "manifest.tsv").read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        fields = line.split("\t")
        if split is None or fields[0] == split:
            rows.append(fields)
    return rows


# ───────────────────────── synth ─────────────────────────

def _one_thread(path):
    """Uma thread por processo: dez processos com uma sessão de 12 threads cada afogam a máquina."""
    import onnxruntime

    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.log_severity_level = 4
    return onnxruntime.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])


def _synth_job(job):
    from piper import PiperVoice, SynthesisConfig

    voice_path, portuguese, items, seed = job
    voice = PiperVoice.load(str(voice_path))
    voice.session = _one_thread(voice_path)
    # Com `portuguese`, a fonetização é a do espeak pt-br: os 904 locutores do LibriTTS falam português.
    phonemizer = PiperVoice.load(str(PT_VOICES["faber"][0])) if portuguese else None
    label = Path(voice_path).stem + ("-pt" if portuguese else "")
    rng = np.random.default_rng(seed)
    out = []
    for speaker, kind, split, text in items:
        config = SynthesisConfig(speaker_id=speaker, length_scale=float(rng.uniform(0.75, 1.35)),
                                 noise_scale=float(rng.uniform(0.4, 0.9)), noise_w_scale=float(rng.uniform(0.5, 1.1)))
        if phonemizer is not None:
            parts = [voice.phoneme_ids_to_audio(voice.phonemes_to_ids(sentence), config)
                     for sentence in phonemizer.phonemize(text)]
            if not parts:
                continue
            audio = np.concatenate(parts)
            pcm = np.clip(audio * 32767, -32768, 32767).astype(np.int16)
            rate = voice.config.sample_rate
        else:
            chunks = list(voice.synthesize(text, syn_config=config))
            if not chunks:
                continue
            pcm = np.frombuffer(b"".join(c.audio_int16_bytes for c in chunks), dtype="<i2")
            rate = chunks[0].sample_rate
        clip, start, end = trim(resample(pcm, rate))
        out.append((kind, split, label, -1 if speaker is None else speaker, text, clip, start, end))
    return out


def cmd_synth(_):
    target = WORK / "synth.npz"
    if target.exists():
        log("synth: já existe")
        return
    rng = np.random.default_rng(SEED)
    transcripts = {s: [r[5] for r in mls_rows(s) if r[5]] for s in ("train", "val", "test")}
    jobs = []
    for name, (path, split) in PT_VOICES.items():
        items = []
        # A edresson não tem o fonema nasal: o "Zordon" dela sai "Zordo". Só entra nos negativos.
        positives = 0 if name == "edresson" else 300 if split == "test" else 900
        for i in range(positives):
            form = FORMS_PT[i % len(FORMS_PT)].format(w=WORDS_PT[(i // len(FORMS_PT)) % len(WORDS_PT)])
            item_split = split if split == "test" else ("val" if i % 10 == 0 else "train")
            items.append((None, "pos", item_split, form))
        items += [(None, "tail", split, text) for text in TAILS_PT for _ in range(3)]
        items += [(None, "conf", split, text) for text in CONFUSABLES_PT for _ in range(6 if split == "train" else 3)]
        lines = transcripts["test" if split == "test" else "train"]
        chosen = rng.choice(len(lines), size=min(len(lines), 180), replace=False) if lines else []
        items += [(None, "speech", split, " ".join(lines[int(k)].split()[:14])) for k in chosen]
        items += [(None, "speech", split, text) for text in NARRATION for _ in range(3)]
        if split == "train":
            val_lines = transcripts["val"]
            chosen = rng.choice(len(val_lines), size=min(len(val_lines), 30), replace=False) if val_lines else []
            items += [(None, "speech", "val", " ".join(val_lines[int(k)].split()[:14])) for k in chosen]
            items += [(None, "conf", "val", text) for text in CONFUSABLES_PT]
        rng.shuffle(items)
        for part in np.array_split(np.arange(len(items)), 4):
            jobs.append((path, False, [items[int(k)] for k in part], int(rng.integers(1 << 31))))
    speakers = np.arange(LIBRITTS_SPEAKERS)
    for part in np.array_split(speakers, 18):
        items = []
        for speaker in part:
            speaker = int(speaker)
            split = "test" if speaker % 10 == 0 else "val" if speaker % 10 == 1 else "train"
            for k in range(3):
                items.append((speaker, "pos", split, FORMS_EN[int(rng.integers(len(FORMS_EN)))]))
            items.append((speaker, "conf", split, CONFUSABLES_EN[int(rng.integers(len(CONFUSABLES_EN)))]))
        jobs.append((LIBRITTS, False, items, int(rng.integers(1 << 31))))
    save_synth(target, run_synth(jobs))


PHRASE_TAILS = TAILS_PT + ["qual é a previsão do tempo?", "abre o IntelliJ.", "quanto de disco eu tenho?",
                           "pausa a música.", "lê o meu e-mail.", "liga o modo aberto."]


def _phrase_job(job):
    """Frases "Zordon, <comando>": a palavra com a prosódia de dentro da frase (tentativa 5)."""
    from piper import PiperVoice, SynthesisConfig

    voice_path, portuguese, items, seed = job
    voice = PiperVoice.load(str(voice_path))
    voice.session = _one_thread(voice_path)
    phonemizer = PiperVoice.load(str(PT_VOICES["faber"][0])) if portuguese else None
    label = Path(voice_path).stem + ("-pt" if portuguese else "")
    rng = np.random.default_rng(seed)
    out = []
    for speaker, split, text in items:
        config = SynthesisConfig(speaker_id=speaker, length_scale=float(rng.uniform(0.8, 1.25)),
                                 noise_scale=float(rng.uniform(0.4, 0.9)), noise_w_scale=float(rng.uniform(0.5, 1.1)))
        if phonemizer is not None:
            parts = [voice.phoneme_ids_to_audio(voice.phonemes_to_ids(sentence), config)
                     for sentence in phonemizer.phonemize(text)]
            if not parts:
                continue
            pcm = np.clip(np.concatenate(parts) * 32767, -32768, 32767).astype(np.int16)
            rate = voice.config.sample_rate
        else:
            chunks = list(voice.synthesize(text, syn_config=config))
            if not chunks:
                continue
            pcm = np.frombuffer(b"".join(c.audio_int16_bytes for c in chunks), dtype="<i2")
            rate = chunks[0].sample_rate
        audio = resample(pcm, rate)
        marks = word_end_in_phrase(audio)
        if marks is None:
            continue
        start, end = marks
        clip = audio[max(0, start - 480):]
        offset = max(0, start - 480)
        out.append(("posphrase", split, label, -1 if speaker is None else speaker, text, clip, start - offset,
                    end - offset))
    return out


def cmd_synth3(_):
    """Tentativa 5: "Zordon, que horas são?" dito de uma vez, com a pausa da vírgula marcando a palavra."""
    target = WORK / "synth-v3.npz"
    if target.exists():
        log("synth3: já existe")
        return
    rng = np.random.default_rng(SEED + 20)
    jobs = []
    def phrase():
        prefix = ["", "", "Ei ", "Ô "][int(rng.integers(4))]   # sem vírgula: a primeira pausa é a da palavra
        word = ["Zordon", "Zórdon", "Zôrdon"][int(rng.integers(3))]
        return prefix + word + ", " + PHRASE_TAILS[int(rng.integers(len(PHRASE_TAILS)))]
    for name, (path, split) in PT_VOICES.items():
        if name == "edresson":
            continue
        count = 200 if split == "test" else 700
        items = [(None, split if split == "test" else ("val" if i % 10 == 0 else "train"), phrase())
                 for i in range(count)]
        for part in np.array_split(np.arange(len(items)), 4):
            jobs.append((path, False, [items[int(k)] for k in part], int(rng.integers(1 << 31))))
    for part in np.array_split(np.arange(LIBRITTS_SPEAKERS), 24):
        items = []
        for speaker in part:
            speaker = int(speaker)
            split = "test" if speaker % 10 == 0 else "val" if speaker % 10 == 1 else "train"
            items += [(speaker, split, phrase()) for _ in range(4)]
        jobs.append((LIBRITTS, True, items, int(rng.integers(1 << 31))))
    log(f"synth3: {sum(len(j[2]) for j in jobs)} frases em {len(jobs)} tarefas")
    records = []
    with ProcessPoolExecutor(WORKERS) as pool:
        for result in pool.map(_phrase_job, jobs):
            records.extend(result)
    save_synth(target, records)


def cmd_synth2(_):
    """Tentativa 2: os locutores do LibriTTS falando português (fonemas pt-br)."""
    target = WORK / "synth-v2.npz"
    if target.exists():
        log("synth2: já existe")
        return
    rng = np.random.default_rng(SEED + 10)
    transcripts = {s: [r[5] for r in mls_rows(s) if r[5]] for s in ("train", "val", "test")}
    jobs = []
    for part in np.array_split(np.arange(LIBRITTS_SPEAKERS), 30):
        items = []
        for speaker in part:
            speaker = int(speaker)
            split = "test" if speaker % 10 == 0 else "val" if speaker % 10 == 1 else "train"
            for _ in range(6):
                word = WORDS_PT[int(rng.integers(len(WORDS_PT)))]
                items.append((speaker, "pos", split, FORMS_PT[int(rng.integers(len(FORMS_PT)))].format(w=word)))
            lines = transcripts[split]
            for _ in range(2):
                items.append((speaker, "speech", split, " ".join(lines[int(rng.integers(len(lines)))].split()[:14])))
                items.append((speaker, "conf", split, CONFUSABLES_PT[int(rng.integers(len(CONFUSABLES_PT)))]))
        jobs.append((LIBRITTS, True, items, int(rng.integers(1 << 31))))
    save_synth(target, run_synth(jobs))


def run_synth(jobs):
    log(f"synth: {sum(len(j[2]) for j in jobs)} falas em {len(jobs)} tarefas, {WORKERS} processos")
    records = []
    with ProcessPoolExecutor(WORKERS) as pool:
        for done, result in enumerate(pool.map(_synth_job, jobs), 1):
            records.extend(result)
            if done % 5 == 0:
                log(f"synth: {done}/{len(jobs)} tarefas")
    return records


def save_synth(target, records):
    audio = [r[5] for r in records]
    offsets = np.cumsum([0] + [len(a) for a in audio])
    meta = [{"kind": r[0], "split": r[1], "voice": r[2], "speaker": r[3], "text": r[4],
             "start": int(r[6]), "end": int(r[7])} for r in records]
    save_npz(target, audio=np.concatenate(audio), offsets=offsets, meta=np.array(json.dumps(meta, ensure_ascii=False)))
    counts = Counter((m["kind"], m["split"]) for m in meta)
    log("synth: " + ", ".join(f"{k}/{s} {n}" for (k, s), n in sorted(counts.items())))


def load_synth():
    out = []
    for name in ("synth.npz", "synth-v2.npz", "synth-v3.npz"):
        if not (WORK / name).exists():
            continue
        data = load_npz(WORK / name)
        meta = json.loads(str(data["meta"]))
        audio, offsets = data["audio"], data["offsets"]
        for i, m in enumerate(meta):
            m["audio"] = audio[offsets[i]:offsets[i + 1]]
        out += meta
    return out


# ───────────────────────── augmentação ─────────────────────────

def noise(rng, kind, n):
    white = rng.normal(0, 1, n)
    if kind == "white":
        return white
    spectrum = np.fft.rfft(white)
    f = np.arange(len(spectrum)) + 1.0
    spectrum /= np.sqrt(f) if kind == "pink" else f
    shaped = np.fft.irfft(spectrum, n)
    return shaped / (np.std(shaped) + 1e-9)


def reverb(rng, x):
    rt60 = rng.uniform(0.15, 0.7)
    length = int(rt60 * SR)
    t = np.arange(length) / SR
    rir = rng.normal(0, 1, length) * np.exp(-6.9 * t / rt60) * rng.uniform(0.1, 0.5)
    rir[0] = 1.0
    size = 1 << int(math.ceil(math.log2(len(x) + length)))
    wet = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(rir, size), size)[:len(x)]
    return wet * (np.max(np.abs(x)) / (np.max(np.abs(wet)) + 1e-9))


def quiet(rng, n):
    if n <= 0:
        return np.zeros(0)
    return noise(rng, rng.choice(["white", "pink", "brown"]), n) * rng.uniform(0.0005, 0.006)


def augment(rng, x, babble):
    """float em −1..1 → int16, com reverberação, ruído de fundo e ganho sorteados."""
    if rng.random() < 0.4:
        x = reverb(rng, x)
    kind = rng.choice(["white", "pink", "brown", "babble", "none"], p=[0.15, 0.25, 0.15, 0.25, 0.2])
    loud = np.abs(x) > 0.05 * (np.max(np.abs(x)) + 1e-9)
    power = np.mean(x[loud] ** 2) if loud.any() else np.mean(x ** 2) + 1e-9
    if kind != "none":
        if kind == "babble" and len(babble) > len(x):
            start = int(rng.integers(len(babble) - len(x)))
            n = babble[start:start + len(x)].astype(np.float64) / 32768
            snr = rng.uniform(10, 25)
        else:
            n = noise(rng, "pink" if kind == "babble" else kind, len(x))
            snr = rng.uniform(5, 30)
        x = x + n * np.sqrt(power / ((np.mean(n ** 2) + 1e-12) * 10 ** (snr / 10)))
    x = x / (np.max(np.abs(x)) + 1e-9) * rng.uniform(0.05, 0.9)
    return np.clip(x * 32767, -32768, 32767).astype(np.int16)


def pitch_tempo(rng, audio, start, end):
    """Muda tom e ritmo juntos (fator 0,88–1,12): outro tamanho de trato vocal, outra velocidade."""
    factor = rng.uniform(0.88, 1.12)
    n = int(len(audio) / factor)
    if n < 2:
        return audio, start, end
    out = np.interp(np.arange(n) * factor, np.arange(len(audio)), audio.astype(np.float64))
    return out.astype(np.int16), int(start / factor), int(end / factor)


def as_float(audio):
    return audio.astype(np.float64) / 32768


# ───────────────────────── features ─────────────────────────

_features = None


def _init_features():
    global _features
    _features = wake.Features(SOURCES / "oww" / "melspectrogram.onnx", SOURCES / "oww" / "embedding_model.onnx")


def _babble(rng, files, count=4):
    picks = [files[int(k)] for k in rng.choice(len(files), size=min(count, len(files)), replace=False)]
    return np.concatenate([decode(Path(p)) for p in picks]) if picks else np.zeros(0, dtype=np.int16)


def _mls_job(paths):
    out = []
    for p in paths:
        try:
            out.append(_features.clip_embeddings(decode(Path(p))).astype(np.float16))
        except Exception as error:  # noqa: BLE001 — um arquivo ruim não derruba 30 h de dados
            log(f"features: {Path(p).name} ignorado: {error}")
    return out


def _negative_job(job):
    clips, babble_files, seed = job
    rng = np.random.default_rng(seed)
    babble = _babble(rng, babble_files)
    out = []
    for clip in clips:
        lead, tail = quiet(rng, int(rng.uniform(1.2, 2.2) * SR)), quiet(rng, int(0.3 * SR))
        if rng.random() < 0.5:
            clip = pitch_tempo(rng, clip, 0, len(clip))[0]
        x = np.concatenate([lead, as_float(clip) * rng.uniform(0.3, 1.0), tail])
        out.append(_features.clip_embeddings(augment(rng, x, babble)).astype(np.float16))
    return out


def _noise_job(seed):
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(20):
        n = int(10 * SR)
        x = noise(rng, rng.choice(["white", "pink", "brown"]), n) * rng.uniform(0.0005, 0.2)
        for _ in range(int(rng.integers(0, 6))):     # estalos e batidas
            at = int(rng.integers(n - 800))
            x[at:at + 800] += rng.normal(0, rng.uniform(0.05, 0.5), 800) * np.exp(-np.arange(800) / 120)
        out.append(_features.clip_embeddings(np.clip(x * 32767, -32768, 32767).astype(np.int16)).astype(np.float16))
    return out


def _positive_job(job):
    """Palavra com contexto antes e depois; devolve janelas marcadas por clipe."""
    items, tails, fillers, babble_files, repeats, strict, seed = job
    rng = np.random.default_rng(seed)
    babble = _babble(rng, babble_files)
    windows, clip_ids, labels, context = [], [], [], []
    for clip_id, (audio, start, end, voice, inline) in items:
        for variant in range(repeats):
            lead_len = int(rng.uniform(2.0, 3.0) * SR)
            choice = rng.random()
            if choice < 0.35 and fillers.get(voice):
                filler = as_float(fillers[voice][int(rng.integers(len(fillers[voice])))])[:lead_len]
                gap = quiet(rng, int(rng.uniform(0.15, 0.8) * SR))
                body = np.concatenate([filler * rng.uniform(0.3, 1.0), gap])[-lead_len:]
                lead = np.concatenate([quiet(rng, lead_len - len(body)), body])
            elif choice < 0.55 and len(babble) > lead_len:
                s = int(rng.integers(len(babble) - lead_len))
                lead = as_float(babble[s:s + lead_len]) * rng.uniform(0.05, 0.3) + quiet(rng, lead_len)
            else:
                lead = quiet(rng, lead_len)
            if inline:
                tail = quiet(rng, int(0.2 * SR))
            elif rng.random() < 0.5 and tails.get(voice):
                t = as_float(tails[voice][int(rng.integers(len(tails[voice])))])
                tail = np.concatenate([quiet(rng, int(rng.uniform(0.05, 0.3) * SR)), t])[:int(0.7 * SR)]
            else:
                tail = quiet(rng, int(0.7 * SR))
            spoken, s0, s1 = pitch_tempo(rng, audio, start, end) if rng.random() < 0.6 else (audio, start, end)
            x = np.concatenate([lead, as_float(spoken), tail])
            word_start, word_end = len(lead) + s0, len(lead) + s1
            emb = _features.clip_embeddings(augment(rng, x, babble))
            for i in range(wake.EMBEDDINGS - 1, len(emb)):
                stop = wake.embedding_end(i)
                window = emb[i - wake.EMBEDDINGS + 1:i + 1].astype(np.float16)
                if word_end <= stop < word_end + (POSITIVE_AFTER if strict else DETECTION_AFTER):
                    windows.append(window)
                    # Cada variante conta: acertar uma não pode esconder erros nas outras.
                    clip_ids.append(clip_id * repeats + variant)
                    labels.append(1 if stop < word_end + POSITIVE_AFTER else 2)
                elif stop <= word_start and strict:
                    context.append(window)
    shape = (0, wake.EMBEDDINGS, wake.EMBEDDING_SIZE)
    return (np.stack(windows) if windows else np.zeros(shape, np.float16), np.array(clip_ids, dtype=np.int32),
            np.array(labels, dtype=np.int8), np.stack(context) if context else np.zeros(shape, np.float16))


def _sequences(parts):
    lengths = np.array([len(p) for p in parts], dtype=np.int64)
    data = np.concatenate(parts) if parts else np.zeros((0, wake.EMBEDDING_SIZE), np.float16)
    return data, np.concatenate([[0], np.cumsum(lengths)])


SYNTH_FEATURES = WORK / "features-v3"


def cmd_features(_):
    out = WORK / "features"
    rng = np.random.default_rng(SEED + 1)
    mls_files = {s: [str(MLS_DIR / s / f"{r[1]}.opus") for r in mls_rows(s)] for s in ("train", "val", "test")}
    with ProcessPoolExecutor(WORKERS, initializer=_init_features) as pool:
        for split, files in mls_files.items():
            target = out / f"mls_{split}.npz"
            if target.exists():
                continue
            log(f"features: MLS {split}, {len(files)} arquivos")
            chunks = [files[i:i + 40] for i in range(0, len(files), 40)]
            parts = [e for result in pool.map(_mls_job, chunks) for e in result]
            data, offsets = _sequences(parts)
            save_npz(target, data=data, offsets=offsets)

        synth = load_synth()
        tails, fillers = {}, {}
        for m in synth:
            if m["kind"] == "tail":
                tails.setdefault(m["voice"], []).append(m["audio"])
            elif m["kind"] in ("speech", "conf") and m["split"] == "train":
                fillers.setdefault(m["voice"], []).append(m["audio"])
        for split in ("train", "val", "test"):
            target = SYNTH_FEATURES / f"neg_{split}.npz"
            if not target.exists():
                clips = [m["audio"] for m in synth if m["kind"] in ("conf", "speech") and m["split"] == split]
                log(f"features: negativos sintéticos {split}, {len(clips)} falas")
                jobs = [(clips[i:i + 60], mls_files[split], int(rng.integers(1 << 31)))
                        for i in range(0, len(clips), 60)]
                data, offsets = _sequences([e for r in pool.map(_negative_job, jobs) for e in r])
                save_npz(target, data=data, offsets=offsets)

            target = SYNTH_FEATURES / f"pos_{split}.npz"
            if not target.exists():
                items = [(i, (m["audio"], m["start"], m["end"], m["voice"], m["kind"] == "posphrase"))
                         for i, m in enumerate(synth) if m["kind"] in ("pos", "posphrase") and m["split"] == split]
                strict = split == "train"
                repeats = 4 if strict else 3
                log(f"features: positivos {split}, {len(items)} falas × {repeats}")
                jobs = []
                for i in range(0, len(items), 40):
                    chunk = items[i:i + 40]
                    names = {voice for _, (_, _, _, voice, _) in chunk}
                    # Cada tarefa leva só as vozes dela, e uma amostra do contexto: o repasse é por cópia.
                    job_fillers = {v: [fillers[v][int(k)] for k in rng.choice(len(fillers[v]), min(30, len(fillers[v])),
                                                                               replace=False)]
                                   for v in names if fillers.get(v)}
                    job_tails = {v: tails[v] for v in names if tails.get(v)}
                    jobs.append((chunk, job_tails, job_fillers, mls_files[split], repeats, strict,
                                 int(rng.integers(1 << 31))))
                results = list(pool.map(_positive_job, jobs))
                voices = np.array([synth[i]["voice"] for i, _ in items for _ in range(repeats)])
                save_npz(target, windows=np.concatenate([r[0] for r in results]),
                         clip=np.concatenate([r[1] for r in results]),
                         label=np.concatenate([r[2] for r in results]),
                         context=np.concatenate([r[3] for r in results]),
                         clip_ids=np.array([i * repeats + v for i, _ in items for v in range(repeats)],
                                           dtype=np.int32), voices=voices)

        target = out / "noise_train.npz"
        if not target.exists():
            log("features: ruído")
            parts = [e for r in pool.map(_noise_job, [int(rng.integers(1 << 31)) for _ in range(10)]) for e in r]
            data, offsets = _sequences(parts)
            save_npz(target, data=data, offsets=offsets)
    log("features: prontas")


# ───────────────────────── train ─────────────────────────

class Sequences:
    """Embeddings de vários arquivos em sequência; janelas de 16 que não cruzam arquivo."""

    def __init__(self, path):
        data = load_npz(path)
        self.data, self.offsets = data["data"], data["offsets"]
        ends = [np.arange(a + wake.EMBEDDINGS - 1, b) for a, b in zip(self.offsets[:-1], self.offsets[1:])
                if b - a >= wake.EMBEDDINGS]
        self.valid = np.concatenate(ends) if ends else np.zeros(0, dtype=np.int64)

    def windows(self, ends):
        return self.data[ends[:, None] + np.arange(-wake.EMBEDDINGS + 1, 1)]

    def sample(self, rng, n):
        return self.windows(rng.choice(self.valid, size=n))

    def hours(self):
        return len(self.data) * wake.EMBEDDING_STRIDE / SR / 3600

    def files(self):
        for a, b in zip(self.offsets[:-1], self.offsets[1:]):
            if b - a >= wake.EMBEDDINGS:
                yield np.arange(a + wake.EMBEDDINGS - 1, b)


class Mlp:
    def __init__(self, rng, hidden=64):
        sizes = (wake.EMBEDDINGS * wake.EMBEDDING_SIZE, hidden, hidden, 1)
        self.w = [rng.normal(0, math.sqrt(2 / a), (a, b)).astype(np.float32) for a, b in zip(sizes[:-1], sizes[1:])]
        self.b = [np.zeros(b, dtype=np.float32) for b in sizes[1:]]
        self.m = [np.zeros_like(p) for p in self.w + self.b]
        self.v = [np.zeros_like(p) for p in self.w + self.b]
        self.t = 0

    def logits(self, x):
        h = x
        for i, (w, b) in enumerate(zip(self.w, self.b)):
            h = h @ w + b
            if i < len(self.w) - 1:
                h = np.maximum(h, 0)
        return h.reshape(-1)

    def step(self, x, y, weight, lr, decay=1e-4):
        acts, pre = [x], []
        h = x
        for i, (w, b) in enumerate(zip(self.w, self.b)):
            z = h @ w + b
            pre.append(z)
            h = np.maximum(z, 0) if i < len(self.w) - 1 else z
            acts.append(h)
        z = pre[-1].reshape(-1)
        p = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
        loss = float(np.sum(weight * (np.logaddexp(0, z) - y * z)) / np.sum(weight))
        grad = ((weight * (p - y)) / np.sum(weight)).astype(np.float32)[:, None]
        gw, gb = [None] * len(self.w), [None] * len(self.b)
        for i in range(len(self.w) - 1, -1, -1):
            gw[i] = acts[i].T @ grad + decay * self.w[i]
            gb[i] = grad.sum(axis=0)
            if i > 0:
                grad = (grad @ self.w[i].T) * (pre[i - 1] > 0)
        self.t += 1
        for k, (param, g) in enumerate(zip(self.w + self.b, gw + gb)):
            self.m[k] = 0.9 * self.m[k] + 0.1 * g
            self.v[k] = 0.999 * self.v[k] + 0.001 * g * g
            mhat = self.m[k] / (1 - 0.9 ** self.t)
            vhat = self.v[k] / (1 - 0.999 ** self.t)
            param -= lr * mhat / (np.sqrt(vhat) + 1e-8)
        return loss


def _scores(mlp, mean, std, windows, batch=8192):
    out = np.empty(len(windows), dtype=np.float32)
    for i in range(0, len(windows), batch):
        x = (windows[i:i + batch].reshape(-1, mean.size).astype(np.float32) - mean) / std
        out[i:i + batch] = 1 / (1 + np.exp(-np.clip(mlp.logits(x), -30, 30)))
    return out


def _sequence_scores(mlp, mean, std, seq: Sequences):
    """Pontuação de cada janela, arquivo por arquivo, na ordem do fluxo."""
    return [_scores(mlp, mean, std, seq.windows(ends)) for ends in seq.files()]


def mine_hard(mlp, mean, std, sequences, floor=0.2, cap=60000):
    """As janelas negativas que o modelo atual mais confunde com a palavra."""
    out = []
    for seq in sequences:
        found = []
        for i in range(0, len(seq.valid), 100_000):
            ends = seq.valid[i:i + 100_000]
            scores = _scores(mlp, mean, std, seq.windows(ends))
            found.append(ends[scores >= floor])
        ends = np.concatenate(found) if found else np.zeros(0, dtype=np.int64)
        if len(ends) > cap:
            ends = np.sort(np.random.default_rng(SEED + 3).choice(ends, size=cap, replace=False))
        out.append((seq, ends))
    log("train: negativos difíceis " + ", ".join(str(len(e)) for _, e in out))
    return out


def false_activations(per_file, threshold):
    """Disparos com o refratário de 2 s, como o WakeGate faria."""
    count = 0
    for scores in per_file:
        hits = np.nonzero(scores >= threshold)[0]
        last = -REFRACTORY
        for h in hits:
            if h - last >= REFRACTORY:
                count += 1
                last = h
    return count


def detection_rate(scores, clips, threshold):
    best = {}
    for s, c in zip(scores, clips):
        best[c] = max(best.get(c, 0.0), float(s))
    return sum(v >= threshold for v in best.values()) / max(1, len(best)), len(best)


def cmd_train(args):
    f = WORK / "features"
    rng = np.random.default_rng(SEED + 2)
    v = SYNTH_FEATURES
    pos = load_npz(v / "pos_train.npz")
    positives, context = pos["windows"], pos["context"]
    mls, neg, quiet_seq = Sequences(f / "mls_train.npz"), Sequences(v / "neg_train.npz"), Sequences(f / "noise_train.npz")
    extra = Sequences(f / "extra_train.npz") if (f / "extra_train.npz").exists() else None
    log(f"train: {len(positives)} janelas positivas, MLS {mls.hours():.1f} h, sintéticos {neg.hours():.1f} h, "
        f"contexto {len(context)}")

    sample = np.concatenate([positives[rng.choice(len(positives), 20000)], mls.sample(rng, 40000),
                             neg.sample(rng, 20000)]).reshape(-1, wake.EMBEDDINGS * wake.EMBEDDING_SIZE)
    mean = sample.astype(np.float32).mean(axis=0)
    std = np.maximum(sample.astype(np.float32).std(axis=0), 1e-3)

    mlp = Mlp(rng, args.hidden)
    steps = args.steps
    hard = []           # (Sequences, fins das janelas que enganam o modelo)
    for step in range(1, steps + 1):
        if step in (int(steps * 0.4), int(steps * 0.7)):
            hard = mine_hard(mlp, mean, std, [mls, neg] + ([extra] if extra else []))
        p = positives[rng.integers(len(positives), size=512)]
        parts = [mls.sample(rng, 900 if extra is None else 500), neg.sample(rng, 400),
                 context[rng.integers(len(context), size=150)], quiet_seq.sample(rng, 86)]
        if extra is not None:
            parts.append(extra.sample(rng, 600))
        for seq, ends in hard:
            if len(ends):
                parts.append(seq.windows(rng.choice(ends, size=150)))
        n = np.concatenate(parts)
        x = np.concatenate([p, n]).reshape(-1, mean.size).astype(np.float32)
        x = (x - mean) / std
        x += rng.normal(0, 0.1, x.shape).astype(np.float32)
        y = np.concatenate([np.ones(len(p)), np.zeros(len(n))]).astype(np.float32)
        negative_weight = 1 + (args.negative_weight - 1) * min(1.0, step / (0.6 * steps))
        weight = np.where(y > 0, 1.0, negative_weight).astype(np.float32)
        lr = 1e-3 if step < 0.8 * steps else 2e-4
        loss = mlp.step(x, y, weight, lr)
        if step % 1000 == 0:
            log(f"train: passo {step}/{steps}, perda {loss:.4f}, peso negativo {negative_weight:.0f}")

    finish(mlp, mean, std, args, steps, len(positives), mls, neg)


def cmd_retune(args):
    """Regrava o limiar de operação do modelo já treinado, sem treinar de novo."""
    saved = load_npz(OUTPUT)
    meta = json.loads(str(saved["meta"]))
    mlp = Mlp(np.random.default_rng(0), int(saved["w1"].shape[1]))
    mlp.w = [saved["w1"], saved["w2"], saved["w3"]]
    mlp.b = [saved["b1"], saved["b2"], saved["b3"]]
    args.hidden = int(saved["w1"].shape[1])
    f = WORK / "features"
    finish(mlp, saved["mean"], saved["std"], args, meta.get("steps", 0),
           len(load_npz(SYNTH_FEATURES / "pos_train.npz")["windows"]),
           Sequences(f / "mls_train.npz"), Sequences(SYNTH_FEATURES / "neg_train.npz"))


def finish(mlp, mean, std, args, steps, positives_count, mls, neg):
    f = WORK / "features"
    v = SYNTH_FEATURES
    # Limiar: o menor que não dispara nenhuma vez na validação.
    val_pos = load_npz(v / "pos_val.npz")
    val_scores = _scores(mlp, mean, std, val_pos["windows"])
    val_neg = _sequence_scores(mlp, mean, std, Sequences(f / "mls_val.npz")) + \
        _sequence_scores(mlp, mean, std, Sequences(v / "neg_val.npz"))
    grid = np.round(np.concatenate([np.arange(0.30, 0.95, 0.05), np.arange(0.95, 0.995, 0.005),
                                    [0.995, 0.996, 0.997, 0.998, 0.999, 0.9995, 0.9999]]), 4)
    strict = next((float(t) for t in grid if false_activations(val_neg, t) == 0), float(grid[-1]))
    # O limiar de operação pode ser escolhido à mão para o teste de campo; o estrito fica no relatório.
    threshold = args.operating_threshold if args.operating_threshold else strict
    val_rate, val_clips = detection_rate(val_scores, val_pos["clip"], threshold)
    log(f"train: limiar {threshold}, validação {val_rate:.1%} de {val_clips} clipes")

    # Teste: só agora, uma vez.
    test_pos = load_npz(v / "pos_test.npz")
    test_scores = _scores(mlp, mean, std, test_pos["windows"])
    mls_test = Sequences(f / "mls_test.npz")
    test_mls = _sequence_scores(mlp, mean, std, mls_test)
    neg_test = Sequences(v / "neg_test.npz")
    test_neg = _sequence_scores(mlp, mean, std, neg_test)
    voices = {int(c): str(v) for c, v in zip(test_pos["clip_ids"], test_pos["voices"])}
    rows = []
    for t in sorted(set([threshold, strict] + [0.5, 0.7, 0.9, 0.95, 0.98, 0.99])):
        rate, clips = detection_rate(test_scores, test_pos["clip"], t)
        by_voice = {}
        for name in sorted(set(voices.values())):
            mask = np.array([voices[int(c)] == name for c in test_pos["clip"]])
            by_voice[name] = detection_rate(test_scores[mask], test_pos["clip"][mask], t)[0]
        rows.append((t, rate, clips, false_activations(test_mls, t), false_activations(test_neg, t), by_voice))

    meta = {"version": "zordon-wake-v1", "seed": SEED, "steps": steps, "negativeWeight": args.negative_weight,
            "threshold": threshold, "strictThreshold": strict, "hidden": args.hidden,
            "trainedAt": time.strftime("%Y-%m-%d"),
            "features": {"melspectrogram": "openWakeWord v0.5.1", "embedding": "Google speech_embedding"}}
    save_npz(OUTPUT, mean=mean, std=std, w1=mlp.w[0], b1=mlp.b[0], w2=mlp.w[1], b2=mlp.b[1], w3=mlp.w[2],
             b3=mlp.b[2], threshold=np.array(threshold, dtype=np.float32),
             meta=np.array(json.dumps(meta, ensure_ascii=False)))
    write_report(meta, rows, threshold, val_rate, val_clips, mls, neg, positives_count, mls_test, neg_test)
    log(f"modelo em {OUTPUT} (sha256 {sha256_of(OUTPUT)[:12]}…)")


def write_report(meta, rows, threshold, val_rate, val_clips, mls, neg, positives, mls_test, neg_test):
    chosen = next(r for r in rows if r[0] == threshold)
    fp_hour = chosen[3] / mls_test.hours()
    lines = [
        "# Relatório do treino — zordon-wake-v1",
        "",
        f"Gerado por `voice/training/train_wake.py` em {meta['trainedAt']} (SPEC-013, ADR-0038).",
        "",
        "## Dados",
        "",
        f"- Treino: {positives} janelas positivas; MLS {mls.hours():.1f} h; negativos sintéticos "
        f"{neg.hours():.1f} h.",
        f"- Teste: MLS {mls_test.hours():.1f} h de locutores fora do treino; negativos sintéticos da voz cadu "
        f"{neg_test.hours():.2f} h; positivos da voz cadu e de 10% dos locutores LibriTTS.",
        "",
        "## Resultado no limiar escolhido",
        "",
        f"- Limiar de operação: **{threshold}**. Limiar estrito (o menor sem disparo na validação): "
        f"{meta['strictThreshold']}; validação no limiar de operação: {val_rate:.1%} de {val_clips} clipes.",
        f"- Acerto no teste: **{chosen[1]:.1%}** de {chosen[2]} clipes (detecção até 600 ms depois da palavra).",
        f"- Falsos disparos no MLS de teste: **{chosen[3]}** em {mls_test.hours():.1f} h "
        f"({fp_hour * 8:.2f} a cada 8 h).",
        f"- Falsos disparos nos negativos sintéticos de teste (palavras parecidas, narração): **{chosen[4]}**.",
        "",
        "## Curva",
        "",
        "| Limiar | Acerto | Por voz | Falsos (MLS) | Falsos (sintéticos) |",
        "|---|---|---|---|---|",
    ]
    for t, rate, _, fp, fp_neg, by_voice in rows:
        voices = ", ".join(f"{k.replace('pt_BR-', '').replace('en_US-', '')} {v:.0%}" for k, v in by_voice.items())
        lines.append(f"| {t} | {rate:.1%} | {voices} | {fp} | {fp_neg} |")
    lines += ["", "O teste de campo (8 h de fala ambiente com o modo `wake`) é do owner (SPEC-013 CA-14).", ""]
    write_atomic(REPORT, "\n".join(lines).encode("utf-8"))
    metrics = {"version": meta["version"], "threshold": threshold, "recall": round(chosen[1], 4),
               "positiveClips": chosen[2], "falseActivationsMls": chosen[3],
               "mlsTestHours": round(mls_test.hours(), 2), "falseActivationsSynthetic": chosen[4],
               "validationRecall": round(val_rate, 4), "sha256": sha256_of(OUTPUT)}
    write_atomic(METRICS, (json.dumps(metrics, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("stage", choices=["fetch", "mls", "synth", "synth2", "synth3", "features", "extra", "train",
                                          "retune", "all"])
    parser.add_argument("--steps", type=int, default=20000)
    parser.add_argument("--negative-weight", type=float, default=30.0)
    parser.add_argument("--hidden", type=int, default=64)
    parser.add_argument("--operating-threshold", type=float, default=None,
                        help="limiar gravado no modelo; sem ele, o menor sem disparo na validação")
    args = parser.parse_args()
    stages = (["fetch", "mls", "synth", "synth2", "synth3", "features", "extra", "train"] if args.stage == "all"
              else [args.stage])
    for stage in stages:
        globals()[f"cmd_{stage}"](args)


if __name__ == "__main__":
    main()
