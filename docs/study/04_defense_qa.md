# 중간발표 예상 질문 + 답변 가이드

> **프로젝트명**: 3D 스캔 데이터 기반 실내 AR 내비게이션 시스템
> **소속**: 한국기술교육대학교 컴퓨터공학부
> **Last Updated**: 2026-03-26

---

## 사용법

각 질문에 대해 세 가지 수준의 답변을 준비했습니다.

| 구분 | 용도 |
|------|------|
| **발표용 답변** | 1~2문장으로 간결하게. 질문 직후 바로 답할 때 사용 |
| **심화 설명** | 교수님이 "좀 더 자세히"라고 할 때 대비 |
| **참고 자료** | 근거 논문, 공식 문서, 프로젝트 내부 문서 링크 |

---

## 목차

1. [근본적인 "왜?" 질문](#1-근본적인-왜-질문)
2. [SLAM / RTAB-Map 심화](#2-slam--rtab-map-심화)
3. [VPS 심화](#3-vps-심화)
4. [좌표계 / AR](#4-좌표계--ar)
5. [경로 탐색 (A*)](#5-경로-탐색-a)
6. [백엔드 / 시스템 설계](#6-백엔드--시스템-설계)
7. [정확도 / 신뢰성](#7-정확도--신뢰성)
8. [실용성 / 차별점](#8-실용성--차별점)

---

## 1. 근본적인 "왜?" 질문

### Q1-1. 왜 GPS가 실내에서 안 되는가?

**발표용 답변:**
GPS 위성 신호는 건물 벽과 천장에 의해 차단/반사되어 실내에서는 수십 미터 이상의 오차가 발생하거나 아예 수신이 불가합니다. 따라서 실내에서는 GPS를 대체할 별도의 측위 기술이 필요합니다.

**심화 설명:**

GPS(Global Positioning System)는 최소 4개의 위성 신호를 수신하여 삼변측량(Trilateration)으로 위치를 계산합니다. 실내에서 문제가 되는 이유는 크게 세 가지입니다.

1. **신호 감쇠(Attenuation)**: 콘크리트, 철근 등 건축 자재가 1.5GHz L-band 신호를 20~30dB 감쇠시킵니다. 수신 감도 이하로 떨어지면 위치 계산 자체가 불가능합니다.
2. **다중 경로 간섭(Multipath)**: 신호가 벽, 바닥, 천장에 반사되어 여러 경로로 수신기에 도달합니다. 직접 신호와 반사 신호의 시간차가 거리 계산 오차를 유발하여, 실내에서는 10~50m 수준의 오차가 발생합니다.
3. **층 구분 불가**: GPS는 수평 정밀도도 낮지만, 수직 정밀도는 더욱 열악합니다(수평 대비 1.5~2배 오차). 실내 내비게이션에서 "지금 몇 층인가"를 구분하는 것은 핵심 요구사항인데, GPS로는 이를 해결할 수 없습니다.

| 환경 | GPS 정밀도 |
|------|-----------|
| 실외 개활지 | 3~5m |
| 도심 (빌딩 사이) | 10~20m (Urban Canyon 효과) |
| 실내 | 수신 불가 또는 50m+ 오차 |

**참고 자료:**
- [GPS.gov - GPS Accuracy](https://www.gps.gov/systems/gps/performance/accuracy/)
- Zafari, F., et al. "A Survey of Indoor Localization Systems and Technologies." IEEE Communications Surveys & Tutorials, 2019.

---

### Q1-2. 왜 AR인가? 2D 지도가 아닌 이유는?

**발표용 답변:**
실내에서는 실외와 달리 도로나 랜드마크 같은 참조물이 부족하여 2D 지도만으로는 자신의 위치와 방향을 파악하기 어렵습니다. AR은 실제 환경 위에 경로를 겹쳐 보여주므로 직관적으로 "어디로 가야 하는지"를 안내할 수 있습니다.

**심화 설명:**

실내 환경에서 2D 지도 기반 내비게이션이 가지는 한계점은 다음과 같습니다.

1. **방향 감각 상실**: 실외에서는 "북쪽 방향"이나 "큰 도로" 같은 기준이 있지만, 실내 복도에서는 자신이 어느 방향을 바라보고 있는지 2D 지도에서 파악하기 어렵습니다.
2. **층간 이동 표현의 어려움**: 2D 지도는 본질적으로 평면이므로 계단/엘리베이터를 통한 수직 이동을 직관적으로 표현하기 힘듭니다.
3. **실시간 피드백**: AR은 카메라 영상 위에 화살표, 경로선을 오버레이하므로 "지금 보고 있는 복도를 따라 직진하세요"처럼 상황에 맞는 안내가 가능합니다.

AR 기반 내비게이션의 핵심 이점은 **인지 부하(Cognitive Load)의 감소**입니다. 사용자가 지도를 해석할 필요 없이 화면에 보이는 경로를 따라가면 됩니다. Google의 Live View(실외 AR 내비게이션) 사용자 조사에서도 AR 안내가 2D 지도 대비 목적지 도달 시간을 평균 25% 단축한다는 결과가 있습니다.

**참고 자료:**
- Rehman, U., & Cao, S. "Augmented-Reality-Based Indoor Navigation: A Comparative Analysis of Handheld Devices Versus Google Glass." IEEE Transactions on Human-Machine Systems, 2017.
- [Google Maps AR - Live View](https://blog.google/products/maps/new-ways-explore-with-live-view/)

---

### Q1-3. VPS가 뭔가? (쉬운 설명)

**발표용 답변:**
VPS(Visual Positioning System)는 카메라로 주변을 촬영하면, 그 이미지를 미리 저장해 둔 3D 공간 데이터와 비교하여 "이 사진을 어디에서 찍었는가"를 알아내는 기술입니다. GPS 없이도 위치를 파악할 수 있습니다.

**심화 설명:**

VPS의 동작 원리를 단계별로 설명하면 다음과 같습니다.

```
[1단계] 사전 준비 (오프라인)
    - RTAB-Map으로 건물 내부를 3D 스캔
    - 스캔 과정에서 촬영된 이미지와 3D 좌표를 DB에 저장
    - 이것이 "기준 맵(Reference Map)"이 됨

[2단계] 위치 추정 (온라인)
    - 사용자가 스마트폰 카메라로 현재 보고 있는 장면을 촬영
    - 서버가 이 이미지에서 특징점(Feature)을 추출
    - 기준 맵의 이미지들과 비교하여 가장 유사한 장면을 찾음
    - 매칭된 기준 이미지의 3D 좌표가 곧 사용자의 현재 위치

[3단계] 정밀 추정
    - 2D-3D 대응점으로 PnP(Perspective-n-Point) 알고리즘 실행
    - 카메라의 6DoF 포즈(x, y, z 위치 + roll, pitch, yaw 방향) 추정
```

비유하자면, 사람이 "이 풍경 어디서 본 것 같은데?"라고 기억을 떠올리는 것과 같습니다. VPS는 이 과정을 컴퓨터 비전 알고리즘으로 자동화한 것입니다.

**참고 자료:**
- Sarlin, P., et al. "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." CVPR 2019.
- [Google ARCore Geospatial API (VPS 상용화 사례)](https://developers.google.com/ar/develop/geospatial)

---

### Q1-4. 스캔이 뭔가? 동영상과의 차이는?

**발표용 답변:**
스캔은 카메라와 LiDAR 센서를 사용하여 공간의 3D 구조(깊이 정보 포함)를 기록하는 과정입니다. 동영상은 2D 이미지의 연속이지만, 스캔 데이터에는 각 지점까지의 거리(Depth)와 카메라의 정확한 위치/방향(Pose)이 함께 저장됩니다.

**심화 설명:**

| 구분 | 동영상 | 3D 스캔 (RTAB-Map) |
|------|--------|-------------------|
| 센서 | RGB 카메라 | RGB 카메라 + LiDAR (또는 Depth 카메라) |
| 데이터 | 2D 이미지 프레임 시퀀스 | 포인트 클라우드 + RGB 이미지 + 카메라 포즈 |
| 깊이 정보 | 없음 | 각 픽셀의 실제 거리(m 단위) |
| 좌표계 | 없음 (픽셀 좌표만) | 미터 단위 3D 좌표계 |
| 용도 | 시청, 편집 | 3D 모델링, 측량, 내비게이션 |
| 파일 크기 | ~10MB/min (1080p) | ~50~200MB/session (RTAB-Map .db) |

이 프로젝트에서 RTAB-Map이 생성하는 .db 파일은 실제로 SQLite 데이터베이스이며, 다음 정보를 포함합니다.

- **RGB 이미지**: 스캔 중 촬영된 키프레임 이미지들
- **깊이 맵(Depth Map)**: LiDAR가 측정한 각 픽셀의 거리
- **카메라 포즈(Pose)**: 각 키프레임을 촬영했을 때 카메라의 3D 위치와 방향 (4x4 변환 행렬)
- **시각 특징(Visual Features)**: 이미지에서 추출한 특징점 기술자
- **포인트 클라우드**: 깊이 맵과 카메라 포즈로부터 재구성된 3D 점군

iPhone 12 Pro 이상의 LiDAR 탑재 모델에서 RTAB-Map iOS 앱을 사용하여 스캔합니다.

**참고 자료:**
- [RTAB-Map 공식 문서](http://introlab.github.io/rtabmap/)
- [Apple LiDAR Scanner](https://developer.apple.com/augmented-reality/arkit/)

---

### Q1-5. 포인트 클라우드가 뭔가?

**발표용 답변:**
포인트 클라우드(Point Cloud)는 3D 공간의 표면을 수많은 점(point)의 집합으로 표현한 데이터입니다. 각 점은 (x, y, z) 좌표와 선택적으로 색상(RGB) 정보를 가지며, 이 점들이 모여 건물 내부의 3D 형상을 표현합니다.

**심화 설명:**

포인트 클라우드는 3D 스캐너, LiDAR, 스테레오 카메라 등으로 취득됩니다. 이 프로젝트에서의 역할은 다음과 같습니다.

```
LiDAR 센서 → 깊이 맵 → 카메라 포즈와 결합 → 3D 포인트 클라우드
```

**구조:**
```
// PLY 파일 포맷 예시 (ASCII)
ply
format ascii 1.0
element vertex 128000
property float x
property float y
property float z
property uchar red
property uchar green
property uchar blue
end_header
1.234 5.678 0.123 128 64 32
...
```

| 특성 | 값 (본 프로젝트 기준) |
|------|---------------------|
| 점 개수 | 한 층당 약 10만~50만 점 |
| 파일 포맷 | PLY (Polygon File Format) |
| 점 하나의 데이터 | x, y, z (float32 x3) + r, g, b (uint8 x3) = 15바이트 |
| 활용 | 관리자 UI에서 3D 시각화, 노드/엣지 편집 시 배경 |

포인트 클라우드 vs 메쉬(Mesh):
- **포인트 클라우드**: 점만 존재. 표면 정보 없음. 처리가 빠름.
- **메쉬**: 점을 삼각형으로 연결하여 표면을 형성. 렌더링은 예쁘지만 생성 비용이 높음.

이 프로젝트에서는 관리자 UI에서 그래프 편집 시 공간을 이해하기 위한 배경으로 포인트 클라우드를 사용하며, 메쉬 변환은 하지 않습니다.

**참고 자료:**
- [PLY 파일 포맷 명세](https://paulbourke.net/dataformats/ply/)
- [Open3D - 포인트 클라우드 처리 라이브러리](http://www.open3d.org/)

---

## 2. SLAM / RTAB-Map 심화

### Q2-1. Loop Closure Detection이란?

**발표용 답변:**
Loop Closure Detection은 로봇이나 카메라가 이전에 방문했던 장소를 다시 인식하는 기술입니다. 이를 통해 이동 중 누적된 위치 오차(드리프트)를 한 번에 보정하여 맵의 정확도를 크게 향상시킵니다.

**심화 설명:**

SLAM에서 가장 큰 문제는 **누적 오차(Drift)**입니다. 카메라가 이동하면서 프레임 간 상대적 이동을 누적하여 위치를 추정하는데, 작은 오차가 계속 쌓이면 수십 미터를 이동한 후에는 실제 위치와 추정 위치 사이에 상당한 차이가 발생합니다.

```
[실제 경로]       [오차 누적 경로]
  ┌──────┐          ┌──────┐
  │      │          │      ╲
  │      │  vs      │       ╲   ← 드리프트로 닫히지 않음
  │      │          │        │
  └──────┘          └────────┘
  (루프가 닫힘)     (루프가 열림)
```

Loop Closure의 동작 과정:

1. **장소 인식**: 현재 이미지에서 추출한 시각 특징(Visual Words)을 Bag-of-Words 방식으로 과거 이미지들과 비교합니다.
2. **후보 검증**: 유사도가 높은 이미지를 찾으면, 특징점 매칭과 기하학적 검증(RANSAC + Fundamental Matrix)으로 실제로 같은 장소인지 확인합니다.
3. **그래프 최적화**: Loop Closure가 확인되면, 현재 위치와 과거 위치 사이에 새로운 제약(Constraint)이 추가됩니다. 전체 포즈 그래프를 g2o(General Graph Optimization) 등의 비선형 최적화 알고리즘으로 재최적화하여 누적 오차를 분산시킵니다.

RTAB-Map은 "Real-Time Appearance-Based Mapping"의 약자로, 이름 그대로 **외형(Appearance) 기반**의 Loop Closure Detection을 실시간으로 수행합니다. 메모리를 WM(Working Memory)과 LTM(Long-Term Memory)으로 나누어 대규모 환경에서도 실시간 처리가 가능합니다.

**참고 자료:**
- Labbe, M., & Michaud, F. "RTAB-Map as an Open-Source Lidar and Visual SLAM Library for Large-Scale and Long-Term Online Operation." Journal of Field Robotics, 2019.
- Galvez-Lopez, D., & Tardos, J. D. "Bags of Binary Words for Fast Place Recognition in Image Sequences." IEEE Transactions on Robotics, 2012.

---

### Q2-2. Feature Extraction 방식은?

**발표용 답변:**
RTAB-Map은 이미지에서 SURF, ORB, SIFT 등의 알고리즘으로 특징점을 추출하고 기술자(Descriptor)를 생성합니다. 이 프로젝트에서는 기본 설정인 GFTT(Good Features To Track) + BRIEF 조합을 사용하며, 속도와 정확도의 균형이 좋습니다.

**심화 설명:**

특징점 추출은 크게 두 단계로 나뉩니다.

**1. 검출(Detection)**: 이미지에서 "독특한 지점"을 찾는 단계
- 코너, 에지, 블롭 등 주변과 구별되는 패턴을 가진 픽셀 위치를 식별
- 대표 알고리즘: Harris Corner, FAST, GFTT

**2. 기술(Description)**: 찾은 지점의 "신원 확인서"를 만드는 단계
- 특징점 주변 영역의 밝기 패턴을 수치 벡터로 인코딩
- 이 벡터(Descriptor)를 비교하여 서로 다른 이미지의 같은 지점을 매칭
- 대표 알고리즘: SURF(128D float), ORB(256bit binary), BRIEF(256bit binary)

| 알고리즘 | 검출 | 기술자 | 속도 | 정확도 | 라이선스 |
|---------|------|--------|------|--------|---------|
| SIFT | SIFT | 128D float | 느림 | 높음 | OpenCV 4.4+ 무료 |
| SURF | SURF | 64/128D float | 중간 | 높음 | 특허(만료 중) |
| ORB | FAST | rBRIEF(32B) | 빠름 | 중간 | 무료 |
| GFTT+BRIEF | GFTT | BRIEF(32B) | 빠름 | 중간 | 무료 |

RTAB-Map에서 LiDAR와 함께 사용할 때는 시각 특징 외에도 **3D 포인트 클라우드 특징**을 함께 활용하여 매칭 정확도를 높입니다. LiDAR의 깊이 정보가 있으므로 2D 특징점을 3D 공간으로 역투영(Back-projection)하여 기하학적 정합을 수행합니다.

**참고 자료:**
- Rublee, E., et al. "ORB: An Efficient Alternative to SIFT or SURF." ICCV 2011.
- [RTAB-Map - Feature Type 파라미터](https://github.com/introlab/rtabmap/wiki/Features-and-Parameters)

---

### Q2-3. 포인트 클라우드 데이터 포맷과 용량은?

**발표용 답변:**
이 프로젝트에서는 PLY(Polygon File Format) 포맷을 사용합니다. 한 층 스캔 시 약 10만~50만 개의 3D 점이 생성되며, PLY 파일 크기는 바이너리 포맷 기준 약 5~30MB입니다.

**심화 설명:**

**데이터 구조:**
```
하나의 점 = {
    x: float32 (4바이트) - 미터 단위 X 좌표
    y: float32 (4바이트) - 미터 단위 Y 좌표
    z: float32 (4바이트) - 미터 단위 Z 좌표
    r: uint8   (1바이트) - 빨강 채널
    g: uint8   (1바이트) - 초록 채널
    b: uint8   (1바이트) - 파랑 채널
}
=> 점 하나당 약 15바이트 (바이너리)
```

**용량 계산 예시:**
| 항목 | 값 |
|------|-----|
| 한 층 면적 | ~1,000m^2 |
| 점 밀도 | ~100~500 points/m^2 |
| 점 개수 | ~100,000~500,000 |
| 바이너리 PLY | ~1.5MB ~ 7.5MB |
| ASCII PLY | ~3MB ~ 15MB (텍스트라 더 큼) |
| 원본 .db 파일 | ~50~200MB (이미지, 깊이 맵 포함) |

**포맷 비교:**
| 포맷 | 장점 | 단점 |
|------|------|------|
| PLY | 단순, 범용, 색상 지원 | 압축 없음 |
| LAS/LAZ | 측량 표준, LAZ 압축 | 오버스펙 |
| PCD | PCL 라이브러리 네이티브 | 범용성 낮음 |
| E57 | 산업 표준, 메타데이터 풍부 | 복잡함 |

PLY를 선택한 이유는 Three.js(관리자 웹 UI)에서 직접 로딩 가능하고, Open3D/PCL 등 Python 라이브러리와의 호환성이 좋기 때문입니다.

**참고 자료:**
- [PLY File Format Specification](https://paulbourke.net/dataformats/ply/)
- 프로젝트 내부 문서: `docs/plan/floor-scan-chunk/03_processing_pipeline.md`

---

### Q2-4. Odometry 방식은?

**발표용 답변:**
이 프로젝트에서 RTAB-Map은 iPhone의 LiDAR 센서와 RGB 카메라를 결합한 Visual-LiDAR Odometry를 사용합니다. LiDAR의 정밀한 깊이 정보가 시각 오도메트리의 스케일 모호성(Scale Ambiguity) 문제를 해결합니다.

**심화 설명:**

Odometry는 연속된 센서 데이터로부터 카메라/로봇의 이동량을 추정하는 과정입니다.

**Visual Odometry (VO):**
- 연속된 이미지 프레임에서 특징점을 추출/매칭하여 카메라의 상대적 이동을 계산
- 단안(Monocular) 카메라만 사용하면 절대 스케일을 알 수 없음 (1m 이동인지 10m 이동인지 구분 불가)

**LiDAR Odometry:**
- LiDAR가 측정한 3D 포인트 클라우드를 ICP(Iterative Closest Point) 알고리즘으로 정합하여 이동량 추정
- 절대 스케일을 알 수 있지만, 특징이 적은 환경(긴 복도)에서 정합이 실패할 수 있음

**Visual-LiDAR Fusion (본 프로젝트):**
```
RGB 이미지 → 특징점 추출 → 2D 매칭
     +                        ↓
LiDAR Depth → 2D→3D 역투영 → 3D-3D 정합 → 6DoF 포즈 추정
```

iPhone LiDAR + ARKit 조합의 장점:
- ARKit이 IMU(관성센서) + 카메라 + LiDAR를 융합한 Visual-Inertial Odometry를 제공
- RTAB-Map은 ARKit의 포즈 추정 결과를 Odometry 입력으로 활용 가능
- LiDAR 깊이 맵으로 포인트 클라우드를 직접 생성

**참고 자료:**
- [RTAB-Map iOS App](https://apps.apple.com/app/rtab-map-3d-scanner/id1564549697)
- [ARKit - Understanding World Tracking](https://developer.apple.com/documentation/arkit/arworldtrackingconfiguration)

---

### Q2-5. Drift(드리프트) 문제는 어떻게 대응하는가?

**발표용 답변:**
RTAB-Map의 Loop Closure Detection과 포즈 그래프 최적화로 누적 오차를 보정합니다. 또한 iOS LiDAR + ARKit의 Visual-Inertial Odometry가 프레임 단위 드리프트를 최소화합니다.

**심화 설명:**

드리프트 대응은 크게 세 단계로 이루어집니다.

**1단계 - 프레임 수준 (Odometry):**
- ARKit의 VIO(Visual-Inertial Odometry)가 IMU 데이터와 시각 정보를 칼만 필터로 융합
- 짧은 시간 범위에서는 IMU가 빠른 회전/이동을 정확히 추적
- LiDAR 깊이로 스케일 오차 방지

**2단계 - 루프 수준 (Loop Closure):**
- 이전에 방문한 장소를 재방문하면 Loop Closure 발생
- Bag-of-Words 기반 장소 인식으로 과거 키프레임 매칭
- 그래프 최적화(g2o/GTSAM)로 전체 경로의 누적 오차를 재분배

**3단계 - 후처리 수준 (청크 병합):**
- 이 프로젝트에서는 층별 청크(Chunk) 분할 스캔을 지원
- 여러 청크를 `rtabmap-reprocess`로 병합할 때, 청크 간 겹치는 영역의 Loop Closure가 발생
- 이로써 개별 청크의 드리프트가 전체적으로 보정됨

```
Chunk A              Chunk B
┌──────────────┐ ┌──────────────┐
│ 복도 왼쪽    │ │ 복도 오른쪽   │
│       [겹침 영역]       │
└──────────────┘ └──────────────┘
         ↓ rtabmap-reprocess
    Loop Closure로 두 청크 정합
```

**참고 자료:**
- Kummerle, R., et al. "g2o: A General Framework for Graph Optimization." ICRA 2011.
- 프로젝트 내부 문서: `docs/plan/floor-scan-chunk/00_master_plan.md`

---

### Q2-6. 왜 ORB-SLAM3 대신 RTAB-Map을 선택했는가?

**발표용 답변:**
RTAB-Map은 iOS LiDAR를 네이티브로 지원하는 완성된 모바일 앱을 제공하며, SLAM 결과를 SQLite DB로 내보내어 후처리 파이프라인과 연동하기 쉽습니다. ORB-SLAM3는 연구 코드 성격이 강하여 iOS 통합에 상당한 추가 개발이 필요합니다.

**심화 설명:**

| 기준 | RTAB-Map | ORB-SLAM3 |
|------|----------|-----------|
| iOS 앱 | 공식 앱스토어 배포 | 없음 (직접 포팅 필요) |
| LiDAR 지원 | iPhone LiDAR 네이티브 지원 | Monocular/Stereo/IMU 중심 |
| 데이터 포맷 | SQLite DB (이미지, 포즈, 포인트클라우드 통합) | 자체 바이너리 포맷 |
| 후처리 도구 | rtabmap-reprocess 제공 (청크 병합) | 없음 |
| 포인트클라우드 | Dense reconstruction 지원 | Sparse points만 |
| Loop Closure | Bag-of-Words (대규모 환경 최적화) | DBoW2 (유사) |
| 라이선스 | BSD (상용 사용 가능) | GPLv3 (상용 제한) |
| 메모리 관리 | WM/LTM 분리로 대규모 맵 지원 | 메모리 제한 있음 |

**ORB-SLAM3의 장점이 상대적으로 중요하지 않은 이유:**
- ORB-SLAM3는 Multi-Map, IMU tight integration 등 학술적으로 뛰어나지만, 이 프로젝트에서는 ARKit이 IMU 통합을 이미 처리
- ORB-SLAM3의 Sparse Map은 VPS 참조 맵으로 활용하기 어려움 (Dense한 시각 정보 필요)
- 졸업 프로젝트 일정 내에서 ORB-SLAM3를 iOS에 포팅하는 것은 비현실적

**참고 자료:**
- Campos, C., et al. "ORB-SLAM3: An Accurate Open-Source Library for Visual, Visual-Inertial and Multi-Map SLAM." IEEE Transactions on Robotics, 2021.
- Labbe, M., & Michaud, F. "RTAB-Map as an Open-Source Lidar and Visual SLAM Library." Journal of Field Robotics, 2019.

---

## 3. VPS 심화

### Q3-1. 매칭 알고리즘은? (SuperPoint + SuperGlue)

**발표용 답변:**
VPS 서비스에서는 CNN 기반 특징점 추출기인 SuperPoint와 그래프 신경망 기반 매칭기인 SuperGlue를 사용합니다. 전통적인 SIFT/ORB 대비 조명 변화와 시점 변화에 훨씬 강건한 매칭 성능을 보입니다.

**심화 설명:**

**SuperPoint (특징점 추출):**
- MagicLeap이 개발한 자기 지도 학습(Self-Supervised) 기반 CNN 특징점 검출/기술 네트워크
- Homographic Adaptation이라는 학습 기법으로 다양한 시점 변환에 강건한 특징점을 학습
- 입력: 단일 이미지 -> 출력: 특징점 위치 + 256D 기술자
- 전통 특징점(SIFT, ORB) 대비 반복성(Repeatability)과 매칭 정확도가 우수

**SuperGlue (특징점 매칭):**
- 두 이미지의 SuperPoint 특징점 집합을 입력받아 최적의 매칭을 찾는 그래프 신경망
- Attention 기반 메시지 패싱으로 특징점 간의 관계를 학습
- Sinkhorn 알고리즘으로 최적 할당(Optimal Assignment) 문제를 풀어 1:1 매칭을 보장
- 매칭 불가능한 특징점(Dustbin)도 명시적으로 처리

```
[쿼리 이미지]              [기준 이미지 DB]
     │                          │
 SuperPoint               SuperPoint (사전 추출)
     │                          │
 특징점 + 기술자           특징점 + 기술자
     │                          │
     └──────── SuperGlue ───────┘
                   │
            매칭된 특징점 쌍
                   │
              PnP + RANSAC
                   │
          6DoF 카메라 포즈 추정
```

**참고 자료:**
- DeTone, D., et al. "SuperPoint: Self-Supervised Interest Point Detection and Description." CVPR Workshops, 2018.
- Sarlin, P., et al. "SuperGlue: Learning Feature Matching with Graph Neural Networks." CVPR 2020.

---

### Q3-2. 검색 최적화는 어떻게 하는가? (NetVLAD)

**발표용 답변:**
건물 전체의 기준 이미지를 하나씩 비교하면 너무 느리므로, NetVLAD라는 이미지 검색 네트워크로 먼저 후보 이미지를 좁힌 뒤(Coarse), SuperPoint+SuperGlue로 정밀 매칭합니다(Fine). 이러한 Hierarchical Localization 방식으로 속도와 정확도를 모두 확보합니다.

**심화 설명:**

**문제 상황:**
- 기준 맵에 수천~수만 장의 이미지가 저장됨
- 쿼리 이미지를 모든 기준 이미지와 SuperGlue로 매칭하면 수 분 이상 소요
- 실시간 위치 추정을 위해 ~1초 이내 응답 필요

**해결: Hierarchical Localization (HLoc)**

```
[1단계 - Coarse: 전역 이미지 검색]
    쿼리 이미지 → NetVLAD → 글로벌 기술자 (4096D 벡터)
                               ↓
                    기준 DB의 글로벌 기술자들과 L2 거리 비교
                               ↓
                    상위 K개 (예: 20장) 후보 이미지 선택
                    ≈ 수 밀리초

[2단계 - Fine: 로컬 특징 매칭]
    쿼리 이미지 × 20개 후보 → SuperPoint + SuperGlue
                               ↓
                    2D-3D 대응점 확보
                               ↓
                    PnP + RANSAC → 6DoF 포즈
                    ≈ 수백 밀리초
```

**NetVLAD 동작 원리:**
- VLAD(Vector of Locally Aggregated Descriptors)를 CNN에 통합한 이미지 검색 네트워크
- 이미지 전체를 하나의 고차원 벡터(Global Descriptor)로 압축
- 같은 장소에서 촬영된 이미지끼리 벡터 거리가 가까워지도록 Triplet Loss로 학습
- Google Street View 데이터로 학습되어 실내외 장소 인식에 범용적으로 사용 가능

**참고 자료:**
- Arandjelovic, R., et al. "NetVLAD: CNN Architecture for Weakly Supervised Place Recognition." CVPR 2016.
- Sarlin, P., et al. "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." CVPR 2019 (HLoc 파이프라인).

---

### Q3-3. 측위 실패 시 fallback은?

**발표용 답변:**
VPS 측위 실패 시 사용자가 층과 현재 위치를 직접 선택하는 수동 입력 방식으로 폴백합니다. 관리자 UI에서 등록된 POI(관심 지점) 목록에서 가장 가까운 장소를 선택하면 해당 좌표를 출발점으로 사용합니다.

**심화 설명:**

**측위 실패가 발생하는 경우:**
1. **낮은 confidence**: VPS 매칭 결과의 신뢰도가 임계값(예: 0.5) 미만
2. **매칭 특징점 부족**: SuperGlue 매칭 쌍이 최소 요구(예: 12개) 미만
3. **기하학적 검증 실패**: PnP + RANSAC의 인라이어 비율이 너무 낮음
4. **서비스 불가**: VPS 서버 다운 또는 네트워크 오류

**Fallback 전략 (우선순위 순):**

| 순위 | 방식 | 설명 |
|------|------|------|
| 1 | VPS 재시도 | 다른 방향으로 촬영하여 재시도 유도 (UI에서 "카메라를 다른 방향으로 향해주세요" 안내) |
| 2 | 다층 병렬 매칭 | 현재 층 매칭 실패 시 다른 층의 맵으로도 매칭 시도 (confidence 최고점 선택) |
| 3 | 수동 선택 | 층 선택 + POI 목록에서 현재 위치 직접 선택 |
| 4 | QR 코드 (향후) | 주요 지점에 QR 코드를 부착하여 스캔 시 위치 확인 (미구현) |

```
localize 요청
    ↓
VPS 서비스 호출 (전 층 병렬)
    ↓
confidence >= threshold?
    ├── Yes → 위치 반환
    └── No → 재시도 안내 또는 수동 입력 폴백
```

**참고 자료:**
- 프로젝트 내부 문서: `docs/architecture/tech-decisions.md` (6. 위치 추정: VPS)
- 프로젝트 내부 문서: `docs/architecture/module-design.md` (7. Localization 모듈)

---

### Q3-4. 조명 변화에 대한 강건성은?

**발표용 답변:**
SuperPoint는 합성 데이터의 Homographic Adaptation으로 학습되어 조명 변화에 강건하며, NetVLAD도 다양한 시간대/조명 조건의 이미지로 학습되었습니다. 다만, 완전한 소등 상태에서는 성능이 저하되므로 최소한의 조명이 필요합니다.

**심화 설명:**

**학습 기반 접근의 장점:**
- 전통적 특징점(SIFT, ORB)은 밝기 값의 그래디언트를 직접 사용하므로 조명 변화에 민감
- SuperPoint는 CNN이 밝기 패턴이 아닌 구조적 특징(Structure Feature)을 학습하여 조명에 덜 민감
- 학습 데이터에 다양한 조명 조건이 포함되어 있어 일반화 성능이 우수

**실내 환경 특성:**
- 실내 조명은 실외 대비 변화 폭이 작음 (형광등/LED는 비교적 균일)
- 하지만 창가/복도/실험실 등 영역별 밝기 차이는 존재
- 야간에 비상등만 켜진 상태에서는 성능 저하 가능

**대응 전략:**
1. **스캔 시**: 일반적인 사용 시간대(주간)에 스캔하여 유사한 조명 조건 확보
2. **매칭 시**: SuperPoint의 조명 불변 특성에 의존
3. **후처리**: 이미지 히스토그램 정규화로 밝기 차이 보정 가능 (향후 적용 검토)

| 조건 | 성능 영향 | 비고 |
|------|----------|------|
| 정상 조명 (스캔 시와 동일) | 영향 없음 | confidence 0.8+ |
| 부분 소등 (일부 조명 off) | 경미한 저하 | confidence 0.6~0.8 |
| 자연광 변화 (창가 시간대 차이) | 경미한 저하 | SuperPoint가 대부분 보상 |
| 완전 소등 | 사용 불가 | 카메라 자체가 이미지 취득 불가 |

**참고 자료:**
- DeTone, D., et al. "SuperPoint: Self-Supervised Interest Point Detection and Description." CVPR Workshops, 2018 (Section 4: Homographic Adaptation).

---

### Q3-5. VPS 서버 스펙 요구사항은?

**발표용 답변:**
SuperPoint + SuperGlue는 CNN/GNN 모델이므로 GPU가 있으면 추론 속도가 크게 향상됩니다. 현재 개발 환경에서는 NVIDIA GPU 서버에서 이미지 3장 기준 약 800ms에 위치 추정이 완료됩니다.

**심화 설명:**

**VPS 파이프라인별 연산 비용:**

| 단계 | 연산 특성 | GPU 유무 영향 |
|------|----------|-------------|
| NetVLAD 전역 검색 | CNN forward pass | GPU 시 10x 빠름 |
| SuperPoint 추출 | CNN forward pass | GPU 시 10x 빠름 |
| SuperGlue 매칭 | GNN forward pass + Sinkhorn | GPU 시 20x 빠름 |
| PnP + RANSAC | CPU 연산 (행렬 분해) | GPU 영향 없음 |

**권장 스펙:**

| 구성 요소 | 최소 | 권장 |
|----------|------|------|
| GPU | GTX 1060 6GB | RTX 3060 이상 |
| RAM | 8GB | 16GB |
| VRAM | 4GB | 8GB |
| CPU | 4코어 | 8코어 |
| Storage | SSD 50GB | SSD 100GB (기준 이미지 저장) |

**CPU 전용 운영 시:**
- SuperGlue 매칭이 병목: 이미지 1장당 ~2초 (GPU 시 ~100ms)
- 소규모 건물(기준 이미지 수천 장)에서는 CPU로도 실시간 가능
- 대규모 건물에서는 GPU 필수

**참고 자료:**
- [SuperGlue 공식 레포 - 성능 벤치마크](https://github.com/magicleap/SuperGluePretrainedNetwork)

---

## 4. 좌표계 / AR

### Q4-1. RTAB-Map 좌표계와 ARKit 좌표계의 차이는?

**발표용 답변:**
RTAB-Map은 스캔 시작 지점을 원점으로 하는 미터 단위 3D 좌표계를 사용하고, ARKit은 앱 시작 시점의 디바이스 위치를 원점으로 하는 별도의 좌표계를 사용합니다. 두 좌표계는 원점과 축 방향이 다르므로 변환이 필요합니다.

**심화 설명:**

**RTAB-Map 좌표계 (맵 좌표):**
- 원점: 스캔 시작 위치
- 축: 오른손 좌표계 (X=오른쪽, Y=위, Z=뒤)
- 단위: 미터
- 특성: 스캔이 끝나면 고정. Loop Closure 후 전체 좌표가 최적화됨
- 저장: 서버 DB에 PathNode 좌표로 저장

**ARKit 좌표계 (세션 좌표):**
- 원점: AR 세션 시작 시 디바이스 위치
- 축: 오른손 좌표계 (X=오른쪽, Y=위, Z=뒤, 카메라 방향 기준)
- 단위: 미터
- 특성: 매 세션마다 원점이 달라짐

**핵심 문제:**
```
RTAB-Map 좌표계          ARKit 좌표계
(서버에 저장된 경로)       (사용자 AR 세션)
     O────→ X              O'────→ X'
     │                      │
     ↓ Z                    ↓ Z'

이 두 좌표계를 정합(Alignment)해야
AR 화면에 경로를 올바르게 오버레이할 수 있음
```

VPS가 반환하는 6DoF 포즈가 바로 이 변환의 핵심입니다. VPS는 "현재 카메라가 RTAB-Map 좌표계에서 어디에 있는가"를 알려주므로, 이 포즈를 이용하여 ARKit 좌표계 -> RTAB-Map 좌표계 변환 행렬을 계산할 수 있습니다.

**참고 자료:**
- [ARKit - Understanding World Tracking](https://developer.apple.com/documentation/arkit/arworldtrackingconfiguration)
- [RTAB-Map Coordinate System](https://github.com/introlab/rtabmap/wiki)

---

### Q4-2. 좌표 변환은 어떻게 하는가?

**발표용 답변:**
VPS가 추정한 카메라 포즈(4x4 변환 행렬)를 이용합니다. 이 행렬은 ARKit 좌표계에서 RTAB-Map 좌표계로의 변환을 나타내며, AR 경로 오버레이 시 서버의 PathNode 좌표를 ARKit 좌표계로 역변환하여 화면에 배치합니다.

**심화 설명:**

**변환 행렬의 구조:**
```
VPS 출력: T_map_camera (4x4 행렬)
┌                    ┐
│ R11 R12 R13  tx    │    R: 3x3 회전 행렬
│ R21 R22 R23  ty    │    t: 3x1 이동 벡터
│ R31 R32 R33  tz    │
│  0   0   0   1     │
└                    ┘

의미: 카메라(ARKit) 좌표를 맵(RTAB-Map) 좌표로 변환
P_map = T_map_camera * P_camera
```

**변환 과정:**
```
1. VPS 위치 추정
   → T_map_camera 획득 (카메라 → 맵 변환)

2. ARKit → 맵 변환 행렬 계산
   T_map_arkit = T_map_camera * T_camera_arkit
   (T_camera_arkit는 ARKit 세션에서 현재 카메라 포즈의 역행렬)

3. 경로 오버레이
   서버에서 받은 PathNode 좌표 (맵 좌표계)
   → T_arkit_map (= T_map_arkit의 역행렬)로 변환
   → ARKit 좌표계의 3D 위치로 변환
   → SceneKit/RealityKit으로 AR 공간에 배치
```

**정밀도 한계:**
- VPS 추정 포즈의 오차가 변환 전체에 전파
- 위치 오차: 약 10~30cm, 회전 오차: 약 2~5도
- 경로가 길어질수록 먼 지점의 오버레이 오차가 누적
- 주기적으로 VPS 재측위하여 보정 필요

---

### Q4-3. 변환 정확도 문제는?

**발표용 답변:**
VPS 측위 오차(약 10~30cm)가 좌표 변환에 그대로 전파됩니다. AR 오버레이에서 멀리 있는 경로일수록 시각적 오차가 커지므로, 가까운 구간만 표시하고 이동하면서 주기적으로 재측위하는 방식으로 대응합니다.

**심화 설명:**

**오차 요인 분석:**

| 요인 | 오차 크기 | 대응 |
|------|----------|------|
| VPS 위치 추정 | 10~30cm | 더 많은 기준 이미지 확보 |
| VPS 회전 추정 | 2~5도 | 5m 거리에서 ~40cm 시각적 오차 |
| ARKit 트래킹 드리프트 | 시간에 비례 | 주기적 VPS 재측위 |
| RTAB-Map 맵 정밀도 | 1~5cm | Loop Closure 확보 |

**시각적 오차 계산 (회전 오차 기준):**
```
오차 각도 3도, 거리 10m인 경우:
시각적 오차 = 10m * tan(3도) = ~52cm

사용자 시점에서 10m 앞의 경로가 약 50cm 벗어나 보임
→ 복도 폭이 2m라면 허용 가능한 범위
```

**대응 전략:**
1. **근접 경로만 표시**: AR에서 전방 5~10m 구간만 렌더링하여 원거리 오차 노출 방지
2. **주기적 재측위**: 30초마다 또는 코너 회전 시 VPS 재호출하여 변환 행렬 갱신
3. **스냅핑**: 경로를 벽/복도 중심선에 스냅하여 소규모 오차 보정
4. **시각적 관대함**: 경로 화살표를 넓은 밴드로 표현하여 +-30cm 오차가 눈에 띄지 않게 처리

---

### Q4-4. ARKit 트래킹 끊김에 어떻게 대응하는가?

**발표용 답변:**
ARKit의 트래킹 상태(Tracking State)를 모니터링하여, Limited나 Not Available 상태가 감지되면 사용자에게 안내 메시지를 표시하고, 트래킹이 복구되면 VPS 재측위를 트리거하여 좌표계를 재정합합니다.

**심화 설명:**

**ARKit 트래킹 상태:**

| 상태 | 의미 | 원인 |
|------|------|------|
| Normal | 정상 추적 | - |
| Limited (Excessive Motion) | 빠른 이동으로 추적 제한 | 사용자가 너무 빨리 움직임 |
| Limited (Insufficient Features) | 시각 특징 부족 | 무지 벽, 어두운 환경 |
| Limited (Initializing) | 초기화 중 | 세션 시작 직후 |
| Not Available | 추적 불가 | 카메라 가려짐 등 |

**대응 전략:**

```
ARKit Tracking State 변경 감지
    ↓
├── Normal: 정상 내비게이션 진행
├── Limited:
│   ├── UI: "천천히 움직여주세요" / "밝은 곳으로 이동해주세요"
│   ├── AR 오버레이 반투명 처리 (불확실성 시각화)
│   └── 내비게이션 데이터는 마지막 정상 상태 기준으로 유지
└── Normal 복귀:
    ├── VPS 재측위 자동 트리거
    ├── 새 변환 행렬 계산
    └── AR 오버레이 갱신
```

**World Map 재활용:**
- ARKit의 `ARWorldMap`을 저장해두면, 세션이 끊겨도 이전 맵을 로딩하여 빠르게 복구 가능
- 단, 이 프로젝트에서는 VPS 기반 재측위가 주력이므로 ARWorldMap은 보조적으로만 활용

**참고 자료:**
- [ARKit - Tracking State](https://developer.apple.com/documentation/arkit/arcamera/trackingstate)

---

## 5. 경로 탐색 (A*)

### Q5-1. 왜 A* 알고리즘을 선택했는가?

**발표용 답변:**
A*는 Dijkstra와 동일하게 최적 경로를 보장하면서도, 휴리스틱(목적지 방향 우선 탐색)으로 탐색 효율이 높습니다. 실내 건물의 그래프 규모(수백~수천 노드)에서 50ms 이내의 빠른 응답이 가능하며, 엘리베이터/계단 선호도를 가중치로 간단히 반영할 수 있습니다.

**심화 설명:**

**알고리즘 비교:**

| 알고리즘 | 최적 보장 | 시간 복잡도 | 특징 |
|---------|----------|-----------|------|
| BFS | 최소 홉 | O(V+E) | 가중치 무시 |
| Dijkstra | 최단 경로 | O((V+E) log V) | 전방위 탐색 |
| A* | 최단 경로 | O((V+E) log V) | 목표 방향 우선 |
| Bellman-Ford | 음수 가중치 | O(VE) | 실내에서 불필요 |

**A*의 핵심 = f(n) = g(n) + h(n):**
- g(n): 시작 노드에서 n까지의 실제 비용
- h(n): n에서 목표까지의 추정 비용 (휴리스틱)
- f(n): 총 예상 비용. PriorityQueue에서 f가 작은 노드부터 탐색

**이 프로젝트의 A* 구현 특징:**
1. **3D 유클리드 휴리스틱**: h(n) = sqrt(dx^2 + dy^2 + dz^2). 층간 이동도 자연스럽게 고려
2. **선호도 가중치**: g(n) 계산 시 엣지 유형별 가중치 적용
3. **메모리 로딩**: 건물 전체 그래프를 DB에서 메모리로 로딩 후 탐색 (I/O 병목 제거)

**pgRouting을 선택하지 않은 이유:**
- pgRouting은 도로 네트워크에 최적화되어 있고, 층간 이동이나 선호도 가중치 같은 실내 특화 로직을 SQL로 구현하기 어려움
- 비즈니스 로직(경로 안내 문구 생성, 층 전환 감지)이 DB에 묶이게 됨
- Java의 PriorityQueue로 직접 구현하면 디버깅과 확장이 용이

**참고 자료:**
- Hart, P. E., Nilsson, N. J., & Raphael, B. "A Formal Basis for the Heuristic Determination of Minimum Cost Paths." IEEE Transactions on Systems Science and Cybernetics, 1968.
- 프로젝트 내부 문서: `docs/architecture/tech-decisions.md` (5. 길찾기 알고리즘)

---

### Q5-2. 최적성이 보장되는가? (Admissible Heuristic)

**발표용 답변:**
네, 보장됩니다. 사용하는 휴리스틱(3D 유클리드 거리)은 실제 이동 거리를 절대 초과하지 않으므로 Admissible 조건을 만족합니다. A*는 Admissible 휴리스틱을 사용하면 최적 해를 보장합니다.

**심화 설명:**

**Admissible 조건:**
```
h(n) <= h*(n)  (모든 노드 n에 대해)
h*(n): n에서 목표까지의 실제 최소 비용
```

**유클리드 거리의 Admissible 증명:**
- 유클리드 거리는 두 점 사이의 직선 거리(최단 가능 거리)
- 실제 경로는 노드와 엣지를 따라가야 하므로 직선 거리 이상
- 따라서 h(n) = euclidean(n, goal) <= h*(n) 항상 성립

**주의: 선호도 가중치 적용 시**
- 엘리베이터 선호 모드에서 계단 엣지에 2.0배 패널티를 부여
- 이 경우 h(n)은 가중치 없는 유클리드 거리이므로 여전히 실제 가중 비용 이하
- h(n)이 과소추정되어 탐색 효율은 떨어질 수 있지만 최적성은 유지

| 선호도 | 수평 가중치 | 계단 가중치 | 엘리베이터 가중치 | 최적성 |
|--------|-----------|-----------|---------------|--------|
| 최단 거리 | 1.0x | 1.0x | 1.0x | 보장 |
| 엘리베이터 선호 | 1.0x | 2.0x | 0.5x | 보장 |
| 계단 선호 | 1.0x | 0.8x | 2.0x | 보장 |

세 경우 모두 유클리드 거리(가중치 없음)가 가중 비용보다 작거나 같으므로 Admissible 합니다. 특히 가중치가 0.5x(할인)인 경우에도 유클리드 거리가 할인된 비용 이하이므로 성립합니다.

**참고 자료:**
- Russell, S. & Norvig, P. "Artificial Intelligence: A Modern Approach." Chapter 3.5 (Informed Search).

---

### Q5-3. 엘리베이터/계단 선호도 가중치는 어떻게 설계했는가?

**발표용 답변:**
엣지의 물리적 거리에 유형별 배율을 곱하여 비용을 계산합니다. 예를 들어 엘리베이터 선호 모드에서는 엘리베이터 엣지 비용을 0.5배(할인), 계단 엣지 비용을 2.0배(페널티)로 적용하여 A*가 자연스럽게 엘리베이터를 포함한 경로를 선택하도록 합니다.

**심화 설명:**

```java
// PathEdge.getWeightedDistance(preference) 의 개념
cost = distance * weightFactor(edgeType, preference)
```

**가중치 테이블:**

| 선호도 \ 엣지 유형 | HORIZONTAL | VERTICAL_STAIRCASE | VERTICAL_ELEVATOR |
|-------------------|-----------|-------------------|------------------|
| SHORTEST | 1.0 | 1.0 | 1.0 |
| ELEVATOR_PREFERRED | 1.0 | 2.0 | 0.5 |
| STAIRCASE_PREFERRED | 1.0 | 0.8 | 2.0 |

**설계 근거:**
- 할인 계수(0.5, 0.8): 해당 이동 수단을 "가상적으로 짧게" 만들어 A*가 우선 선택
- 페널티 계수(2.0): 해당 이동 수단을 "가상적으로 길게" 만들어 A*가 기피
- 수평 이동은 모든 모드에서 1.0으로 고정 (기본 이동 비용 불변)

**예시 시나리오:**
```
출발(3층) → 목적지(1층)

경로 A: 3층 → 계단 → 1층    (계단 이동 거리: 10m)
경로 B: 3층 → 엘리베이터 → 1층 (엘리베이터 이동 거리: 15m + 대기)

SHORTEST 모드:      A = 10m,  B = 15m  → 경로 A 선택
ELEVATOR_PREFERRED: A = 20m,  B = 7.5m → 경로 B 선택
STAIRCASE_PREFERRED:A = 8m,   B = 30m  → 경로 A 선택
```

**참고 자료:**
- 프로젝트 내부 문서: `docs/architecture/overview.md` (5. 길찾기 알고리즘 - 경로 선호도별 가중치)
- 소스 코드: `src/main/java/.../pathfinding/application/service/AStarPathfinder.java`

---

### Q5-4. 경로 재탐색 조건은?

**발표용 답변:**
현재는 사용자가 경로에서 크게 이탈했을 때(VPS 재측위 결과가 경로로부터 일정 거리 이상 벗어남) 클라이언트가 새로운 출발점으로 재탐색을 요청하는 방식입니다. 자동 경로 이탈 감지 및 재탐색은 향후 구현 예정입니다.

**심화 설명:**

**재탐색이 필요한 경우:**

| 상황 | 감지 방법 | 대응 |
|------|----------|------|
| 경로 이탈 | VPS 재측위 시 경로까지 거리 > 5m | 새 출발점으로 재탐색 |
| 목적지 변경 | 사용자가 새 목적지 입력 | 현재 위치 → 새 목적지 재탐색 |
| 엘리베이터 대기 | 엘리베이터가 고장/만석 | 선호도를 계단으로 변경하여 재탐색 |
| 층 오인식 | VPS가 다른 층으로 판단 | 올바른 층 기준 재탐색 |

**현재 구현 상태:**
```
[현재] 클라이언트 주도 재탐색
    사용자가 "길 다시 찾기" 버튼 탭
    → 현재 VPS 위치를 새 출발점으로
    → POST /pathfinding 재호출

[향후] 자동 재탐색 (미구현)
    VPS 위치와 경로 간 거리 지속 모니터링
    → 임계값 초과 시 자동 재탐색
    → WebSocket으로 새 경로 푸시
```

**A* 재탐색 성능:**
- 142노드 그래프 기준 ~50ms이므로, 재탐색 시에도 체감 지연 없음
- 재탐색 비용이 매우 낮으므로 공격적인 재탐색 정책이 가능

---

### Q5-5. 노드 수가 증가하면 성능은?

**발표용 답변:**
A*의 시간 복잡도는 O((V+E) log V)이지만, Admissible 휴리스틱 덕분에 실제 탐색 노드 수는 전체의 일부입니다. 1,000노드까지는 100ms 미만, 10,000노드에서도 수백 ms 수준으로 실시간 사용에 문제없습니다.

**심화 설명:**

**현재 성능 데이터 (school.db 기준):**
| 지표 | 값 |
|------|-----|
| 노드 수 | 142 |
| 엣지 수 | 198 |
| A* 응답 시간 | ~50ms |

**예상 스케일링:**

| 규모 | 노드 수 | 예상 응답 시간 | 예시 |
|------|--------|-------------|------|
| 소형 건물 | ~200 | ~50ms | 학교 건물 1동 |
| 중형 건물 | ~1,000 | ~100ms | 대형 백화점 |
| 대형 복합단지 | ~5,000 | ~300ms | 코엑스 |
| 초대형 | ~10,000 | ~500ms | 인천공항 |

**최적화 방안 (필요 시):**
1. **그래프 캐싱**: 건물별 그래프를 메모리에 캐싱하여 DB 로딩 시간 절약
2. **Bidirectional A***: 양방향 탐색으로 탐색 공간 절반 감소
3. **계층적 탐색**: 층 단위로 먼저 탐색 후, 층 내 상세 탐색 (HPA* - Hierarchical Pathfinding A*)
4. **층별 그래프 분리 로딩**: 출발/목적 층과 연결된 층만 로딩

현재 건물 단위(수백 노드) 그래프에서는 최적화 없이도 충분히 빠르므로, 추가 최적화는 실제 병목이 발생할 때 적용할 예정입니다.

**참고 자료:**
- 프로젝트 내부 문서: `docs/research/연구노트_6회차.md` (성능 측정 결과)

---

## 6. 백엔드 / 시스템 설계

### Q6-1. 기술 스택을 설명해 주세요.

**발표용 답변:**
메인 API 서버는 Spring Boot 4.0.2 + Java 25, 경로 추출 서비스는 Python FastAPI, 위치 추정 서비스는 Python 기반 VPS 서버이며, 데이터베이스는 PostgreSQL + PostGIS(공간 데이터 확장)를 사용합니다.

**심화 설명:**

| 구성 요소 | 기술 | 선택 근거 |
|----------|------|----------|
| **메인 API** | Spring Boot 4.0.2, Java 25 | 타입 안전성, Hibernate Spatial(PostGIS 연동), DDD 지원 |
| **경로 추출** | Python 3.11, FastAPI | RTAB-Map SDK, 점군 처리(Open3D, NumPy) 라이브러리 풍부 |
| **VPS 서버** | Python, SLAM 기반 | SuperPoint/SuperGlue 등 딥러닝 모델 추론 (PyTorch) |
| **DB** | PostgreSQL 16 + PostGIS 3.4 | 3D 좌표(POINTZ, LINESTRINGZ) 네이티브 지원, 공간 인덱스(GiST) |
| **인프라** | Docker Compose | 로컬 개발과 배포 환경 통일 |
| **CI/CD** | GitHub Actions | Push -> Build -> Deploy 자동화 |
| **테스트** | JUnit 5, Testcontainers | PostGIS 통합 테스트를 위한 실제 DB 컨테이너 |

**Spring Boot + Java를 선택한 핵심 이유:**
1. **Hibernate Spatial**: JPA 엔티티에서 `@Column(columnDefinition = "geometry(PointZ, 4326)")` 같은 공간 타입을 직접 매핑
2. **JTS(Java Topology Suite)**: 기하학 연산(거리 계산, 경계 박스, 교차 판정)을 Java 코드에서 직접 수행
3. **타입 안전성**: 3D 좌표, 그래프 노드/엣지 등 복잡한 도메인 모델에서 컴파일 타임 오류 검출

**Python 서비스를 별도로 분리한 이유:**
- RTAB-Map의 Python API(`rtabmap` 패키지), Open3D, SciPy 등 점군 처리 생태계가 Python에 집중
- `rtabmap-reprocess` CLI 도구가 Python에서 호출하기 용이
- 딥러닝 모델(SuperPoint, SuperGlue, NetVLAD) 추론은 PyTorch 기반

**참고 자료:**
- 프로젝트 내부 문서: `docs/architecture/tech-decisions.md`

---

### Q6-2. 층별 청크 업로드 + 서버 병합 설계는 왜 필요한가?

**발표용 답변:**
대형 층은 한 번에 스캔하기 물리적으로 어렵고, 일부 영역이 변경되어도 전체를 재스캔해야 하는 문제가 있었습니다. 청크 분할 업로드 + 서버 병합(rtabmap-reprocess) 방식으로 부분 스캔, 부분 교체, 점진적 업데이트가 가능해졌습니다.

**심화 설명:**

**기존 방식의 문제 (건물 전체 단일 스캔):**

| 문제 | 설명 |
|------|------|
| 전체 재스캔 필요 | 1개 층 변경 시에도 건물 전체 재스캔 |
| 대형 층 스캔 불가 | 넓은 층은 배터리/메모리 한계로 단일 스캔 불가능 |
| 층 분리 오류 | Z-range 기반 자동 층 분리는 메자닌, 반층 구조에서 실패 |
| VPS 전체 재등록 | 한 층 변경 시 건물 전체 VPS 맵 재생성 필요 |

**새로운 방식 (층별 청크 분할):**

```
AS-IS: Building → ScanSession (건물 전체)
TO-BE: Building → Floor → ScanChunk (부분 스캔, 1개 이상)
                        → MergedScan (병합 결과, 최대 1개)
```

**병합 파이프라인:**
```
1. 관리자가 층을 지정하여 여러 .db 청크 업로드
2. POST /floors/{floorId}/scans/merge 트리거
3. 서버가 rtabmap-reprocess로 청크들을 병합
   - 청크 간 겹침 영역의 Loop Closure로 정합
   - 단일 청크인 경우 병합 스킵
4. 병합 결과 .db에서 경로 추출 + PLY 추출
5. VPS 맵 등록
```

**부분 업데이트 시나리오:**
```
기존: Chunk1(왼쪽 복도) + Chunk2(오른쪽 복도) = MergedScan

변경: Chunk2만 재스캔 → Chunk2_new로 교체

재병합: Chunk1 + Chunk2_new = MergedScan_v2
→ Chunk1은 재스캔 불필요
```

**참고 자료:**
- 프로젝트 내부 문서: `docs/plan/floor-scan-chunk/00_master_plan.md`

---

### Q6-3. VPS 서버와 API 서버를 왜 분리했는가?

**발표용 답변:**
VPS 서버는 GPU 기반 딥러닝 추론(SuperPoint, SuperGlue)이 필요하고, API 서버는 CRUD와 비즈니스 로직 처리가 주 역할입니다. 기술 스택(Python/PyTorch vs Java/Spring)과 하드웨어 요구사항(GPU vs CPU)이 근본적으로 다르므로 분리했습니다.

**심화 설명:**

**분리의 근거:**

| 관점 | API 서버 (Spring Boot) | VPS 서버 (Python) |
|------|----------------------|------------------|
| 언어 | Java 25 | Python 3.11 |
| 핵심 라이브러리 | Spring, JPA, JTS | PyTorch, SuperGlue, Open3D |
| 하드웨어 | CPU 중심 | GPU 필수 (추론) |
| 스케일링 | 수평 확장 (Stateless) | GPU 단위 스케일링 |
| 요청 특성 | 짧은 응답 (<100ms) | 상대적으로 긴 응답 (~800ms) |
| 배포 주기 | 비즈니스 로직 변경 시 | 모델 업데이트 시 |

**통신 구조:**
```
클라이언트 → Spring Boot(:8080)
                ├── 직접 처리: 건물/층 CRUD, A* 길찾기
                ├── → Python FastAPI(:8000): 경로 추출
                └── → VPS 서버(:5000): 위치 추정

Spring Boot가 Gateway 역할을 수행하여 클라이언트는 단일 진입점만 알면 됨
```

**이 구조의 이점:**
1. VPS 서버 다운 시에도 경로 탐색, 건물 관리 등 나머지 기능 정상 동작
2. VPS 모델 교체(SuperGlue v2 등) 시 API 서버 변경 불필요
3. GPU 서버 비용 최적화: VPS만 GPU 인스턴스에 배포, 나머지는 일반 인스턴스

---

### Q6-4. 동시 사용자 처리는?

**발표용 답변:**
Spring Boot의 스레드 풀 기반 요청 처리로 동시 접속을 지원합니다. A* 길찾기는 50ms 이내로 빠르고 Stateless하므로 동시 요청에 유리합니다. VPS 호출은 비동기(CompletableFuture)로 처리하여 스레드 블로킹을 최소화합니다.

**심화 설명:**

**병목 지점 분석:**

| 기능 | 응답 시간 | 병목 요소 | 동시 처리 전략 |
|------|----------|----------|-------------|
| 건물/층 CRUD | <10ms | DB I/O | 커넥션 풀 (HikariCP) |
| A* 길찾기 | ~50ms | CPU (메모리 그래프) | Stateless, 스레드 안전 |
| VPS 위치 추정 | ~800ms | VPS 서버 GPU | 비동기 호출, 타임아웃 |
| 스캔 처리 | ~30s | Python 서비스 | 비동기 + 폴링 |

**동시 요청 시나리오 (예: 100명 동시 길찾기):**
```
100개 요청 → Spring Boot 스레드 풀 (200 기본)
          → 각 스레드에서 독립적으로 A* 실행
          → 그래프 데이터: 건물당 캐시 가능
          → 총 처리 시간: ~50ms (병렬)
```

**한계와 대응:**
- VPS 서버는 GPU 1대 기준 동시 추론 제한 있음
- 대규모 동시 접속 시 VPS 요청 큐잉 필요
- 현재 졸업 프로젝트 규모에서는 동시 10~20명 수준으로 충분

---

### Q6-5. DDD 아키텍처를 선택한 이유는?

**발표용 답변:**
건물-층-경로-노드-스캔-위치추정이라는 복잡한 도메인을 다루므로, 전통적 3계층 구조에서는 서비스 계층이 비대해지기 쉽습니다. DDD 기반 모듈 구조로 각 도메인의 책임을 명확히 분리하고, 모듈 간 이벤트 통신으로 느슨한 결합을 유지합니다.

**심화 설명:**

**적용한 DDD 개념:**

| 개념 | 적용 방식 |
|------|----------|
| Bounded Context | 모듈별 독립 패키지 (building, floor, pathfinding, scan, localization 등) |
| Aggregate | Building이 루트 애그리거트, Floor/PathNode 등이 하위 엔티티 |
| Rich Domain Model | 비즈니스 로직을 엔티티 내부에 작성 (PathEdge.getWeightedDistance()) |
| Domain Event | ScanFileUploadedEvent로 모듈 간 느슨한 결합 |
| Value Object | Point3D를 Embeddable로 정의 |
| Application Service | 명령별 분리 (Creator, Updater, Reader, Deleter) |

**경량 DDD를 선택한 이유:**
- 헥사고날 아키텍처: Port/Adapter 패턴은 깔끔하지만 졸업작품 규모에서는 과도한 추상화
- Entity = Domain Model: JPA Entity와 Domain Model 분리 시 변환 코드가 증가하므로 통합하여 실용성 확보
- CQRS 미적용: 읽기/쓰기 비율이 극단적이지 않고, 단일 DB로 충분한 규모

**모듈 구조:**
```
modules/
├── building/        (건물 관리 - 루트 애그리거트)
├── floor/           (층 관리 + 층별 경로)
├── passage/         (계단/엘리베이터)
├── scan/            (3D 스캔 업로드 → 이벤트 발행)
├── pathprocessing/  (Python 서비스 연동)
├── pathfinding/     (A* 길찾기 + POI)
└── localization/    (VPS 위치 추정)
```

**참고 자료:**
- 프로젝트 내부 문서: `docs/architecture/tech-decisions.md` (3. DDD 적용)
- 프로젝트 내부 문서: `docs/architecture/module-design.md`

---

## 7. 정확도 / 신뢰성

### Q7-1. VPS 정확도는 어느 정도인가?

**발표용 답변:**
학교 건물 테스트 기준, VPS 위치 추정 정확도는 약 10~30cm(위치 오차), 2~5도(방향 오차)이며, confidence 0.87 수준입니다. 이는 실내 내비게이션에서 "어느 복도에 있는가"를 판단하기에 충분한 정밀도입니다.

**심화 설명:**

**측정 결과 (school.db 기준):**

| 지표 | 값 | 비고 |
|------|-----|------|
| 위치 오차 (Median) | ~20cm | Ground Truth 대비 |
| 위치 오차 (90th percentile) | ~50cm | 최악 케이스 |
| 방향 오차 (Median) | ~3도 | |
| Confidence (평균) | 0.87 | 이미지 3장 기준 |
| 응답 시간 | ~800ms | 네트워크 포함 |

**기존 실내 측위 기술과의 비교:**

| 기술 | 정밀도 | 인프라 비용 | 비고 |
|------|--------|-----------|------|
| BLE 비콘 | 1~3m | 높음 (비콘 설치) | 비콘 설치/관리 필요 |
| Wi-Fi 핑거프린팅 | 3~5m | 중간 (AP 설치) | 환경 변화에 민감 |
| UWB | 10~30cm | 매우 높음 (앵커 설치) | 최고 정밀도이나 비용이 큼 |
| **VPS (본 프로젝트)** | **10~30cm** | **없음** (카메라만) | 추가 인프라 불필요 |

VPS의 핵심 장점은 정밀도와 인프라 비용의 균형입니다. UWB 수준의 정밀도를 추가 하드웨어 없이 달성할 수 있습니다. 단, VPS는 시각 정보에 의존하므로 시각적 특징이 부족한 환경에서는 성능이 저하됩니다.

**참고 자료:**
- 프로젝트 내부 문서: `docs/research/연구노트_6회차.md` (성능 측정)
- Zafari, F., et al. "A Survey of Indoor Localization Systems and Technologies." IEEE Communications Surveys & Tutorials, 2019.

---

### Q7-2. 반복 구조 복도에서는 어떻게 구분하는가?

**발표용 답변:**
반복 구조(동일한 디자인의 복도가 여러 개)는 VPS의 대표적인 난제입니다. NetVLAD의 전역 검색 단계에서 여러 후보가 동점이 되며, SuperGlue의 로컬 매칭에서 기하학적 검증(PnP + RANSAC)으로 최종 구별합니다. 그래도 오인식이 발생할 수 있어, 이동 연속성과 층 정보를 추가 단서로 활용합니다.

**심화 설명:**

**문제의 본질:**
```
복도 A ─────────────────── 출입구
      │  동일 디자인  │
복도 B ─────────────────── 출입구
      │  동일 디자인  │
복도 C ─────────────────── 출입구

→ 세 복도의 이미지가 매우 유사하여 VPS가 혼동할 수 있음
```

**대응 전략 (다단계):**

1. **기하학적 차별점 활용**: 완전히 동일한 복도는 거의 없음. 소화기 위치, 방번호 표지판, 창문 바깥 풍경 등 미세한 차이가 특징점 매칭을 통해 구별됨

2. **Confidence 기반 필터링**: 여러 후보 중 confidence가 가장 높은 것을 선택하되, 1위와 2위의 confidence 차이가 작으면 불확실하다고 판단하여 재촬영 유도

3. **시간적 연속성**: 이전 측위 결과와의 물리적 이동 가능성을 검증
   ```
   이전 위치: 3층 복도 A 중간
   현재 측위: 1층 복도 C 끝
   → 2초 사이에 이동 불가능 → 복도 A 유지
   ```

4. **층 제약**: 현재 VPS가 모든 층을 병렬로 매칭하되, 이전 측위 결과의 층을 우선적으로 신뢰

**한계 인정:**
- 완전히 동일한 공간(예: 동일 설계의 다른 건물 동)에서는 원리적으로 구분 불가
- 이 경우 GPS 또는 건물 선택 같은 추가 단서가 필요

---

### Q7-3. 사람이 많은 환경에서는?

**발표용 답변:**
사람이 많으면 VPS 기준 이미지와 현재 영상의 차이가 커져 매칭 성능이 저하될 수 있습니다. 하지만 SuperPoint/SuperGlue는 배경의 구조적 특징(벽, 바닥, 천장)에 집중하여 매칭하므로, 보행자가 화면의 50% 이하를 차지하면 대체로 정상 동작합니다.

**심화 설명:**

**영향 분석:**

| 요소 | VPS 영향 | ARKit 영향 |
|------|---------|-----------|
| 보행자가 특징점 차폐 | 매칭 쌍 감소 | 특징점 추적 방해 |
| 보행자 이동으로 인한 동적 객체 | False match 가능 | Relocalization 지연 |
| 혼잡으로 카메라 방향 제한 | 기준 이미지와 시점 차이 증가 | 트래킹 불안정 |

**대응 전략:**
1. **다중 이미지 촬영**: 1장이 아닌 3장을 촬영하여 서로 다른 방향의 정보 확보
2. **정적 영역 집중**: 상단(천장, 벽 상부)을 포함하도록 촬영 유도 (사람이 가리지 않는 영역)
3. **RANSAC 이상치 제거**: 보행자 위의 잘못된 매칭을 기하학적 검증으로 필터링
4. **낮은 confidence 시 재시도**: "사람이 적은 방향으로 촬영해주세요" 안내

---

### Q7-4. 환경이 변하면? (포스터, 가구 이동 등)

**발표용 답변:**
소규모 변화(포스터 교체, 가구 이동)는 건물의 구조적 특징(벽, 기둥, 바닥 패턴)이 유지되므로 VPS 성능에 큰 영향을 주지 않습니다. 대규모 변화(리모델링)가 있으면 해당 층의 청크를 재스캔하여 기준 맵을 갱신해야 합니다.

**심화 설명:**

**환경 변화 유형별 영향:**

| 변화 유형 | VPS 영향 | 대응 필요성 |
|----------|---------|-----------|
| 포스터/게시물 교체 | 경미 | 불필요 |
| 가구 소규모 이동 | 경미~중간 | 불필요 |
| 계절 장식 (크리스마스 트리 등) | 중간 | 재스캔 권장 |
| 리모델링/벽 철거 | 심각 | 재스캔 필수 |
| 바닥재 교체 | 중간~심각 | 재스캔 필요 |

**환경 변화에 대한 강건성 근거:**
1. **구조적 특징의 지속성**: 벽 코너, 기둥, 천장 구조는 쉽게 변하지 않음
2. **특징점 분산**: SuperPoint가 추출하는 수백 개 특징점 중 일부만 변해도 나머지로 매칭 가능
3. **RANSAC의 이상치 내성**: 변경된 영역의 잘못된 매칭을 자동 필터링

**유지보수 전략:**
- 청크 기반 부분 업데이트가 핵심 이점
- 변경된 영역의 청크만 재스캔하고 재병합하면 전체 재스캔 불필요
- 정기적 기준 맵 갱신 주기: 학기 단위(6개월) 권장

---

### Q7-5. 층 오인식에 어떻게 대응하는가?

**발표용 답변:**
VPS가 모든 층의 맵을 병렬로 매칭한 후 confidence가 가장 높은 층을 선택합니다. 반복 구조로 인한 오인식은 이전 측위 결과의 층 정보와 이동 연속성을 검증하여 필터링합니다. 기압 센서(Barometer) 데이터를 보조 단서로 활용하는 방안도 검토 중입니다.

**심화 설명:**

**다층 병렬 매칭 전략:**
```
사용자 이미지 촬영
    ↓
Spring Boot → VPS에 전 층 맵 병렬 요청
    ├── Floor 1 map: localize → confidence 0.3
    ├── Floor 2 map: localize → confidence 0.4
    └── Floor 3 map: localize → confidence 0.9  ← 최고 confidence
    ↓
Floor 3으로 결정
```

**오인식 방지 전략:**

1. **Confidence 차이 검증**: 1위와 2위의 차이가 0.2 미만이면 불확실. 추가 촬영 요청
2. **이동 연속성 검증**: "2초 전에 3층에 있었는데 갑자기 1층"은 불가능. 이전 층 유지
3. **기압 센서 보조 (향후)**:
   - 스마트폰 기압계로 상대 높이 변화 감지
   - 층간 이동(계단/엘리베이터) 시에만 층 전환 허용
4. **수동 보정**: UI에서 "지금 몇 층이세요?" 확인 기능 제공

**참고 자료:**
- 프로젝트 내부 문서: `docs/plan/floor-scan-chunk/00_master_plan.md` (VPS 층별 관리)

---

## 8. 실용성 / 차별점

### Q8-1. 다비오(Dabeeomaps) 등 기존 서비스와 비교하면?

**발표용 답변:**
다비오 같은 상용 서비스는 BLE 비콘 기반 측위로 건물에 하드웨어 설치가 필수입니다. 본 시스템은 스마트폰 카메라와 LiDAR만으로 스캔과 측위를 모두 수행하므로, 추가 인프라 설치 없이 누구나 실내 내비게이션을 구축할 수 있다는 것이 핵심 차별점입니다.

**심화 설명:**

| 비교 항목 | 다비오/네이버 인도어 | 본 프로젝트 |
|----------|-------------------|-----------|
| **측위 기술** | BLE 비콘 + Wi-Fi | VPS (카메라만) |
| **인프라 비용** | 비콘 설치 + 유지보수 | 없음 (스마트폰만) |
| **맵 제작** | 전문 측량 + CAD 도면 | RTAB-Map 앱으로 직접 스캔 |
| **맵 제작 진입장벽** | 높음 (전문 장비/인력) | 낮음 (iPhone + 앱) |
| **측위 정밀도** | 1~3m (BLE) | 10~30cm (VPS) |
| **경로 안내** | 2D 지도 오버레이 | AR 3D 경로 오버레이 |
| **유지보수** | 비콘 배터리 교체, 위치 재조정 | 변경 영역 재스캔 |
| **규모** | 대형 건물 (비용 대비 효과) | 소~중형 건물 (비용 무관) |

**본 프로젝트의 한계 (vs 상용 서비스):**
- 상용 서비스는 수년간의 안정화를 거쳐 다양한 엣지 케이스를 처리
- BLE 비콘은 시각 조건과 무관하게 동작 (VPS는 카메라 의존)
- 대규모 건물(공항, 쇼핑몰)에서의 검증이 부족

**본 프로젝트의 고유 가치:**
- **Zero Infrastructure**: 추가 하드웨어 설치 비용 0원
- **Self-Service**: 건물 관리자가 직접 스캔하여 맵 구축 가능
- **부분 업데이트**: 청크 기반으로 변경 영역만 재스캔
- **AR 기반 안내**: 직관적인 3D 경로 안내

---

### Q8-2. 유지보수 방식은?

**발표용 답변:**
환경 변화 시 변경된 영역의 청크만 재스캔하여 교체하고 재병합합니다. 전체 재스캔이 필요 없으므로 유지보수 비용이 낮습니다. POI 정보와 노드/엣지 그래프는 관리자 웹 UI에서 직접 편집할 수 있습니다.

**심화 설명:**

**유지보수 시나리오별 작업:**

| 시나리오 | 필요 작업 | 소요 시간 (예상) |
|---------|----------|---------------|
| 교실 이름 변경 | POI 이름 수정 (API 호출) | 1분 |
| 화장실 리모델링 | 해당 영역 청크 재스캔 + 재병합 | 30분 |
| 새 건물 동 추가 | 새 건물/층 등록 + 전체 스캔 | 2~3시간 |
| 경로 수정 (우회로 추가) | 관리자 UI에서 노드/엣지 수동 편집 | 10분 |
| 계절별 장식 대응 | 해당 영역 청크 재스캔 | 20분 |

**관리자 UI 기능:**
- 3D 포인트 클라우드 위에서 노드/엣지 시각적 편집
- POI(관심 지점) CRUD + 카테고리 관리
- 수직 통로(계단/엘리베이터) 수동 연결 설정
- 스캔 처리 상태 모니터링 + 결과 미리보기

---

### Q8-3. 오프라인에서도 동작하는가?

**발표용 답변:**
현재는 VPS 위치 추정과 경로 탐색 모두 서버 통신이 필요하여 오프라인 미지원입니다. 다만, 경로 그래프를 디바이스에 캐싱하면 오프라인 경로 탐색은 가능하며, VPS 대신 ARKit의 로컬 트래킹으로 제한적인 위치 추적이 가능합니다.

**심화 설명:**

**오프라인 전략 (향후 구현 검토):**

| 기능 | 오프라인 가능성 | 필요 작업 |
|------|---------------|----------|
| 경로 탐색 (A*) | 가능 | 그래프 데이터 사전 다운로드 + 디바이스에서 A* 실행 |
| AR 경로 표시 | 부분 가능 | 사전 다운로드된 경로 + ARKit 로컬 트래킹 |
| VPS 위치 추정 | 불가 | 서버 GPU 추론 필수 |
| 초기 위치 설정 | 수동만 가능 | QR 코드 스캔 또는 POI 선택 |

**오프라인 시나리오:**
```
1. 온라인 상태에서 건물 데이터 사전 다운로드
   - 그래프 (PathNode, PathEdge)
   - POI 목록
   - 기본 경로 데이터

2. 오프라인 진입 시
   - 현재 위치: 수동 선택 (층 + POI)
   - 경로 탐색: 디바이스 로컬에서 A* 실행
   - AR 표시: ARKit 로컬 트래킹으로 경로 오버레이
   - 제한: VPS 재측위 불가 → 드리프트 누적
```

---

### Q8-4. 배터리 소모는 어떠한가?

**발표용 답변:**
카메라, LiDAR, AR 렌더링, 네트워크 통신을 동시에 사용하므로 배터리 소모가 큽니다. iPhone 15 Pro 기준으로 연속 내비게이션 시 시간당 약 20~30%의 배터리를 소모할 것으로 예상됩니다.

**심화 설명:**

**배터리 소모 요인별 분석:**

| 요인 | 소모 정도 | 최적화 방안 |
|------|----------|-----------|
| ARKit 세션 (카메라 + IMU) | 높음 | 필수 (AR 기반이므로 줄일 수 없음) |
| AR 렌더링 (SceneKit/RealityKit) | 중간 | 경로 근처만 렌더링, LOD 적용 |
| VPS 이미지 촬영/전송 | 중간 | 촬영 빈도 최적화 (30초마다) |
| 네트워크 통신 | 낮음 | 경로 데이터 캐싱 |
| LiDAR (스캔 시에만) | 높음 | 일반 사용자는 스캔 불필요 |

**비교 (실내 내비게이션 기준):**
| 방식 | 시간당 배터리 소모 (예상) |
|------|----------------------|
| 2D 지도 + BLE | ~5~10% |
| 본 프로젝트 (AR + VPS) | ~20~30% |
| Pokemon GO (AR 모드) | ~25~35% |

**최적화 방안:**
1. VPS 호출 빈도를 최소화 (위치 안정 시 간격 연장)
2. AR 렌더링 품질 조절 (배터리 절약 모드)
3. 목적지 도착 시 즉시 세션 종료
4. 경로 직선 구간에서는 카메라 프레임 레이트 절감

---

### Q8-5. 잘못된 스캔 데이터 업로드에 대한 보안은?

**발표용 답변:**
관리자 인증 기반 접근 제어로 승인된 사용자만 스캔 데이터를 업로드할 수 있습니다. 업로드된 .db 파일은 서버에서 RTAB-Map 포맷 검증을 거치며, 처리 결과의 노드/엣지 수가 비정상적으로 적으면 경고를 발생시킵니다.

**심화 설명:**

**보안 위협 분석:**

| 위협 | 대응 | 현재 상태 |
|------|------|----------|
| 비인가 업로드 | 관리자 인증 (JWT/세션) | 구현 예정 |
| 악의적 .db 파일 | 파일 포맷 검증 (SQLite 테이블 구조 확인) | 기본 검증 |
| 거짓 스캔 데이터 (다른 건물) | 처리 결과의 좌표 범위 확인 | 부분 구현 |
| 초대형 파일 DoS | 파일 크기 제한 (500MB) | 구현됨 |
| 파일 경로 조작 | UUID 기반 파일명으로 저장 | 구현됨 |

**데이터 무결성 검증:**
```
업로드된 .db 파일
    ↓
1. 파일 크기 검증 (500MB 제한)
2. SQLite 포맷 검증 (RTAB-Map 테이블 존재 확인)
3. 처리 실행
    ↓
4. 결과 검증
   - 노드 수 최소 기준 충족?
   - 좌표 범위가 합리적? (건물 규모에 맞는가?)
   - 연결 그래프가 분리되어 있지 않은가?
```

---

### Q8-6. 학술적 기여는 무엇인가?

**발표용 답변:**
기존 연구는 SLAM과 VPS를 개별적으로 다루지만, 본 프로젝트는 RTAB-Map 스캔부터 VPS 측위, A* 경로 탐색, AR 안내까지의 End-to-End 파이프라인을 설계하고 구현하여, 추가 인프라 없이 실내 AR 내비게이션이 가능함을 실증했습니다.

**심화 설명:**

**학술적 기여 포인트:**

1. **Zero-Infrastructure Indoor Navigation Pipeline**
   - BLE/Wi-Fi/UWB 없이 스마트폰 카메라만으로 실내 측위 + 경로 안내
   - 스캔 데이터를 VPS 기준 맵과 경로 그래프로 동시에 활용하는 이중 활용(Dual-Use) 설계

2. **Chunk-based Scan Management**
   - 층별 분할 스캔 + 서버 병합(rtabmap-reprocess)으로 대규모 건물과 부분 업데이트 문제를 해결
   - 기존 RTAB-Map 연구에서 다루지 않은 실용적인 맵 관리 전략

3. **3D Graph-based Indoor Pathfinding**
   - PostGIS의 3D 공간 데이터 타입을 활용한 실내 경로 그래프 설계
   - 사용자 선호도(엘리베이터/계단)를 가중치로 반영하는 A* 변형

4. **시스템 통합(System Integration)**
   - SLAM(RTAB-Map) + VPS(SuperPoint/SuperGlue) + Pathfinding(A*) + AR(ARKit)의 이종 기술 통합
   - 각 기술의 좌표계, 데이터 포맷, 통신 프로토콜을 통일하는 설계 경험

**관련 연구와의 차별점:**

| 연구 | 측위 | 경로 | AR | 인프라 |
|------|------|------|-----|--------|
| Indoor Atlas | 지자기 | X | X | 핑거프린트 |
| Google Live View | VPS | O | O | 실외 전용 |
| NavCog (CMU) | BLE | O | X | 비콘 필요 |
| **본 프로젝트** | **VPS** | **O** | **O** | **없음** |

**참고 자료:**
- 프로젝트 내부 문서: `docs/research/연구노트_6회차.md` (연구 결과 요약)
- Labbe, M., & Michaud, F. "RTAB-Map as an Open-Source Lidar and Visual SLAM Library." Journal of Field Robotics, 2019.
- Sarlin, P., et al. "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." CVPR 2019.

---

## 부록: 발표 시 자주 나오는 일반 질문

### "시연 가능한가?"

> 네. 학교 2공학관 데이터로 실제 시연이 가능합니다. 건물 등록 → 스캔 업로드 → 경로 추출 → VPS 측위 → A* 길찾기 → 턴바이턴 안내까지의 전체 플로우를 보여드릴 수 있습니다.

### "향후 계획은?"

> 1. 실시간 위치 갱신: WebSocket 기반 연속 측위 및 경로 이탈 시 자동 재탐색
> 2. AR 경로 오버레이: ARKit/RealityKit으로 실제 화면에 3D 경로 표시
> 3. 다건물 지원: 캠퍼스 전체를 포괄하는 건물 간 경로 탐색
> 4. 오프라인 모드: 경로 데이터 사전 다운로드 + 디바이스 로컬 A*

### "한계점은 무엇인가?"

> 1. VPS는 시각 정보에 의존하므로 완전 소등/무특징 환경에서 동작하지 않습니다
> 2. AR 기반이므로 배터리 소모가 큽니다
> 3. iPhone LiDAR 탑재 모델만 스캔이 가능합니다 (일반 사용은 카메라만으로 가능)
> 4. 대규모 건물에서의 충분한 검증이 이루어지지 않았습니다
