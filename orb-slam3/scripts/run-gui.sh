#!/bin/bash
# ORB-SLAM3 GUI 실행 스크립트 (macOS)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "========================================"
echo "  ORB-SLAM3 GUI 실행"
echo "========================================"

# XQuartz 확인
if [ ! -d "/Applications/Utilities/XQuartz.app" ]; then
    echo "[ERROR] XQuartz가 설치되어 있지 않습니다."
    echo "설치: brew install --cask xquartz"
    echo "설치 후 로그아웃 → 재로그인 필요"
    exit 1
fi

# XQuartz 실행 확인
if ! pgrep -x "Xquartz" > /dev/null && ! pgrep -x "X11" > /dev/null; then
    echo "[INFO] XQuartz 시작 중..."
    open -a XQuartz
    sleep 3
fi

# xhost 설정
echo "[INFO] X11 연결 허용 설정..."
/opt/X11/bin/xhost +localhost 2>/dev/null || xhost +localhost 2>/dev/null || true

# DISPLAY 설정
export DISPLAY=host.docker.internal:0

echo "[INFO] Docker 컨테이너 실행..."

# 데이터셋 확인
if [ ! -d "data/input/rgbd_dataset_freiburg1_xyz" ]; then
    echo "[WARNING] 테스트 데이터셋이 없습니다."
    echo "다운로드: cd data/input && curl -L -o dataset.tgz https://cvg.cit.tum.de/rgbd/dataset/freiburg1/rgbd_dataset_freiburg1_xyz.tgz && tar -xzf dataset.tgz"
fi

# Docker 실행
docker run -it --rm \
    -e DISPLAY=host.docker.internal:0 \
    -e QT_X11_NO_MITSHM=1 \
    -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
    -v "$PROJECT_DIR/data:/data" \
    orb-slam3:latest \
    bash -c "
        echo 'ORB-SLAM3 컨테이너 접속됨'
        echo ''
        echo '실행 예시:'
        echo '  ./Examples/RGB-D/rgbd_tum Vocabulary/ORBvoc.txt Examples/RGB-D/TUM1.yaml /data/input/rgbd_dataset_freiburg1_xyz /data/input/rgbd_dataset_freiburg1_xyz/associations.txt'
        echo ''
        exec bash
    "
