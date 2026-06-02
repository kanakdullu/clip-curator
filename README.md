# Clip Curator

Clip Curator is a local-first multimodal search app for video and image assets.

- Frontend: React + TypeScript + Vite
- API/Orchestration: Spring Boot (Java 21)
- Local AI service: FastAPI (Whisper + CLIP)
- Infra dependencies: Postgres + Redis (Docker Compose)
- External services: S3 (asset storage), Pinecone (vector search)

## Architecture

1. Frontend requests upload initialization from Spring.
2. Frontend uploads media directly to S3 with a presigned URL.
3. Frontend confirms upload; Spring queues processing via Redis.
4. Worker downloads media, extracts transcript/frames, gets embeddings from local FastAPI.
5. Spring stores metadata in Postgres and vectors in Pinecone.
6. Search queries embed text and return grouped results (best audio + best visual per asset).

## Prerequisites

- macOS/Linux (Windows works with equivalent shell commands)
- Java 21
- Maven Wrapper (included: `./mvnw`)
- Node.js 20+
- Python 3.10+
- Docker Desktop (or Docker Engine + Compose)
- `ffmpeg` and `ffprobe` on PATH

## Repository Structure

- `frontend/` React UI
- `model/` local FastAPI model service
- `src/main/java/` Spring Boot backend
- `src/main/resources/application.yaml` backend configuration
- `docker-compose.yaml` local Postgres + Redis
- `.env.example` local env template (copy to `.env`)

## One-Time Setup

### 1) Clone and enter repo

```bash
git clone <your-repo-url>
cd clip-curator
```

### 2) Configure environment

Copy env template and fill values:

```bash
cp .env.example .env
```

Required values in `.env`:

- `PINECONE_API_KEY`
- `PINECONE_INDEX_NAME`
- `PINECONE_NAMESPACE`

Notes:

- `.env` is loaded by Spring via `spring.config.import`.
- `.env` is ignored by git; do not commit secrets.

### 3) Start infra dependencies

```bash
docker compose -f docker-compose.yaml up -d
```

This starts:

- Postgres on `127.0.0.1:5432`
- Redis on `127.0.0.1:6379`

### 4) Install frontend deps

```bash
cd frontend
npm install
cd ..
```

### 5) Set up model service venv

```bash
cd model
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cd ..
```

## Run Locally (3 App Processes)

Use separate terminals for each process.

### Terminal A: Local AI model service

```bash
cd model
source .venv/bin/activate
uvicorn local_ai_service:app --host 127.0.0.1 --port 8000
```

Health check:

```bash
curl http://127.0.0.1:8000/health
```

### Terminal B: Spring Boot backend

If you use local AWS profile credentials:

```bash
cd <repo-root>
AWS_PROFILE=<your-profile> AWS_REGION=us-east-1 ./mvnw spring-boot:run
```

Or run from VS Code debug profile with equivalent env vars.

### Terminal C: Frontend

```bash
cd frontend
npm run dev
```

Vite usually starts at:

- `http://127.0.0.1:5173` (or next available port)

## Quick Verification

1. Open frontend.
2. Upload a video/image.
3. Observe upload + processing lifecycle notifications.
4. Search for a concept (e.g., `guitar`).
5. Confirm grouped result behavior in UI and playback.

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

Bring down infra:

```bash
docker compose -f docker-compose.yaml down
```

## Configuration Notes

- Backend config: `src/main/resources/application.yaml`
- Pinecone values come from `.env` (no hardcoded key in config)
- Hugging Face token is not required for local model mode
- Redis/Postgres defaults are for local development only

## Troubleshooting

### App fails to start

- Ensure Docker dependencies are up (`docker compose ps`).
- Ensure Postgres `5432` and Redis `6379` are free.
- Ensure `ffmpeg`/`ffprobe` are installed and on PATH.

### Model requests timeout

- Verify FastAPI service is running at `127.0.0.1:8000`.
- Check model terminal logs for startup/model loading issues.

### Search returns fewer results than expected

- Backend returns grouped per-asset results (best audio + best visual).
- UI filter (All/Audio/Visual) is client-side only.

### AWS/S3 errors

- Ensure your local AWS credentials/profile has S3 access.
- Confirm bucket in config/env exists and is accessible.

## Security Checklist Before Publishing

- Never commit `.env`.
- Rotate any previously exposed credentials.
- Keep `.env.example` placeholders only.
- Enable GitHub secret scanning/push protection.

## License

Add your project license details here.
