{
  description = "prime-agent — feat/clojure-runtime dev env (Clojure source + GraalVM native-image + SCI, no JVM at runtime) alongside the existing TypeScript/Node checks";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        graalvm = pkgs.graalvmPackages.graalvm-ce;

        # package.json engines 는 node >= 22.8.0, CI 는 node-version 22.
        # nixpkgs 기본 nodejs 가 그 하한 위이므로 기본 판을 쓴다 (버전 핀은 flake.lock).
        nodejs = pkgs.nodejs;

        # TypeScript/Node 축. node_modules 는 npm ci 로 채운다 (Nix 가 npm tree 를 대신 잡지 않는다).
        # biome 는 nixpkgs 판을 함께 둬서 node_modules 가 없는 상태에서도
        # .husky/pre-commit 이 `biome: command not found` 로 죽지 않게 한다.
        nodeTools = [
          nodejs
          pkgs.biome
        ];

        # Clojure 축. 실행 시 JVM 을 띄우지 않는 방향이므로 graalvm 이 기본 JDK.
        # clojure CLI / native-image 빌드 자체는 빌드타임 JVM 을 쓴다.
        clojureTools = with pkgs; [
          clojure
          graalvm
          babashka   # SCI + native-image 선행 구현. 스크립트/참고선.
          clj-kondo
          cljfmt
        ];

        commonTools = with pkgs; [
          jq
          git
          coreutils
        ];

        # FHS 환경 — native-image 산출물이 표준 경로 인터프리터를 갖도록.
        # voscli / dictcli / geworfen / proxycli 정합 (Docker / non-NixOS 호환).
        fhsEnv = pkgs.buildFHSEnv {
          name = "prime-agent-clj-build";
          targetPkgs = pkgs: with pkgs; [
            clojure
            graalvm
            zlib
            zlib.static
            glibc
            glibc.static
          ];
          runScript = pkgs.writeShellScript "prime-agent-clj-build-init" ''
            export JAVA_HOME=${graalvm}
            export GRAALVM_HOME=${graalvm}
            exec bash "$@"
          '';
        };
      in
      {
        packages.fhs = fhsEnv;

        devShells = {
          # 기본 — Clojure/GraalVM + Node 를 한 shell 에.
          # Phase A 구현팀이 여기서 runtime 빌드와 기존 TS check 를 모두 돈다.
          default = pkgs.mkShell {
            name = "prime-agent-clj";
            buildInputs = clojureTools ++ nodeTools ++ commonTools;
            JAVA_HOME = graalvm;
            GRAALVM_HOME = graalvm;
            shellHook = ''
              echo "prime-agent dev shell — feat/clojure-runtime"
              echo "============================================"
              echo "  GraalVM:  $(native-image --version 2>/dev/null | head -1 || echo 'native-image not on PATH')"
              echo "  Java:     $(java -version 2>&1 | head -1)"
              echo "  Clojure:  $(clojure -Sdescribe 2>/dev/null | head -1 || echo 'not yet initialized')"
              echo "  Babashka: $(bb --version 2>/dev/null)"
              echo "  Node:     $(node --version)  npm $(npm --version)"
              echo "  Biome:    $(biome --version 2>/dev/null)"
              echo ""
              echo "Clojure runtime (prime-agent-runtime-clj/, Phase A):"
              echo "  clj -M:test                 — wire tests"
              echo "  clj -M:repl                 — nREPL"
              echo "  clj -T:build uber           — uberjar (빌드 중간물)"
              echo "  nix develop .#fhs -c ...    — native-image 빌드 (표준 경로 인터프리터)"
              echo ""
              echo "TypeScript/Node (기존 축):"
              echo "  npm ci                      — node_modules (최초 1회, Nix 가 대신하지 않음)"
              echo "  npm run check               — biome + tsgo + installer/browser smoke"
              echo ""
              echo "경계: 실행 시 JVM-hosted runtime 을 쓰지 않는다. native executable + SCI."
            '';
          };

          # Clojure 만 — native-image 가 필요 없는 가벼운 개발/테스트 자리.
          # graalvm 대신 jdk21_headless (빌드타임 JVM 만).
          jvm = pkgs.mkShell {
            name = "prime-agent-clj-jvm";
            buildInputs = (with pkgs; [ clojure jdk21_headless babashka clj-kondo cljfmt ]) ++ commonTools;
            shellHook = ''
              echo "prime-agent dev shell — Clojure only (JVM, native-image 없음)"
              echo "  clj -M:test  /  clj -M:repl"
            '';
          };

          # Node 만 — 기존 TypeScript check 만 돌릴 자리.
          node = pkgs.mkShell {
            name = "prime-agent-node";
            buildInputs = nodeTools ++ commonTools;
            shellHook = ''
              echo "prime-agent dev shell — Node only"
              echo "  Node: $(node --version)  npm $(npm --version)  biome $(biome --version 2>/dev/null)"
              echo "  npm ci && npm run check"
            '';
          };
        };
      });
}
