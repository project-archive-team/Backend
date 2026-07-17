# Backend

프로젝트 아카이브 에이전트의 Spring 백엔드. 멀티 소스 수집 · 파일 파싱 · 청킹 · 인증/DB · AI 서버 오케스트레이션을 담당한다.
분석 · 임베딩 · 벡터 검색 · 에이전트는 [AI 서버(FastAPI)](#ai-서버-contract) 책임이며, 이 저장소에는 벡터 DB가 없다.

## 실행

```bash
docker compose up -d          # postgres + app
./gradlew bootRun             # 앱만 (postgres는 따로 떠 있어야 함)
./gradlew build               # 빌드 + 테스트 (Testcontainers → Docker 필요)
```

기본 포트 8080, DB는 `localhost:5432/archive`.

## 환경변수

미설정이어도 개발용 기본값으로 뜨지만, **배포 시 아래는 반드시 채워야 한다.**

| 변수 | 설명 |
|------|------|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Postgres 접속 |
| `JWT_SECRET` | 32바이트 이상. 짧으면 부팅 실패 |
| `CRYPTO_PASSWORD` / `CRYPTO_SALT` | OAuth 토큰 암호화 키. salt는 hex |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth 앱 |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth 앱 |
| `AI_BASE_URL` | FastAPI 주소 |
| `CORS_ORIGINS` / `OAUTH_REDIRECT_URI` | 프론트 주소 |

## API

인증은 `Authorization: Bearer <accessToken>`.

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/auth/signup` `/login` `/refresh` | 이메일 로그인 |
| GET | `/api/auth/me` | 내 정보 |
| GET | `/oauth2/authorization/{github\|google}` | OAuth 로그인 시작 (프론트가 직접 연다) |
| GET/POST | `/api/projects` | 목록 / 생성 |
| GET/DELETE | `/api/projects/{id}` | 상세 / 삭제 |
| POST/DELETE | `/api/projects/{id}/sources[/{sourceId}]` | 소스 연결 / 해제 |
| POST | `/api/projects/{id}/files` | 파일 업로드 (pdf, pptx, md, txt) |
| POST | `/api/projects/{id}/sync` | 수집 시작 (비동기, 202) |
| GET | `/api/projects/{id}/sync` | 수집 진행상황 폴링 |
| GET | `/api/projects/{id}/artifacts?type=` | 산출물 목록 |
| GET | `/api/projects/{id}/timeline` | 타임라인 |
| GET/POST | `/api/projects/{id}/chat` | RAG Q&A (출처 포함) |
| GET | `/api/projects/{id}/summary?days=7` | 회의록·커밋 요약 |
| POST | `/api/projects/{id}/interview` | 자소서·면접 답변 초안 |
| GET/PUT | `/api/integrations[/notion]` | 연결 상태 / Notion 토큰 등록 |

OAuth 로그인 성공 시 `OAUTH_REDIRECT_URI?accessToken=..&refreshToken=..` 으로 리다이렉트한다.

## AI 서버 contract

백엔드가 FastAPI에 기대하는 인터페이스. **AI 담당은 이 4개만 구현하면 된다.**

```
POST /index      { projectId, chunks: [{ artifactId, type, title, path, url, author, occurredAt, seq, text }] }
              →  { indexed: int, techStack: [str] }

POST /chat       { projectId, question }
              →  { answer, citations: [{ artifactId, title, url, snippet }] }

POST /summary    { projectId, since }         # since 이후 활동 요약
              →  { summary }

POST /interview  { projectId, question }
              →  { answer, citations: [...] }
```

`type`은 `COMMIT | CODE | DOC | MEETING`. 청킹은 백엔드가 하고(1000자, 150자 오버랩), 임베딩·검색은 AI 서버가 한다.
`/index`가 돌려준 `techStack`은 프로젝트 기술스택에 합집합으로 병합된다.

## 구조

```
auth/     JWT · OAuth2 로그인 · 토큰 암호화
collect/  소스별 수집기(GitHub/Drive/Notion) · 파일 파싱 · 동기화 오케스트레이션
ai/       청킹 · FastAPI 클라이언트 · Q&A/요약/면접 엔드포인트
project/  프로젝트 · 소스 · 산출물 · 타임라인 API
domain/   JPA 엔티티
repo/     리포지토리
```

설계상 알아둘 것:

- **타임라인은 테이블이 없다.** `artifacts.occurred_at` 정렬로 파생한다.
- **청크는 저장하지 않는다.** 본문이 `artifacts.content`에 있어 언제든 다시 자를 수 있다.
- **소스는 각각 독립 트랜잭션**이라 하나가 실패해도(`sources.status=FAILED`) 나머지는 커밋된다 — "자료가 일부만 있어도 동작".
- **업로드 원본은 보관하지 않는다.** 파싱한 텍스트만 남긴다.
- 스키마 소유권은 Flyway(`db/migration`)에 있고 Hibernate는 `validate`만 한다.
