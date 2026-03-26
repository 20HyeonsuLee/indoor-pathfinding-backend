# 02. VPS (Visual Positioning System) 원리와 기술

## 목차

1. [VPS란?](#1-vps란)
2. [VPS 파이프라인 (hloc 기반)](#2-vps-파이프라인-hloc-기반)
3. [hloc (Hierarchical Localization)](#3-hloc-hierarchical-localization)
4. [VPS 정확도와 한계](#4-vps-정확도와-한계)
5. [VPS vs 다른 실내 측위 기술 비교](#5-vps-vs-다른-실내-측위-기술-비교)
6. [관련 벤치마크/데이터셋](#6-관련-벤치마크데이터셋)

---

## 1. VPS란?

### 1.1 정의

VPS(Visual Positioning System)는 **카메라 이미지를 사용하여 6DoF(6 Degrees of Freedom) 위치를 추정**하는 기술이다. 6DoF란 3차원 공간에서의 위치(x, y, z)와 자세(roll, pitch, yaw)를 의미하며, 카메라가 찍은 사진 한 장(또는 연속 프레임)으로부터 "이 사진이 어디에서, 어떤 방향으로 찍혔는가"를 알아내는 것이 핵심이다.

기본 원리는 다음과 같다:
1. 사전에 환경의 3D 맵(포인트 클라우드)을 구축한다.
2. 쿼리 이미지에서 특징점을 추출한다.
3. 3D 맵의 특징점과 매칭하여 카메라의 위치와 방향을 역산한다.

> **참고:** VPS의 전체적인 개념과 산업 동향은 Google의 VPS 관련 발표에서 잘 설명되어 있다.
> - [Google ARCore Geospatial API](https://developers.google.com/ar/develop/geospatial)

### 1.2 기존 GPS가 실내에서 안 되는 이유

GPS(Global Positioning System)는 위성 신호를 수신하여 위치를 결정한다. 실내에서 사용할 수 없는 핵심 이유는 다음과 같다:

| 문제 | 설명 |
|------|------|
| **신호 차단** | GPS 위성 신호(L1: 1575.42MHz)는 건물 벽, 천장, 철근 콘크리트를 관통하지 못한다 ([GPS.gov - GPS Accuracy](https://www.gps.gov/systems/gps/performance/accuracy/)) |
| **다중 경로 효과(Multipath)** | 실내에서 신호가 벽면에 반사되어 거리 계산이 왜곡된다 |
| **정밀도 한계** | 실외에서도 민간용 GPS 정밀도는 약 3~5m 수준이며, 실내 내비게이션에는 부족하다 ([GPS.gov - Performance](https://www.gps.gov/systems/gps/performance/accuracy/)) |
| **수직 정보 부재** | GPS는 고도 정밀도가 낮아 건물 내 층 구분이 어렵다 |

### 1.3 VPS의 장점

VPS는 이러한 GPS의 한계를 극복할 수 있는 대안이다:

- **추가 인프라 불필요:** Wi-Fi AP, BLE 비콘 등 별도 장비를 설치할 필요 없이, 사전에 촬영한 이미지로 3D 맵만 구축하면 된다.
- **카메라만 있으면 작동:** 스마트폰 카메라 하나로 충분하며, 별도의 센서 하드웨어가 필요 없다.
- **높은 정밀도:** 센티미터(cm) 수준의 위치 추정이 가능하다. InLoc 벤치마크에서 0.25m / 10도 이내의 정확도가 보고되었다 ([Taira et al., 2018](https://arxiv.org/abs/1803.10368)).
- **6DoF 추정:** 위치뿐 아니라 방향(orientation)까지 알 수 있어 AR 등과 연계하기 좋다.
- **유지보수 용이:** 환경이 변하면 새로운 이미지를 촬영하여 맵을 업데이트하면 된다.

---

## 2. VPS 파이프라인 (hloc 기반)

VPS의 전체 과정은 크게 4단계로 나뉜다. hloc 프레임워크에서는 이를 체계적으로 통합하고 있다 ([Sarlin et al., 2019](https://arxiv.org/abs/1812.03506)).

```
쿼리 이미지
    │
    ▼
┌─────────────────┐
│ Feature          │  ← (a) 이미지에서 특징점 추출
│ Extraction       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Image Retrieval  │  ← (c) 전체 맵에서 유사 이미지 후보 검색
│ (후보 검색)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Feature          │  ← (b) 쿼리와 후보 이미지 간 특징점 매칭
│ Matching         │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Pose             │  ← (d) 매칭 결과로 6DoF 위치 추정
│ Estimation       │
└─────────────────┘
```

### 2a. Feature Extraction (특징점 추출)

이미지에서 **고유하고 반복 가능한 지점(keypoint)과 그 주변의 기술자(descriptor)**를 추출하는 단계이다. 좋은 특징점은 조명이나 시점이 바뀌어도 동일한 위치에서 검출되어야 한다.

#### 전통 방식

| 알고리즘 | 핵심 아이디어 | 특징 |
|----------|--------------|------|
| **SIFT** (Scale-Invariant Feature Transform) | DoG(Difference of Gaussians)로 스케일 공간에서 극값 검출 | 스케일/회전 불변, 느리지만 정확 ([Lowe, 2004](https://www.cs.ubc.ca/~lowe/papers/ijcv04.pdf)) |
| **SURF** (Speeded-Up Robust Features) | Hessian 행렬 기반 검출, 적분 이미지로 가속 | SIFT보다 빠르고 유사한 성능 ([Bay et al., 2008](https://doi.org/10.1016/j.cviu.2007.09.014)) |
| **ORB** (Oriented FAST and Rotated BRIEF) | FAST 코너 검출 + BRIEF 기술자에 방향 정보 추가 | 매우 빠르고 특허 무료, 정확도는 SIFT보다 낮음 ([Rublee et al., 2011](http://www.willowgarage.com/sites/default/files/orb_final.pdf)) |

#### 학습 기반: SuperPoint

**SuperPoint** (DeTone et al., 2018)는 딥러닝 기반 특징점 검출 및 기술의 선구적 연구이다.

- **논문:** [SuperPoint: Self-Supervised Interest Point Detection and Description](https://arxiv.org/abs/1712.07629)

**핵심 특징:**

1. **Self-Supervised 학습:** 합성 도형(Synthetic Shapes) 데이터로 사전 학습한 MagicPoint 모델을 기반으로, Homographic Adaptation이라는 자기지도 학습 기법을 적용한다. 다양한 호모그래피 변환을 적용한 이미지들에서 코너를 검출하고 집계하여, 라벨 없는 실제 이미지에서도 반복적이고 정확한 키포인트를 학습한다.

2. **단일 네트워크 구조:** 하나의 인코더(VGG 스타일)로 공유 특징을 추출한 뒤, 두 개의 디코더 헤드로 분기한다:
   - **Interest Point Decoder:** 키포인트 위치 검출 (65채널 출력, 8x8 셀 내 64개 위치 + dustbin)
   - **Descriptor Decoder:** 256차원 고정 길이 기술자 출력

3. **조명 변화에 강건:** 자기지도 학습 과정에서 다양한 조명 조건을 경험하므로, 낮/밤, 그림자 등 조명 변화에도 안정적으로 동일 지점을 검출한다.

4. **시점 변화에 강건:** Homographic Adaptation 과정에서 다양한 시점 변환을 경험하므로, 카메라 각도가 달라져도 키포인트를 안정적으로 찾는다.

**성능 비교 (HPatches 벤치마크):**

| 방법 | Repeatability | Homography Estimation AUC |
|------|:------------:|:------------------------:|
| ORB | 0.56 | 0.28 |
| SIFT | 0.61 | 0.51 |
| SuperPoint | **0.66** | **0.55** |

> 수치 출처: [DeTone et al., 2018, Table 1, Table 3](https://arxiv.org/abs/1712.07629)

### 2b. Feature Matching (특징점 매칭)

추출된 특징점들 사이의 대응 관계를 찾는 단계이다. 쿼리 이미지의 키포인트가 데이터베이스 이미지의 어떤 키포인트와 같은 3D 지점을 관측하고 있는지를 결정한다.

#### 전통 방식

| 방법 | 설명 |
|------|------|
| **Nearest Neighbor (NN)** | 기술자 공간에서 가장 가까운(유클리드/해밍 거리) 쌍을 매칭. Lowe's ratio test(최근접/차근접 비율 < 0.7~0.8)로 모호한 매칭 제거 ([Lowe, 2004](https://www.cs.ubc.ca/~lowe/papers/ijcv04.pdf)) |
| **FLANN** (Fast Library for Approximate Nearest Neighbors) | KD-Tree, K-Means Tree 등을 활용한 근사 최근접 이웃 탐색. 대규모 데이터에서 NN보다 훨씬 빠름 ([Muja & Lowe, 2009](https://doi.org/10.1109/TPAMI.2014.2321376)) |

#### 학습 기반: SuperGlue

**SuperGlue** (Sarlin et al., 2020)는 GNN(Graph Neural Network)과 Attention 메커니즘을 활용한 학습 기반 매칭 네트워크이다.

- **논문:** [SuperGlue: Learning Feature Matching with Graph Neural Networks](https://arxiv.org/abs/1911.11763)

**핵심 아이디어:**

1. **Graph Neural Network 기반 매칭:**
   - 각 이미지의 키포인트를 그래프의 노드로 취급한다.
   - **Self-attention:** 같은 이미지 내 키포인트들 사이의 관계를 학습한다 (예: "이 키포인트는 문 옆에 있고, 저 키포인트는 창문 옆에 있다"는 공간적 맥락).
   - **Cross-attention:** 두 이미지 간 키포인트들의 관계를 학습한다 (예: "이미지 A의 이 코너가 이미지 B의 저 코너와 같은 곳이다").

2. **Attentional GNN 구조:**
   ```
   이미지 A 키포인트 ──┐
                       ├─→ Self-Attention ↔ Cross-Attention (L번 반복) ─→ 매칭 행렬
   이미지 B 키포인트 ──┘
   ```
   - L개의 Attentional GNN 레이어를 교대로 적용하여 키포인트의 표현을 점진적으로 정제한다.

3. **Optimal Transport로 매칭 결정:**
   - 최종적으로 Sinkhorn 알고리즘([Sinkhorn, 1967](https://doi.org/10.1214/aoms/1177703591))을 적용하여 소프트 할당 행렬을 계산한다.
   - **Dustbin** 개념을 도입하여, 매칭 상대가 없는 키포인트(occluded, 시야 밖)를 명시적으로 "매칭 없음"으로 분류한다.
   - 이를 통해 outlier가 자연스럽게 제거된다.

4. **위치 인코딩(Positional Encoding):**
   - 키포인트의 2D 좌표, 검출 신뢰도, 기술자를 MLP로 인코딩하여 초기 노드 특징으로 사용한다.
   - 공간적 위치 정보가 매칭에 반영되어, 기술자만으로는 구분이 어려운 유사 특징점을 공간적 맥락으로 구분할 수 있다.

**성능 비교 (ScanNet 실내 데이터셋, Pose Estimation AUC):**

| 방법 | AUC@5deg | AUC@10deg | AUC@20deg |
|------|:--------:|:---------:|:---------:|
| NN + mutual | 9.43 | 21.55 | 36.09 |
| NN + OANet | 11.76 | 26.90 | 43.85 |
| SuperGlue | **16.16** | **33.81** | **51.84** |

> 수치 출처: [Sarlin et al., 2020, Table 2](https://arxiv.org/abs/1911.11763)

### 2c. Image Retrieval (후보 검색)

#### 왜 필요한가

전체 3D 맵에는 수천~수만 장의 이미지가 있을 수 있다. 쿼리 이미지를 이들 **모든** 이미지와 매칭하면:

- **시간:** SuperGlue 매칭 1쌍에 약 50~100ms라고 할 때, 10,000장이면 500~1,000초가 소요된다.
- **정확도 저하:** 무관한 이미지와의 잘못된 매칭(false positive)이 증가한다.

따라서 **먼저 쿼리와 시각적으로 유사한 후보 이미지 K개(보통 20~50개)를 빠르게 검색**한 뒤, 이 후보들에 대해서만 정밀 매칭을 수행한다.

#### NetVLAD

**NetVLAD** (Arandjelovic et al., 2016)는 CNN 기반의 장소 인식(place recognition) 네트워크이다.

- **논문:** [NetVLAD: CNN architecture for weakly supervised place recognition](https://arxiv.org/abs/1511.07247)

**핵심 아이디어:**

1. **VLAD(Vector of Locally Aggregated Descriptors)의 미분 가능 버전:**
   - 전통적인 VLAD([Jegou et al., 2010](https://doi.org/10.1109/CVPR.2010.5540039))는 로컬 기술자들을 K개 클러스터 중심에 대한 잔차(residual)로 집계하여 하나의 글로벌 기술자를 만든다.
   - NetVLAD는 이 과정을 **미분 가능하게(differentiable)** 만들어 end-to-end 학습을 가능하게 했다.

2. **구조:**
   ```
   입력 이미지
       │
       ▼
   CNN Backbone (VGG-16 등)
       │
       ▼
   W x H x D 특징 맵
       │
       ▼
   NetVLAD Pooling Layer
       │
       ▼
   K x D 차원 글로벌 기술자 (PCA로 4096차원으로 축소)
   ```

3. **Weakly Supervised 학습:**
   - Google Street View의 GPS 태그를 약한 감독 신호(weak supervision)로 사용한다.
   - Triplet ranking loss로 학습: 같은 장소의 이미지는 가깝게, 다른 장소는 멀게.

4. **검색 속도:**
   - 글로벌 기술자 간 코사인 유사도 계산은 O(D) 연산이므로, 10,000장에서도 수 밀리초 이내에 상위 K개 후보를 검색할 수 있다.

### 2d. Pose Estimation (위치 추정)

특징점 매칭 결과를 바탕으로 카메라의 6DoF 포즈(위치 + 방향)를 계산하는 최종 단계이다.

#### PnP (Perspective-n-Point) 알고리즘

PnP는 **3D 공간 좌표와 2D 이미지 좌표 간의 대응 관계로부터 카메라 포즈를 복원**하는 고전적 알고리즘이다 ([Lepetit et al., 2009](https://doi.org/10.1007/s11263-008-0152-6)).

- **입력:** n개의 3D-2D 대응점 쌍 (3D 점은 SfM으로 구축한 맵에서, 2D 점은 쿼리 이미지의 키포인트)
- **출력:** 카메라의 회전 행렬 R과 이동 벡터 t (= 6DoF 포즈)
- **최소 필요 대응점:** 4개 (P3P의 경우 3개 + 검증용 1개)

#### RANSAC으로 Outlier 제거

실제 매칭 결과에는 잘못된 대응(outlier)이 섞여 있으므로, **RANSAC(Random Sample Consensus)**을 적용한다 ([Fischler & Bolles, 1981](https://doi.org/10.1145/358669.358692)).

```
RANSAC 과정:
1. 전체 매칭 중 최소 개수(4개)를 무작위 선택
2. 선택한 대응점으로 PnP를 풀어 포즈 가설을 생성
3. 나머지 대응점들이 이 포즈와 얼마나 일치하는지 평가 (reprojection error)
4. 일치하는 점(inlier)이 가장 많은 가설을 최종 포즈로 채택
5. 최종 inlier들로 다시 PnP를 풀어 포즈를 정제
```

#### 결과: 6DoF Camera Pose

최종 출력은 다음 6개의 값이다:

| 자유도 | 의미 | 단위 |
|--------|------|------|
| x | 좌우 위치 | 미터(m) |
| y | 상하 위치 (높이) | 미터(m) |
| z | 앞뒤 위치 | 미터(m) |
| roll | 좌우 기울기 | 도(deg) |
| pitch | 상하 기울기 | 도(deg) |
| yaw | 좌우 회전 (방위각) | 도(deg) |

---

## 3. hloc (Hierarchical Localization)

### 3.1 개요

**hloc**은 위에서 설명한 VPS 파이프라인의 **전체 과정을 하나의 프레임워크로 통합**한 오픈소스 도구이다. "Coarse-to-Fine" 전략으로 효율적이고 정확한 위치 추정을 수행한다.

- **논문:** [From Coarse to Fine: Robust Hierarchical Localization at Large Scale](https://arxiv.org/abs/1812.03506) (Sarlin et al., CVPR 2019)
- **GitHub:** [https://github.com/cvg/Hierarchical-Localization](https://github.com/cvg/Hierarchical-Localization)

### 3.2 Hierarchical (계층적) 접근

hloc이 "Hierarchical"인 이유는 **두 단계로 나누어 위치를 추정**하기 때문이다:

1. **Coarse Stage (대략적 위치):**
   - Image Retrieval(NetVLAD 등)로 유사 이미지 후보를 검색한다.
   - 전체 맵에서 "대략 어디쯤인가"를 빠르게 좁힌다.

2. **Fine Stage (정밀 위치):**
   - 후보 이미지들과 정밀 특징점 매칭(SuperPoint + SuperGlue)을 수행한다.
   - PnP + RANSAC으로 정확한 6DoF 포즈를 계산한다.

### 3.3 3D 맵 구축: Structure-from-Motion (SfM)

VPS가 작동하려면 먼저 환경의 **3D 포인트 클라우드 맵**이 필요하다. 이를 구축하는 기술이 SfM이다.

```
SfM 파이프라인:
1. 환경을 다양한 각도에서 촬영 (수백~수천 장)
2. 각 이미지에서 특징점 추출 (SuperPoint)
3. 이미지 쌍 간 특징점 매칭 (SuperGlue)
4. 매칭된 특징점들의 3D 좌표를 삼각측량(Triangulation)으로 복원
5. Bundle Adjustment로 카메라 포즈와 3D 점을 동시에 최적화
```

hloc은 SfM 엔진으로 **COLMAP**([Schonberger & Frahm, 2016](https://arxiv.org/abs/1608.05539))을 사용한다.

- **COLMAP GitHub:** [https://github.com/colmap/colmap](https://github.com/colmap/colmap)

### 3.4 hloc의 전체 워크플로우

```
[오프라인: 맵 구축]
다수의 환경 이미지
       │
       ├─→ SuperPoint로 특징점 추출
       ├─→ NetVLAD로 글로벌 기술자 추출
       ├─→ 유사 이미지 쌍 선정 + SuperGlue 매칭
       └─→ COLMAP SfM으로 3D 맵 구축
              │
              ▼
       3D 맵 + 이미지 DB + 특징점 DB

[온라인: 위치 추정]
쿼리 이미지
       │
       ├─→ SuperPoint로 특징점 추출
       ├─→ NetVLAD로 글로벌 기술자 추출
       │         │
       │         ▼
       │    DB에서 상위 K개 유사 이미지 검색
       │         │
       │         ▼
       ├─→ SuperGlue로 쿼리 ↔ 후보 이미지 매칭
       │         │
       │         ▼
       │    2D-3D 대응점 확보 (후보 이미지의 키포인트 → 3D 맵 좌표)
       │         │
       │         ▼
       └─→ PnP + RANSAC으로 6DoF 포즈 추정
```

### 3.5 hloc의 성능

Aachen Day-Night 벤치마크에서의 결과 (야간 쿼리, 주간 맵):

| 방법 | 0.25m, 2deg | 0.5m, 5deg | 5m, 10deg |
|------|:-----------:|:----------:|:---------:|
| Active Search (전통) | 16.3 | 31.6 | 64.3 |
| hloc (SuperPoint + SuperGlue) | **39.8** | **55.1** | **77.6** |

> 수치 출처: [Sarlin et al., 2020, Table 3](https://arxiv.org/abs/1911.11763) 및 [hloc GitHub](https://github.com/cvg/Hierarchical-Localization)

---

## 4. VPS 정확도와 한계

### 4a. 조명 변화 (낮 vs 밤)

**문제:** 같은 장소라도 조명이 달라지면 이미지 외형이 크게 변하여 특징점 매칭이 실패할 수 있다.

**학습 기반 vs 전통 방식:**

| 조건 | ORB (전통) | SuperPoint (학습 기반) |
|------|:----------:|:---------------------:|
| 조명 일정 | 양호 | 양호 |
| 조명 변화 (밝기) | 매칭률 급감 | 상대적으로 안정 |
| 극단적 변화 (낮→밤) | 거의 불가 | 성능 저하 있으나 작동 |

SuperPoint는 학습 과정에서 다양한 조명 조건의 데이터를 경험하므로, 전통적인 gradient 기반 특징점(ORB, SIFT)보다 조명 변화에 **훨씬 강건하다** ([DeTone et al., 2018](https://arxiv.org/abs/1712.07629)).

그러나 **극단적인 조명 변화**(완전한 암흑, 역광 실루엣)에서는 여전히 성능이 저하된다.

**실내 환경의 이점:** 실내는 인공조명으로 조도가 비교적 일정하게 유지되므로, 실외(낮/밤 변화)보다 조명 문제가 덜하다. 다만 조명을 끄거나 창문에서 들어오는 자연광의 변화는 고려해야 한다.

### 4b. 사람이 많을 때 (Dynamic Objects)

**문제:** 이동하는 사람, 물체가 배경을 가리면 매칭에 사용할 수 있는 고정 구조물의 특징점이 줄어든다.

**자연 필터링 효과:**
- VPS의 매칭 과정에서 움직이는 객체의 특징점은 **3D 맵과 일관된 대응을 형성하지 못하므로** RANSAC 과정에서 outlier로 자동 제거된다.
- 결과적으로 벽, 기둥, 천장 등 **고정 구조물의 특징점 위주로 매칭**이 이루어진다.

**한계:**
- 벽이나 주요 구조물이 사람에 의해 **완전히 가려지면** 사용 가능한 특징점이 절대적으로 부족해져 매칭 자체가 어려워진다.
- 인파가 매우 밀집된 환경(예: 콘서트장, 출퇴근 시간 지하철역)에서는 성능 저하가 불가피하다.

**대응 방안:**
- **Semantic Segmentation으로 사람 영역 마스킹:** 사전에 학습된 세그멘테이션 모델(예: DeepLabV3+ ([Chen et al., 2018](https://arxiv.org/abs/1802.02611)))로 이미지에서 사람 영역을 검출하고, 해당 영역의 특징점을 매칭에서 제외한다.
- **상부 구조물 활용:** 천장, 높은 벽면 등 사람에 의해 가려지기 어려운 영역의 특징점에 가중치를 부여한다.

### 4c. 반복 구조 (Perceptual Aliasing)

**문제:** 동일하게 생긴 복도, 반복되는 교실 문, 규격화된 사무실 등에서 시각적으로 구분이 안 되는 장소가 다수 존재한다. 이 경우 잘못된 장소에 매칭될 수 있다 (Perceptual Aliasing).

**예시:**
```
실제 위치: 3층 복도 A
잘못된 매칭: 4층 복도 A (동일한 구조와 외형)
```

**해결 방안:**

1. **시간적 연속성 (Temporal Consistency):**
   - 연속 프레임 간 위치 변화의 물리적 합리성을 검증한다.
   - 이전 프레임에서 3층에 있었는데 갑자기 4층으로 점프하면 이상치로 판단하여 제거한다.
   - Visual Odometry나 IMU와 결합하면 더 효과적이다 ([Sarlin et al., 2022](https://arxiv.org/abs/2205.15007)).

2. **이전 위치 이력 활용:**
   - 이전 위치 추정 결과를 prior로 사용하여 검색 범위를 제한한다.
   - 예: "직전에 3층에 있었으므로 3층 이미지만 후보로 검색"

3. **층 정보 제한:**
   - 기압계, 계단/엘리베이터 감지 등으로 현재 층을 파악하고, 해당 층의 이미지만 검색 후보로 제한한다.

### 4d. 텍스처리스 환경 (Textureless Surfaces)

**문제:** 무늬 없는 흰 벽, 유리, 거울, 금속 표면 등에서는 특징점이 거의 검출되지 않는다.

**원인:**
- 특징점 검출기는 밝기의 급격한 변화(gradient)가 있는 지점을 찾는데, 균일한 표면에는 이러한 변화가 없다.
- 유리/거울은 반사로 인해 시점마다 다른 이미지를 보여주므로 일관된 특징점을 형성하지 못한다.

**대응 방안:**

1. **Line Feature (직선 특징) 활용:**
   - 무늬 없는 벽에도 벽과 바닥/천장의 **경계선**은 존재한다.
   - Line segment 검출기 + 매칭기를 특징점과 함께 사용할 수 있다.
   - 관련 연구: [Line Segment Detection using Transformers without Edges (LETR)](https://arxiv.org/abs/2101.01909) (Xu et al., 2021)
   - hloc에서도 line feature 통합을 지원한다: [GlueStick](https://arxiv.org/abs/2304.02008) (Pautrat et al., 2023)

2. **다중 센서 융합:**
   - 텍스처리스 환경에서는 VPS 단독으로 한계가 있으므로, IMU, LiDAR 등 보조 센서와 융합하여 보완한다.

3. **환경 개선:**
   - 실내 환경을 설계/개조할 수 있다면, 벽에 포스터, 안내판 등 시각적 랜드마크를 배치하여 특징점을 인위적으로 증가시킬 수 있다.

---

## 5. VPS vs 다른 실내 측위 기술 비교

> 이 내용은 **03_indoor_positioning.md**에서 상세히 다루므로 여기서는 간략하게만 비교한다.

| 기준 | VPS | Wi-Fi Fingerprinting | BLE Beacon | UWB |
|------|-----|---------------------|------------|-----|
| **정밀도** | cm급 ([Taira et al., 2018](https://arxiv.org/abs/1803.10368)) | 1~5m ([Yang et al., 2015](https://doi.org/10.1109/COMST.2015.2423443)) | 1~3m ([Zafari et al., 2019](https://doi.org/10.1109/COMST.2019.2911558)) | 10~30cm ([Zafari et al., 2019](https://doi.org/10.1109/COMST.2019.2911558)) |
| **추가 인프라** | 불필요 (카메라만) | 기존 AP 활용 가능 | 비콘 설치 필요 | 앵커 설치 필요 |
| **6DoF** | 가능 (위치 + 방향) | 불가 (위치만) | 불가 (위치만) | 제한적 |
| **실시간성** | 100ms~1s | 수초 (스캔 시간) | 수초 | 수 ms |
| **환경 의존성** | 시각적 특징 필요 | 전파 환경 변화에 민감 | 전파 환경 변화에 민감 | 차폐에 민감 |
| **유지보수** | 맵 재구축 필요 | 핑거프린트 재수집 | 비콘 배터리 교체 | 앵커 유지보수 |
| **비용** | 낮음 (소프트웨어 기반) | 중간 | 높음 (비콘 비용) | 매우 높음 |

---

## 6. 관련 벤치마크/데이터셋

VPS 알고리즘의 성능을 평가하기 위한 주요 벤치마크와 데이터셋은 다음과 같다.

### 6.1 InLoc: Indoor Visual Localization

- **논문:** [InLoc: Indoor Visual Localization with Dense Matching and View Synthesis](https://arxiv.org/abs/1803.10368) (Taira et al., CVPR 2018)
- **특징:**
  - 실제 대학 건물 내부에서 수집한 실내 데이터셋
  - RGBD 파노라마 이미지로 구축한 3D 맵 + 스마트폰 쿼리 이미지
  - Dense matching과 view synthesis를 활용한 검증 파이프라인 제안
  - 실내 VPS 벤치마크의 사실상 표준(de facto standard)
- **프로젝트 페이지:** [http://www.okhaz.com/inloc](http://www.okhaz.com/inloc)

### 6.2 Aachen Day-Night Benchmark

- **관련 논문:** [From Coarse to Fine: Robust Hierarchical Localization at Large Scale](https://arxiv.org/abs/1812.03506) (Sarlin et al., CVPR 2019)
- **특징:**
  - 독일 아헨(Aachen) 도심에서 수집한 실외 데이터셋
  - 핵심: **주간에 구축한 맵으로 야간 쿼리의 위치를 추정**하는 도전적 과제
  - 조명 변화 강건성을 평가하는 데 적합
  - 공식 벤치마크: [https://www.visuallocalization.net/](https://www.visuallocalization.net/)

### 6.3 7-Scenes Dataset

- **관련 논문:** [Scene Coordinate Regression Forests for Camera Relocalization in RGB-D Images](https://www.microsoft.com/en-us/research/publication/scene-coordinate-regression-forests-for-camera-relocalization-in-rgb-d-images/) (Shotton et al., CVPR 2013)
- **특징:**
  - Microsoft Research에서 공개한 실내 RGB-D 데이터셋
  - 7개의 소규모 실내 장면(Chess, Fire, Heads, Office, Pumpkin, RedKitchen, Stairs)
  - Kinect로 촬영, 각 장면당 수천 프레임
  - 소규모 환경에서의 카메라 재위치화(relocalization) 평가용
  - **다운로드:** [https://www.microsoft.com/en-us/research/project/rgb-d-dataset-7-scenes/](https://www.microsoft.com/en-us/research/project/rgb-d-dataset-7-scenes/)

### 6.4 12-Scenes Dataset

- **관련 논문:** [Learning Less is More - 6D Camera Localization via 3D Surface Regression](https://arxiv.org/abs/1711.10228) (Valentin et al., 2016)
- **특징:**
  - 7-Scenes의 확장판으로, 12개의 실내 장면으로 구성
  - 더 넓은 범위의 실내 환경을 포함
  - 다양한 텍스처 수준과 구조를 가진 장면 포함

### 6.5 기타 주요 데이터셋/벤치마크

| 데이터셋 | 환경 | 특징 | 링크 |
|----------|------|------|------|
| **Cambridge Landmarks** | 실외 (캠브리지 대학) | PoseNet 논문에서 제안, 6개 장면 | [Kendall et al., 2015](https://arxiv.org/abs/1505.07427) |
| **RobotCar Seasons** | 실외 (옥스퍼드) | 1년간 같은 경로를 반복 주행, 계절/날씨 변화 | [Sattler et al., 2018](https://arxiv.org/abs/1707.09092) |
| **ETH3D** | 실내/실외 | Multi-view stereo 벤치마크, 고정밀 ground truth | [https://www.eth3d.net/](https://www.eth3d.net/) |
| **Visual Localization Benchmark** | 통합 | 다수 데이터셋의 통합 리더보드 | [https://www.visuallocalization.net/](https://www.visuallocalization.net/) |

---

## 참고 문헌 목록

| # | 논문/리소스 | 링크 |
|---|-----------|------|
| 1 | SuperPoint: Self-Supervised Interest Point Detection and Description (DeTone et al., 2018) | [arXiv:1712.07629](https://arxiv.org/abs/1712.07629) |
| 2 | SuperGlue: Learning Feature Matching with Graph Neural Networks (Sarlin et al., 2020) | [arXiv:1911.11763](https://arxiv.org/abs/1911.11763) |
| 3 | NetVLAD: CNN architecture for weakly supervised place recognition (Arandjelovic et al., 2016) | [arXiv:1511.07247](https://arxiv.org/abs/1511.07247) |
| 4 | From Coarse to Fine: Robust Hierarchical Localization at Large Scale (Sarlin et al., 2019) | [arXiv:1812.03506](https://arxiv.org/abs/1812.03506) |
| 5 | InLoc: Indoor Visual Localization with Dense Matching and View Synthesis (Taira et al., 2018) | [arXiv:1803.10368](https://arxiv.org/abs/1803.10368) |
| 6 | COLMAP: Structure-from-Motion Revisited (Schonberger & Frahm, 2016) | [arXiv:1608.05539](https://arxiv.org/abs/1608.05539) |
| 7 | hloc GitHub | [github.com/cvg/Hierarchical-Localization](https://github.com/cvg/Hierarchical-Localization) |
| 8 | COLMAP GitHub | [github.com/colmap/colmap](https://github.com/colmap/colmap) |
| 9 | SIFT: Distinctive Image Features from Scale-Invariant Keypoints (Lowe, 2004) | [cs.ubc.ca/~lowe/papers/ijcv04.pdf](https://www.cs.ubc.ca/~lowe/papers/ijcv04.pdf) |
| 10 | FLANN: Fast Approximate Nearest Neighbors (Muja & Lowe, 2009) | [DOI:10.1109/TPAMI.2014.2321376](https://doi.org/10.1109/TPAMI.2014.2321376) |
| 11 | EPnP: An Accurate O(n) Solution to the PnP Problem (Lepetit et al., 2009) | [DOI:10.1007/s11263-008-0152-6](https://doi.org/10.1007/s11263-008-0152-6) |
| 12 | RANSAC (Fischler & Bolles, 1981) | [DOI:10.1145/358669.358692](https://doi.org/10.1145/358669.358692) |
| 13 | DeepLabV3+ (Chen et al., 2018) | [arXiv:1802.02611](https://arxiv.org/abs/1802.02611) |
| 14 | GlueStick: Joint Deep Matching of Points and Lines (Pautrat et al., 2023) | [arXiv:2304.02008](https://arxiv.org/abs/2304.02008) |
| 15 | Back to the Feature: Learning Robust Camera Localization from Pixels to Pose (Sarlin et al., 2022) | [arXiv:2205.15007](https://arxiv.org/abs/2205.15007) |
| 16 | Visual Localization Benchmark | [visuallocalization.net](https://www.visuallocalization.net/) |
| 17 | GPS Accuracy (GPS.gov) | [gps.gov/systems/gps/performance/accuracy](https://www.gps.gov/systems/gps/performance/accuracy/) |
| 18 | Indoor Positioning Survey (Yang et al., 2015) | [DOI:10.1109/COMST.2015.2423443](https://doi.org/10.1109/COMST.2015.2423443) |
| 19 | UWB/BLE Survey (Zafari et al., 2019) | [DOI:10.1109/COMST.2019.2911558](https://doi.org/10.1109/COMST.2019.2911558) |
| 20 | 7-Scenes Dataset (Shotton et al., 2013) | [Microsoft Research](https://www.microsoft.com/en-us/research/project/rgb-d-dataset-7-scenes/) |
| 21 | 12-Scenes / Learning Less is More (Valentin et al., 2016) | [arXiv:1711.10228](https://arxiv.org/abs/1711.10228) |
| 22 | PoseNet (Kendall et al., 2015) | [arXiv:1505.07427](https://arxiv.org/abs/1505.07427) |
| 23 | RobotCar Seasons (Sattler et al., 2018) | [arXiv:1707.09092](https://arxiv.org/abs/1707.09092) |
| 24 | LETR: Line Segment Detection Using Transformers (Xu et al., 2021) | [arXiv:2101.01909](https://arxiv.org/abs/2101.01909) |
| 25 | Google ARCore Geospatial API | [developers.google.com/ar/develop/geospatial](https://developers.google.com/ar/develop/geospatial) |
