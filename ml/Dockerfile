FROM python:3.11-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        libgomp1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./

# Install CPU-only torch first to avoid pulling CUDA wheels (~2GB saved).
RUN pip install --index-url https://download.pytorch.org/whl/cpu \
        torch==2.3.1 torchvision==0.18.1 \
    && pip install -r requirements.txt

COPY src ./src
COPY scripts ./scripts
COPY checkpoints ./checkpoints
COPY data ./data

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8000/docs >/dev/null || exit 1

CMD ["uvicorn", "src.serve.api:app", "--host", "0.0.0.0", "--port", "8000"]
