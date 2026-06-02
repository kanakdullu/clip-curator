# Clip Curator

Clip Curator turns long-form media into searchable knowledge.

Upload videos or images, process them through a local multimodal AI pipeline, and search moments using natural language (for example: `guitar solo`, `red car`, `person speaking at whiteboard`).

This repository is designed for local-first development with explicit infrastructure dependencies and production-style data flow.

## What You Get

- Direct-to-S3 uploads via presigned URLs (browser never proxies file bytes through Spring).
- Async processing pipeline triggered by Redis topic events.
- Local AI service for:
	- Whisper transcription: `openai/whisper-medium.en`
	- CLIP embeddings: `clip-ViT-B-32`
- Persistent metadata in Postgres and vector search in Pinecone.
- Search responses grouped per media asset with:
	- best audio match
	- best visual match
- SSE status stream to frontend for processing lifecycle updates.

## Stack

- Frontend: React + TypeScript + Vite (`frontend/`)
- API + orchestration: Spring Boot 3.5.x, Java 21 (`src/main/java/`)
- Local model service: FastAPI + Transformers + SentenceTransformers (`model/`)
- Local infra: Postgres + Redis (`docker-compose.yaml`)
- External infra: AWS S3 + Pinecone

## Architecture Deep Dive

### 1) Upload bootstrap and direct object upload

1. Frontend calls `POST /api/v1/upload/init`.
2. Spring validates mime/size and generates an S3 presigned PUT URL.
3. Frontend uploads file bytes directly to S3 using that URL.
4. Frontend confirms completion via `POST /api/v1/upload/confirm/{id}`.

Key endpoints:

- Upload init: `/api/v1/upload/init`
- Upload confirm: `/api/v1/upload/confirm/{id}`

### 2) Queueing and async worker

- Confirm step publishes message to Redis topic: `video-processing-queue`.
- Worker consumes topic and dispatches to async executor (`videoWorkerExecutor`).
- Current executor profile is deliberately serialized:
	- core pool: 1
	- max pool: 1
	- queue capacity: 100

### 3) Media processing pipeline

For each asset:

1. Download from S3 into local temp working dir.
2. Extract visual frames via FFmpeg (`fps` configurable).
3. If audio exists, extract speech-optimized WAV and transcribe.
4. Persist transcripts/frames in Postgres.
5. Generate embeddings via local FastAPI model service.
6. Upsert vectors to Pinecone with metadata (`type`, `media_asset_id`).
7. Publish status updates over SSE (`asset-status` event).
8. Cleanup local working directory.

SSE endpoint:

- `GET /api/v1/assets/{id}/status-stream`

### 4) Search and hydration

1. Query text embedded through local CLIP text path.
2. Pinecone queried with configurable `top-k` and `minimum-score`.
3. Matches hydrated from Postgres transcript/frame IDs.
4. Results grouped per media asset with best audio + best visual.
5. Frontend supports UI-only filtering by `All / Audio / Visual`.

Search endpoint:

- `GET /api/v1/search?q=<text>`

### 5) Processing-status UX

- Frontend opens EventSource stream after upload confirmation.
- Backend emits `PROCESSING`, `COMPLETED`, `FAILED`.
- Frontend shows lifecycle notifications and refreshes completed assets.

## AI Models and Local Endpoints

FastAPI service (`model/local_ai_service.py`) exposes:

- `GET /health`
- `POST /models/openai/whisper-transcribe`
- `POST /pipeline/feature-extraction/sentence-transformers/clip-ViT-B-32`

Model defaults:

- Whisper: `openai/whisper-medium.en` (overridable via `WHISPER_MODEL_NAME`)
- CLIP: `clip-ViT-B-32`

Spring defaults point to local model service:

- Whisper URL: `http://127.0.0.1:8000/models/openai/whisper-transcribe`
- CLIP URL: `http://127.0.0.1:8000/pipeline/feature-extraction/sentence-transformers/clip-ViT-B-32`

## Engineering Improvements Already Applied

Highlights from recent iterations:

- Upgraded transcription model to `whisper-medium.en` for stronger ASR quality.
- Increased Spring AI client read timeout to 300s to tolerate heavier local inference.
- Switched audio extraction from lossy MP3 to speech-friendly WAV (`pcm_s16le`, mono, 16kHz).
- Added speech enhancement filter chain before Whisper:
	- highpass
	- lowpass
	- afftdn
	- loudnorm
- Added transcript text normalization/truncation before CLIP text embedding to avoid long-sequence failures.
- Improved delete semantics to clean up all three layers:
	- Postgres rows
	- Pinecone vectors
	- S3 objects
- Search now returns grouped asset-level results (best audio + best visual), not raw vector list.

## Prerequisites

- macOS/Linux (Windows works with equivalent commands)
- Java 21
- Node.js 20+
- Python 3.10+
- Docker + Docker Compose
- `ffmpeg` and `ffprobe` available on PATH
- AWS account + S3 access
- Pinecone account + index

## Required Accounts and Cloud Resources

### AWS S3

Create your own bucket (for example `my-clip-curator-dev-bucket`) in the same region you will run from.

Minimum capabilities needed by your AWS identity:

- `s3:PutObject`
- `s3:GetObject`
- `s3:DeleteObject`

Spring resolves credentials through AWS SDK default provider chain (env vars/profile/instance role).

### Pinecone

Create a Pinecone index with:

- dimension: `512`
- metric: `cosine` (recommended with normalized embeddings)

You will need:

- API key
- index name
- namespace

## Repository Structure

- `frontend/` user interface
- `model/` local AI service
- `src/main/java/` Spring Boot backend
- `src/main/resources/application.yaml` runtime config
- `docker-compose.yaml` Postgres + Redis
- `.env.example` local env template

## Environment Configuration

Copy template:

```bash
cp .env.example .env
```

Fill at least:

- `PINECONE_API_KEY`
- `PINECONE_INDEX_NAME`
- `PINECONE_NAMESPACE`

Optional but recommended to set explicitly:

- `AWS_REGION`
- `CLIPCURATOR_S3_BUCKET` (your bucket name)
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_DATA_REDIS_PORT`

Notes:

- Root `.env` is loaded by Spring via `spring.config.import`.
- `.env` must not be committed.
- `.env.example` should contain placeholders only.

## One-Time Local Setup

### 1) Clone repo

```bash
git clone <your-repo-url>
cd clip-curator
```

### 2) Frontend dependencies

```bash
cd frontend
npm install
cd ..
```

### 3) Model virtual environment

```bash
cd model
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cd ..
```

### 4) Bring up local infra

```bash
docker compose -f docker-compose.yaml up -d
```

Defaults:

- Postgres: `127.0.0.1:5432`
- Redis: `127.0.0.1:6379`

## Run Locally (3-Terminal Workflow)

Use separate terminals for each process.

### Terminal A: model service

```bash
cd model
source .venv/bin/activate
uvicorn local_ai_service:app --host 127.0.0.1 --port 8000
```

Health check:

```bash
curl http://127.0.0.1:8000/health
```

### Terminal B: Spring Boot

If using named AWS profile:

```bash
cd <repo-root>
AWS_PROFILE=<your-profile> AWS_REGION=us-east-1 ./mvnw spring-boot:run
```

You can also run from VS Code launch config with equivalent environment variables.

### Terminal C: frontend

```bash
cd frontend
npm run dev
```

## API Summary

- Upload init: `POST /api/v1/upload/init`
- Upload confirm: `POST /api/v1/upload/confirm/{id}`
- Completed assets: `GET /api/v1/assets/completed`
- Delete asset: `DELETE /api/v1/assets/{id}`
- Status stream (SSE): `GET /api/v1/assets/{id}/status-stream`
- Search: `GET /api/v1/search?q=<query>`

## Quick Smoke Test

1. Start all services.
2. Open frontend.
3. Upload a short MP4 or image.
4. Wait for processing to complete.
5. Run search query such as `guitar`.
6. Toggle `All / Audio / Visual` filter in results panel.

## Common Troubleshooting

### Spring starts but processing fails

- Confirm Redis and Postgres containers are healthy.
- Confirm model service is up at `127.0.0.1:8000`.
- Confirm Pinecone env values are set and index is 512-dim.

### S3 access issues

- Verify `CLIPCURATOR_S3_BUCKET` points to an existing bucket.
- Verify AWS identity has object read/write/delete permissions.
- Verify `AWS_REGION` matches bucket region.

### ffmpeg errors

- Ensure `ffmpeg` and `ffprobe` are installed.
- Override binary path with `FFMPEG_COMMAND` if needed.

### Fewer search hits than raw Pinecone queries

- Backend returns grouped, hydrated asset-level matches.
- Not all raw vectors necessarily hydrate to rows in Postgres.

## Security and Publishing Checklist

- Never commit `.env`.
- Rotate any credential that was ever committed.
- Keep repo examples secret-free.
- Enable GitHub secret scanning and push protection.

## Useful Commands

Backend compile:

```bash
./mvnw -DskipTests compile
```

Backend tests:

```bash
./mvnw test
```

Frontend build:

```bash
cd frontend && npm run build
```

Bring infra down:

```bash
docker compose -f docker-compose.yaml down
```

## License

Add your preferred license and copyright.
