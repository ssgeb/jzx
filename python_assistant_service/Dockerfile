FROM python:3.11-slim

WORKDIR /app
COPY python_assistant_service/pyproject.toml /app/pyproject.toml
COPY python_assistant_service /app/python_assistant_service
COPY legacy-service/src/main/resources/rag /app/legacy-service/src/main/resources/rag
COPY README.md /app/README.md
RUN pip install --no-cache-dir .
RUN mkdir -p /app/runtime/assistant-skills \
    && chown -R 65532:65532 /app/runtime

USER 65532:65532
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/internal/v1/health', timeout=3).read()"]
CMD ["python", "-m", "uvicorn", "python_assistant_service.app.main:app", "--host", "0.0.0.0", "--port", "8090"]
