# Brain/Out control panel image.
# Build:  docker build -t brainout-panel .
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8080

# The panel talks to the Docker daemon (socket mounted at runtime) to manage the
# game container, and reads the game config from PANEL_SERVER_DIR (mounted ro).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
