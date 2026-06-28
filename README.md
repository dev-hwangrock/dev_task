# AI Chatbot Backend

OpenAI 기반 AI 챗봇 서비스의 백엔드 API 서버입니다. 사용자 인증, 대화/스레드 관리, 피드백, 분석·보고 기능을 제공합니다.

## 과제 회고

### 과제를 어떻게 분석하셨나요?

먼저 기능 요구사항을 도메인 단위로 정리해 ERD와 패키지 아키텍처를 설계하고, 비기능 요구사항을 정리했습니다.
기능 요구사항 충족을 목표로 1차 구현을 마친 뒤, 실제 OpenAI 호출을 포함한 E2E 테스트로 프로덕션 동작을 검증했습니다.
이후 비기능 요구사항(트랜잭션과 외부 I/O 분리, N+1 제거, 커넥션 풀·스트리밍 최적화 등)을 기준으로 리팩터링하며 완성도를 높였습니다.

### 과제를 진행함에 있어 AI를 어떻게 활용하셨나요? 어떤 어려움이 있었나요?

설계와 우선순위 판단은 직접 하고, 구현은 AI로 빠르게 진행해 시간을 단축했습니다.
다만 AI가 생성한 코드를 그대로 신뢰하지 않고 빌드·테스트·E2E로 검증하는 과정을 반드시 거쳤습니다.
가장 큰 병목은 라이브러리·도구 버전 호환성을 맞추는 부분이었습니다. 환경을 안정화한 뒤로는 구현 속도가 크게 올랐습니다.

### 구현하기 가장 어려웠던 기능을 설명해주세요.

스레드 30분 규칙, 스트리밍, 성능이 한 흐름에 얽혀 있어서 대화 생성 기능이 제일 까다로웠습니다.

특히 처음엔 대화 저장을 트랜잭션 하나로 묶었는데, OpenAI 응답을 기다리는 몇 초 동안 DB 커넥션을 계속 쥐고 있어서 요청이 몰리면 커넥션 풀이 마르는 구조였습니다.
그래서 트랜잭션은 '스레드 결정·히스토리 조회'와 '대화 저장'이라는 짧은 단위만 맡기고, 시간이 걸리는 OpenAI 호출은 트랜잭션 밖으로 분리했습니다.
스트리밍은 별도 스레드로 처리해 이벤트 루프를 막지 않게 하고, 30분 경계(29/30/31분)는 테스트로 먼저 정의했습니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Kotlin 1.9.25 |
| Framework | Spring Boot 3.4.5 |
| Build | Gradle 8.11.1 (Wrapper) |
| JVM | Java 17 |
| Database | PostgreSQL 15.8 |
| Migration | Flyway |
| Auth | JWT (jjwt 0.12.6), Spring Security 6 |
| AI 연동 | OpenAI Chat Completions API |
| HTTP Client | WebClient (Reactor Netty, non-blocking) — 스트리밍/고동시성 최적 |

## 아키텍처 개요

- **도메인형 패키지 구조**: `auth`, `chat`, `feedback`, `analytics` + 공통 인프라(`global`), AI 추상화(`ai`), 엔티티(`domain`)
- **AI Provider 추상화**: `AiProvider` 인터페이스 → `OpenAiClient` 구현. 다른 Provider로 교체 가능
- **인증**: Stateless JWT. 회원가입/로그인 외 전 요청 인증 필터 통과 필요
- **권한 3계층**: JWT 필터(인증) → `@PreAuthorize`(역할) → 서비스(소유권)
- **성능 설계**:
  - 외부 OpenAI 호출을 **트랜잭션 밖으로 분리**(퍼사드 `ChatService` + 트랜잭션 전담 `ChatTransactionService`의 짧은 tx1/tx2). AI 응답 대기 동안 DB 커넥션을 점유하지 않아 풀 고갈 방지
  - 스트리밍은 `publishOn(boundedElastic)`로 Reactor Netty 이벤트루프를 블로킹 작업(JDBC·SSE 전송)으로부터 보호. SSE 타임아웃(130s) + 구독 정리(`onTimeout/onError/onCompletion`)로 누수 차단
  - CSV 보고서는 `JOIN FETCH`(N+1 제거) + JPA `Stream`(전량 적재 대신 청크 스트리밍)으로 처리
  - HikariCP 커넥션 풀 튜닝, OpenAI 호출 재시도(백오프)·타임아웃 계층화

## 사전 요구사항

- JDK 17
- Docker & Docker Compose
- OpenAI API Key (결제가 활성화된 계정, 사용할 모델 접근 권한 필요)

## 실행 방법

### 1. 환경 변수 설정

```bash
cp .env.example .env
# .env 파일을 열어 OPENAI_API_KEY 등 실제 값으로 수정
```

### 2. 데이터베이스 기동

```bash
docker compose up -d      # postgres 15.8 + chatbot / chatbot_test DB 생성
docker compose ps         # healthy 확인
```

### 3. 애플리케이션 실행

```bash
export $(grep -v '^#' .env | xargs)   # .env 환경변수 주입 (또는 IDE EnvFile)
./gradlew bootRun
```

서버는 `http://localhost:8080` 에서 실행됩니다.

## 빌드 & 테스트

```bash
./gradlew build           # 전체 빌드 + 테스트
./gradlew test            # 테스트만 (단위 + 통합, chatbot_test DB 필요)
```

## API 명세

기본 경로: `/api/v1` · 인증: `Authorization: Bearer <token>` (auth 제외 전 요청 필수)

### 인증 (Auth)

| Method | Path | 설명 | 권한 |
|---|---|---|---|
| POST | `/auth/signup` | 회원가입 (email/password/name) → 201 | public |
| POST | `/auth/login` | 로그인 → JWT 발급 (200) | public |

### 대화 (Chat)

| Method | Path | 설명 | 권한 |
|---|---|---|---|
| POST | `/chats` | 대화 생성. `isStreaming=true` 시 SSE 스트리밍, `model`로 모델 지정 | MEMBER+ |
| GET | `/chats?sort=&page=&size=` | 대화 목록 (스레드 단위 그룹화, 정렬·페이지네이션). 본인(관리자는 전체) | MEMBER+ |
| DELETE | `/threads/{threadId}` | 스레드 삭제 (소유자만) → 204 | 소유자 |

- **스레드 규칙**: 첫 질문이거나 마지막 질문 후 30분 경과 시 신규 스레드, 30분 이내면 기존 스레드 유지. OpenAI 호출 시 해당 스레드의 이전 대화를 히스토리로 함께 전송(멀티턴).
- **모델**: 허용 목록 외 또는 `gemini-*`(Google) 모델은 `422 UNSUPPORTED_MODEL`.

### 피드백 (Feedback)

| Method | Path | 설명 | 권한 |
|---|---|---|---|
| POST | `/feedbacks` | 피드백 생성 (chatId, isPositive). 본인 대화만(관리자 전체), 한 대화당 1개 | MEMBER+ |
| GET | `/feedbacks?isPositive=&sort=&page=&size=` | 피드백 목록. 본인(관리자 전체), 긍/부정 필터 | MEMBER+ |
| PATCH | `/feedbacks/{id}/status` | 상태 변경 (pending/resolved) | ADMIN |

### 분석·보고 (Analytics)

| Method | Path | 설명 | 권한 |
|---|---|---|---|
| GET | `/analytics/activity` | 최근 24h 회원가입/로그인/대화생성 수 | ADMIN |
| GET | `/analytics/report` | 최근 24h 전체 대화 CSV 보고서 (생성자 포함) | ADMIN |

### 에러 응답

```json
{ "code": "DUPLICATE_FEEDBACK", "message": "...", "timestamp": "..." }
```

| HTTP | code | 의미 |
|---|---|---|
| 400 | VALIDATION_FAILED | 요청 유효성 실패 |
| 401 | UNAUTHORIZED | JWT 없음/만료 |
| 403 | FORBIDDEN | 권한/소유권 부족 |
| 404 | NOT_FOUND | 리소스 없음 |
| 409 | DUPLICATE_FEEDBACK / DUPLICATE_EMAIL | 중복 |
| 422 | UNSUPPORTED_MODEL | 미지원 모델 |
| 502 | AI_API_ERROR | OpenAI 호출 실패 |

## 환경 변수

| 변수명 | 설명 | 기본값 |
|--------|------|--------|
| `DB_HOST` | PostgreSQL 호스트 | `localhost` |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | 데이터베이스 이름 | `chatbot` |
| `DB_USER` | DB 사용자명 | `chatbot` |
| `DB_PASSWORD` | DB 비밀번호 | `chatbot` |
| `DB_POOL_MAX` | HikariCP 최대 커넥션 수 | `20` |
| `DB_POOL_MIN` | HikariCP 최소 유휴 커넥션 수 | `5` |
| `JWT_SECRET` | JWT 서명 키 (Base64, 256bit+) | (로컬 기본값 제공) |
| `OPENAI_API_KEY` | OpenAI API 키 | — (필수) |
| `PORT` | 서버 포트 | `8080` |

## 테스트 구성

- **단위 테스트** (MockK): JWT, 스레드 30분 규칙(경계값), 모델 검증, 퍼사드 트랜잭션 호출 순서, 피드백 소유권/중복, 분석 집계
- **통합 테스트** (`@SpringBootTest` + 실 PostgreSQL `chatbot_test`): 인증/대화/피드백 E2E, 권한, 상태코드
- 총 55개 테스트 (전부 통과)
