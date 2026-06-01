# Local AI Model Service

This service runs Whisper and CLIP locally and exposes endpoints compatible with the existing Spring Boot integration.

## Models

- Whisper: `openai/whisper-medium.en` (default)
- CLIP: `clip-ViT-B-32`

## Endpoints

- `GET /health`
- `POST /models/openai/whisper-transcribe`
  - Expects JSON: `{ "inputs": "<base64-audio>" }`
  - Returns JSON: `{ "segments": [{ "start": 0.0, "end": 1.2, "text": "..." }] }`
- `POST /pipeline/feature-extraction/sentence-transformers/clip-ViT-B-32`
  - Expects JSON: `{ "inputs": "<text>" }` or `{ "inputs": "data:image/jpeg;base64,..." }`
  - Returns a 512-dimensional float array

## Run Locally

1. Create a virtual environment:
   - `python3 -m venv .venv`
2. Activate it:
   - `source .venv/bin/activate`
3. Install dependencies:
   - `pip install -r requirements.txt`
4. Start the API:
   - `uvicorn local_ai_service:app --host 127.0.0.1 --port 8000`

## Notes

- On Apple Silicon host execution, this service uses MPS when available.
- Ensure `ffmpeg` is installed on your machine for audio decoding.
- Override Whisper model if needed via env var:
   - `WHISPER_MODEL_NAME=openai/whisper-medium.en`
