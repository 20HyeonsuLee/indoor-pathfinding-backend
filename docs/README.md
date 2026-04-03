# 실내 길찾기 백엔드 시스템 문서

> 한국기술교육대학교 컴퓨터공학부 졸업작품 프로젝트

**3D 스캔 데이터 기반 실내 내비게이션 시스템**의 백엔드 서버입니다.

---

## 문서 구조

### 아키텍처 (`architecture/`)

| 문서 | 설명 |
|------|------|
| [시스템 개요](./architecture/overview.md) | 전체 시스템 구조, 서비스 간 관계, 데이터 흐름, 길찾기 알고리즘 |
| [모듈 설계](./architecture/module-design.md) | 도메인별 모듈 구조와 책임 (7개 모듈 + shared) |
| [기술 결정](./architecture/tech-decisions.md) | 주요 기술 선택지와 선택 근거 |

### API (`api/`)

| 문서 | 설명 |
|------|------|
| [엔드포인트](./api/endpoints.md) | 현재 구현된 전체 REST API 목록 |

### 인프라 (`infrastructure/`)

| 문서 | 설명 |
|------|------|
| [인프라/배포](./infrastructure/setup.md) | Docker 구성, DB 초기화, 환경 프로파일, 빌드 설정 |

### 가이드 (`guide/`)

| 문서 | 설명 |
|------|------|
| [RTAB-Map 처리 파이프라인](./guide/rtab-processing.md) | Python 경로 추출 서비스의 7단계 처리 과정 + 설계 결정 기록 |
| [PLY 포인트클라우드 추출](./guide/ply-extraction.md) | PLY 추출 옵션 튜닝, 헤더 동적 파싱, 좌표계 변환 |
| [관리자 웹 FPS 모드](./guide/admin-web-fps.md) | 1인칭 시점 탐색, 단축키, 노드/엣지/POI 편집, 계단 연결 |

### 계획 (`plan/`)

| 문서 | 설명 | 상태 |
|------|------|------|
| [층별 청크 스캔 관리](./plan/floor-scan-chunk/00_master_plan.md) | 층별 분할 업로드 + 서버 병합 체계 전환 계획 | Proposed |

### 연구노트 (`research/`)

| 문서 | 설명 |
|------|------|
| [1회차](./research/연구노트_1회차.md) | 배경, 시스템 설계, 제약 조건 |
| [2회차](./research/연구노트_2회차.md) ~ [5회차](./research/연구노트_5회차.md) | 기능 구현 진행 |
| [6회차](./research/연구노트_6회차.md) | 통합 테스트, 성능 측정 |
| [7회차](./research/연구노트_7회차.md) | 백엔드 구현, 노드/엣지 구성 방식 진화, 중간발표 준비 |

---

## 빠른 시작

```bash
# Docker Compose로 전체 시스템 실행
docker-compose up -d

# API 문서 확인
open http://localhost:8080/swagger-ui.html
```

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| 백엔드 프레임워크 | Spring Boot 4.0.2 (Java 25) |
| 데이터베이스 | PostgreSQL + PostGIS |
| 경로 추출 | Python FastAPI |
| 위치 추정 | VPS (Visual Positioning System) |
| 컨테이너화 | Docker + Docker Compose |
