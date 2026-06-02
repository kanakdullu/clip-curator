from __future__ import annotations

import base64
import binascii
import io
import os
import tempfile
from contextlib import suppress
from typing import Any

import torch
from fastapi import FastAPI
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from transformers import pipeline

WHISPER_MODEL_NAME = os.getenv("WHISPER_MODEL_NAME", "openai/whisper-medium.en")
CLIP_MODEL_NAME = "clip-ViT-B-32"


def resolve_device() -> str:
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


def resolve_whisper_device(device: str) -> Any:
    if device == "cpu":
        return -1
    if device == "cuda":
        return 0
    return device


DEVICE = resolve_device()
app = FastAPI(title="Clip Curator Local AI Service", version="1.0.0")

whisper_pipeline: Any | None = None
clip_model: SentenceTransformer | None = None


class InferenceRequest(BaseModel):
    inputs: str
    parameters: dict[str, Any] | None = None


@app.on_event("startup")
def load_models() -> None:
    global whisper_pipeline, clip_model

    whisper_pipeline = pipeline(
        "automatic-speech-recognition",
        model=WHISPER_MODEL_NAME,
        device=resolve_whisper_device(DEVICE),
    )
    clip_model = SentenceTransformer(CLIP_MODEL_NAME, device=DEVICE)
    print("Using device - ", DEVICE)


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "device": DEVICE,
        "whisperModel": WHISPER_MODEL_NAME,
        "clipModel": CLIP_MODEL_NAME,
    }


@app.post("/models/openai/whisper-transcribe")
def transcribe_audio(request: InferenceRequest) -> dict[str, Any]:
    ensure_models_loaded()

    temp_path: str | None = None
    try:
        audio_bytes = decode_base64_payload(request.inputs)

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_file:
            temp_file.write(audio_bytes)
            temp_path = temp_file.name

        result = whisper_pipeline(temp_path, return_timestamps=True)
        segments = parse_whisper_segments(result)

        if not segments:
            return {"error": "Whisper response did not include timestamped transcript segments."}

        return {"segments": segments}
    except Exception as ex:
        return {"error": f"Whisper transcription failed: {ex}"}
    finally:
        if temp_path is not None:
            with suppress(OSError):
                os.remove(temp_path)


@app.post("/pipeline/feature-extraction/sentence-transformers/clip-ViT-B-32")
def embed_clip(request: InferenceRequest) -> list[float] | dict[str, str]:
    ensure_models_loaded()

    try:
        raw_input = request.inputs.strip()
        if not raw_input:
            return {"error": "Field 'inputs' must be a non-empty string."}

        if raw_input.startswith("data:image"):
            image = decode_base64_image(raw_input)
            vector = clip_model.encode(image, normalize_embeddings=True).tolist()
        else:
            vector = clip_model.encode(raw_input, normalize_embeddings=True).tolist()

        if len(vector) != 512:
            return {"error": f"Expected 512-dimensional embedding but received {len(vector)}."}

        return [float(value) for value in vector]
    except Exception as ex:
        return {"error": f"CLIP embedding failed: {ex}"}


def ensure_models_loaded() -> None:
    if whisper_pipeline is None or clip_model is None:
        raise RuntimeError("Models are not loaded yet. Wait for startup to complete.")


def decode_base64_payload(value: str) -> bytes:
    raw_value = value.strip()
    if not raw_value:
        raise ValueError("Field 'inputs' must be a non-empty base64 string.")

    if raw_value.startswith("data:") and "," in raw_value:
        raw_value = raw_value.split(",", 1)[1]

    try:
        return base64.b64decode(raw_value, validate=True)
    except (binascii.Error, ValueError) as ex:
        raise ValueError("Invalid base64 payload.") from ex


def decode_base64_image(value: str) -> Image.Image:
    image_bytes = decode_base64_payload(value)

    try:
        return Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except UnidentifiedImageError as ex:
        raise ValueError("Image payload is not a valid image.") from ex


def parse_whisper_segments(result: Any) -> list[dict[str, Any]]:
    segments: list[dict[str, Any]] = []

    if isinstance(result, dict):
        chunks = result.get("chunks")
        if isinstance(chunks, list):
            for chunk in chunks:
                if not isinstance(chunk, dict):
                    continue

                text = str(chunk.get("text", "")).strip()
                if not text:
                    continue

                start, end = parse_timestamp_pair(chunk.get("timestamp"))
                segments.append(
                    {
                        "start": round(start, 3),
                        "end": round(end, 3),
                        "text": text,
                    }
                )

        if segments:
            return segments

        fallback_text = str(result.get("text", "")).strip()
        if fallback_text:
            return [{"start": 0.0, "end": 0.0, "text": fallback_text}]

    return segments


def parse_timestamp_pair(timestamp: Any) -> tuple[float, float]:
    if isinstance(timestamp, (list, tuple)) and len(timestamp) >= 2:
        start = parse_float(timestamp[0], 0.0)
        end = parse_float(timestamp[1], start)
        return start, end

    if isinstance(timestamp, dict):
        start = parse_float(timestamp.get("start"), 0.0)
        end = parse_float(timestamp.get("end"), start)
        return start, end

    value = parse_float(timestamp, 0.0)
    return value, value


def parse_float(value: Any, fallback: float) -> float:
    if value is None:
        return fallback

    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("local_ai_service:app", host="127.0.0.1", port=8000, reload=False)
