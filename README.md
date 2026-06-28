# AI Chatbot Backend

OpenAI 기반 AI 챗봇 서비스의 백엔드 API 서버입니다. 사용자 인증, 대화/스레드 관리, 피드백, 분석·보고 기능을 제공합니다.

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
