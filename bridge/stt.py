#!/usr/bin/env python3
"""Постоянный STT-воркер для Cursor Bridge.

Протокол: JSON-строки stdin/stdout.
  запрос:  {"file": "/путь/к/аудио", "language": "ru"}
  ответ:   {"text": "распознанный текст"} | {"error": "..."}

Модель держится в памяти между запросами. VAD-фильтр отсекает тишину.
"""
import json
import os
import re
import sys

MODEL = os.environ.get("STT_MODEL", "large-v3")
THREADS = int(os.environ.get("STT_THREADS", "8"))
PROMPT = os.environ.get("STT_PROMPT") or (
    "Это диктовка сообщения. Записываю дословно, грамотно расставляя знаки "
    "препинания: точки, запятые, вопросительные и восклицательные знаки. "
    "Начинаю запись."
)

def log(msg):
    print(msg, file=sys.stderr, flush=True)

log(f"stt: загружаю модель {MODEL} (int8, {THREADS} потоков)…")
from faster_whisper import WhisperModel  # noqa: E402

model = WhisperModel(MODEL, device="cpu", compute_type="int8", cpu_threads=THREADS)
log("stt: модель готова")
print(json.dumps({"ready": True}), flush=True)


def transcribe(path, language, prompt, temperature):
    segments, _info = model.transcribe(
        path,
        language=language or None,
        beam_size=5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 900, "speech_pad_ms": 300},
        condition_on_previous_text=False,
        temperature=temperature,
        initial_prompt=prompt,
    )
    return " ".join(s.text.strip() for s in segments).strip()


for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        req = json.loads(line)
        lang = req.get("language")
        prompt = req.get("prompt") or PROMPT
        text = transcribe(req["file"], lang, prompt, [0.0, 0.2, 0.4])
        if len(text) > 60 and not re.search(r"[.,!?…:;—]", text):
            log("stt: пунктуация потеряна, повторный проход")
            retry = transcribe(req["file"], lang, prompt, [0.3, 0.5])
            if re.search(r"[.,!?…:;—]", retry):
                text = retry
        print(json.dumps({"text": text}), flush=True)
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": str(e)}), flush=True)
