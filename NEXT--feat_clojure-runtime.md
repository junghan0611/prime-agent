# feat/clojure-runtime — Phase A boot sector

CPython 복제가 아니다. Lisp workspace를 실제로 써서 다른 form이 생기는지 보는 번역 실험.
계약은 `docs/clojure-runtime.md` 한 장. 이 파일은 좌표다.

# COMPASS — Entwurf #88

나머지를 안 읽어도 이건 읽는다.

1. 목표는 CPython 복제가 아니다. Lisp workspace를 **실제로 써서** 다른 form이 생기는지 보는 것이다.
2. REPL 언어축 / steering 표현축 / Emacs 공존면축은 독립이다. 지금은 **첫 축만** 연다.
3. Entwurf는 transport, local REPL은 computation이다. 섞지 않는다.
4. 자연어는 탐색과 해석, Lisp는 공개 상태·계약·계산 가능한 form이다.
5. install은 execute가 아니다. capability / provenance / retract가 경계를 만든다.
6. 성공은 **GLG가 실제 form을 읽고 고치며 더 명확히 판단하는가**다. parity도 속도도 아니다.

# RAIL — 현재 좌표

- [x] **1. 연구 질문과 경계 복원**
- [x] **2. Phase A 설계** — native-image + SCI. 계약은 `docs/clojure-runtime.md`.
- [x] **3. Lisp runtime vertical slice** — native process가 persistent form과 `(rlm ...)`을 관통한다.
- [x] **3b. 실행 경로 분리** — native가 정본 SUT, `bin/rlm`. 영수증은 로컬 native-image + test. GitHub는 린트만.
- [ ] **4. Prime Agent 실제 접속** ← CURRENT: **Hop 1(`bin/rlm`)은 열려 있음. 입구 다섯은 승인 대기. host diff 아님.**
- [ ] **5. 공존언어 probe** ← PAUSED: RAIL 4 닫힌 뒤. Emmy/SICM 작은 adapter.

현재 좌표: 1–3b 완료 → 4 Hop 1 가능 / 입구 승인 대기 → 5 보류

# NOW — RAIL 4 입구 계약

- Stem: runtime이 native로 선다. 영수증은 로컬(`./native-image/build.sh` + `clojure -M:test` + `ldd`에 libjvm 없음). host를 붙이기 전에 되돌리기 어려워지는 것을 먼저 정한다.
- Next: (1) Hop 1 — `./bin/rlm`으로 persistent form 촉감. host 없음. 구현 아님. (2) 아래 다섯을 GLG가 판독. (3) 둘 다 끝나기 전에 host diff 시작하지 않는다.
- Verify: RAIL 4는 test count·속도·wire parity로 닫히지 않는다. 실제 model trace 4개 + `semantics-gap`/`model-fumble`/`harness-gap` + GLG Hop 판독이 있어야 닫힌다.
- Read: 이 문서 COMPASS + NOW, 계약은 `docs/clojure-runtime.md`. 손잡이는 `prime-agent-runtime-clj/README.md`.
- Do not touch: Python oracle, TypeScript host, `ipython` 개명, reflect-config, write/process capability, Emmy, parity 항목 승격, JVM protocol SUT 부활, GitHub에 GraalVM/native-image 올리기.
- Blocker: 입구 다섯 미판독. Hop 1은 blocker가 아니다 — `bin/rlm`은 있다.

## 표류 방지

원문을 안 읽은 팀이 관성으로 흘러갈 경로. 각 문장이 막는다.

1. **parity를 backlog로 읽기.** 어제 감사 문서는 지웠다. gap을 RAIL/NOW로 올리려면 **측정된 Phase A workload failure**와 **GLG 명시 승격**이 함께 있어야 한다. Python oracle `repl.md`는 비교 원본이지 할 일 목록이 아니다.
2. **편의로 capability 열기.** `id` · `mode` · `install/retract` · **native +/- 테스트**가 함께 오지 않으면 거부. reflect-config와 광범위 Java interop은 경계 변경, 별도 GLG gate.
3. **JVM을 작업면에 끼우기.** 작업면은 native process + SCI다. 가운데가 두꺼워서다. GitHub Actions에 GraalVM/native-image/JDK를 올리지 않는다. 린트만 돌린다. 이미지 빌드는 로컬 Nix. `nix develop .#jvm`은 편집 셸이다. 어떤 checkpoint도 로컬 native SUT + fresh `build.sh` 영수증 없이 닫지 않는다.
4. **Python 모양으로 shim.** Host adapter는 transport·spawn·bootstrap만 번역한다. Python API/이름/await를 SCI에 주입해 기존 prompt를 통과시키지 않는다. 그건 `harness-gap`.
5. **엉뚱한 성공 측정.** "92 tests green", "CPython보다 빠름", "테스트 개수 증가"는 acceptance가 아니다.

## 입구에서 정할 다섯 — GLG 판독 대기

### 1. read-only document capability (write 아님)
`slurp`와 `spit`을 묶지 마라. RAIL 4에 필요한 건 **읽기 하나**.
AOT wrapper가 session/project root 아래 path를 검증하고 text를 반환. stdout·Java interop에 노출하지 않는다. 읽기만이면 output boundary 재설계가 아니다.
native에 positive(읽기) + negative(쓰기·interop·raw output 닫힘)를 **짝으로**.
RAIL 4 안에서 발견으로 정할 것: API 이름, path scope/size/error, read-only로 부족한 사례. **사례 전에는 write를 열지 않는다.**

### 2. retract의 의미 — 이름 unmap은 retract가 아니다
SCI에서 이름만 지워도, 모델이 그 함수 값을 `_`·atom·closure에 담아두면 계속 호출한다.
계약: **retract는 이전에 보관된 reference를 통한 이후 호출도 실패시킨다.** 못 하면 `unbind`라 부른다.
구현(revocable proxy / generation token / active atom)은 RAIL 4에서 고른다. 이미 컴파일된 raw Var를 그대로 노출하는 안은 rollback이 아니다.

### 3. evidence-integrity 경계 (adversarial sandbox 아님)
"IO가 없어 공짜"는 SCI 안의 직접 수정에만 참이다. `(rlm ...)` child는 host 안에서 파일을 고친다.
verifier 실행과 증거 수집은 SCI **밖**(coordinator/CI). workspace capability로 verifier 명령·fixture를 노출하지 않는다.
child diff는 별도 review + 영수증. Phase A evidence-integrity 경계이지 적대적 보안 경계가 아니다.

### 4. capability manifest
install/retract 이전에 "무엇을 설치하는가"의 SSOT. 최소 `id`, `mode`, 노출 Var, active generation/version, native 테스트. `ready` frame을 당장 늘릴 필요는 없다.

### 5. RAIL 4 실험 기록 양식
workload 4개마다: 생성된 Clojure form, result/error, 사용한 capability, 세 실패 라벨, GLG 판독.
없으면 host를 붙여도 Python shim과 엉뚱한 성공을 판별할 수 없다.

### 지금 정하지 않을 것
general steering DSL, write/process capability, 전체 sandbox, Emmy surface, 최종 prompt 문구, capability grammar 전체.

# RECENT

- [2026-08-29] 포크 `junghan0611/prime-agent` + `feat/clojure-runtime`. 틀을 잡았다. 성공.
- [2026-08-29] JVM-hosted eval 초안을 버리고 native-image + SCI로 관통. native 32/158.
- [2026-08-29] JVM을 작업면에서 뺐다. 다음 실무는 native + SCI만.
- [2026-08-30] `docs/`를 계약 한 장으로 접었다. devenv·parity-audit을 지웠다.
- [2026-08-30] GitHub에서 GraalVM/native-image/`test-jvm`을 뺐다. Actions는 clj-kondo만. 이미지 빌드는 로컬 Nix — GH가 불안해서 무거운 게이트를 올릴 자리가 아니다.
