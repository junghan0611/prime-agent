# Phase A dev environment — Nix flake

Author: claude-opus-5 (Claude Code, 2026-08-29) — not GLG direct; review as a separate viewpoint.

`feat/clojure-runtime` Phase A 구현팀이 바로 들어갈 개발환경. 이 문서는 환경만 다룬다.
runtime 설계는 `docs/clojure-runtime.md`, 좌표는 `NEXT--feat_clojure-runtime.md`.

## 파일

| 파일 | 역할 |
|---|---|
| `flake.nix` | devShell 3개 + `packages.fhs` (native-image 빌드용 FHS) |
| `flake.lock` | 버전 핀. nixpkgs `nixos-26.05` (`d57af92`, 2026-08-27) |
| `.envrc` | `use flake` — direnv 자동 진입 |
| `.gitignore` | `.direnv/`, `result`, `.cpcache/`, `prime-agent-runtime-clj/target/` 추가 |

## 진입

```bash
direnv allow          # 최초 1회. 이후 cd 만으로 자동 진입
# 또는
nix develop           # 기본 shell (Clojure + GraalVM + Node)
nix develop .#jvm     # Clojure 만 (jdk21, native-image 없음)
nix develop .#node    # Node/biome 만 (기존 TS check 자리)
```

## shell 구성

측정값 (2026-08-29, x86_64-linux, `nix develop` 안에서 실측):

| 도구 | 버전 | 자리 |
|---|---|---|
| GraalVM CE `native-image` | 25.0.2 | Clojure source → native executable |
| `java` (graalvm) | openjdk 25.0.2 | **빌드타임 전용** |
| `clojure` CLI | 1.12.5.1645 | deps/test/build |
| `babashka` | v1.12.218 | SCI + native-image 선행 구현. 스크립트/참고선 |
| `clj-kondo` | v2026.01.19 | lint |
| `cljfmt` | 0.16.4 | format |
| `node` / `npm` | v24.19.0 / 11.17.0 | 기존 TypeScript 축 |
| `biome` | 2.4.15 | fallback lint/format — 아래 주의 참조 |

`JAVA_HOME` / `GRAALVM_HOME` 은 기본 shell에서 graalvm 으로 export 된다.
`.#jvm` 은 `jdk21_headless` 로, native-image 가 필요 없는 가벼운 자리다.

## 검증 명령

```bash
# 환경
nix develop --command bash -c 'native-image --version; clojure -Sdescribe | head -1; node --version'

# native-image 빌드 자리 (FHS — 표준 경로 인터프리터)
nix build .#fhs && ./result/bin/prime-agent-clj-build -c 'native-image --version'

# TypeScript/Node (node_modules 가 채워진 뒤)
nix develop .#node --command bash -c 'npm ci && npm run check'
```

## 실측으로 확인한 것 (2026-08-29)

Phase A 의 핵심 기제 — **Clojure source → GraalVM native-image → SCI persistent context,
실행 시 JVM 없음** — 을 scratchpad 에서 끝까지 통과시켰다. 리포 안에는 runtime source 를
넣지 않았다.

- `sci 0.9.44` 를 `clojure -M` 으로 평가: `(def x 41)` 후 다른 eval 에서 `(inc x)` → `42`.
  같은 `sci/init` context 가 cell 사이 binding 을 유지한다.
- 같은 코드를 native-image 로 빌드: 28.7s, 산출물은 ELF pie executable.
- native binary 실행 결과 동일 (`sci= 42`). JVM 프로세스 없음.

### native-image 플래그 — 먼저 밟은 함정

`--initialize-at-build-time` 없이 빌드하면 링크는 되지만 실행 시 죽는다:

```text
java.io.FileNotFoundException: Could not locate clojure/core__init.class,
clojure/core.clj or clojure/core.cljc on classpath.
```

최소 동작 플래그 (실측):

```bash
native-image -cp "$(clojure -Spath):classes" smoke.main target/smoke \
  --no-fallback --initialize-at-build-time -H:+ReportExceptionStackTraces
```

운영 플래그 셋의 검증된 선례는 `~/repos/work/voscli/build.clj:75-131` (`native-image-args` /
`native` task) — `-O3`, `-march`, `--enable-url-protocols`, TLS/SecureRandom 의
`--initialize-at-run-time` 분리가 거기 이유와 함께 적혀 있다. HTTP/TLS 를 열 때 그 파일을 먼저 본다.

## 주의 — biome 버전 skew

`package.json` 은 `@biomejs/biome 2.5.5`, nixpkgs 26.05 는 `2.4.15` 다.
`npm run check` 는 npm script PATH 규칙상 `node_modules/.bin` 을 먼저 잡으므로
`npm ci` 를 돌린 뒤에는 **리포가 고정한 2.5.5 가 정본**이다.
nixpkgs biome 는 `node_modules` 가 비었을 때 `.husky/pre-commit` 이
`biome: command not found` 로 죽지 않게 하는 fallback 일 뿐이다.
node_modules 없이 `npm run check` 를 돌려 `--write` 로 포맷을 덮지 말 것.

`npm ci` 는 Nix 가 대신하지 않는다. `tsgo`(`@typescript/native-preview`), vitest 등은
nixpkgs 에 없고 `.npmrc` 의 `min-release-age=7` 도 npm 쪽 규칙이다.

## 근거가 된 기존 flake 패턴

| 파일 | 가져온 것 |
|---|---|
| `~/repos/work/voscli/flake.nix:13-31,38-62` | graalvm-ce + `buildFHSEnv` + `JAVA_HOME/GRAALVM_HOME` + default/jvm 2단 shell |
| `~/repos/gh/dictcli/flake.nix:16-31,38-58` | FHS targetPkgs 에 `zlib.static`/`glibc.static` 까지 |
| `~/repos/gh/geworfen/flake.nix:38,57` | 두 shell 모두에 `babashka` |
| `~/repos/gh/proxycli/flake.nix`, `~/repos/gh/claude-code-openai-wrapper/clj-wrapper/flake.nix` | 같은 골격의 반복 — 최소 셋 확인 |
| `~/repos/gh/memex-kb/flake.nix:65-68` | nix 에는 `nodejs` 런타임만, npm 패키지는 shell 안에서 npm 이 |
| `~/repos/gh/andenken/flake.nix:15-30` | node-only shell 의 shellHook 문구 형태 |
| `~/repos/work/incidentcli/flake.nix:81-82` | 반례 — JVM 배포가 목표면 GraalVM shell 을 두지 않는다 |

nixpkgs 채널만 기존 리포의 `nixos-25.11` 이 아니라 `nixos-26.05` 를 쓴다 (GLG 지시, 2026-08-29).

## 하지 않은 것

- Clojure runtime 구현 (`prime-agent-runtime-clj/` scaffold 없음)
- `deps.edn` / `build.clj` 작성 — 구현팀 몫
- `npm install` / `npm ci` 실행 — 환경만 잡았고 `node_modules/` 는 여전히 비어 있다
- Python source, TypeScript host 수정
- commit / push
