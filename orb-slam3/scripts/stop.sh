#!/bin/bash
# ORB-SLAM3 Docker Container Stop Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "Stopping ORB-SLAM3 container..."
docker-compose down

echo "Container stopped."
