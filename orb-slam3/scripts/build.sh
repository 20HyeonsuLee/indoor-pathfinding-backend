#!/bin/bash
# ORB-SLAM3 Docker Image Build Script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "========================================"
echo "  Building ORB-SLAM3 Docker Image"
echo "========================================"
echo ""

# Create data directories if they don't exist
mkdir -p data/input data/output

# Build the Docker image
echo "[1/2] Building Docker image..."
docker-compose build --no-cache

echo ""
echo "[2/2] Verifying build..."
docker images | grep orb-slam3

echo ""
echo "========================================"
echo "  Build Complete!"
echo "========================================"
echo ""
echo "Next steps:"
echo "  1. Place your dataset in: ./data/input/"
echo "  2. Run: ./scripts/run.sh"
echo ""
