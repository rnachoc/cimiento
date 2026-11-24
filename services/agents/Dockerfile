# Intake worker (ADR 0018): analyses the tenders people upload in the console.
FROM python:3.12.8-slim-bookworm
RUN pip install --no-cache-dir uv==0.13.1
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src src
RUN uv sync --frozen --no-dev && useradd --system --uid 10001 worker
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER worker
CMD ["cimiento-agents", "worker"]
