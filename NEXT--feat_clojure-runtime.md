# feat/clojure-runtime — Phase A boot sector

이 브랜치는 production parity 작업이 아니다. Prime Agent의 persistent programmable workspace를 Lisp으로 먼저 바꾸고, 실제 사용에서 다음 설계를 발견하는 번역 실험이다.

# COMPASS — Entwurf #88

이 여섯 줄이 원문 대신 방향을 잡는다. 나머지를 안 읽어도 이건 읽는다.

1. 목표는 CPython 복제가 아니다. Lisp workspace를 **실제로 써서** 다른 form이 생기는지 보는 것이다.
2. REPL 언어축 / steering 표현축 / Emacs 공존면축은 독립이다. 지금은 **첫 축만** 연다.
3. Entwurf는 transport, local REPL은 computation이다. 섞지 않는다.
4. 자연어는 탐색과 해석, Lisp는 공개 상태·계약·계산 가능한 form이다.
5. install은 execute가 아니다. capability / provenance / retract가 경계를 만든다.
6. 성공은 **GLG가 실제 form을 읽고 고치며 더 명확히 판단하는가**다. parity도 속도도 아니다.

# RAIL — 현재 좌표

- [x] **1. 연구 질문과 경계 복원** — Entwurf #88 원문, Prime Agent 실측, parity review를 대조했다.
- [x] **2. Phase A 설계 다시 쓰기** — GraalVM native-image + SCI vertical slice로 `docs/clojure-runtime.md`를 재구성했다.
- [x] **3. Lisp runtime vertical slice** — native process가 persistent form과 `(rlm ...)` host bridge를 관통한다.
- [x] **3b. 실행 경로 분리와 workspace 촉감** — native가 정본 SUT가 됐고 `bin/rlm`으로 form을 직접 친다. CI가 native gate를 잡는다.
- [ ] **4. Prime Agent 실제 접속** ← CURRENT: **입구 계약과 GLG 승인 대기 — 구현 시작 아님.**
- [ ] **5. 공존언어 probe와 갈림길** — Emmy/SICM 계보를 작은 adapter로 열고 계속 확장할 축을 GLG가 고른다.

현재 좌표: 1–3b 완료 → 4 진행 → 5 대기

# NOW — RAIL 4 입구 계약

구현이 아니다. **입구에서 정할 것을 정하고 GLG 승인을 받는 단계**다.

- Stem: runtime이 native로 서고 CI가 지킨다. host를 붙이기 전에, 붙이는 순간 되돌리기 어려워지는 것들을 먼저 정한다.
- Next: 아래 다섯 결정을 GLG가 판독한다. 승인 전에는 host diff를 시작하지 않는다.
- Verify: RAIL 4는 **test count·속도·wire parity로 닫히지 않는다.** 실제 model trace 4개 + `semantics-gap`/`model-fumble`/`harness-gap` 분류 + GLG Hop 판독이 있어야 닫힌다.

## 표류 방지 — 이 브랜치를 처음 보는 팀에게

원문을 안 읽은 팀이 관성으로 흘러갈 다섯 경로다. 각 항목의 문장이 그 경로를 막는다.

1. **parity audit을 backlog로 읽기.**
   `docs/clojure-runtime--parity-audit.md`의 gap은 **작업 권한이 아니다.** 항목을 RAIL/NOW로 올리려면 **측정된 Phase A workload failure**와 **GLG의 명시적 승격**이 함께 있어야 한다. test parity 자체는 승격 근거가 아니다.
2. **편의로 capability 열기.**
   Capability는 convenience로 추가하지 않는다. `id` · `mode` · `install/retract` · **native positive/negative 테스트**가 함께 오지 않으면 거부한다. reflect-config와 광범위 Java interop은 capability 구현이 아니라 **경계 변경**이며 별도 GLG gate다.
3. **JVM을 작업면에 끼우기.**
   작업면은 native process + SCI다. JVM이 가운데 있으면 이 브랜치의 도구가 아니다 — 느려서가 아니라 두께 때문이다. `:test-jvm`은 deps에 남은 잔여물이지 체크포인트·영수증·일상 실행이 아니다. 어떤 runtime behavior checkpoint도 native SUT + fresh native build/CI 영수증 없이 닫지 않는다. 빠르더라도 JVM으로 일하지 않는다.
   (참고 실측: 같은 32케이스 native 4초, JVM 110초. 느린 건 일회성 이미지 빌드 ~30s / CI ~1m30s와, 케이스마다 JVM을 띄우는 비용이다. 이유는 속도가 아니다.)
4. **Python 모양으로 shim.**
   Host adapter는 **transport·spawn·bootstrap만** 번역한다. Python API/이름/await 의미를 SCI에 주입해 기존 prompt를 통과시키지 않는다. 모델이 만든 form이 Clojure로 바뀌지 않았는데 통과한 것은 성공이 아니라 **harness-gap**이다.
5. **엉뚱한 성공 측정.**
   "92 tests green", "CPython보다 빠름", "테스트 개수 증가"는 acceptance가 아니다.

## 입구에서 정할 다섯 — GLG 판독 대기

### 1. read-only document capability (write 아님)
`slurp`와 `spit`을 하나로 묶지 마라. RAIL 4에 필요한 건 **읽기 하나**다.
- AOT 컴파일된 wrapper가 session/project root 아래 path를 검증하고 text를 반환한다. stdout에도 Java interop에도 노출하지 않는다. **읽기만이면 output boundary 재설계가 아니다.**
- framing 재설계가 필요해지는 때는 raw output·process·Java interop을 열 때다.
- native rail에 positive(읽기 됨) + negative(쓰기·interop·raw output 여전히 닫힘) 테스트를 **짝으로** 둔다.
- reflect-config 부재로 생긴 **우연한** native closure를 보안 계약이라고 부르지 않는다.
- RAIL 4 안에서 발견으로 정할 것: API 이름과 form ergonomics, path scope/size/error가 충분한지, read-only로 부족한 실제 사례가 나오는지. **사례 전에는 write를 열지 않는다.**

### 2. retract의 의미 — 이름 unmap은 retract가 아니다
SCI 네임스페이스에서 이름만 지워도, 모델이 그 함수 값을 `_`·atom·closure에 담아뒀다면 계속 호출할 수 있다.
- 계약: **retract는 이전에 보관된 reference를 통한 이후 호출도 실패시킨다.** 이 보장을 못 하면 retract라 부르지 말고 `unbind`라 부른다.
- 구현(revocable proxy / generation token / active atom)은 RAIL 4에서 고른다. **이미 컴파일된 raw Var를 그대로 노출하는 안은 rollback 의미를 충족하지 않는다.**

### 3. evidence-integrity 경계 (adversarial sandbox 아님)
"IO가 없어 공짜"는 **SCI 안의 직접 수정에만** 참이다. `(rlm ...)` child는 host 안에서 파일을 고칠 수 있으므로 "에이전트 전체가 verifier를 못 고친다"는 이미 거짓이다.
- verifier 실행과 증거 수집은 SCI runtime **밖**(coordinator/CI)에 둔다.
- workspace capability로 verifier 명령·fixture 변형을 직접 노출하지 않는다.
- child가 만든 diff는 별도 diff review + test-count/음성대조 영수증으로 검증한다.
- 이것은 **Phase A evidence-integrity 경계**이지 적대적 보안 경계가 아니다. 정확히 그렇게 부른다.

### 4. capability manifest
install/retract 이전에 "무엇을 설치하는가"의 SSOT가 필요하다. 최소 `id`, `mode`, 노출 Var 목록, active generation/version, native 테스트. `ready` frame을 당장 늘릴 필요는 없고 runtime introspection으로 시작해도 된다.

### 5. RAIL 4 실험 기록 양식
구현 전에 workload 4개마다 무엇을 남길지 고정한다: 생성된 Clojure form, result/error, 사용한 capability, 세 실패 라벨, GLG 판독. **이게 없으면 host를 붙여도 Python shim과 엉뚱한 성공을 판별할 수 없다.**

### 지금 정하지 않을 것
general steering DSL, write/process capability, 전체 sandbox, Emmy surface, parity audit 항목, 최종 prompt 문구, capability grammar 전체. 실제 form과 실패를 본 뒤에 고른다.

## 구현자가 남긴 것 — 코드를 읽어야만 알 수 있던 것

`prime-agent-runtime-clj/`를 손으로 만든 시민(xai/grok-4.6)이 남긴 목록. 문서에 없어 매번 다시 알아내야 했던 자리다.

1. **protocol writer에 `*out*`을 쓰면 안 된다.** `-main`이 `*out*`/`*err*`를 stderr로 재바인딩한다. 디버그 `println` 하나가 프레임을 찢는다. **테스트가 없다** — 다음 테스트 홉 1순위.
2. **SCI+JVM과 SCI+native는 같은 allow-list가 아니다.** 아래 `## 측정된 발견` 표 참조. reflect-config 한 줄이면 native도 JVM처럼 열린다.
3. **`(def x 41)`의 마지막 값은 nil이 아니라 SCI var다.** 그래서 첫 셀에 `result` 이벤트가 나온다. Python `x = 5`는 result가 없다. Python 테스트를 그대로 옮기면 여기서 깨진다.
4. **테스트 러너는 네임스페이스를 명시 require 한다.** 파일만 추가하면 조용히 안 돈다.
5. **FHS 안에서 빌드해도 바이너리 interpreter는 nix store glibc다.** FHS는 빌드 도구의 자리이지 이식 가능한 ELF를 자동으로 만들지 않는다. CI(표준 ubuntu)는 `/lib64`를 박는다.
6. **AOT 산출물은 `target/classes`로 간다.** `.gitignore`가 `target/`만 덮기 때문이다.

### 6개월 뒤 오해할 자리

- `deps.edn`의 `:test`는 **native**다. "그냥 테스트"로 보이지만 바이너리가 없으면 실패하는 게 맞다.
- `build.sh`의 `--initialize-at-build-time`(인자 없음)은 뭉툭해 보여 패키지 목록으로 "고치기" 쉽다. 그러면 SCI가 런타임에 `core__init`을 못 찾는다.
- `bin/rlm`은 **Hop 1 손잡이**다. TypeScript host가 아니고 제품 REPL도 아니다.
- `ready.python = "clojure-native"`는 버그가 아니라 v2 host gate용 placeholder다.

## 관통 완료 — 측정 (2026-08-29)

- `ldd target/rlm-repl` → glibc 뿐. `libjvm.so` 없음. process table 단일 `rlm-repl`, java 없음.
- `file target/rlm-repl` → ELF 64-bit LSB pie executable, x86-64, stripped, 34.2 MB.
- native SUT: `clojure -M:test` / `:test-native` → 32 tests / 158 assertions, 0 failures. CI native job 동일.
- JVM 숫자 green은 delivery evidence가 아니다. `:test-jvm`을 돌리는 시간은 이 브랜치의 일이 아니다.
- `nix develop .#node --command npm run check` → exit 0 (938 files, installer, browser-smoke).
- `git status --porcelain` → `?? prime-agent-runtime-clj/` 한 줄. 공유파일·docs·Python oracle 무손상.
- LOC: src 287 + test ~300 + native-image 124. 계약 경보기(구현 480–1,050) 안쪽.

## 측정된 발견 — 두 rail의 capability 경계가 다르다

같은 소스인데 SUT에 따라 Java interop 경계가 갈린다 (2026-08-29 실측, 두 rail에 같은 form을 보냄):

| form | native | JVM |
|---|---|---|
| `(.toUpperCase "ab")` | error | **ok → "AB"** |
| `(.length "abc")` | error | **ok → 3** |
| `(.getBytes "a")` | error | **ok → byte[]** |
| `(slurp "deps.edn")` | error | error |
| `(System/getProperty …)` | error | error |
| `(future 1)` | error | error |

즉 `docs/clojure-runtime.md`의 "Java 경로가 닫혀 있다"는 **native delivery에서만 참이다.** 그리고 `native-image/`에 reflect-config가 없다 — 인스턴스 interop이 닫힌 것은 명시적 allow-list 결정이 아니라 **reflection metadata 부재의 결과**로 보인다(이 인과는 추정, 경계 차이 자체는 실측). 나중에 라이브러리 때문에 reflect-config를 추가하면 경계가 조용히 열린다.

이 표는 JVM을 레일로 유지하라는 뜻이 아니다. JVM 표면이 다르다는 경고다. 테스트는 native에서만 interop-closed를 고정하고, JVM의 열림을 성공으로 못 박지 않는다.

## 알려진 편차 — parity라고 부르지 않는다

- output이 cell 끝에 batch flush된다. oracle은 writing thread에서 동기적으로 흘린다 (`src/rlm/eval.clj:23`).
- `error.ename` 이 SCI wrapper class이고 traceback에 runtime frame이 실린다 (`src/rlm/repl.clj:45`).
- interrupt 는 파싱만 하고 취소를 보장하지 않는다.

# SCALE — 날짜 대신 코드량과 개입 홉

- Phase A 예상 전체 변경량: 구현 약 1,000–1,900 LOC + 테스트·설정 약 800–1,400 LOC.
- 숫자는 목표치가 아니라 scope 경보기다. 범위를 넘으면 parity/hardening이 stem으로 섞였는지 먼저 본다.
- GLG 개입: 3–4홉만 연다 — workspace 촉감 → `(rlm ...)` 방향타 → Emmy probe → Phase A 갈림길.
- JSON codec, native-image 설정, framing, 단위 테스트 같은 기계 판단으로 GLG 홉을 소비하지 않는다.

# ACCEPTANCE — Phase A

1. 모델이 Python 흉내가 아니라 persistent Lisp form으로 실제 작업을 조직한다.
2. 셀 사이 binding이 살아 있고 문서를 값으로 붙잡아 필요한 부분만 계산한다.
3. `(rlm ...)`이 citizen 내부 child를 열고 host reply를 값으로 돌려준다.
4. GLG가 form을 읽고 고칠 수 있으며, 다음 설계 판단에 쓸 실제 흔적이 남는다.
5. 실패는 `semantics-gap`, `model-fumble`, `harness-gap`으로 나눠 기록한다.

다음은 Phase A의 선행조건이 아니다: raw fd 완전 격리, pure infinite-loop interrupt parity, 함수 snapshot roundtrip, CPython 92 tests 전체 green.

# GLG HOPS

- Hop 1 — workspace: 실제 persistent forms를 보고 Clojure/SCI의 촉감과 계속 진행 여부를 판단한다.
- Hop 2 — direction: 실제 child call을 보고 `(rlm ...)`의 동기값/handle/future 표면을 고른다.
- Hop 3 — coexistence: 작은 Emmy adapter로 form → symbolic → numeric/render 경로를 만져본다.
- Hop 4 — fork: runtime hardening, Emmy 확장, evaluator/language 변경, 중단 중 하나를 고른다.

# READ FIRST

**필수는 이 문서 하나다.** 위 `# COMPASS`가 원문 대신 방향을 잡는다. 나머지는 필요할 때만 연다.

1. **이 문서** — 유일한 필수.
2. `docs/clojure-runtime.md` — 현재 구현/실험 contract.
3. Entwurf #88 합본 — `한 장의 통합 지도` · `Phase A` · `현재 연구 질문` **세 절만** 지정해서 읽는다.
   `/home/junghan/sync/org/llmlog/20260827T222209--prime-agent-rlm을-lisp-emacs-공존언어로-옮기기-entwurf-88-원문과-선행조사-임시-합본__agent_emacs_entwurf_lisp_llmlog_metaprogramming_primeagent_repl_research.org`
4. `docs/clojure-runtime--devenv.md` — 구현에 들어갈 때.
5. `prime-agent-runtime/src/rlm/repl.md` — wire adapter를 만질 때만. Phase A가 가져갈 vocabulary와 나중 parity backlog를 가르는 데만 쓴다.

**CONDITIONAL REFERENCE** — `docs/clojure-runtime--parity-audit.md`. 측정된 workload gap이 parity 문제인지 판독할 때만 연다. backlog가 아니다.

# RECENT

- [2026-08-29] 첫 설계는 CPython protocol v2 production parity를 전제로 작성됐다.
- [2026-08-29] review는 interrupt·raw fd·function snapshot의 parity gap을 확인했다. 이 gap은 Phase A 착수 blocker가 아니다.
- [2026-08-29] runtime 방향을 JVM-hosted `clojure.core/eval`에서 GraalVM native-image + SCI로 바로잡았다.
- [2026-08-29] 일정 표현을 날짜에서 코드량과 GLG 개입 홉으로 바꿨다.
- [2026-08-29] `docs/clojure-runtime.md`를 production replacement 설계에서 Phase A 실험 명세로 전면 재작성했다.
- [2026-08-29] 기존 GLG Clojure/GraalVM flake 계보를 따라 devShell 3개와 FHS native-image build surface를 세우고 `nix flake check`를 통과했다.
- [2026-08-29] `prime-agent-runtime-clj/` vertical slice가 native executable로 관통했다. 실행 시 JVM 없음을 `ldd`와 process table로 확인했다.
- [2026-08-29] 기본 테스트 경로가 조용히 JVM 런타임으로 떨어진다는 것을 발견했다. native를 정본 SUT로 승격하는 일이 3b가 됐다.
- [2026-08-29] push + CI 첫 실행이 green. native job이 `ldd`로 libjvm 부재를 확인하고 native 바이너리로 wire suite를 돌린다.
- [2026-08-29] 회귀를 16/76 → 32/158로 늘렸다. 셀을 가로지르는 fn·macro·atom·closure, capability 경계, 프레임 위조 방어, 유니코드 왕복, `rlm` 오류 3분기, protocol error 3종을 고정했다.
- [2026-08-29] 두 rail의 Java interop 경계가 다르다는 것을 측정했다. framing 보증은 native delivery에서만 참이다.
- [2026-08-29] rail 속도를 실측했다 — native 4s, JVM 110s. "native가 느리다"는 직관이 거꾸로였다. 이유는 속도가 아니다: JVM이 가운데 있으면 에이전트 도구가 아니다.
- [2026-08-29] 새 팀의 표류를 막기 위해 COMPASS 6줄, 표류 5경로 방지문, 입구 결정 5건을 NEXT에 박았다. parity review를 `--parity-audit`로 개명하고 REFERENCE-ONLY banner를 달았다.
- [2026-08-29] JVM을 작업면/레일/일상 실행에서 뺐다. 다음 실무는 native + SCI만.
