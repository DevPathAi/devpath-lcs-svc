# devpath-lcs-svc

**DevPath AI** LCS(Learning Context Snapshot) 서비스 — 질문 작성 시 사용자의 현재 학습 맥락을 스냅샷으로 조립해 불변 첨부합니다(MD3 슬라이스 #9).

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| draft | 스냅샷 초안 조립(질문 작성 중 미리보기) |
| domain | 스냅샷·프라이버시 설정 도메인 모델 |
| client | 학습 시스템 등 소스오브트루스 조회 클라이언트 |
| service | 스냅샷 draft/commit, preferences 비즈니스 로직 |
| api | HTTP 엔드포인트 |

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/lcs/snapshots/draft` | 질문 작성 시 미리보기 스냅샷 생성 |
| POST | `/lcs/snapshots/{draftId}/commit` | 질문 게시 시 영속화 |
| GET | `/lcs/snapshots/{id}` | 스냅샷 조회 |
| GET | `/lcs/snapshots/by-question/{questionId}` | 질문 ID로 스냅샷 역조회 |
| GET | `/lcs/preferences` | 유저 프라이버시 설정 조회 |
| PUT | `/lcs/preferences` | 유저 프라이버시 설정 변경 |

상세 설계: [documents/26_학습맥락_자동첨부_구현](https://github.com/DevPathAi/documents/blob/main/26_학습맥락_자동첨부_구현.md)

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- [devpath-svc-template](https://github.com/DevPathAi/devpath-svc-template) 기반
- DB: `learning_context_snapshots`, `user_context_preferences` (devpath-shared Flyway, `flyway.enabled: false`로 devpath-shared 중앙 마이그레이션에 위임하고 이 서비스는 validate만)

## 빌드 / 실행

```bash
./gradlew build
./gradlew bootRun    # 기본 포트 8080 (gateway 라우트 대상은 LCS_SVC_URI, 기본 http://localhost:8087)
```

로컬 인프라는 [devpath-shared](https://github.com/DevPathAi/devpath-shared)의 docker-compose를 사용합니다.

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 코드 리뷰: [documents/12_코드_리뷰_규칙](https://github.com/DevPathAi/documents/blob/main/12_코드_리뷰_규칙.md)
- 테스트 전략: [documents/11_테스트_전략서](https://github.com/DevPathAi/documents/blob/main/11_테스트_전략서.md)
