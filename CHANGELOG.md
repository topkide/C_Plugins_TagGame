# 변경 이력

## 1.0.1

빌드 파이프라인(GitHub Actions) 도입 및 빌드 실패 원인 수정. **게임 동작 변경 없음.**

### 수정된 빌드 문제

**1. `gradlew` 실행 권한 누락 → Linux 빌드 실패**

`gradlew` 파일이 git 에 실행 권한 없는 모드(`100644`)로 커밋되어 있었습니다.
Windows 에서는 문제가 없지만, Linux(GitHub Actions `ubuntu-latest`) 나 macOS 에서
체크아웃 후 `./gradlew build` 를 실행하면 다음과 같이 실패합니다.

```
/bin/bash: line 1: ./gradlew: Permission denied
```

→ git 파일 모드를 `100755` 로 변경(`git update-index --chmod=+x gradlew`)했고,
워크플로에도 `chmod +x gradlew` 단계를 넣어 이중으로 방어했습니다.

**2. GitHub Actions 액션 버전이 Node.js 20 기반 → deprecated 경고**

최초 워크플로가 사용한 액션들이 지원 종료된 Node.js 20 런타임을 대상으로 하고 있어
빌드 로그에 아래 경고가 출력되었습니다.

```
setup-java v4 is deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5.
Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24:
  actions/checkout@v4, actions/setup-java@v4, actions/upload-artifact@v4, gradle/actions/setup-gradle@v4
```

→ 현재 지원되는 메이저 버전으로 올렸습니다.

| 액션 | 이전 | 변경 |
|---|---|---|
| `actions/checkout` | v4 | v7 |
| `actions/setup-java` | v4 | v5 |
| `actions/upload-artifact` | v4 | v7 |
| `gradle/actions/setup-gradle` | v4 | v6 |

**3. 의존성 jar 손상으로 인한 간헐적 컴파일 실패**

CI 에서 아래 오류와 함께 `compileJava` 가 17개 에러로 실패하는 현상이 발생했습니다.

```
error: error reading .../net.kyori/examination-string/1.3.0/examination-string-1.3.0.jar;
       zip END header not found
error: cannot access com.dotorimaru.taggame
error: cannot find symbol  symbol: class CommandSender
error: cannot find symbol  symbol: class List      ← java.util.List 까지 못 찾음
```

Adventure 의 전이 의존성 jar 하나가 **깨진 채로 다운로드**되어, javac 이 컴파일
클래스패스 전체를 읽지 못하고 `java.util.List` 같은 JDK 클래스까지 해석에
실패한 것이 원인입니다. 소스 코드 문제가 아니며(동일한 소스가 직전 커밋에서
정상 빌드됨), Gradle 캐시 복원도 없었으므로 다운로드 자체가 간헐적으로
손상된 파일을 받은 경우입니다.

캐시를 비우고 `--refresh-dependencies` 로 다시 받아도 **똑같이 깨진 파일**이
내려왔습니다. 즉 일시적 네트워크 문제가 아니라 특정 저장소가 손상된 아티팩트를
계속 서빙하는 상황이었습니다. 문제의 `net.kyori:examination-string` 은
paper-api 의 전이 의존성이고 Maven Central 에도 존재하는데,
`build.gradle` 이 PaperMC 저장소를 먼저 조회하도록 되어 있어
Paper 미러의 깨진 사본을 받고 있었습니다.

→ `repositories` 순서를 바꿔 `mavenCentral()` 을 먼저 조회하도록 했습니다.
`paper-api` 자체는 Central 에 없으므로 PaperMC 저장소에서 그대로 해석됩니다.

→ 추가로 두 워크플로의 빌드 단계에서 해당 오류 패턴이 감지되면
`~/.gradle/caches/modules-2` 를 삭제하고 `--refresh-dependencies` 로
**1회만 재시도**하도록 했습니다. 그 외의 실패(실제 컴파일 에러 등)는
재시도 없이 즉시 실패합니다.

**4. 빌드 산출물이 git 에 커밋될 수 있는 상태**

`.gitignore` 가 없어 `.gradle/`, `build/` 같은 로컬 빌드 캐시가 실수로 커밋될 수
있었습니다. → `.gitignore` 추가.

### 추가된 것

- `.github/workflows/build.yml` — push / PR / 수동 실행 시 JDK 21 로 빌드하고
  `TagGame-<version>.jar` 을 아티팩트로 업로드
- `.github/workflows/release.yml` — `v*` 태그 push 또는 수동 실행 시 빌드하여
  GitHub 릴리스에 jar 첨부

### 알려진 경고 (수정하지 않음)

컴파일 시 아래 참고 메시지가 출력되지만 빌드는 정상 성공합니다.

```
Note: GameManager.java uses or overrides a deprecated API.
```

`BossBar` / `BarColor` / `ChatColor` 등 레거시 Bukkit API 사용으로 인한 것입니다.
Paper 1.21.4 에서 정상 동작하며, Adventure API 로 교체하는 것은 동작 변경 위험이 있어
이번 버전에서는 손대지 않았습니다.

## 1.0.0

최초 릴리스.
