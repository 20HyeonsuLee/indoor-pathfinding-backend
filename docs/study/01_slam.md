# SLAM 기술 총정리 (Visual SLAM 중심)

> 졸업 프로젝트 발표 심사용 스터디 가이드
> 작성일: 2026-03-26

---

## 목차

1. [SLAM이란?](#1-slam이란)
2. [Visual SLAM vs 기타 SLAM](#2-visual-slam-vs-기타-slam)
3. [주요 Visual SLAM 구현체 비교](#3-주요-visual-slam-구현체-비교)
4. [비교 요약표](#4-비교-요약표)
5. [우리 프로젝트에서 RTAB-Map을 선택한 이유](#5-우리-프로젝트에서-rtab-map을-선택한-이유)
6. [SLAM의 핵심 도전 과제](#6-slam의-핵심-도전-과제)

---

## 1. SLAM이란?

### 1.1 정의

**SLAM (Simultaneous Localization and Mapping)** 은 로봇이나 디바이스가 **미지의 환경**에서 동시에 두 가지 작업을 수행하는 기술이다.

- **Localization (위치 추정):** 환경 내에서 자신의 현재 위치와 자세(pose)를 실시간으로 추정한다.
- **Mapping (지도 작성):** 주변 환경의 3D 또는 2D 지도를 점진적으로 구축한다.

이 두 작업은 상호 의존적이다. 정확한 위치를 알아야 정확한 지도를 만들 수 있고, 정확한 지도가 있어야 정확한 위치를 알 수 있다. 이것이 SLAM의 본질적 난제인 **Chicken-and-Egg Problem**이다.

> **참고:** SLAM이라는 용어는 Hugh Durrant-Whyte와 John J. Leonard의 연구에서 처음 체계화되었다.
> - H. Durrant-Whyte and T. Bailey, "Simultaneous localization and mapping: part I," *IEEE Robotics & Automation Magazine*, 2006. [IEEE Link](https://ieeexplore.ieee.org/document/1638022)
> - T. Bailey and H. Durrant-Whyte, "Simultaneous localization and mapping (SLAM): part II," *IEEE Robotics & Automation Magazine*, 2006. [IEEE Link](https://ieeexplore.ieee.org/document/1678144)

### 1.2 SLAM의 핵심 구성요소

SLAM 시스템은 크게 **Frontend**와 **Backend**, 그리고 이들을 보완하는 **Loop Closure**와 **Map Management**로 구성된다.

#### Frontend (센서 데이터 처리)

Frontend는 센서로부터 들어오는 원시 데이터를 처리하여 로봇의 움직임과 환경 정보를 추출하는 단계이다.

- **Feature Extraction (특징점 추출):** 이미지에서 반복적으로 식별 가능한 점(코너, 엣지 등)을 검출한다. 대표적으로 ORB, SIFT, SURF 등의 알고리즘이 사용된다.
  - ORB: Oriented FAST and Rotated BRIEF. 속도와 회전 불변성 사이의 균형이 좋아 SLAM에서 가장 널리 쓰인다. [ORB 논문 (Rublee et al., 2011)](https://ieeexplore.ieee.org/document/6126544)
- **Feature Tracking / Matching (특징점 추적):** 연속된 프레임 간에 같은 특징점을 매칭하여 카메라의 상대적 움직임(Visual Odometry)을 계산한다.
- **Depth Estimation (깊이 추정):** Stereo 카메라나 RGB-D 센서에서는 직접 깊이를 얻고, Monocular 카메라에서는 삼각측량(triangulation)을 통해 깊이를 추정한다.

#### Backend (최적화)

Frontend에서 추출된 포즈와 랜드마크 정보는 노이즈를 포함하므로, Backend에서 전체적인 일관성을 유지하도록 최적화한다.

- **Graph-based Optimization (그래프 기반 최적화):** 각 카메라 포즈를 노드, 포즈 간 관계(odometry, loop closure)를 엣지로 하는 **Pose Graph**를 구성하고, 전체 그래프의 에러를 최소화한다.
  - g2o (General Graph Optimization): [GitHub](https://github.com/RainerKuemmerle/g2o), [논문 (Kuemmerle et al., 2011)](https://ieeexplore.ieee.org/document/5979949)
  - GTSAM (Georgia Tech Smoothing and Mapping): [GitHub](https://github.com/borglab/gtsam), [공식 문서](https://gtsam.org/)
- **Bundle Adjustment (번들 조정):** 카메라 파라미터, 포즈, 3D 점의 위치를 동시에 최적화하는 비선형 최적화 기법이다. Visual SLAM의 정확도를 결정짓는 핵심 요소이다.
  - [Bundle Adjustment 개요 (Triggs et al., 2000)](https://hal.inria.fr/inria-00548290/document)

#### Loop Closure (루프 클로저)

이전에 방문했던 장소를 다시 인식하여, 누적된 drift 오차를 한 번에 보정하는 과정이다. SLAM 시스템의 장기적 정확도를 결정짓는 가장 중요한 요소 중 하나이다.

- **Bag of Words (BoW):** 특징점 디스크립터를 시각 단어(visual word)로 양자화하여 이미지 간 유사도를 빠르게 비교한다.
  - DBoW2: [GitHub](https://github.com/dorian3d/DBoW2), [논문 (Galvez-Lopez & Tardos, 2012)](https://ieeexplore.ieee.org/document/6202705)
- **작동 원리:** 현재 프레임과 과거 모든 키프레임을 BoW로 비교 -> 유사도가 임계값을 넘으면 후보 검출 -> 기하학적 검증(Essential/Fundamental Matrix) -> 포즈 그래프에 루프 엣지 추가 -> 전체 그래프 재최적화

#### Map Management (맵 관리)

- 키프레임 선택 전략: 모든 프레임을 저장하면 메모리와 연산이 폭증하므로, 충분한 시점 변화가 있는 프레임만 키프레임으로 선정한다.
- 맵 포인트 관리: 관측 빈도가 낮거나 신뢰도가 낮은 3D 점을 제거(culling)한다.
- 대규모 환경 대응: 맵이 커질수록 메모리와 최적화 비용이 증가하므로, 서브맵 분할이나 메모리 계층 관리가 필요하다.

### 1.3 SLAM이 필요한 이유 (Chicken-and-Egg Problem)

전통적인 접근법에서는 지도가 미리 주어져 있으면 위치를 추정하고 (Localization), 위치가 알려져 있으면 지도를 만들 수 있다 (Mapping). 그러나 실제 현장에서는 다음과 같은 상황이 빈번하다.

| 문제 상황 | 설명 |
|:---|:---|
| **지도 없음** | 새로운 건물, 시공 현장, 재난 현장 등 사전 지도가 존재하지 않는 환경 |
| **GPS 불가** | 실내, 지하, 수중 등 GPS 신호가 도달하지 않는 환경 |
| **환경 변화** | 가구 배치 변경, 공사 등으로 기존 지도가 더 이상 유효하지 않은 경우 |
| **자율 탐색** | 로봇이 인간의 도움 없이 스스로 환경을 파악해야 하는 경우 |

SLAM은 이러한 상황에서 **지도와 위치를 동시에 점진적으로 추정**함으로써 Chicken-and-Egg 문제를 해결한다. 특히 우리 프로젝트처럼 **실내 환경에서 GPS 없이 3D 지도를 구축하고 위치를 파악**해야 하는 경우, SLAM은 필수 기술이다.

---

## 2. Visual SLAM vs 기타 SLAM

### 2.1 Visual SLAM (카메라 기반)

카메라 영상(이미지)을 주요 입력으로 사용하는 SLAM이다. 카메라의 종류에 따라 세분화된다.

- **Monocular SLAM:** 단일 카메라만 사용. 깊이 정보를 직접 얻을 수 없어 삼각측량에 의존하며, 스케일 모호성(scale ambiguity)이 존재한다.
- **Stereo SLAM:** 두 대의 카메라를 일정 간격(baseline)으로 배치하여 스테레오 시차로 깊이를 직접 계산한다.
- **RGB-D SLAM:** 컬러 이미지와 깊이 이미지를 동시에 제공하는 센서(Intel RealSense, Microsoft Kinect, Apple LiDAR 등)를 사용한다.

> **참고:** Visual SLAM 종합 서베이
> - C. Cadena et al., "Past, Present, and Future of Simultaneous Localization and Mapping: Toward the Robust-Perception Age," *IEEE T-RO*, 2016. [arXiv](https://arxiv.org/abs/1606.05830)

### 2.2 LiDAR SLAM

LiDAR(Light Detection and Ranging) 센서가 레이저 펄스를 발사하고 반사되어 돌아오는 시간을 측정하여 정밀한 거리 정보를 포인트 클라우드 형태로 획득한다.

- **대표 구현체:** Cartographer (Google), LeGO-LOAM, LIO-SAM
  - Google Cartographer: [GitHub](https://github.com/cartographer-project/cartographer), [논문 (Hess et al., 2016)](https://ieeexplore.ieee.org/document/7487258)
  - LIO-SAM: [GitHub](https://github.com/TixiaoShan/LIO-SAM), [논문 (Shan et al., 2020)](https://arxiv.org/abs/2007.00258)
- **특징:** 밀리미터 수준의 거리 정확도, 조명 조건에 강건하지만 센서가 고가이고 텍스처 정보가 없다.

### 2.3 Visual-Inertial SLAM (카메라 + IMU)

카메라 영상과 IMU(Inertial Measurement Unit, 관성 측정 장치)를 융합하는 방식이다. IMU는 가속도계와 자이로스코프로 구성되어 빠른 움직임에서도 포즈 추정이 가능하다.

- **Loosely-coupled:** 카메라와 IMU를 각각 독립적으로 처리한 후 결과를 융합한다.
- **Tightly-coupled:** 카메라와 IMU 데이터를 하나의 최적화 프레임워크에서 동시에 처리한다. 정확도가 더 높다.
- **대표 구현체:** VINS-Mono, VINS-Fusion, ORB-SLAM3 (VI 모드), MSCKF
  - MSCKF: [논문 (Mourikis & Roumeliotis, 2007)](https://ieeexplore.ieee.org/document/4209642)

### 2.4 장단점 비교표

| 항목 | Visual SLAM | LiDAR SLAM | Visual-Inertial SLAM |
|:---|:---|:---|:---|
| **센서 비용** | 저가 (일반 카메라) | 고가 (LiDAR 센서) | 중간 (카메라 + IMU) |
| **깊이 정확도** | 중간 (RGB-D) ~ 낮음 (Mono) | 매우 높음 (mm 단위) | 중간 |
| **조명 의존성** | 높음 (어두운 환경 취약) | 낮음 (능동 센서) | 높음 (카메라 의존) |
| **텍스처 필요성** | 높음 (특징점 필요) | 낮음 (구조 기반) | 높음 (카메라 의존) |
| **빠른 움직임 대응** | 약함 (모션 블러) | 중간 | 강함 (IMU 보상) |
| **맵 표현** | Dense/Semi-dense/Sparse | Dense Point Cloud | Sparse ~ Semi-dense |
| **실내/실외** | 실내 적합 (특히 RGB-D) | 둘 다 우수 | 둘 다 가능 |
| **소형화/모바일** | 용이 | 어려움 | 용이 (스마트폰 내장) |
| **대표 응용** | AR, 실내 내비게이션 | 자율주행, 측량 | 드론, 스마트폰 AR |

> **참고:** 센서 융합 SLAM 서베이
> - S. Huang et al., "Visual Odometry and Mapping for Autonomous Flight Using an RGB-D Camera," *ISRR*, 2017. [Springer](https://link.springer.com/chapter/10.1007/978-3-319-60916-4_14)

---

## 3. 주요 Visual SLAM 구현체 비교

### 3.1 ORB-SLAM3

#### 원리

ORB-SLAM3는 **Feature-based Visual SLAM**의 대표적 구현체로, ORB (Oriented FAST and Rotated BRIEF) 특징점을 기반으로 동작한다.

- **3가지 카메라 모드:** Monocular, Stereo, RGB-D를 모두 지원한다.
- **파이프라인 구성:**
  1. **Tracking:** 매 프레임에서 ORB 특징점을 추출하고, 이전 프레임 또는 로컬 맵과 매칭하여 카메라 포즈를 실시간 추정한다.
  2. **Local Mapping:** 새 키프레임이 삽입되면, 로컬 영역의 맵 포인트와 키프레임에 대해 Local Bundle Adjustment를 수행한다.
  3. **Loop Closing & Map Merging:** DBoW2 기반 Place Recognition으로 루프를 탐지하고, Pose Graph Optimization으로 전체 맵을 보정한다.

#### Multi-map System (Atlas)

ORB-SLAM3의 핵심 혁신 중 하나는 **Atlas** 시스템이다. 트래킹이 실패하면 새로운 서브맵을 생성하고, 이후 같은 장소를 재방문하면 서브맵들을 병합한다. 이를 통해 트래킹 손실에서의 견고한 복구가 가능하다.

#### IMU 통합 (Visual-Inertial Mode)

- **Tightly-coupled** 방식으로 IMU 데이터를 통합하여, IMU preintegration을 활용한 Visual-Inertial Bundle Adjustment를 수행한다.
- IMU 초기화 과정에서 중력 방향, 스케일, IMU 바이어스를 동시에 추정한다.

#### 장점

- **학술적으로 가장 많이 인용되는 SLAM 라이브러리.** Visual SLAM 벤치마크에서 최고 수준의 정확도를 기록한다.
- Mono/Stereo/RGB-D/Visual-Inertial 등 다양한 모드를 하나의 통합 프레임워크에서 지원한다.
- Multi-map 시스템으로 트래킹 손실에 강건하다.

#### 단점

- **빌드 난이도가 높다.** 다수의 의존성(Pangolin, DBoW2, g2o, Eigen 등)과 특정 버전 제약으로 환경 구성이 까다롭다.
- **실시간 대규모 맵 관리가 어렵다.** 전체 맵이 메모리에 상주해야 하므로, 대규모 환경에서는 메모리 사용량이 급증한다.
- **GPL 라이선스:** 상업적 활용 시 소스 코드 공개 의무가 있다.
- 공식적으로 모바일 플랫폼(iOS/Android)을 지원하지 않는다.

#### 참고 자료

- **논문:** C. Campos, R. Elvira, J. J. G. Rodriguez, J. M. M. Montiel, and J. D. Tardos, "ORB-SLAM3: An Accurate Open-Source Library for Visual, Visual-Inertial and Multi-Map SLAM," *IEEE T-RO*, 2021. [arXiv](https://arxiv.org/abs/2007.11898)
- **이전 버전 논문 (ORB-SLAM2):** R. Mur-Artal and J. D. Tardos, "ORB-SLAM2: An Open-Source SLAM System for Monocular, Stereo, and RGB-D Cameras," *IEEE T-RO*, 2017. [arXiv](https://arxiv.org/abs/1610.06475)
- **GitHub:** [https://github.com/UZ-SLAMLab/ORB_SLAM3](https://github.com/UZ-SLAMLab/ORB_SLAM3)

---

### 3.2 RTAB-Map (Real-Time Appearance-Based Mapping)

#### 원리

RTAB-Map은 **Appearance-based Loop Closure Detection**을 핵심으로 하는 Graph-based SLAM 시스템이다.

- **Bag of Words 기반 Place Recognition:** 키프레임의 시각적 특징을 BoW 벡터로 변환하고, 과거 키프레임들과의 유사도를 계산하여 루프 클로저를 탐지한다.
- **다중 센서 융합:** Stereo 카메라, RGB-D 센서, 2D/3D LiDAR를 지원하며, 이들을 조합하여 사용할 수도 있다.
- **Visual Odometry 모듈:** 자체 VO를 내장하고 있으며(F2M: Frame-to-Map, F2F: Frame-to-Frame), 외부 odometry(wheel encoder, IMU 등)와의 융합도 지원한다.

#### Memory Management (STM / WM / LTM)

RTAB-Map의 가장 독보적인 특징은 **3단계 메모리 관리 아키텍처**이다. 이것이 대규모 환경에서 실시간 동작을 가능하게 하는 핵심이다.

| 메모리 계층 | 역할 | 특징 |
|:---|:---|:---|
| **STM (Short-Term Memory)** | 가장 최근 키프레임을 저장 | 루프 클로저 검색에서 제외 (너무 최근은 자기 자신과 매칭될 수 있으므로) |
| **WM (Working Memory)** | 루프 클로저 탐색 대상 | 실시간 제약 조건 내에서 처리 가능한 크기로 유지. 가중치(최근성 + 이웃 빈도)로 관리 |
| **LTM (Long-Term Memory)** | WM에서 밀려난 키프레임 저장 | 디스크(SQLite DB)에 저장. 루프 클로저 탐지 시 다시 WM으로 불러올 수 있음 |

이 구조 덕분에 **맵 크기에 관계없이 일정한 처리 시간**을 유지할 수 있다. 맵이 커지면 오래된 노드를 LTM으로 이동시키되, 루프 클로저로 해당 영역이 다시 관측되면 LTM에서 WM으로 복원(retrieval)한다.

#### Multi-session Mapping

- 여러 번의 매핑 세션을 수행한 후, 각 세션의 맵 데이터베이스(SQLite .db 파일)를 **병합(merge)** 할 수 있다.
- 동일 환경을 여러 번 스캔하여 정밀도를 높이거나, 다른 사람이 스캔한 결과를 합칠 수 있다.
- 이 기능은 우리 프로젝트의 **청크별 업로드 + 서버 병합** 전략과 직접적으로 부합한다.

#### 장점

- **대규모 환경에서의 실시간 동작:** 메모리 관리 아키텍처 덕분에 건물 전체 규모의 환경도 실시간으로 처리할 수 있다.
- **다양한 센서/플랫폼 지원:** ROS, ROS2, 독립 실행(standalone), iOS, Android를 모두 지원한다.
- **Multi-session 매핑:** 세션 간 맵 병합이 네이티브로 지원된다.
- **Output 다양성:** 3D Point Cloud, OctoMap, 2D Occupancy Grid 등 다양한 맵 포맷을 출력할 수 있다.
- **BSD 라이선스:** 상업적 활용이 자유롭다.

#### 단점

- **Feature Extraction 품질이 환경 의존적:** 텍스처가 부족한 환경(흰 벽, 유리)에서는 특징점 매칭 성능이 저하된다.
- **순수 VO 정확도는 ORB-SLAM3 대비 다소 낮을 수 있다:** RTAB-Map의 강점은 개별 포즈 정확도보다는 대규모 맵 관리와 루프 클로저에 있다.
- **파라미터가 많다:** 다양한 센서와 상황을 지원하기 위해 조정 가능한 파라미터가 매우 많아, 최적 설정을 찾기 위한 튜닝이 필요하다.

#### 참고 자료

- **논문:** M. Labbe and F. Michaud, "RTAB-Map as an Open-Source Lidar and Visual SLAM Library for Large-Scale and Long-Term Online Operation," *Journal of Field Robotics*, 2019. [Wiley](https://doi.org/10.1002/rob.21831), [arXiv 프리프린트 관련](https://arxiv.org/abs/1906.02899)
- **이전 논문:** M. Labbe and F. Michaud, "Online Global Loop Closure Detection for Large-Scale Multi-Session Graph-Based SLAM," *IROS*, 2014. [IEEE](https://ieeexplore.ieee.org/document/6942926)
- **메모리 관리 논문:** M. Labbe and F. Michaud, "Appearance-Based Loop Closure Detection for Online Large-Scale and Long-Term Operation," *IEEE T-RO*, 2013. [IEEE](https://ieeexplore.ieee.org/document/6594910)
- **공식 사이트:** [http://introlab.github.io/rtabmap/](http://introlab.github.io/rtabmap/)
- **GitHub:** [https://github.com/introlab/rtabmap](https://github.com/introlab/rtabmap)
- **iOS 앱 (RTABMapApp):** [https://github.com/introlab/rtabmap/tree/master/app/ios](https://github.com/introlab/rtabmap/tree/master/app/ios)
- **파라미터 문서:** [https://github.com/introlab/rtabmap/wiki/Parameters](https://github.com/introlab/rtabmap/wiki/Parameters)

---

### 3.3 Stella-SLAM (구 OpenVSLAM)

#### 원리

Stella-SLAM은 ORB 특징점 기반의 Visual SLAM이지만, ORB-SLAM 시리즈와는 **독립적으로 처음부터 재구현**된 시스템이다. OpenVSLAM이라는 이름으로 시작되었으나, 코드 유사성 논란 이후 stella-cv 커뮤니티에서 Stella-SLAM으로 이름을 변경하고 유지보수를 이어가고 있다.

- **Tracking:** ORB 특징점을 사용하여 Frame-to-Frame 및 Frame-to-Map 매칭으로 포즈를 추정한다.
- **Mapping:** Local Bundle Adjustment로 로컬 맵을 최적화한다.
- **Loop Closure:** DBoW2 기반 Place Recognition과 Pose Graph Optimization을 수행한다.

#### Map Save/Load

- 구축한 맵을 MessagePack 형식으로 저장하고, 이후 로드하여 **Localization-only 모드**로 활용할 수 있다.
- 이 기능은 한 번 맵을 만들어 놓고 이후 위치 추정에만 사용하는 시나리오에 유용하다.

#### 장점

- **BSD 라이선스:** ORB-SLAM의 GPL 라이선스 제약에서 자유로워 상업적 활용이 가능하다.
- **실용적인 API:** Map save/load가 기본 지원되어, 사전 구축 맵 기반 Localization에 활용하기 좋다.
- **비교적 깔끔한 코드:** 모듈화가 잘 되어 있어 커스터마이즈가 용이하다.
- **Socket.IO Viewer:** 웹 브라우저에서 실시간으로 SLAM 결과를 시각화할 수 있다.

#### 단점

- **ORB-SLAM3 대비 정확도 다소 낮음:** 특히 Visual-Inertial 모드가 없고, Multi-map 시스템이 없어 트래킹 손실 복구 능력이 제한적이다.
- **커뮤니티 규모:** ORB-SLAM3이나 RTAB-Map에 비해 사용자 기반과 생태계가 작다.
- **LiDAR 지원 없음:** 순수 Visual SLAM만 지원한다.

#### 참고 자료

- **GitHub:** [https://github.com/stella-cv/stella_vslam](https://github.com/stella-cv/stella_vslam)
- **문서:** [https://stella-cv.readthedocs.io/](https://stella-cv.readthedocs.io/)
- **원 OpenVSLAM 논문:** S. Sumikura, M. Shibuya, and K. Sakurada, "OpenVSLAM: A Versatile Visual SLAM Framework," *ACM MM*, 2019. [arXiv](https://arxiv.org/abs/1910.01122)

---

### 3.4 VINS-Mono / VINS-Fusion

#### 원리

VINS-Mono는 **Visual-Inertial Odometry (VIO)** 에 특화된 시스템으로, 홍콩과기대(HKUST) 공중 로봇 그룹에서 개발했다. 카메라와 IMU 데이터를 **Tightly-coupled** 방식으로 융합하여 높은 정확도의 상태 추정을 수행한다.

- **IMU Preintegration:** 연속된 IMU 측정값을 사전 적분하여, 매 최적화마다 IMU 데이터를 재처리하는 비용을 절감한다.
- **Sliding Window Optimization:** 최근 N개의 키프레임과 IMU 측정값에 대해 비선형 최적화(Ceres Solver)를 수행한다.
- **Loop Closure:** DBoW2 기반으로 탐지하며, 4-DOF Pose Graph Optimization으로 보정한다.
- **Relocalization:** 트래킹 실패 시 BoW를 이용하여 이전 맵에서 재위치를 추정한다.

#### VINS-Fusion

VINS-Mono의 확장 버전으로, 다음을 추가 지원한다.

- **Stereo 카메라** 지원
- **Stereo + IMU** 융합
- **GPS 융합** (실외 환경)

#### 장점

- **IMU 융합이 매우 강력하다.** Tightly-coupled 최적화로 빠른 움직임이나 짧은 텍스처 부족 구간에서도 안정적인 추정이 가능하다.
- **모바일 최적화:** AR/드론 등 리소스가 제한된 플랫폼에서의 효율적인 동작을 목표로 설계되었다.
- **실시간 성능:** 경량 아키텍처로 실시간 VIO를 달성한다.

#### 단점

- **대규모 맵 관리 기능이 부족하다.** RTAB-Map 같은 메모리 관리 시스템이 없다.
- **Dense Mapping 미지원:** Sparse한 포즈 추정에 집중하며, Dense 3D 복원은 별도 도구가 필요하다.
- **RGB-D 센서 미지원:** 깊이 카메라 입력을 직접 처리하지 않는다.

#### 참고 자료

- **VINS-Mono 논문:** T. Qin, P. Li, and S. Shen, "VINS-Mono: A Robust and Versatile Monocular Visual-Inertial State Estimator," *IEEE T-RO*, 2018. [arXiv](https://arxiv.org/abs/1708.03852)
- **VINS-Fusion 논문:** T. Qin, J. Pan, S. Cao, and S. Shen, "A General Optimization-based Framework for Local Odometry Estimation with Multiple Sensors," 2019. [arXiv](https://arxiv.org/abs/1901.03638)
- **VINS-Mono GitHub:** [https://github.com/HKUST-Aerial-Robotics/VINS-Mono](https://github.com/HKUST-Aerial-Robotics/VINS-Mono)
- **VINS-Fusion GitHub:** [https://github.com/HKUST-Aerial-Robotics/VINS-Fusion](https://github.com/HKUST-Aerial-Robotics/VINS-Fusion)

---

## 4. 비교 요약표

| 항목 | ORB-SLAM3 | RTAB-Map | Stella-SLAM | VINS-Mono/Fusion |
|:---|:---|:---|:---|:---|
| **센서** | Mono, Stereo, RGB-D, IMU | Stereo, RGB-D, 2D/3D LiDAR | Mono, Stereo, RGB-D | Mono+IMU, Stereo+IMU |
| **접근 방식** | Feature-based (ORB) | Appearance-based (BoW) + Graph | Feature-based (ORB) | Tightly-coupled VIO |
| **Loop Closure** | DBoW2 + Pose Graph Opt. | BoW + Memory Management | DBoW2 + Pose Graph Opt. | DBoW2 + 4-DOF Pose Graph |
| **Multi-session** | Atlas (Multi-map) | 네이티브 DB 병합 지원 | Map save/load (단일 세션) | 미지원 |
| **대규모 맵 관리** | 전체 맵 메모리 상주 | STM/WM/LTM 메모리 계층 | 전체 맵 메모리 상주 | Sliding Window (최근만) |
| **Dense Mapping** | Sparse | Dense (Point Cloud, OctoMap) | Sparse | Sparse |
| **라이선스** | GPLv3 | BSD | BSD | GPLv3 |
| **모바일 지원** | 미지원 | iOS, Android 앱 존재 | 미지원 | 제한적 (연구 수준) |
| **ROS 지원** | 커뮤니티 래퍼 | 공식 지원 (ROS/ROS2) | 공식 지원 | 공식 지원 |
| **정확도 (벤치마크)** | 최상위권 | 상위권 | 상위권 (ORB-SLAM3 대비 약간 낮음) | 상위권 (VIO 특화) |
| **빌드 난이도** | 높음 | 중간 | 중간 | 중간 |
| **주요 용도** | 학술 연구, 정밀 측위 | 대규모 실내외 매핑 | 실용 SLAM 애플리케이션 | 드론, AR, 모바일 VIO |

---

## 5. 우리 프로젝트에서 RTAB-Map을 선택한 이유

우리 졸업 프로젝트는 **실내 환경의 3D 맵을 구축하고, 이를 기반으로 실내 내비게이션 서비스를 제공**하는 것을 목표로 한다. 이러한 요구사항에 RTAB-Map이 가장 적합한 이유는 다음과 같다.

### 5.1 iOS LiDAR 센서 직접 지원

- iPhone Pro / iPad Pro 모델에 탑재된 **Apple LiDAR Scanner**를 직접 활용할 수 있다.
- LiDAR를 통해 RGB-D 데이터를 획득하므로, Monocular SLAM의 스케일 모호성 문제가 발생하지 않는다.
- 별도의 외부 센서 장비 없이 **스마트폰 한 대로 매핑이 가능**하다.

> **참고:** Apple LiDAR Scanner는 dToF(direct Time-of-Flight) 센서로 최대 5m 범위의 깊이를 측정한다.
> - [Apple Developer - Depth API](https://developer.apple.com/documentation/arkit/arframe/3566299-smootheddepthmap)

### 5.2 Multi-session Mapping (청크별 업로드 + 병합)

- 건물 전체를 한 번에 스캔하는 것은 비현실적이다 (배터리, 메모리, 파일 크기 제약).
- RTAB-Map은 **여러 번의 스캔 세션을 별도로 저장한 후, 서버에서 병합**할 수 있다.
- 우리 프로젝트의 아키텍처:
  1. 사용자가 iOS 앱으로 건물의 일부를 스캔 (세션 1)
  2. 스캔 결과(.db 파일)를 서버에 업로드
  3. 다른 영역을 추가 스캔하여 업로드 (세션 2, 3, ...)
  4. 서버에서 RTAB-Map CLI 도구(`rtabmap-reprocess`)를 이용하여 세션들을 병합
  5. 병합된 맵에서 3D Point Cloud 및 경로 그래프를 추출

### 5.3 실시간 대규모 맵 관리 (메모리 관리 아키텍처)

- STM/WM/LTM 메모리 계층 구조 덕분에 **건물 전체 규모의 맵도 메모리 제한 내에서 처리**할 수 있다.
- 대학교 건물 한 동 전체(여러 층)를 매핑해야 하는 우리 프로젝트의 규모에서, ORB-SLAM3처럼 전체 맵을 메모리에 상주시키는 방식은 부담이 크다.
- RTAB-Map의 SQLite 기반 데이터베이스는 맵 데이터의 영속적 저장과 점진적 업데이트를 자연스럽게 지원한다.

### 5.4 기존 iOS 앱 존재 (RTABMapApp)

- RTAB-Map 개발팀이 공식으로 제공하는 **iOS 앱(RTABMapApp)** 이 App Store에 출시되어 있다.
- 이 앱의 소스 코드가 공개되어 있어, 우리 프로젝트의 **iOS 클라이언트 개발 시 참고 또는 커스터마이즈**가 가능하다.
- Apple ARKit과의 연동이 이미 검증되어 있어, LiDAR 데이터 파이프라인을 처음부터 구현할 필요가 없다.

> **RTABMapApp iOS:**
> - App Store: [RTABMap on App Store](https://apps.apple.com/app/rtabmap-3d-scanner/id1564775006)
> - 소스 코드: [GitHub - rtabmap/app/ios](https://github.com/introlab/rtabmap/tree/master/app/ios)

### 5.5 BSD 라이선스

- ORB-SLAM3 (GPL)이나 VINS-Mono (GPL)와 달리, RTAB-Map은 **BSD 라이선스**이므로 졸업 프로젝트 이후 상업적 확장이나 비공개 배포에도 제약이 없다.

### 5.6 선택 근거 요약

| 요구사항 | ORB-SLAM3 | RTAB-Map | Stella-SLAM | VINS-Mono |
|:---|:---:|:---:|:---:|:---:|
| iOS LiDAR 지원 | X | **O** | X | X |
| Multi-session 맵 병합 | 제한적 | **O** | 제한적 | X |
| 대규모 맵 메모리 관리 | X | **O** | X | X |
| Dense 3D Reconstruction | X | **O** | X | X |
| 모바일 앱 존재 | X | **O** | X | X |
| BSD 라이선스 | X | **O** | O | X |

---

## 6. SLAM의 핵심 도전 과제

### 6.1 Drift 누적과 Loop Closure의 중요성

**Drift(드리프트)** 는 Visual Odometry의 작은 추정 오차가 프레임마다 누적되어, 장거리 이동 시 실제 위치와 추정 위치 사이에 점점 큰 차이가 발생하는 현상이다.

- **원인:** 매 프레임의 포즈 추정은 이전 프레임에 대한 상대 변환의 연쇄이므로, 오차가 불가피하게 누적된다.
- **해결:** Loop Closure가 핵심 해결책이다. 이전에 방문한 장소를 재인식하면, 시작점과 끝점의 오차를 포즈 그래프 전체에 분산시켜 보정한다.
- **우리 프로젝트에서의 의미:** 건물 한 바퀴를 돌아 출발점으로 돌아왔을 때 Loop Closure가 발생하지 않으면, 맵에 불일치(alignment error)가 생긴다. RTAB-Map의 BoW 기반 Loop Closure와 메모리 관리 시스템은 이를 효과적으로 처리한다.

> **참고:** Loop Closure의 효과를 시각적으로 보여주는 좋은 예시
> - [RTAB-Map Loop Closure 데모 영상](http://introlab.github.io/rtabmap/)

### 6.2 특징점 부족 환경 (Textureless Environments)

Visual SLAM은 이미지에서 추출한 특징점에 의존하므로, 다음과 같은 환경에서 성능이 크게 저하된다.

| 환경 | 문제 원인 |
|:---|:---|
| **무늬 없는 흰 벽** | 특징점이 거의 추출되지 않음 |
| **유리벽/거울** | 반사와 투과로 잘못된 특징점이 생성됨 |
| **반복 구조 (복도, 선반)** | 서로 다른 위치의 특징점이 유사해 잘못된 매칭 발생 (perceptual aliasing) |
| **넓은 빈 공간** | 특징점 밀도 부족으로 삼각측량 정확도 저하 |

**대응 전략:**
- RGB-D / LiDAR 센서 사용으로 깊이 정보를 직접 획득하여 의존도를 낮춤
- IMU 융합으로 특징점 부족 구간을 관성 데이터로 보완
- 직접법(Direct Method) SLAM 활용: 픽셀 밝기 기반으로 동작하여 특징점에 덜 의존 (예: LSD-SLAM, DSO)
  - LSD-SLAM: [논문 (Engel et al., 2014)](https://arxiv.org/abs/1407.6126)
  - DSO: [논문 (Engel et al., 2018)](https://arxiv.org/abs/1607.02565)

### 6.3 Dynamic Objects (움직이는 물체)

SLAM은 기본적으로 **정적 환경(static world assumption)** 을 가정한다. 그러나 실제 환경에는 사람, 차량, 문 등 움직이는 물체가 존재한다.

- **문제:** 동적 물체의 특징점을 맵에 포함시키면 맵이 오염되고, 해당 특징점을 트래킹에 사용하면 포즈 추정 정확도가 떨어진다.
- **대응 전략:**
  - **기하학적 접근:** RANSAC 등을 이용하여 이동하는 특징점을 아웃라이어로 제거
  - **딥러닝 기반:** Semantic Segmentation (사람, 차량 등을 의미적으로 인식하여 제외)
    - DynaSLAM: [논문 (Bescos et al., 2018)](https://arxiv.org/abs/1806.05620), [GitHub](https://github.com/BertaBesworking/DynaSLAM)
  - **RTAB-Map의 접근:** 맵 포인트의 관측 빈도와 일관성을 기반으로 불안정한 포인트를 자연스럽게 제거(culling)

### 6.4 Illumination Change (조명 변화)

카메라 기반 SLAM은 조명 변화에 민감하다. 같은 장소라도 조명이 달라지면 특징점의 디스크립터가 변하여 매칭 실패나 잘못된 루프 클로저가 발생할 수 있다.

| 조명 변화 유형 | 예시 |
|:---|:---|
| **급격한 밝기 변화** | 창문 근처 역광, 복도에서 밝은 로비로 진입 |
| **부분 조명** | 형광등 일부만 켜진 사무실, 그림자 |
| **시간에 따른 변화** | 낮/밤, 날씨 변화 (Multi-session의 경우) |
| **인공 조명 깜빡임** | 형광등 주파수에 의한 이미지 밝기 변동 |

**대응 전략:**
- **특징점 디스크립터의 조명 불변성:** ORB는 BRIEF 디스크립터의 이진 비교 기반이므로 어느 정도 조명 변화에 강건하다.
- **히스토그램 정규화:** 이미지 전처리로 밝기 분포를 균일화
- **Auto Exposure / White Balance:** 카메라 자체의 자동 노출 보정 활용
- **LiDAR 융합:** 깊이 정보는 조명에 영향을 받지 않으므로, RGB-D나 LiDAR 데이터를 함께 사용하면 조명 변화의 영향을 줄일 수 있다. 우리 프로젝트에서 iOS LiDAR를 사용하는 것이 이 측면에서도 유리하다.

> **참고:** 조명 변화에 강건한 Visual Place Recognition 서베이
> - S. Lowry et al., "Visual Place Recognition: A Survey," *IEEE T-RO*, 2016. [IEEE](https://ieeexplore.ieee.org/document/7339473)

---

## 참고 문헌 종합

### 서베이 및 개론

| 제목 | 저자 | 연도 | 링크 |
|:---|:---|:---:|:---|
| Past, Present, and Future of SLAM | Cadena et al. | 2016 | [arXiv](https://arxiv.org/abs/1606.05830) |
| Simultaneous localization and mapping: part I | Durrant-Whyte & Bailey | 2006 | [IEEE](https://ieeexplore.ieee.org/document/1638022) |
| Simultaneous localization and mapping (SLAM): part II | Bailey & Durrant-Whyte | 2006 | [IEEE](https://ieeexplore.ieee.org/document/1678144) |
| Visual Place Recognition: A Survey | Lowry et al. | 2016 | [IEEE](https://ieeexplore.ieee.org/document/7339473) |

### SLAM 구현체

| 구현체 | 핵심 논문 | 연도 | 링크 |
|:---|:---|:---:|:---|
| ORB-SLAM3 | Campos et al. | 2021 | [arXiv](https://arxiv.org/abs/2007.11898) |
| ORB-SLAM2 | Mur-Artal & Tardos | 2017 | [arXiv](https://arxiv.org/abs/1610.06475) |
| RTAB-Map (JFR) | Labbe & Michaud | 2019 | [Wiley](https://doi.org/10.1002/rob.21831) |
| RTAB-Map (T-RO) | Labbe & Michaud | 2013 | [IEEE](https://ieeexplore.ieee.org/document/6594910) |
| OpenVSLAM / Stella-SLAM | Sumikura et al. | 2019 | [arXiv](https://arxiv.org/abs/1910.01122) |
| VINS-Mono | Qin et al. | 2018 | [arXiv](https://arxiv.org/abs/1708.03852) |
| VINS-Fusion | Qin et al. | 2019 | [arXiv](https://arxiv.org/abs/1901.03638) |

### 관련 기술

| 기술 | 핵심 논문/도구 | 링크 |
|:---|:---|:---|
| ORB Feature | Rublee et al., 2011 | [IEEE](https://ieeexplore.ieee.org/document/6126544) |
| DBoW2 | Galvez-Lopez & Tardos, 2012 | [GitHub](https://github.com/dorian3d/DBoW2) |
| g2o | Kuemmerle et al., 2011 | [GitHub](https://github.com/RainerKuemmerle/g2o) |
| GTSAM | Dellaert et al. | [GitHub](https://github.com/borglab/gtsam) |
| Google Cartographer | Hess et al., 2016 | [GitHub](https://github.com/cartographer-project/cartographer) |
| LIO-SAM | Shan et al., 2020 | [arXiv](https://arxiv.org/abs/2007.00258) |
| LSD-SLAM | Engel et al., 2014 | [arXiv](https://arxiv.org/abs/1407.6126) |
| DSO | Engel et al., 2018 | [arXiv](https://arxiv.org/abs/1607.02565) |
| DynaSLAM | Bescos et al., 2018 | [arXiv](https://arxiv.org/abs/1806.05620) |

### GitHub 저장소

| 프로젝트 | URL |
|:---|:---|
| ORB-SLAM3 | [https://github.com/UZ-SLAMLab/ORB_SLAM3](https://github.com/UZ-SLAMLab/ORB_SLAM3) |
| RTAB-Map | [https://github.com/introlab/rtabmap](https://github.com/introlab/rtabmap) |
| Stella-SLAM | [https://github.com/stella-cv/stella_vslam](https://github.com/stella-cv/stella_vslam) |
| VINS-Mono | [https://github.com/HKUST-Aerial-Robotics/VINS-Mono](https://github.com/HKUST-Aerial-Robotics/VINS-Mono) |
| VINS-Fusion | [https://github.com/HKUST-Aerial-Robotics/VINS-Fusion](https://github.com/HKUST-Aerial-Robotics/VINS-Fusion) |
