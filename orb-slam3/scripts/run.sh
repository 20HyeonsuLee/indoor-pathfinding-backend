#!/bin/bash
# ORB-SLAM3 Docker Container Run Script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "========================================"
echo "  Starting ORB-SLAM3 Container"
echo "========================================"

# Allow X11 forwarding (macOS)
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "[INFO] Detected macOS - configuring XQuartz..."
    # Check if XQuartz is installed
    if ! command -v xquartz &> /dev/null && [ ! -d "/Applications/Utilities/XQuartz.app" ]; then
        echo "[WARNING] XQuartz not found. Install it for GUI support:"
        echo "  brew install --cask xquartz"
        echo ""
    fi

    # Set DISPLAY for macOS
    export DISPLAY=host.docker.internal:0

    # Allow connections from localhost
    if command -v xhost &> /dev/null; then
        xhost +localhost 2>/dev/null || true
    fi
fi

# Allow X11 forwarding (Linux)
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "[INFO] Detected Linux - configuring X11..."
    xhost +local:docker 2>/dev/null || true
fi

# Start the container
echo "[INFO] Starting container..."
docker-compose up -d

echo ""
echo "========================================"
echo "  Container Started!"
echo "========================================"
echo ""
echo "To enter the container:"
echo "  docker exec -it orb-slam3 bash"
echo ""
echo "To run ORB-SLAM3 examples:"
echo "  # Monocular mode"
echo "  ./Examples/Monocular/mono_tum Vocabulary/ORBvoc.txt Examples/Monocular/TUM1.yaml /data/input/sequence"
echo ""
echo "  # RGB-D mode"
echo "  ./Examples/RGB-D/rgbd_tum Vocabulary/ORBvoc.txt Examples/RGB-D/TUM1.yaml /data/input/sequence associations.txt"
echo ""
echo "To stop the container:"
echo "  ./scripts/stop.sh"
echo ""
