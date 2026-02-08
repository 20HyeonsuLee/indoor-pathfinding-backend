# ORB-SLAM3 Docker Environment

ORB-SLAM3를 Docker로 실행하기 위한 환경입니다.

## 사전 요구사항

- Docker Desktop 설치
- (macOS) XQuartz 설치 (GUI 사용 시): `brew install --cask xquartz`

## 디렉토리 구조

```
orb-slam3/
├── Dockerfile              # Docker 이미지 빌드 파일
├── docker-compose.yml      # Docker Compose 설정
├── config/                 # 카메라 설정 파일
│   ├── realsense_d435.yaml # RealSense D435 설정
│   └── iphone_lidar.yaml   # iPhone LiDAR 설정
├── data/
│   ├── input/              # 입력 데이터 (이미지/비디오)
│   └── output/             # 출력 결과 (맵, trajectory)
├── scripts/
│   ├── build.sh            # 이미지 빌드
│   ├── run.sh              # 컨테이너 실행
│   ├── stop.sh             # 컨테이너 중지
│   └── exec.sh             # 컨테이너 접속
└── vocabulary/             # ORB vocabulary (빌드 시 자동 생성)
```

## 빠른 시작

### 1. Docker 이미지 빌드

```bash
cd orb-slam3
./scripts/build.sh
```

> ⚠️ 빌드에 20-40분 소요될 수 있습니다.

### 2. 컨테이너 실행

```bash
./scripts/run.sh
```

### 3. 컨테이너 접속

```bash
./scripts/exec.sh
# 또는
docker exec -it orb-slam3 bash
```

### 4. 컨테이너 종료

```bash
./scripts/stop.sh
```

## ORB-SLAM3 실행 예제

컨테이너 내부에서 실행:

### Monocular 모드 (TUM 데이터셋)

```bash
./Examples/Monocular/mono_tum \
    Vocabulary/ORBvoc.txt \
    Examples/Monocular/TUM1.yaml \
    /data/input/rgbd_dataset_freiburg1_xyz
```

### RGB-D 모드 (TUM 데이터셋)

```bash
./Examples/RGB-D/rgbd_tum \
    Vocabulary/ORBvoc.txt \
    Examples/RGB-D/TUM1.yaml \
    /data/input/rgbd_dataset_freiburg1_xyz \
    /data/input/rgbd_dataset_freiburg1_xyz/associations.txt
```

### Stereo 모드 (EuRoC 데이터셋)

```bash
./Examples/Stereo/stereo_euroc \
    Vocabulary/ORBvoc.txt \
    Examples/Stereo/EuRoC.yaml \
    /data/input/MH_01_easy \
    Examples/Stereo/EuRoC_TimeStamps/MH01.txt
```

## 테스트 데이터셋 다운로드

### TUM RGB-D 데이터셋

```bash
# data/input 디렉토리에 다운로드
cd data/input
wget https://cvg.cit.tum.de/rgbd/dataset/freiburg1/rgbd_dataset_freiburg1_xyz.tgz
tar -xzf rgbd_dataset_freiburg1_xyz.tgz
```

### EuRoC MAV 데이터셋

```bash
cd data/input
wget http://robotics.ethz.ch/~asl-datasets/ijrr_euroc_mav_dataset/machine_hall/MH_01_easy/MH_01_easy.zip
unzip MH_01_easy.zip
```

## 커스텀 카메라 사용

`config/` 디렉토리의 YAML 파일을 수정하거나 새로 생성하여 사용:

```yaml
%YAML:1.0

Camera.type: "PinHole"

Camera1.fx: 615.0    # 초점 거리 x
Camera1.fy: 615.0    # 초점 거리 y
Camera1.cx: 320.0    # 주점 x
Camera1.cy: 240.0    # 주점 y

Camera.width: 640
Camera.height: 480
Camera.fps: 30

RGBD.DepthMapFactor: 1000.0  # depth 스케일
```

## macOS GUI 설정 (XQuartz)

1. XQuartz 설치 및 실행
2. XQuartz 환경설정 → 보안 → "네트워크 클라이언트에서의 연결 허용" 체크
3. 터미널에서 `xhost +localhost` 실행
4. 로그아웃 후 재로그인

## 문제 해결

### 빌드 실패 시

```bash
# 캐시 삭제 후 재빌드
docker-compose build --no-cache
```

### GUI가 표시되지 않을 때

```bash
# Linux
xhost +local:docker

# macOS (XQuartz 필요)
xhost +localhost
```

### 메모리 부족 시

`docker-compose.yml`의 메모리 제한 조정:

```yaml
deploy:
  resources:
    limits:
      memory: 16G
```

## 참고 자료

- [ORB-SLAM3 공식 저장소](https://github.com/UZ-SLAMLab/ORB_SLAM3)
- [TUM RGB-D 데이터셋](https://cvg.cit.tum.de/data/datasets/rgbd-dataset)
- [EuRoC MAV 데이터셋](https://projects.asl.ethz.ch/datasets/doku.php?id=kmavvisualinertialdatasets)
