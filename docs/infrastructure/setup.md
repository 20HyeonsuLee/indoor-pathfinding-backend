# 인프라와 배포

> Docker 구성, 데이터베이스 초기화, 배포 환경, 개발/운영 프로파일을 설명합니다.

---

## 1. Docker 구성

전체 시스템은 **Docker Compose**로 한 번에 실행됩니다.

```
docker-compose.yml
├── indoor-pathfinding-db    PostgreSQL + PostGIS
├── indoor-path-service      Python FastAPI (경로 추출)
└── indoor-spring-app        Spring Boot (메인 백엔드)

네트워크: indoor-network (bridge)
```

### 서비스 간 의존성

```
PostgreSQL (헬스체크 통과)
    |
    ├──> Python FastAPI (DB 의존)
    |         |
    └─────────┴──> Spring Boot (DB + FastAPI 의존)
```

Spring Boot는 PostgreSQL과 Python FastAPI가 모두 준비된 후에야 시작됩니다.

### 볼륨 구성

```
postgres_data (영구)     PostgreSQL 데이터 (컨테이너 재시작해도 유지)

./storage/uploads/       스캔 파일 (Spring ↔ Python 공유)
./storage/output/        처리 결과 (Python → Spring)
./storage/maps/          맵 데이터 (Spring 전용)
./storage/images/        추출 이미지 (Spring 전용)
```

**핵심 설계**: `uploads`와 `output` 디렉토리는 Spring Boot와 Python 서비스가 **동일 볼륨을 마운트**합니다. 이로써 대용량 .db 파일을 네트워크로 전송하지 않고 파일시스템을 통해 공유합니다.

> 관련 파일: [docker-compose.yml](../docker-compose.yml)

---

## 2. 컨테이너별 상세

### PostgreSQL + PostGIS

```
이미지: pgrouting/pgrouting:latest
포트: 5432
DB명: indoor_pathfinding
계정: indoor / indoor1234
```

초기화 시 다음 PostgreSQL 확장을 활성화합니다:
- **PostGIS** - 공간 데이터 타입 (POINT, LINESTRING 등)
- **PostGIS Topology** - 위상 관계 처리
- **pgRouting** - 그래프 기반 경로 탐색

초기화 스크립트가 `indoor` 스키마를 생성하고 권한을 부여합니다.

> 관련 파일: [01-init.sql](../docker/postgres/init/01-init.sql)

### Python FastAPI (경로 추출 서비스)

```
빌드: ./rtab/path_service/Dockerfile
베이스: Python 3.11-slim
포트: 8000
엔트리: uvicorn main:app
```

RTAB-Map .db 파일에서 이동 가능한 경로를 추출하고, 노드/엣지 형태로 반환합니다.

> 관련 파일: [rtab/path_service/Dockerfile](../rtab/path_service/Dockerfile)

### Spring Boot (메인 백엔드)

```
빌드: 2-stage Dockerfile
  Stage 1: Eclipse Temurin JDK 25 (빌드)
  Stage 2: Eclipse Temurin JRE 25 (런타임)
포트: 8080
프로파일: docker
```

멀티 스테이지 빌드로 이미지 크기를 최소화합니다:
1. JDK 이미지에서 `./gradlew bootJar`로 JAR 빌드
2. JRE 이미지에 JAR만 복사하여 실행

> 관련 파일: [Dockerfile](../Dockerfile)

---

## 3. 환경 프로파일

### 로컬 개발 (`default`)

```
DB: H2 (파일 기반, ./data/indoor_pathfinding)
H2 콘솔: http://localhost:8080/h2-console
서비스: localhost:8000 (Python), localhost:5000 (VPS)
DDL: update (스키마 자동 갱신)
```

H2를 사용하므로 **DB 설치 없이 즉시 개발**할 수 있습니다.
단, PostGIS 공간 함수는 H2에서 지원하지 않으므로, 공간 쿼리 테스트는 Docker 환경에서 수행해야 합니다.

> 관련 파일: [application.yml](../src/main/resources/application.yml)

### Docker 운영 (`docker`)

```
DB: PostgreSQL + PostGIS (indoor-pathfinding-db:5432)
Dialect: PostgisPG10Dialect (공간 함수 지원)
서비스: indoor-path-service:8000, indoor-pathfinding-web:5000
DDL: update
로깅: TRACE (SQL 파라미터 바인딩까지)
```

Docker 내부 네트워크에서 **컨테이너 이름으로 서비스를 참조**합니다.

> 관련 파일: [application-docker.yml](../src/main/resources/application-docker.yml)

### 테스트 (`test`)

```
DB: PostgreSQL (Testcontainers가 자동 생성)
DDL: create-drop (매 실행마다 스키마 초기화)
저장소: ./build/test-storage/ (빌드 디렉토리)
```

**Testcontainers**가 테스트 실행 시 임시 PostgreSQL 컨테이너를 자동으로 생성하고, 테스트 종료 후 제거합니다. 실제 PostgreSQL + PostGIS 환경에서 테스트하므로 공간 쿼리도 검증됩니다.

> 관련 파일: [application-test.yml](../src/test/resources/application-test.yml)

---

## 4. ORB-SLAM3 환경 (선택적)

별도의 Docker 환경으로, SLAM 처리를 직접 실행할 때 사용합니다.

```
orb-slam3/
├── Dockerfile            ORB-SLAM3 빌드 (Ubuntu 20.04 기반)
├── docker-compose.yml    GUI 지원 컨테이너 설정
└── scripts/
    ├── build.sh          이미지 빌드 (20-40분)
    ├── run.sh            X11 포워딩 + 컨테이너 시작
    ├── run-gui.sh        macOS GUI 설정 (XQuartz)
    ├── stop.sh           컨테이너 중지
    └── exec.sh           컨테이너 접속
```

### 빌드 구성

ORB-SLAM3 Docker 이미지는 다음을 소스에서 컴파일합니다:
- **Pangolin** v0.6 - 시각화 라이브러리
- **OpenCV** 4.4.0 - 컴퓨터 비전 (Qt + OpenGL 지원)
- **ORB-SLAM3** - Visual SLAM 알고리즘

메모리 사용량이 크므로 `make -j2`로 병렬도를 제한합니다.

### GUI 지원

macOS에서는 **XQuartz**를 통해, Linux에서는 **X11 소켓 마운트**를 통해 SLAM 시각화 GUI를 호스트에서 볼 수 있습니다.

> 관련 파일: [orb-slam3/](../orb-slam3/)

---

## 5. 빌드 설정 요약

| 항목 | 값 |
|------|-----|
| 빌드 도구 | Gradle (Kotlin DSL) |
| Java 버전 | 25 (Eclipse Temurin) |
| Spring Boot | 4.0.2 |
| 아티팩트 | bootJar (실행 가능 JAR) |
| 테스트 | JUnit 5 + Testcontainers |

### 주요 의존성

| 카테고리 | 라이브러리 |
|---------|-----------|
| 웹 | Spring WebMVC, Spring WebFlux (HTTP 클라이언트) |
| 데이터 | Spring Data JPA, PostgreSQL, H2 |
| 공간 | Hibernate Spatial, JTS Core, Geolatte |
| 파일 | Commons IO, SQLite JDBC (RTAB-Map 읽기) |
| 문서 | SpringDoc OpenAPI (Swagger UI) |
| 테스트 | Testcontainers, JUnit 5 |

> 관련 파일: [build.gradle.kts](../build.gradle.kts)

---

## 6. API 문서

Swagger UI가 자동 생성되어 모든 API를 브라우저에서 테스트할 수 있습니다.

```
Swagger UI:  http://localhost:8080/swagger-ui.html
OpenAPI JSON: http://localhost:8080/v3/api-docs
```

또한, VPS 서비스의 API는 별도의 OpenAPI 명세([VPS.json](../VPS.json))로 관리됩니다.

---

## 7. 실행 방법

### Docker Compose (권장)

```bash
# 전체 시스템 시작
docker-compose up -d

# 로그 확인
docker-compose logs -f indoor-spring-app

# 중지
docker-compose down
```

### 로컬 개발

```bash
# Spring Boot만 실행 (H2 DB 사용)
./gradlew bootRun

# Python 서비스 별도 실행 필요
cd rtab/path_service && uvicorn main:app --port 8000
```

### 테스트

```bash
# 전체 테스트 (Testcontainers가 PostgreSQL 자동 생성)
./gradlew test
```
