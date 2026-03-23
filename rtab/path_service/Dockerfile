FROM python:3.11-slim

WORKDIR /app

# Install system dependencies + rtabmap CLI
RUN apt-get update && apt-get install -y \
    gcc \
    libpq-dev \
    software-properties-common \
    wget \
    gnupg2 \
    && rm -rf /var/lib/apt/lists/*

# rtabmap 설치 (Debian bookworm)
RUN echo "deb http://deb.debian.org/debian bookworm main" > /etc/apt/sources.list.d/bookworm.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends rtabmap \
    && rm -rf /var/lib/apt/lists/* \
    || echo "rtabmap not available in apt, will try pip fallback"

# Copy requirements first for caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Create directories
RUN mkdir -p /app/uploads /app/output /app/ply_cache

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
