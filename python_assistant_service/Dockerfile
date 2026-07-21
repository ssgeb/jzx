FROM python:3.10-slim

WORKDIR /app
COPY python_assistant_service/pyproject.toml /app/pyproject.toml
COPY python_assistant_service /app/python_assistant_service
COPY legacy-service/src/main/resources/rag /app/legacy-service/src/main/resources/rag
COPY README.md /app/README.md
RUN pip install --no-cache-dir .

USER 65532:65532
EXPOSE 8090
CMD ["python", "-m", "uvicorn", "python_assistant_service.app.main:app", "--host", "0.0.0.0", "--port", "8090"]
