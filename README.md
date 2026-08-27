# Kyuchestration

**뀨케스트레이션** — "뀨" + 오케스트레이션.

여러 레포가 하나의 목표로 묶여 움직일 때, 각 레포의 독립성을 유지한 채 조율하는 CLI 도구 `wd`.

- 설계: [workdir-orchestrator-design.md](workdir-orchestrator-design.md)
- 핵심 원칙: **프로세스는 격리, 지식은 공유**
- 스택: Go + tmux 백엔드

## 구조

```
cmd/wd/          # 진입점, 명령 라우팅
internal/
    session/     # SessionBackend 인터페이스 + tmux 구현 (유일한 플랫폼 의존부)
    workdir/     # 스캔 · 상태 추론 · plan 파싱 (플랫폼 무관 핵심)
    cli/         # 명령 구현
```

## 개발

```sh
go test ./...
go build ./cmd/wd
```
