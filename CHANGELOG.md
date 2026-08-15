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

**3. 빌드 산출물이 git 에 커밋될 수 있는 상태**

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
