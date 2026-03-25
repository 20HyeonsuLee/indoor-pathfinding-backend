# 기술 선택과 근거

> 주요 기술적 결정마다 **어떤 선택지가 있었고, 왜 이 방식을 선택했는지**를 설명합니다.

---

## 1. 공간 데이터베이스: PostgreSQL + PostGIS

### 선택지

| 선택지 | 설명 |
|--------|------|
| **PostgreSQL + PostGIS** | 오픈소스 관계형 DB + 공간 확장 |
| MySQL + Spatial Extensions | MySQL 내장 공간 기능 |
| MongoDB (GeoJSON) | NoSQL의 지리 쿼리 기능 |
| 전용 그래프 DB (Neo4j) | 그래프 탐색에 최적화된 DB |

### 선택: PostgreSQL + PostGIS

**이유:**

- **3D 좌표 지원** - 실내 내비게이션은 X, Y, Z(층 높이) 3차원 좌표가 필수입니다. PostGIS의 `POINTZ`, `LINESTRINGZ` 타입은 이를 네이티브로 지원합니다. MySQL의 공간 확장은 2D 중심이라 3D 처리에 제약이 있습니다.

- **pgRouting** - PostGIS 위에서 동작하는 경로 탐색 확장입니다. 그래프 기반 라우팅 알고리즘을 DB 레벨에서 지원하므로, 추후 서버 사이드 경로 계산이 필요할 때 활용할 수 있습니다.

- **Hibernate Spatial 호환** - Spring Data JPA와 함께 Hibernate Spatial을 사용하면 PostGIS의 공간 타입을 Java 객체(JTS Geometry)로 자연스럽게 매핑할 수 있습니다. MongoDB는 JPA 생태계를 벗어나게 됩니다.

- **Neo4j를 선택하지 않은 이유** - 길찾기 그래프는 전체 시스템의 일부일 뿐, 건물/층/스캔 등 대부분의 데이터는 관계형 구조입니다. 전용 그래프 DB를 도입하면 두 개의 데이터베이스를 관리해야 하는 복잡도가 생깁니다.

> 관련 설정: [docker-compose.yml](../../docker-compose.yml), [01-init.sql](../../docker/postgres/init/01-init.sql)

---

## 2. 백엔드 프레임워크: Spring Boot

### 선택지

| 선택지 | 설명 |
|--------|------|
| **Spring Boot (Java)** | 엔터프라이즈 Java 프레임워크 |
| Django/FastAPI (Python) | Python 웹 프레임워크 |
| NestJS (Node.js) | TypeScript 기반 프레임워크 |
| Go (gin/echo) | 경량 고성능 프레임워크 |

### 선택: Spring Boot 4.0.2 + Java 25

**이유:**

- **공간 데이터 생태계** - Hibernate Spatial, JTS(Java Topology Suite), Geolatte 등 Java 진영의 공간 데이터 처리 라이브러리가 성숙합니다. PostGIS와의 연동도 가장 잘 문서화되어 있습니다.

- **타입 안전성** - 3D 좌표, 기하학 객체, 그래프 노드/엣지 등 복잡한 도메인 모델을 다룹니다. Java의 강한 타입 시스템과 컴파일 타임 검증이 실수를 줄여줍니다.

- **DDD 지원** - Spring의 계층 분리, 의존성 주입, 이벤트 시스템이 DDD 아키텍처를 자연스럽게 지원합니다.

- **Python을 경로 추출에 활용** - RTAB-Map SDK와 점군(Point Cloud) 처리는 Python 생태계가 압도적이므로, 이 부분만 Python FastAPI로 분리하고 Spring Boot가 조율하는 구조를 선택했습니다.

> 관련 설정: [build.gradle.kts](../../build.gradle.kts)

---

## 3. DDD(도메인 주도 설계) 적용

### 선택지

| 선택지 | 설명 |
|--------|------|
| 전통적 3계층 (Controller-Service-Repository) | 가장 단순한 구조 |
| **DDD 기반 모듈 구조** | 도메인별 독립 모듈 |
| 헥사고날 아키텍처 | Port & Adapter 패턴 |
| CQRS | 읽기/쓰기 모델 분리 |

### 선택: DDD 기반 모듈 구조 (경량 DDD)

**이유:**

- **도메인 복잡도** - "건물-층-경로-노드-스캔-위치추정"이라는 복잡한 도메인을 다룹니다. 전통적 3계층에서는 서비스가 비대해지기 쉽습니다. 도메인별 모듈로 나누면 각 모듈이 자신의 책임만 갖습니다.

- **Entity = Domain Model** - 순수한 DDD에서는 JPA Entity와 Domain Model을 분리하지만, 이 프로젝트에서는 변환 비용을 줄이기 위해 JPA Entity를 Domain Model로 직접 사용합니다. 대신 비즈니스 로직은 Entity 내부에 작성합니다.

- **헥사고날을 선택하지 않은 이유** - Port/Adapter 패턴은 구조가 깔끔하지만, 졸업작품 규모에서는 과도한 추상화입니다. 필요한 곳(외부 서비스 연동)에만 인터페이스를 두는 실용적 접근을 택했습니다.

- **CQRS를 선택하지 않은 이유** - 읽기/쓰기 비율이 극단적으로 치우치지 않고, 단일 DB로 충분한 규모이므로 모델 분리의 이점이 크지 않습니다.

### 적용한 DDD 개념

| 개념 | 적용 방식 |
|------|----------|
| **모듈 경계** | `building`, `floor`, `pathfinding` 등 도메인별 최상위 패키지 |
| **Rich Domain Model** | 비즈니스 로직은 Entity 메서드 안에 (예: `PathNode.distanceTo()`, `PathEdge.getWeightedDistance()`) |
| **도메인 이벤트** | `ApplicationEventPublisher`로 모듈 간 느슨한 결합 |
| **Value Object** | `Point3D`를 Embeddable로, Enum을 의미 있는 도메인 타입으로 활용 |
| **Application Service** | 명령별 서비스 분리 (`Creator`, `Updater`, `Reader` 등) |

> 관련 코드: [modules/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/)

---

## 4. 스캔 처리 파이프라인: 비동기 폴링

### 선택지

| 선택지 | 설명 |
|--------|------|
| 동기 처리 (요청-응답) | 업로드 후 결과까지 대기 |
| WebSocket 실시간 알림 | 서버가 완료 시 클라이언트에 푸시 |
| **비동기 + 클라이언트 폴링** | 작업 시작 후 상태를 주기적으로 확인 |
| 메시지 큐 (RabbitMQ/Kafka) | 큐 기반 비동기 처리 |

### 선택: 비동기 + 클라이언트 폴링

**이유:**

- **긴 처리 시간** - RTAB-Map .db 파일의 경로 추출은 수 분 이상 걸릴 수 있습니다. 동기 방식은 HTTP 타임아웃 문제가 있습니다.

- **단순함** - 메시지 큐를 도입하면 인프라 복잡도가 크게 올라갑니다. 졸업작품 규모에서 RabbitMQ/Kafka는 과도합니다.

- **클라이언트 구현 용이** - 폴링은 클라이언트 구현이 단순합니다. 상태 조회 API를 주기적으로 호출하면 되므로 모바일 앱에서도 쉽게 구현할 수 있습니다.

- **WebSocket을 선택하지 않은 이유** - 스캔 처리는 빈번하지 않은 관리자 작업입니다. 상시 연결을 유지하는 WebSocket은 이 시나리오에 비해 과합니다.

### 처리 흐름

```
클라이언트                   Spring Boot                    Python 서비스
   |                            |                               |
   |--- POST /process -------->|--- 파일 업로드 --------------->|
   |<-- jobId 반환 ------------|                               |
   |                            |                     경로 추출 중...
   |--- GET /status?jobId ---->|--- 상태 조회 ------->|
   |<-- {progress: 60%} -------|<-- {status: processing}       |
   |                            |                               |
   |--- GET /status?jobId ---->|--- 상태 조회 ------->|
   |<-- {status: completed} ---|<-- {status: completed}        |
   |                            |                               |
   |--- POST /apply ---------->|--- 결과 조회 ------->|
   |<-- 적용 완료 -------------|<-- 노드/엣지 데이터            |
```

> 관련 코드: [pathprocessing 모듈](../../src/main/java/com/koreatech/indoor_pathfinding/modules/pathprocessing/)

---

## 5. 길찾기 알고리즘: A*

### 선택지

| 선택지 | 설명 |
|--------|------|
| Dijkstra | 모든 노드까지의 최단 경로 탐색 |
| **A*** | 휴리스틱을 사용한 목표 지향 탐색 |
| pgRouting (DB 레벨) | DB 내장 라우팅 알고리즘 |
| 가시 그래프 (Visibility Graph) | 장애물 회피 기반 경로 탐색 |

### 선택: Java 내 A* 구현

**이유:**

- **효율성** - A*는 Dijkstra와 달리 목적지 방향으로 우선 탐색합니다. 실내 건물 규모(수백~수천 노드)에서 탐색 노드 수를 크게 줄입니다.

- **3D 휴리스틱** - 층간 이동이 있는 실내 환경에서 유클리드 거리(3D)를 휴리스틱으로 사용하면 다른 층에 있는 목적지도 자연스럽게 처리됩니다.

- **선호도 가중치** - 엣지 유형(수평/계단/엘리베이터)별 가중치를 적용하여 "엘리베이터 선호", "계단 선호" 같은 사용자 요구를 쉽게 반영할 수 있습니다. 이는 A*의 비용 함수만 수정하면 되므로 확장이 간단합니다.

- **서버 사이드 구현** - 경로 그래프를 DB에서 메모리로 로드한 뒤 A*를 실행합니다. 건물 단위로 그래프를 로드하므로 메모리 사용이 제한적이고, Java의 PriorityQueue로 효율적인 구현이 가능합니다.

- **pgRouting을 선택하지 않은 이유** - pgRouting은 도로 네트워크에 최적화되어 있고, 층간 이동이나 선호도 가중치 같은 실내 특화 로직을 구현하기 어렵습니다. 비즈니스 로직이 DB에 묶이는 것도 단점입니다.

### 알고리즘 수도코드

```
findPath(start, goal, preference):
    open = PriorityQueue(by f-score)
    open.add(start, f = heuristic(start, goal))

    while open is not empty:
        current = open.poll()

        if current == goal:
            return reconstructPath()

        for each edge from current:
            cost = edge.getWeightedDistance(preference)
            tentative_g = g[current] + cost

            if tentative_g < g[neighbor]:
                g[neighbor] = tentative_g
                f[neighbor] = tentative_g + heuristic(neighbor, goal)
                open.add(neighbor, f[neighbor])

    return NO_PATH_FOUND
```

> 관련 코드: [AStarPathfinder](../../src/main/java/com/koreatech/indoor_pathfinding/modules/pathfinding/application/service/AStarPathfinder.java)

---

## 6. 위치 추정: VPS (Visual Positioning System)

### 선택지

| 선택지 | 설명 |
|--------|------|
| BLE 비콘 | 블루투스 비콘 기반 삼각측량 |
| Wi-Fi 핑거프린팅 | Wi-Fi 신호 패턴 매칭 |
| UWB (Ultra-Wideband) | 초광대역 통신 기반 정밀 측위 |
| **VPS (Visual Positioning)** | 카메라 이미지 기반 위치 추정 |

### 선택: VPS

**이유:**

- **추가 인프라 불필요** - BLE 비콘이나 UWB는 건물 곳곳에 하드웨어를 설치해야 합니다. VPS는 스마트폰 카메라만 있으면 됩니다.

- **스캔 데이터 활용** - RTAB-Map으로 스캔한 3D 데이터가 이미 있으므로, 이 데이터를 VPS의 기준 맵으로 활용할 수 있습니다. 별도의 측위 인프라 구축 비용이 없습니다.

- **정밀도** - 시각 기반 측위는 환경 특징점이 풍부한 실내에서 높은 정밀도를 보입니다. BLE(수 미터)보다 우수한 정밀도를 기대할 수 있습니다.

- **SLAM과의 시너지** - 스캔(SLAM)과 위치 추정(VPS)이 동일한 시각적 특징을 기반으로 동작하므로, 데이터 파이프라인이 자연스럽게 연결됩니다.

> 관련 코드: [localization 모듈](../../src/main/java/com/koreatech/indoor_pathfinding/modules/localization/)

---

## 7. 모듈 간 통신: 도메인 이벤트

### 선택지

| 선택지 | 설명 |
|--------|------|
| 직접 서비스 주입 | 다른 모듈의 서비스를 @Autowired로 주입 |
| **Spring 도메인 이벤트** | ApplicationEventPublisher 기반 |
| 메시지 큐 | RabbitMQ, Kafka 등 |
| REST API 호출 | 같은 프로세스 내 HTTP 호출 |

### 선택: Spring 도메인 이벤트

**이유:**

- **모듈 독립성** - 직접 주입은 모듈 간 강한 결합을 만듭니다. Scan 모듈이 Localization 모듈을 직접 참조하면, 두 모듈을 독립적으로 변경하기 어렵습니다.

- **적절한 복잡도** - 메시지 큐는 프로세스 간 통신에는 적합하지만, 동일 JVM 내 모듈 간 통신에는 과합니다. Spring의 `ApplicationEventPublisher`는 별도 인프라 없이 이벤트 기반 통신을 제공합니다.

- **트랜잭션 제어** - `@TransactionalEventListener`를 사용하면 메인 트랜잭션이 성공한 후에만 부가 작업을 실행할 수 있습니다.

---

## 8. 파일 저장: 로컬 파일시스템

### 선택지

| 선택지 | 설명 |
|--------|------|
| AWS S3 / GCS | 클라우드 오브젝트 스토리지 |
| **로컬 파일시스템** | 서버 디스크에 직접 저장 |
| MinIO | 자체 호스팅 S3 호환 스토리지 |

### 선택: 로컬 파일시스템 + Docker 볼륨

**이유:**

- **운영 단순성** - 졸업작품 프로젝트로서 클라우드 스토리지의 비용과 설정 복잡도를 피했습니다.

- **대용량 파일 처리** - RTAB-Map .db 파일은 수백 MB에 달합니다. 로컬 저장은 네트워크 지연 없이 Python 서비스와 파일을 공유할 수 있습니다 (Docker 볼륨 마운트).

- **확장 가능성** - Storage 경로를 설정(application.yml)으로 관리하므로, 추후 S3 등으로 전환 시 저장 계층만 교체하면 됩니다.

> 관련 설정: [application.yml의 storage 섹션](../../src/main/resources/application.yml)

---

## 9. 개발/운영 환경 분리: Spring Profiles

### 프로파일 구성

| 프로파일 | DB | 용도 |
|---------|-----|------|
| `default` | H2 (파일 기반) | 로컬 개발. DB 설치 없이 즉시 실행 |
| `docker` | PostgreSQL + PostGIS | Docker 환경. 실제 공간 쿼리 테스트 |
| `test` | PostgreSQL (Testcontainers) | 테스트. 매 실행마다 스키마 초기화 |

**이유:**

- **개발 편의성** - H2를 기본 프로파일로 사용하면 PostgreSQL을 설치하지 않아도 대부분의 개발이 가능합니다.

- **환경 일관성** - Docker 프로파일은 운영 환경과 동일한 PostgreSQL + PostGIS를 사용하므로, 공간 쿼리의 동작을 정확히 검증할 수 있습니다.

- **테스트 격리** - Testcontainers로 매 테스트마다 새로운 PostgreSQL 인스턴스를 생성하므로, 테스트 간 데이터 간섭이 없습니다.

> 관련 설정: [application.yml](../../src/main/resources/application.yml), [application-docker.yml](../../src/main/resources/application-docker.yml), [application-test.yml](../../src/test/resources/application-test.yml)

---

## 10. Application Service 분리 전략

### 선택지

| 선택지 | 설명 |
|--------|------|
| 도메인당 단일 서비스 | `BuildingService` 하나에 모든 메서드 |
| **명령별 서비스 분리** | `BuildingCreator`, `BuildingUpdater`, `BuildingReader` 등 |
| CQRS | Command/Query를 완전 분리 |

### 선택: 명령별 서비스 분리

**이유:**

- **단일 책임** - 한 서비스가 한 가지 유형의 작업만 담당합니다. `BuildingCreator`는 생성만, `BuildingReader`는 조회만 합니다. 서비스 클래스가 비대해지는 것을 방지합니다.

- **변경 영향 최소화** - 생성 로직을 수정해도 조회 로직에 영향을 주지 않습니다. 각 서비스가 작으므로 변경의 범위가 명확합니다.

- **테스트 용이** - 작은 서비스는 테스트하기 쉽습니다. 필요한 의존성만 주입하면 됩니다.

> 관련 코드: 각 모듈의 [application/service/](../../src/main/java/com/koreatech/indoor_pathfinding/modules/) 디렉토리
