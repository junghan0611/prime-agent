---
name: prime-agent
description: "prime-agent 포크(Clojure/SCI 팔 vs Python oracle 비교 실험)의 운영 면 — 실제로 팔을 띄우고·재고·거둘 때. AGENTS.md 가 '규칙', NEXT.md 가 '지금 좌표', 이슈 #1 이 '영수증'을 담는다면 이 스킬은 그 문서들이 front-load 못 하는 운영 반사신경을 담는다: 세션 시작·끝의 자기수선 루틴(./run.sh tidy), daemon 이 클라이언트보다 오래 사는 계약, run.sh 가 유일한 입구라는 것, 두 팔 격리 영수증, 유료 축을 실수로 건드리지 않는 법. 트리거: 'prime-agent', 'RLM', 'rlm-repl', 'clojure runtime', 'SCI', '두 팔', 'BENCH', 'BASELINE', '팔 띄워', 'daemon', '데몬 남았', '프로세스 잔재', 'orphan', '정리', '거둬', 'reap', 'tidy', '자기수선', '메모리 잡아먹', 'run.sh', 'native-image', 'GraalVM'."
user_invocable: true
---

# prime-agent — 오퍼레이터의 운영 면

Repo: `~/repos/gh/prime-agent`. **상류 [PrimeIntellect-ai/prime-agent](https://github.com/PrimeIntellect-ai/prime-agent) 의 포크**이고,
Prime Agent 의 persistent RLM workspace 를 **Clojure/SCI 로 세워 Python oracle 과 비교하는 실험**이다.

> ⚠️ 이건 제품 개선 리포가 아니다. **측정 리포**다. 그래서 두 팔이 같이 있어야 하고
> (Python oracle 을 지우지 않는다), 커버리지 없이 우위를 말하지 않는다.
> *무엇을* 하느냐는 `AGENTS.md`, *지금 어디*냐는 `NEXT.md`. 이 스킬은 **매번 다시 당하는 운영 반사신경**이다.

## 0. 먼저 — 주변부터 정리한다

작업을 **시작할 때와 끝낼 때** 한 번씩:

```bash
./run.sh tidy
```

읽고 → 거두고 → 죽은 소켓을 훑고 → 다시 읽는다. **눈 감고 불러도 되게 만들었다** —
세션이 붙은 daemon 은 멈추지 않는다(§3). 끝 상태가 화면에 남는 것이 요점이다.

왜 이게 0번인가는 §3 에 있다. 요약하면: **이 리포의 팔은 클라이언트보다 오래 산다.**

## 1. 문서 라우팅 — 뭘 열까

| 작업 맥락 | 펼칠 문서 |
|---|---|
| 이 포크의 규칙·금지·Hard Rules | `AGENTS.md` (**먼저**) |
| 코드 품질·커밋·provider 추가·daemon protocol | `AGENTS.upstream.md` (**그 축의 SSOT**) |
| 지금 좌표와 다음 한 걸음 | `NEXT.md` |
| 진행·표·영수증·결정 | [issue #1](https://github.com/junghan0611/prime-agent/issues/1) — **문서가 아니라 이슈에 쌓는다** |
| Clojure 런타임 계약·알려진 편차 | `docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 |
| 홉 H1–H8 과 그 다음 | `ROADMAP.md` |
| 운영자 인터뷰 프로토콜 | `BASELINE.md` |
| daemon 프로세스 토폴로지 | `packages/coding-agent/docs/daemon.md` |
| 담당자 노트 | denote id `20260521T134542` (`~/org/botlog/`) — **제목 말고 id 로 붙든다** |

## 2. run.sh — 유일한 입구

사람+에이전트 공용 인터페이스이자 **명령 문자열의 SSOT**. 문서에 명령을 적어두면 낡으므로
`./run.sh help` 가 정본이다. 이미 `run.sh` 경로가 있는 일이면 **중복 만들지 말고 확장하라.**

세 묶음이다 — **두 팔 띄우기**(`clj` / `py`) · **재는 법**(`build` / `test` / `test-native` / `lint` / `check`) ·
**정리**(`tidy` / `daemons` / `reap`).

- `./run.sh test` 는 **한 파일만** 받는다. `npm test` 전체는 금지다(AGENTS.md Hard Rule 9).
- `./run.sh clj` 는 네이티브 바이너리를 요구하고 **python 으로 fallback 하지 않는다.**
  clean checkout 의 첫 손이 여기서 막힌다 → `./run.sh build` (GraalVM, 로컬 전용. CI 에는 없다).
- 코드 변경 뒤 `./run.sh check` 는 의무(upstream 규율).

## 3. 자기수선 루틴 — daemon 은 클라이언트보다 오래 산다

**이게 이 리포에서 제일 자주 당하는 것이다.**

### 왜 남는가 (계약이지 버그가 아니다)

`packages/coding-agent/docs/daemon.md` 「Resident Workers」:
> "Closing the TUI detaches the client; it does not stop the worker."

detached supervisor 하나가 worker 하나를 데리고 **PPID 1 로 산다.** **idle timeout 이 없다.**
회수 동사는 오직 명시적인 것뿐이다. 그래서 **누가 거두지 않으면 영원히 남는다.**

> 2026-09-15 oracle 측정: 2026-09-08 의 BENCH-1 run 1(8 세션) + smoke + 수동 launch 가
> **supervisor 15 + worker 15 = 30 프로세스**를 **6 일간** 남겼다. RSS 합 4634 MiB,
> PSS 합 3188 MiB. `prime-agent status` 가 15 개 전부 `sessions=0 · uptime 6d` 로 찍었다.
> 산 세션은 하나도 없었다.

### 무엇이 안전한가

`./run.sh reap` 은 **세션이 붙은 daemon 을 멈추지 않는다** — `daemon-ps.ts` 의 `planReap` 이
`sessionCount` 로 거르고, `reapReachableDaemon` 이 멈추기 **직전에 다시 확인한다.**
그러니 형제가 쓰는 중인 팔은 이 명령으로 죽지 않는다.

- `tidy` / `reap` = `doctor --fix` → **default socket 은 일부러 건너뛴다**(`planReap` 의 `isDefault` 가드).
- `reap --all` = 거기에 `shutdown --force` 를 더한다 → **이 머신의 모든 prime-agent daemon.**
  다른 체크아웃·다른 형제의 팔까지 간다. 아무도 안 쓰는 것이 확실할 때만.

### 상류 doctor 가 못 보는 자리 — `tidy` 가 따로 훑는 이유

`discoverDaemons` 의 발견 경로 셋 중 파일만 보는 `scanSocketDir()` 는
**default 소켓 디렉터리 바로 아래 한 층만** 읽는다(`defaultDaemonSocketDir`, 재귀 없음).
그런데 `run.sh` 는 격리를 위해 소켓을 그 **하위 디렉터리**에 판다. 그래서 거기서 daemon 이
죽으면 남은 소켓 파일이 doctor 에게 **안 보인다.** `tidy` 의 `sweep_stale_sockets` 가 그 자리를
훑는다 — listener 가 붙은 소켓은 건드리지 않는다.

### 평가·벤치를 돌릴 때

`evals/bench1/run.sh` 는 이제 **셀이 자기 daemon 을 자기가 거둔다**(`stop_cell_daemon`),
런이 끊겨도 `trap` 이 `$OUT` 아래만 훑는다(`sweep_run_daemons`).
새 eval 러너를 만들면 **같은 짝을 붙여라 — 띄운 것은 띄운 쪽이 거둔다.**

소켓 하나만 겨냥하는 공개 동사는 **없다.** `shutdown` 은 `runShutdownAll` 이라 머신 전체를
가져가고(병렬 셀이면 형제 셀까지 죽는다), `daemon` 하위명령은 `public-command.ts` 의
`rejectRemovedCommand` 가 막았다. 그래서 러너는 소켓 경로로 listener 를 찾아 그 pid 에만
TERM 을 보낸다. TERM 하나로 worker 까지 내려가고 소켓 파일도 supervisor 가 스스로 지운다
(2026-09-15 측정: supervisor+worker 가 0.4 초 안에 사라졌다).

### 진단 (거두기 전에 보고 싶을 때)

```bash
./run.sh daemons                      # 읽기 전용
ps -eo pid,ppid,lstart,rss,args | grep -E 'prime-agent$'
ss -xlp | grep prime-agent            # 어느 소켓을 누가 쥐고 있나
```

## 4. 반사신경 — 다시 당하지 말 것

- **유료 축을 실수로 건드리지 마라.** `./prime-agent.sh <flags> shutdown` 처럼 **하위명령을 뒤에**
  두면 `shutdown` 이 **프롬프트로 해석돼 모델 호출이 나간다**(2026-09-15 에 실제로 1회 나갔다).
  하위명령은 **항상 첫 위치 인자**다. 비용 상한은 에이전트가 정하지 않는다(Hard Rule 4).
- **daemon 하나를 무료로 띄울 수 있다** — `--mode daemon --daemon-socket <path>` 는 supervisor 만
  세우고 모델을 부르지 않는다. 정리 로직의 kill receipt 는 이걸로 만든다.
- **소켓은 체크아웃마다·arm 마다 갈린다.** 상류 기본값은 체크아웃과 무관한 단일 `daemon.sock`
  이라 다른 worktree 의 daemon 이 잡는다. `run.sh` 가 경로에 체크아웃을 넣는 이유이고,
  **띄울 때 찍히는 그 경로가 격리 영수증이다.**
- **`git stash` 를 쓰지 않는다.** 여러 형제가 같은 트리에서 일한다. 격리가 필요하면 임시 worktree.
- **줄번호로 가리키지 않는다** — 문서는 제목·규칙 이름으로, 소스는 **심볼 이름**으로 앵커한다.
  줄번호는 다음 커밋에 낡고 인용한 쪽은 그것을 모른다.
- **`grep -c 'deftest '` 는 require 의 `[deftest is]` 까지 세어 부풀린다.** `^(deftest` 로 앵커한다.
- **native 테스트 러너는 네임스페이스를 명시 require 한다.** 파일만 추가하면 **조용히 안 돈다.**
- **protocol writer 에 `*out*` 을 쓰면 프레임이 찢어진다.** 디버그 `println` 하나로 깨진다.
- **CI 는 clojure 축에서 lint 전용이다**(GitHub 에 GraalVM 이 없다). native 영수증은 로컬에만 남는다.

## 5. 커밋 규율

커밋 전 `commit` 스킬, 릴리즈 전 `tag-release` 스킬. 로그 깨끗하게
("Generated with Claude"·`Co-Authored-By` 금지). 에이전트는 활성 commit workflow 안에서만
커밋하고, **push 는 GLG 가 이 세션에서 명시 요청할 때만.** 커밋 요청이 푸시를 함의하지 않는다.
global `core.hooksPath` 안전벽을 우회하지 마라(`AGENT_ALLOW_UNSAFE_COMMIT=1`·`--no-verify` 금지).

## 6. 영속 사실이 사는 곳 (썩는 문서 말고)

| 사실 | 집 |
|---|---|
| 이 포크의 규칙·금지 | `AGENTS.md` |
| 상류 유지보수 규칙 | `AGENTS.upstream.md` |
| 지금 좌표·다음 한 걸음 | `NEXT.md` |
| **진행·표·영수증·결정** | **issue #1** — `docs/` 아래 새 문서를 만들지 않는다(Hard Rule 5) |
| Clojure 런타임 계약·편차 | `docs/clojure-runtime.md` |
| 명령 문자열 | `./run.sh help` |
| 담당자 노트 | denote `20260521T134542` |

이 스킬 자체(`.claude/skills/prime-agent/SKILL.md`)는 **양쪽 하네스가 읽는다**: Claude Code 는
`.claude/skills/` 를 네이티브로, pi 는 `.pi/settings.json` 의 `["../.claude/skills"]` 로.
`.claude/` 는 상류가 통째로 gitignore 하므로 이 포크가 `.claude/skills/` 만 되살렸다
— **명시적 fork-specific divergence** 다.
