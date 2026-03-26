# 실내 측위 기술 비교 (비콘, Wi-Fi, UWB, VPS)

> 졸업 프로젝트 디펜스를 위한 학습 가이드
> 최종 수정: 2026-03-26

---

## 목차

1. [실내 측위 기술 개요](#1-실내-측위-기술-개요)
2. [BLE 비콘 (Bluetooth Low Energy)](#2-ble-비콘-bluetooth-low-energy)
3. [Wi-Fi 핑거프린팅](#3-wi-fi-핑거프린팅)
4. [UWB (Ultra-Wideband)](#4-uwb-ultra-wideband)
5. [VPS (Visual Positioning System)](#5-vps-visual-positioning-system)
6. [기술 비교 요약표](#6-기술-비교-요약표)
7. [우리 프로젝트에서 VPS를 선택한 이유](#7-우리-프로젝트에서-vps를-선택한-이유)
8. [상용 서비스 사례](#8-상용-서비스-사례)

---

## 1. 실내 측위 기술 개요

### 1.1 왜 GPS는 실내에서 작동하지 않는가

GPS(Global Positioning System)는 약 20,200km 상공의 위성으로부터 L1(1575.42MHz) 신호를 수신하여 삼변측량(Trilateration)으로 위치를 계산한다. 실내에서 GPS가 실패하는 근본적 원인은 다음 두 가지이다.

#### (1) 신호 감쇠 (Signal Attenuation)

GPS 위성 신호의 수신 전력은 지표면 기준 약 -130dBm으로 매우 약하다. 건물의 벽, 천장, 철골 구조물 등을 통과하면서 **20~30dB 이상의 추가 감쇠**가 발생하여 수신기가 신호를 포착하지 못한다. 특히 철근 콘크리트 구조의 건물 내부에서는 감쇠가 더욱 심각하다.

- 콘크리트 벽: 약 10~15dB 감쇠
- 철근 콘크리트: 약 20~30dB 감쇠
- Low-E 유리(저방사 유리): 약 25~40dB 감쇠

> 참고: Kaplan, E. D., & Hegarty, C. J. (2017). *Understanding GPS/GNSS: Principles and Applications* (3rd ed.). Artech House.
> https://us.artechhouse.com/Understanding-GPS-GNSS-Principles-and-Applications-Third-Edition-P1969.aspx

#### (2) 다중 경로 문제 (Multipath)

실내 환경에서는 GPS 신호가 벽, 바닥, 천장 등에서 반사되어 수신기에 **여러 경로로 도달**한다. 직접 경로(Line of Sight)의 신호와 반사 경로의 신호가 간섭하면서, 의사거리(Pseudorange) 측정에 수십 미터에서 수백 미터의 오차가 발생한다. 실외에서도 multipath는 존재하지만, 실내에서는 반사면이 가까이 밀집되어 있어 오차가 극대화된다.

> 참고: Brena, R. F., et al. (2017). "Evolution of Indoor Positioning Technologies: A Survey." *Journal of Sensors*, vol. 2017.
> https://doi.org/10.1155/2017/2630413

### 1.2 실내 측위의 핵심 과제

실내 측위 시스템(Indoor Positioning System, IPS)을 설계할 때 반드시 고려해야 할 세 가지 핵심 과제가 있다.

| 과제 | 설명 |
|------|------|
| **정확도 (Accuracy)** | 응용 분야에 따라 요구되는 정확도가 다르다. 단순 층 구분은 3~5m면 충분하지만, AR 오버레이나 로봇 네비게이션은 수십 cm 이하를 요구한다. |
| **인프라 비용 (Infrastructure Cost)** | 비콘이나 UWB 앵커 설치, Wi-Fi AP 추가 배치 등 초기 설치 및 유지보수에 소요되는 비용. 기존 인프라를 얼마나 활용할 수 있는가가 경제성을 좌우한다. |
| **확장성 (Scalability)** | 건물 수, 층 수, 면적이 늘어날 때 시스템을 확장하기 위한 추가 비용과 노력. 사전 데이터 수집(핑거프린트, 3D 스캔 등)의 범위가 확장성에 직접 영향을 미친다. |

추가적으로 **에너지 효율**(모바일 기기의 배터리 소모), **프라이버시**(카메라 기반 시스템의 개인정보 이슈), **실시간성**(위치 갱신 지연 시간) 등도 실제 배포 시 중요한 고려사항이다.

> 참고: Zafari, F., Gkelias, A., & Leung, K. K. (2019). "A Survey on Indoor Localization Systems and Technologies." *IEEE Communications Surveys & Tutorials*, 21(3), 2568-2599.
> https://ieeexplore.ieee.org/document/8713754

---

## 2. BLE 비콘 (Bluetooth Low Energy)

### 2.1 원리

BLE 비콘은 **Bluetooth 4.0+** 사양의 저전력 모드를 이용하여 주기적으로 광고(Advertising) 패킷을 브로드캐스트한다. 수신 기기(스마트폰 등)는 비콘에서 수신한 **RSSI(Received Signal Strength Indicator)** 값을 이용하여 위치를 추정한다.

위치 추정에는 두 가지 주요 방식이 사용된다:

#### (1) 삼변측량 (Trilateration)

RSSI 값을 **경로손실 모델(Path Loss Model)**로 변환하여 비콘까지의 거리를 추정한 뒤, 3개 이상의 비콘에 대한 거리 원(circle)의 교점으로 위치를 계산한다.

```
RSSI = -(10 * n * log10(d) + A)
```

- `A`: 1m 거리에서의 수신 전력 (보통 -59dBm ~ -65dBm)
- `n`: 환경에 따른 경로손실 지수 (자유공간: 2.0, 실내: 2.5~4.0)
- `d`: 비콘과 수신기 사이의 거리

#### (2) 핑거프린팅 (Fingerprinting)

사전에 건물 내 여러 지점에서 각 비콘의 RSSI 패턴을 수집하여 데이터베이스를 구축한다. 실시간으로 측정된 RSSI 패턴을 DB의 패턴과 매칭(k-NN, SVM, 딥러닝 등)하여 위치를 추정한다. 삼변측량보다 실내 환경에서 더 안정적인 결과를 제공하는 경우가 많다.

### 2.2 대표 서비스

| 프로토콜 | 개발사 | 특징 |
|----------|--------|------|
| **iBeacon** | Apple | UUID + Major + Minor로 비콘 식별. iOS 네이티브 지원. 2013년 WWDC에서 발표. |
| **Eddystone** | Google | URL, UID, TLM(텔레메트리), EID(암호화) 프레임 지원. 오픈 프로토콜. |

> 참고: Apple iBeacon 공식 문서
> https://developer.apple.com/ibeacon/
> 참고: Google Eddystone 사양
> https://github.com/google/eddystone

### 2.3 정확도

- **일반적 환경**: 1~3m
- **복잡한 실내 환경** (사람이 많거나 가구가 밀집된 경우): 5m 이상까지 저하
- 비콘 배치 밀도와 RSSI 보정 알고리즘에 크게 의존

### 2.4 장점

- **저전력**: BLE 비콘 하나가 코인 셀 배터리(CR2032)로 1~3년 동작 가능
- **저비용 하드웨어**: 비콘 단가가 개당 $5~$30 수준으로 저렴
- **범용성**: iOS와 Android 모두 BLE 스캔을 네이티브로 지원
- **빠른 설치**: 비콘을 천장이나 벽에 부착하는 것만으로 인프라 구축 가능

### 2.5 단점

- **비콘 설치 및 유지보수 부담**: 대형 건물에서는 수백 개의 비콘이 필요하며, 배터리 교체 주기 관리가 필수적이다
- **RSSI 불안정성**: BLE 2.4GHz 대역의 신호는 다음 요인에 의해 불안정하다
  - **인체 차폐 (Body Shadowing)**: 사람의 몸이 비콘과 수신기 사이에 있으면 6~15dB 감쇠 발생
  - **가구/장애물**: 금속 캐비닛, 벽체 등에 의한 반사 및 차폐
  - **2.4GHz 간섭**: Wi-Fi, 전자레인지 등 동일 주파수 대역의 기기와 간섭
- **2D 위치만 제공**: 기본적으로 x, y 좌표만 추정하며, 높이(z축)나 방향(orientation) 정보는 제공하지 않는다
- **환경 변화 취약**: 가구 배치 변경, 인원 밀집도 변화 등에 따라 RSSI 패턴이 달라져 핑거프린트 DB 재구축이 필요할 수 있다

> 참고: Faragher, R., & Harle, R. (2015). "Location Fingerprinting With Bluetooth Low Energy Beacons." *IEEE Journal on Selected Areas in Communications*, 33(11), 2418-2428.
> https://ieeexplore.ieee.org/document/7275492

### 2.6 상용 사례

- **다비오 (Davio)**: 국내 대표적인 BLE 비콘 기반 실내 측위 솔루션. 백화점, 병원, 공항 등 대형 건물에 특화. 비콘 설치 + 모바일 SDK 제공 방식.
  > https://www.dabeeo.com/
- **IndoorAtlas**: BLE와 지자기 센서를 융합한 하이브리드 방식. 핀란드 기반.
  > https://www.indooratlas.com/
- **Estimote**: BLE 비콘 하드웨어 + 소프트웨어 플랫폼 제공. 근접 감지(Proximity) 및 실내 측위 SDK 제공.
  > https://estimote.com/

> 핵심 참고 논문: Zafari, F., Gkelias, A., & Leung, K. K. (2019). "A Survey on Indoor Localization Systems and Technologies." *IEEE Communications Surveys & Tutorials*, 21(3), 2568-2599.
> https://ieeexplore.ieee.org/document/8713754

---

## 3. Wi-Fi 핑거프린팅

### 3.1 원리

Wi-Fi 핑거프린팅은 건물 내 각 위치에서 관측되는 **Wi-Fi AP(Access Point)들의 RSSI 패턴**을 사전에 수집하여 데이터베이스(Fingerprint DB, Radio Map)를 구축하고, 사용자의 실시간 RSSI 측정값을 이 DB와 비교하여 위치를 추정하는 방식이다.

#### 오프라인 단계 (Training Phase)

1. 건물 내부를 일정 간격(1~2m)의 격자로 나눈다 (Reference Points)
2. 각 격자점에서 주변 Wi-Fi AP들의 RSSI를 일정 시간 동안 수집한다
3. 각 격자점의 RSSI 벡터를 DB에 저장한다: `RP_i = [RSSI_AP1, RSSI_AP2, ..., RSSI_APn]`

#### 온라인 단계 (Positioning Phase)

1. 사용자의 기기가 현재 위치에서 Wi-Fi RSSI를 측정한다
2. 측정된 RSSI 벡터를 Fingerprint DB의 벡터들과 비교한다
3. 가장 유사한 패턴을 가진 격자점(들)의 좌표를 사용자 위치로 추정한다

매칭 알고리즘으로는 **k-NN(k-Nearest Neighbors)**, **확률론적 방법(Probabilistic Methods)**, **SVM**, **신경망(Neural Network)** 등이 사용된다.

### 3.2 정확도

- **기본 핑거프린팅**: 2~5m
- **Wi-Fi RTT 적용 시**: 1~2m

### 3.3 장점

- **추가 하드웨어 불필요**: 대부분의 건물에 이미 설치된 Wi-Fi AP를 그대로 활용할 수 있다
- **넓은 커버리지**: Wi-Fi 신호는 BLE보다 도달 거리가 길어 적은 수의 AP로도 넓은 영역을 커버할 수 있다
- **범용성**: 거의 모든 스마트폰, 태블릿, 노트북이 Wi-Fi를 탑재하고 있다

### 3.4 단점

- **Fingerprint DB 구축의 노동집약성**: 건물 전체를 격자 단위로 돌며 RSSI를 수집하는 작업(Site Survey)은 대형 건물에서 수일~수주가 소요된다
- **AP 변경 시 재구축 필요**: AP가 추가, 제거, 이동되면 Radio Map이 무효화되어 재수집이 필요하다
- **RSSI 변동성**: 시간대, 인원 밀집도, 문 개폐 상태 등에 따라 RSSI 값이 변동한다
- **Android 제한**: Android 9(Pie) 이상에서 Wi-Fi 스캔 빈도를 2분당 4회로 제한하여, 빈번한 위치 갱신이 어려워졌다

> 참고: Android Wi-Fi scanning throttling
> https://developer.android.com/develop/connectivity/wifi/wifi-scan#wifi-scan-throttling

### 3.5 Wi-Fi RTT (IEEE 802.11mc)

**Wi-Fi RTT(Round-Trip Time)**는 IEEE 802.11mc 표준에 기반하여, AP와 기기 사이의 **왕복 시간(ToF)**을 측정해 거리를 계산하는 방식이다. RSSI 기반의 불안정한 거리 추정 대신 시간 기반 측정을 사용하므로 정확도가 크게 향상된다.

- **정확도**: 1~2m (RSSI 핑거프린팅 대비 2배 이상 향상)
- **요구사항**: 802.11mc를 지원하는 AP와 기기가 필요
- **Android 지원**: Android 9(Pie)부터 Wi-Fi RTT API 제공
- **제한**: 지원 AP와 기기가 아직 제한적

> 참고: IEEE 802.11mc 표준
> https://standards.ieee.org/standard/802_11mc-2014.html
> 참고: Android Wi-Fi RTT API
> https://developer.android.com/develop/connectivity/wifi/wifi-rtt

### 3.6 Google Indoor Positioning 사례

Google은 Google Maps에서 **Wi-Fi 기반 실내 지도 및 측위** 기능을 제공한다. 건물 관리자가 Wi-Fi AP 정보를 등록하고 실내 지도(Floor Plan)를 업로드하면, Google Maps 앱에서 실내 위치를 파란 점(Blue Dot)으로 표시한다. 공항, 쇼핑몰 등 대형 시설에서 주로 활용된다.

> 참고: Google Maps Indoor Positioning
> https://developers.google.com/maps/documentation/android-sdk/indoor

### 3.7 핵심 참고 문헌

- Poulose, A., & Han, D. S. (2023). "WiFi Fingerprinting Indoor Positioning Overview." *arXiv preprint arXiv:2301.02448*.
  https://arxiv.org/abs/2301.02448
- He, S., & Chan, S.-H. G. (2016). "Wi-Fi Fingerprint-Based Indoor Positioning: Recent Advances and Comparisons." *IEEE Communications Surveys & Tutorials*, 18(1), 466-490.
  https://ieeexplore.ieee.org/document/7170879

---

## 4. UWB (Ultra-Wideband)

### 4.1 원리

UWB(Ultra-Wideband)는 **500MHz 이상의 넓은 대역폭**(3.1~10.6GHz)을 사용하여 매우 짧은 펄스(수 나노초)를 송수신하는 무선 통신 기술이다. 실내 측위에서는 주로 다음 두 가지 방식으로 거리를 측정한다.

#### (1) ToF (Time of Flight)

UWB 태그와 앵커(Anchor) 사이에 신호가 왕복하는 시간을 측정하여 거리를 계산한다. **TWR(Two-Way Ranging)** 방식이 대표적이며, 송신기와 수신기의 시계를 동기화할 필요가 없다는 장점이 있다.

```
거리 d = (c * t_round_trip) / 2
```

- `c`: 빛의 속도 (약 3 * 10^8 m/s)
- UWB의 나노초 단위 시간 분해능 → cm급 거리 분해능

#### (2) TDoA (Time Difference of Arrival)

여러 앵커에 도달하는 신호의 **시간 차이**를 이용하여 위치를 계산한다. 앵커 간 시계 동기화가 필요하지만, 태그는 신호를 한 번만 송신하면 되므로 태그의 전력 소모가 적다. 대규모 자산 추적에 적합하다.

### 4.2 정확도

- **일반적 환경**: 10~30cm
- **최적 조건 (LOS 확보, 충분한 앵커 배치)**: 5cm 이하도 가능
- **NLOS 환경**: 30~50cm (그래도 다른 기술 대비 우수)

UWB가 이처럼 높은 정확도를 달성하는 이유는 **넓은 대역폭**에 있다. 대역폭이 넓을수록 시간 분해능이 높아져 multipath 신호를 직접 경로(First Path)와 분리할 수 있다. 이를 **multipath 강건성(resilience)**이라 한다.

### 4.3 장점

- **cm급 정확도**: 모든 실내 측위 기술 중 가장 높은 정확도
- **Multipath 강건성**: 넓은 대역폭 덕분에 직접 경로를 반사 경로와 구분 가능
- **낮은 간섭**: 매우 낮은 전력 밀도(-41.3dBm/MHz)로 송신하여 기존 무선 시스템과 간섭이 적다
- **3D 측위 가능**: 앵커를 3차원으로 배치하면 x, y, z 좌표 모두 추정 가능
- **높은 갱신 속도**: 수백 Hz 이상의 위치 갱신이 가능하여 실시간 추적에 적합

### 4.4 단점

- **앵커 설치 비용**: UWB 앵커는 BLE 비콘 대비 고가(개당 $50~$200+)이며, 정밀한 배치와 캘리브레이션이 필요하다
- **기기 지원 제한**:
  - **Apple**: iPhone 11 이상 (U1/U2 칩), Apple Watch Series 6 이상
  - **Android**: Samsung Galaxy Note 20 이상, Google Pixel 6 Pro 이상 등 일부 플래그십 모델만 지원
  - 중저가 스마트폰은 대부분 UWB 미지원
- **커버리지 제한**: UWB 신호의 유효 범위가 약 10~30m로 짧아, 넓은 공간에는 많은 앵커가 필요하다
- **LOS 요구**: 벽이나 두꺼운 장애물 뒤에서는 성능이 급격히 저하된다

> 참고: Apple U1 칩 및 Nearby Interaction 프레임워크
> https://developer.apple.com/nearby-interaction/
> 참고: Android UWB API
> https://developer.android.com/develop/connectivity/uwb

### 4.5 표준 및 생태계

UWB 실내 측위의 핵심 표준은 **IEEE 802.15.4z**(2020)이다. 이 표준은 기존 IEEE 802.15.4a의 측거(ranging) 기능을 보안 강화(Scrambled Timestamp Sequence, STS)와 함께 개정한 것이다.

- **IEEE 802.15.4z**: Enhanced Impulse Radio UWB PHY
  > https://standards.ieee.org/standard/802_15_4z-2020.html
- **FiRa Consortium**: Apple, Samsung, Google, NXP 등이 참여하는 UWB 상호운용성 표준화 단체
  > https://www.firaconsortium.org/
- **CCC (Car Connectivity Consortium)**: UWB 기반 디지털 키(Digital Key) 표준화
  > https://carconnectivity.org/

### 4.6 소비자 제품 사례

| 제품 | 제조사 | UWB 활용 |
|------|--------|----------|
| **AirTag** | Apple | U1 칩 기반 정밀 찾기(Precision Finding). iPhone과의 방향 및 거리를 cm 단위로 표시. |
| **SmartTag+** / **SmartTag2** | Samsung | UWB 기반 AR 찾기(AR Find). Galaxy 기기와 연동. |
| **Tile Ultra** | Tile | UWB + BLE 하이브리드. 정밀 찾기 기능. |

> 참고: Apple AirTag 기술 사양
> https://www.apple.com/airtag/
> 참고: Samsung SmartTag
> https://www.samsung.com/smarttag/

### 4.7 핵심 참고 문헌

- Alarifi, A., et al. (2016). "Ultra Wideband Indoor Positioning Technologies: Analysis and Recent Advances." *Sensors*, 16(5), 707.
  https://doi.org/10.3390/s16050707
- Wymeersch, H., et al. (2020). "5G mmWave Positioning for Vehicular Networks." *IEEE Wireless Communications*, 27(6).
  https://ieeexplore.ieee.org/document/9311190
- IEEE 802.15.4z-2020 Standard
  https://standards.ieee.org/standard/802_15_4z-2020.html

---

## 5. VPS (Visual Positioning System)

### 5.1 원리

VPS(Visual Positioning System)는 **카메라로 촬영한 이미지에서 시각적 특징점(Visual Features)을 추출**하고, 사전에 구축된 3D 맵의 특징점과 매칭하여 카메라의 **6DoF(6 Degrees of Freedom) 포즈**를 추정하는 기술이다.

6DoF는 다음을 포함한다:
- **3DoF 위치 (Translation)**: x, y, z 좌표
- **3DoF 방향 (Rotation)**: roll, pitch, yaw

#### VPS의 전형적인 파이프라인

```
[카메라 이미지 입력]
       |
       v
[1단계: 특징점 추출 (Feature Extraction)]
  - 전통적: SIFT, ORB, SuperPoint
  - 학습 기반: SuperPoint (DeTone et al., 2018)
       |
       v
[2단계: 이미지 검색 (Image Retrieval)]
  - 입력 이미지와 유사한 참조 이미지를 DB에서 검색
  - NetVLAD, AP-GeM 등 딥러닝 기반 글로벌 디스크립터 사용
       |
       v
[3단계: 특징점 매칭 (Feature Matching)]
  - 입력 이미지의 2D 특징점과 참조 맵의 3D 포인트를 대응
  - SuperGlue (Sarlin et al., 2020) 등 학습 기반 매처 활용
       |
       v
[4단계: 포즈 추정 (Pose Estimation)]
  - PnP (Perspective-n-Point) + RANSAC로 카메라 포즈 계산
  - 2D-3D 대응점으로부터 6DoF 포즈 도출
```

### 5.2 핵심 기술: Hierarchical Localization (hloc)

현대적 VPS의 사실상 표준(de facto standard)은 **hloc (Hierarchical Localization)** 파이프라인이다. Sarlin et al. (2019)이 제안한 이 프레임워크는 다음 계층 구조를 따른다:

1. **Coarse Localization**: NetVLAD 등으로 현재 이미지와 유사한 DB 이미지를 빠르게 검색
2. **Fine Localization**: SuperPoint + SuperGlue로 정밀한 2D-3D 매칭 수행
3. **Pose Estimation**: PnP + RANSAC으로 최종 6DoF 포즈 산출

이 계층적 접근은 대규모 환경에서도 효율적으로 작동하며, 전체 3D 포인트 클라우드를 탐색하지 않고도 빠른 로컬라이제이션이 가능하다.

> 참고: Sarlin, P.-E., et al. (2019). "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." *CVPR 2019*.
> https://arxiv.org/abs/1812.03506
> 참고: hloc GitHub 리포지토리
> https://github.com/cvg/Hierarchical-Localization

### 5.3 정확도

- **구조화된 실내 환경 (사무실, 복도 등)**: 수십 cm ~ 1m
- **텍스처가 풍부한 환경**: 10~30cm까지 가능
- **텍스처가 부족한 환경 (흰 벽, 반복 패턴)**: 1m 이상 저하 또는 실패
- **방향 정확도**: 약 1~3도

### 5.4 장점

- **추가 하드웨어 불필요**: 스마트폰 카메라만으로 동작. 비콘, 앵커 등 물리적 인프라 설치가 필요 없다
- **6DoF 포즈 제공**: 위치뿐 아니라 방향까지 알 수 있어 **AR(Augmented Reality) 오버레이에 최적**이다
- **풍부한 환경 인식**: 시각 정보를 통해 의미론적(Semantic) 이해까지 확장 가능 (어떤 방인지, 어떤 표지판인지 등)
- **높은 확장 잠재력**: 3D 맵만 구축하면 추가 하드웨어 없이 여러 건물로 확장 가능

### 5.5 단점

- **연산 비용**: 특징점 추출, 매칭, 포즈 추정 과정이 계산 집약적이다. 실시간 처리를 위해 서버 측 처리 또는 모바일 GPU 최적화가 필요하다
- **조명 변화 민감성**: 낮/밤, 자연광/인공조명 전환 시 특징점의 외형(appearance)이 달라져 매칭 실패 가능성이 증가한다
- **환경 변화**: 가구 이동, 인테리어 변경, 계절 변화(창밖 풍경) 등이 발생하면 3D 맵을 업데이트해야 한다
- **서버 의존성**: 대규모 3D 맵과 딥러닝 추론을 모바일에서 직접 수행하기 어려워, 서버 통신이 필요한 경우가 많다
- **텍스처 부족 문제**: 흰 벽, 유리면, 반복적 타일 등 시각적 특징이 부족한 환경에서는 로컬라이제이션 성능이 저하된다
- **프라이버시 우려**: 카메라 이미지를 서버로 전송하는 과정에서 개인정보(행인의 얼굴 등) 노출 위험이 있다

> 참고: Piasco, N., et al. (2018). "A Survey on Visual-Based Localization: On the Benefit of Heterogeneous Data." *Pattern Recognition*, vol. 74, 90-109.
> https://doi.org/10.1016/j.patcog.2017.09.013

### 5.6 상용 사례

#### Google Visual Positioning System (Google Maps AR / Live View)

Google은 **Street View 데이터**를 기반으로 VPS를 구축하여, Google Maps의 **Live View** 기능에서 AR 기반 길 안내를 제공한다. 사용자가 카메라를 들면 실제 거리 위에 화살표와 방향 안내가 오버레이된다. 실내에서도 공항, 쇼핑몰 등 일부 시설에서 **Indoor Live View**를 제공한다.

> 참고: Google VPS / Live View 소개
> https://blog.google/products/maps/new-maps-updates-io-2023/
> 참고: Google ARCore Geospatial API (VPS 기반)
> https://developers.google.com/ar/develop/geospatial

#### Apple Indoor Maps

Apple은 **IMDF(Indoor Mapping Data Format)** 표준과 함께 실내 측위를 제공한다. ARKit의 Visual-Inertial Odometry(VIO)와 Wi-Fi, BLE 등을 융합한 방식을 사용하며, 일부 공항과 쇼핑몰에서 실내 지도와 함께 Blue Dot 위치를 표시한다.

> 참고: Apple Indoor Maps Program
> https://register.apple.com/indoor
> 참고: Apple IMDF
> https://register.apple.com/resources/imdf/

### 5.7 핵심 참고 문헌

- Sarlin, P.-E., et al. (2019). "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." *CVPR 2019*.
  https://arxiv.org/abs/1812.03506
- Sarlin, P.-E., et al. (2020). "SuperGlue: Learning Feature Matching with Graph Neural Networks." *CVPR 2020*.
  https://arxiv.org/abs/1911.11763
- DeTone, D., Malisiewicz, T., & Rabinovich, A. (2018). "SuperPoint: Self-Supervised Interest Point Detection and Description." *CVPRW 2018*.
  https://arxiv.org/abs/1712.07629
- Arandjelovic, R., et al. (2016). "NetVLAD: CNN Architecture for Weakly Supervised Place Recognition." *CVPR 2016*.
  https://arxiv.org/abs/1511.07247
- Labbe, M. & Michaud, F. (2019). "RTAB-Map as an Open-Source Lidar and Visual Simultaneous Localization and Mapping Library for Large-Scale and Long-Term Online Operation." *Journal of Field Robotics*, 36(2), 416-446.
  https://doi.org/10.1002/rob.21831

---

## 6. 기술 비교 요약표

| 항목 | BLE 비콘 | Wi-Fi 핑거프린팅 | UWB | VPS |
|------|----------|------------------|-----|-----|
| **정확도** | 1~3m | 2~5m | 10~30cm | 30cm~1m |
| **측위 원리** | RSSI 삼변측량/핑거프린팅 | RSSI 핑거프린팅 | ToF / TDoA | 이미지 특징점 매칭 |
| **추가 인프라** | 비콘 설치 필요 | 기존 AP 활용 가능 | UWB 앵커 설치 필요 | 없음 (사전 3D 스캔만) |
| **설치 비용** | 중간 ($5-30/비콘) | 낮음 (기존 인프라) | 높음 ($50-200+/앵커) | 낮음 (SW + 스캔 비용) |
| **유지보수** | 비콘 배터리 교체 | AP 변경 시 재수집 | 앵커 관리 및 캘리브레이션 | 환경 변경 시 재스캔 |
| **6DoF 지원** | X (2D만) | X (2D만) | X (3D 위치 가능, 방향 X) | O (위치 + 방향) |
| **AR 연동 적합성** | 어려움 (방향 정보 없음) | 어려움 (방향 정보 없음) | 가능 (위치 정확하나 방향 별도) | 최적 (6DoF 직접 제공) |
| **서버 의존도** | 낮음 (로컬 계산 가능) | 중간 (DB 조회 필요) | 낮음 (로컬 계산 가능) | 높음 (3D 맵 + 추론) |
| **조명 영향** | 없음 | 없음 | 없음 | 큼 (조명 변화 시 성능 저하) |
| **기기 호환성** | 높음 (BLE 지원 기기) | 높음 (Wi-Fi 지원 기기) | 낮음 (UWB 칩 필요) | 높음 (카메라만 필요) |
| **실시간성** | 1~3초 (스캔 주기) | 2~30초 (스캔 제한) | <100ms | 100ms~수초 (서버 처리) |
| **프라이버시** | 양호 | 양호 | 양호 | 우려 (카메라 이미지) |

### 기술별 최적 활용 시나리오

- **BLE 비콘**: 쇼핑몰 근접 마케팅, 박물관 전시 안내 등 **근접 감지(Proximity)**가 핵심인 경우
- **Wi-Fi 핑거프린팅**: 기존 Wi-Fi 인프라가 있는 **대형 건물에서 추가 비용 없이** 대략적 위치가 필요한 경우
- **UWB**: 물류 창고 자산 추적, 산업용 로봇 제어 등 **cm급 정밀도**가 필수인 경우
- **VPS**: **AR 오버레이, 시각적 내비게이션** 등 6DoF 포즈가 필요한 경우

---

## 7. 우리 프로젝트에서 VPS를 선택한 이유

본 졸업 프로젝트는 **실내 AR 경로 안내 시스템**을 구축하는 것이 목표이다. 이를 위해 네 가지 기술을 종합적으로 검토한 결과, **VPS가 가장 적합한 기술**이라고 판단하였다. 그 근거는 다음과 같다.

### 7.1 추가 하드웨어 없이 스마트폰만으로 구축 가능

BLE 비콘이나 UWB 앵커를 건물 곳곳에 설치하면 비용과 유지보수 부담이 발생한다. 특히 학교 건물처럼 **시설 변경 권한이 제한된 환경**에서는 하드웨어 설치 자체가 어렵다. VPS는 사전에 3D 맵을 스캔해 두기만 하면, 사용자는 **스마트폰 카메라 하나만으로** 로컬라이제이션을 수행할 수 있다.

### 7.2 6DoF 포즈 제공 -- AR 경로 오버레이의 필수 조건

AR로 경로를 화면 위에 오버레이하려면, 단순히 "어디에 있는가(위치)"뿐만 아니라 **"어디를 바라보고 있는가(방향)"**까지 알아야 한다. 즉, **6DoF(3DoF 위치 + 3DoF 방향)**가 필수이다.

- BLE/Wi-Fi: 2D 위치만 제공 → AR 오버레이 불가
- UWB: 3D 위치는 가능하나 방향 정보는 별도 IMU 센서 융합 필요
- **VPS: 이미지 기반으로 6DoF 포즈를 직접 산출** → AR에 즉시 활용 가능

### 7.3 RTAB-Map 스캔 데이터를 VPS 기준 맵으로 직접 활용

본 프로젝트에서는 **RTAB-Map(Real-Time Appearance-Based Mapping)**을 사용하여 건물 내부를 3D 스캔한다. RTAB-Map이 생성하는 **3D 포인트 클라우드와 특징점 맵**은 VPS의 참조 맵(Reference Map)으로 그대로 활용할 수 있다. 즉, **별도의 맵 구축 과정 없이** 스캔 결과물 자체가 로컬라이제이션 인프라가 된다.

- RTAB-Map은 Visual SLAM(Simultaneous Localization and Mapping) 기술로, 카메라와 깊이 센서 데이터를 이용하여 실시간으로 3D 맵을 구축한다
- 생성된 맵에는 시각적 특징점(Visual Features), 포인트 클라우드, 카메라 포즈 등이 포함되어 있어 VPS 파이프라인에 직접 투입 가능하다
- BLE/Wi-Fi/UWB 방식은 RTAB-Map 데이터와 별개로 독자적인 인프라(비콘, 앵커)를 구축해야 한다

> 참고: RTAB-Map 공식 페이지
> http://introlab.github.io/rtabmap/
> 참고: Labbe, M. & Michaud, F. (2019). "RTAB-Map as an Open-Source Lidar and Visual SLAM Library for Large-Scale and Long-Term Online Operation." *Journal of Field Robotics*.
> https://doi.org/10.1002/rob.21831

### 7.4 단점의 완화 가능성

VPS의 주요 단점인 조명 민감성과 연산 비용은 본 프로젝트의 맥락에서 상당 부분 완화된다.

| VPS 단점 | 완화 요인 |
|----------|-----------|
| **조명 변화에 민감** | 실내 환경은 인공 조명 중심이므로 조명 변화 폭이 실외 대비 작다. 야간에도 동일한 조명 조건이 유지된다. |
| **연산 비용 높음** | 특징점 매칭 및 포즈 추정을 **서버에서 처리**하여 모바일 기기의 부하를 줄인다. 본 프로젝트의 백엔드 서버가 이 역할을 담당한다. |
| **환경 변경 시 재스캔** | 학교 건물은 인테리어 변경이 드물어 한 번 구축한 맵의 유효 기간이 길다. |
| **프라이버시 우려** | 로컬라이제이션에 사용하는 이미지는 특징점 추출 후 폐기하거나, 특징점만 서버로 전송하는 방식으로 완화 가능하다. |

### 7.5 요약

```
[프로젝트 요구사항]          [VPS 제공 가치]
─────────────────          ────────────────
AR 경로 오버레이       ←──  6DoF 포즈 (위치 + 방향)
추가 HW 설치 불가      ←──  카메라만으로 동작
RTAB-Map 활용          ←──  스캔 맵을 참조 맵으로 직접 사용
실내 환경              ←──  조명 안정성 (실내 인공조명)
```

---

## 8. 상용 서비스 사례

### 8.1 다비오 (Dabeeo)

- **기반 기술**: BLE 비콘 기반 실내 측위
- **특화 영역**: 대형 건물(백화점, 병원, 공항, 복합 쇼핑몰)
- **서비스 내용**:
  - 비콘 설치 + 실내 지도 제작 + 모바일 SDK 제공
  - 실내 내비게이션, 위치 기반 마케팅(쿠폰 푸시), 시설 관리
  - 국내 주요 백화점(롯데, 신세계 등), 인천국제공항 등에 적용
- **정확도**: 약 1~3m
- **특징**: 비콘 인프라가 필수이지만, 한국 대형 상업시설에서 검증된 안정적 솔루션

> 참고: https://www.dabeeo.com/

### 8.2 IndoorAtlas

- **기반 기술**: 지자기 센서(Magnetometer) + Wi-Fi 핑거프린팅 융합
- **특화 영역**: 비콘 없는 실내 측위
- **서비스 내용**:
  - 지구 자기장의 실내 왜곡 패턴을 핑거프린트로 활용 (건물 내 철근, 배선 등이 자기장을 왜곡)
  - Wi-Fi RSSI 핑거프린팅과 융합하여 정확도 향상
  - 별도 비콘 설치 없이 스마트폰 내장 센서만으로 동작
  - MapCreator 앱으로 건물 내부를 걸어 다니며 핑거프린트 수집
- **정확도**: 1~3m (환경에 따라 다름)
- **특징**: 지자기 기반이므로 추가 HW가 불필요하지만, 대규모 건물에서는 핑거프린트 수집 노력이 상당하다

> 참고: https://www.indooratlas.com/
> 참고: IndoorAtlas 기술 백서
> https://www.indooratlas.com/resources/

### 8.3 Google Indoor Live View

- **기반 기술**: VPS (Visual Positioning System)
- **특화 영역**: AR 기반 실내 내비게이션
- **서비스 내용**:
  - Google Maps의 Live View 기능을 실내로 확장
  - 사용자가 스마트폰 카메라를 비추면 실내 공간 위에 AR 화살표로 경로 안내
  - 사전에 Google Street View 기술로 건물 내부를 스캔하여 VPS 맵 구축
  - 전 세계 주요 공항, 쇼핑몰, 기차역 등에서 서비스 중
- **정확도**: 약 30cm~1m
- **특징**: 추가 하드웨어 없이 카메라만으로 동작하며, AR 오버레이 품질이 우수하다. 본 프로젝트와 가장 유사한 상용 서비스이다.

> 참고: Google Indoor Live View 소개
> https://blog.google/products/maps/google-maps-indoor-live-view/
> 참고: Google ARCore Geospatial API
> https://developers.google.com/ar/develop/geospatial

### 8.4 Pointr

- **기반 기술**: BLE + Visual Positioning 하이브리드
- **특화 영역**: 대형 시설의 실내 내비게이션 및 디지털 트윈
- **서비스 내용**:
  - BLE 비콘으로 대략적 위치 파악 후, VPS로 정밀 위치 보정하는 하이브리드 전략
  - Deep Location 기술: 딥러닝 기반 Visual Positioning
  - 3D 실내 지도, 경로 안내, 분석 대시보드 제공
  - Heathrow 공항, Harrods 백화점, Cisco 사옥 등에 적용
- **정확도**: BLE 단독 1~3m, VPS 결합 시 ~1m
- **특징**: 단일 기술의 한계를 극복하기 위한 **하이브리드 접근**이 인상적이다. BLE가 VPS의 초기 위치 추정(coarse localization)을 보조하고, VPS가 정밀 로컬라이제이션을 담당한다.

> 참고: https://www.pointr.tech/
> 참고: Pointr Deep Location 기술 소개
> https://www.pointr.tech/deep-location

### 8.5 기타 주목할 만한 사례

| 서비스/제품 | 기반 기술 | 적용 분야 | 참고 링크 |
|------------|-----------|-----------|-----------|
| **Mappedin** | Wi-Fi + BLE | 쇼핑몰 실내 지도/내비게이션 | https://www.mappedin.com/ |
| **Cisco DNA Spaces** | Wi-Fi 핑거프린팅 | 기업 시설 관리, 자산 추적 | https://dnaspaces.cisco.com/ |
| **Ubisense** | UWB 기반 RTLS | 제조업 자산/인력 추적 | https://ubisense.com/ |
| **Pozyx** | UWB | 물류/제조 실시간 위치 추적 | https://www.pozyx.io/ |
| **Naver Labs** | VPS + HD Map | 대형 건물 로봇 자율주행 | https://www.naverlabs.com/ |

---

## 참고 문헌 종합

### 서베이 논문

1. Zafari, F., Gkelias, A., & Leung, K. K. (2019). "A Survey on Indoor Localization Systems and Technologies." *IEEE Communications Surveys & Tutorials*, 21(3), 2568-2599.
   https://ieeexplore.ieee.org/document/8713754

2. Brena, R. F., et al. (2017). "Evolution of Indoor Positioning Technologies: A Survey." *Journal of Sensors*, vol. 2017.
   https://doi.org/10.1155/2017/2630413

3. He, S., & Chan, S.-H. G. (2016). "Wi-Fi Fingerprint-Based Indoor Positioning: Recent Advances and Comparisons." *IEEE Communications Surveys & Tutorials*, 18(1), 466-490.
   https://ieeexplore.ieee.org/document/7170879

4. Piasco, N., et al. (2018). "A Survey on Visual-Based Localization: On the Benefit of Heterogeneous Data." *Pattern Recognition*, vol. 74.
   https://doi.org/10.1016/j.patcog.2017.09.013

### 기술별 핵심 논문

5. Faragher, R., & Harle, R. (2015). "Location Fingerprinting With Bluetooth Low Energy Beacons." *IEEE JSAC*, 33(11).
   https://ieeexplore.ieee.org/document/7275492

6. Poulose, A., & Han, D. S. (2023). "WiFi Fingerprinting Indoor Positioning Overview." *arXiv:2301.02448*.
   https://arxiv.org/abs/2301.02448

7. Alarifi, A., et al. (2016). "Ultra Wideband Indoor Positioning Technologies: Analysis and Recent Advances." *Sensors*, 16(5).
   https://doi.org/10.3390/s16050707

8. Wymeersch, H., et al. (2020). "5G mmWave Positioning for Vehicular Networks." *IEEE Wireless Communications*.
   https://ieeexplore.ieee.org/document/9311190

9. Sarlin, P.-E., et al. (2019). "From Coarse to Fine: Robust Hierarchical Localization at Large Scale." *CVPR 2019*.
   https://arxiv.org/abs/1812.03506

10. Sarlin, P.-E., et al. (2020). "SuperGlue: Learning Feature Matching with Graph Neural Networks." *CVPR 2020*.
    https://arxiv.org/abs/1911.11763

11. DeTone, D., et al. (2018). "SuperPoint: Self-Supervised Interest Point Detection and Description." *CVPRW 2018*.
    https://arxiv.org/abs/1712.07629

12. Arandjelovic, R., et al. (2016). "NetVLAD: CNN Architecture for Weakly Supervised Place Recognition." *CVPR 2016*.
    https://arxiv.org/abs/1511.07247

13. Labbe, M. & Michaud, F. (2019). "RTAB-Map as an Open-Source Lidar and Visual SLAM Library." *Journal of Field Robotics*, 36(2).
    https://doi.org/10.1002/rob.21831

### 표준 문서

14. IEEE 802.11mc-2014 (Wi-Fi RTT)
    https://standards.ieee.org/standard/802_11mc-2014.html

15. IEEE 802.15.4z-2020 (UWB Enhanced Ranging)
    https://standards.ieee.org/standard/802_15_4z-2020.html

### 공식 개발자 문서

16. Apple iBeacon: https://developer.apple.com/ibeacon/
17. Google Eddystone: https://github.com/google/eddystone
18. Android Wi-Fi RTT: https://developer.android.com/develop/connectivity/wifi/wifi-rtt
19. Apple Nearby Interaction (UWB): https://developer.apple.com/nearby-interaction/
20. Android UWB: https://developer.android.com/develop/connectivity/uwb
21. Google ARCore Geospatial API: https://developers.google.com/ar/develop/geospatial
22. RTAB-Map: http://introlab.github.io/rtabmap/
23. hloc: https://github.com/cvg/Hierarchical-Localization
