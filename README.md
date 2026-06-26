# devpath-svc-template

**DevPath AI** 백엔드 서비스 공통 스켈레톤 템플릿입니다. 새 `devpath-*-svc` 레포는 이 템플릿을 복제해서 시작합니다.

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- 기본 스타터: WebMVC, Actuator, Validation, Lombok
- 선택 의존성(JPA + PostgreSQL, Redis, Security, Kafka)은 `build.gradle.kts`에 주석으로 포함 — 서비스 특성에 맞게 해제
- `docs/project-management/` — [workflow-dashboard](https://github.com/DevPathAi/workflow-dashboard) 동기화 대상 디렉터리

## 새 서비스 만들기

1. 이 레포 내용을 새 레포로 복사
2. 치환:
   - `settings.gradle.kts`의 `rootProject.name`
   - `build.gradle.kts`의 `description`
   - 패키지 `ai.devpath.template` → `ai.devpath.<도메인>`
   - 메인 클래스 `SvcTemplateApplication` → `<도메인>Application`
3. `application.yml`의 `spring.application.name` 수정
4. 필요한 의존성 주석 해제

## 빌드 / 실행

```bash
./gradlew build        # 빌드 + 테스트
./gradlew bootRun      # 로컬 실행 (기본 포트 8080)
```

로컬 인프라(PostgreSQL, Redis, Kafka 등)는 [devpath-shared](https://github.com/DevPathAi/devpath-shared)의 docker-compose를 사용합니다.

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 코드 리뷰: [documents/12_코드_리뷰_규칙](https://github.com/DevPathAi/documents/blob/main/12_코드_리뷰_규칙.md)
- 테스트 전략: [documents/11_테스트_전략서](https://github.com/DevPathAi/documents/blob/main/11_테스트_전략서.md)
