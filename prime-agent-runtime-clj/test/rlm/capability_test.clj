(ns rlm.capability-test
  "What is closed. Framing rests on this allow-list — docs/clojure-runtime.md."
  (:require [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(defn- closed
  [repl id code]
  (let [events (h/execute repl id code)
        err (h/one events "error")]
    (is (some? err) id)
    (is (h/error-shape? err) id)
    (is (= "error" (get (h/one events "done") "status")) id)
    (is (= 1 (h/done-count events)) id)
    events))

(deftest java-and-io-paths-are-closed
  ;; framing 보증이 이 경계에 기댄다 — docs/clojure-runtime.md 참조
  (h/with-repl
    (fn [repl _]
      (closed repl "slurp" "(slurp \"/etc/passwd\")")
      (closed repl "spit" "(spit \"/tmp/rlm-capability-probe\" \"x\")")
      (closed repl "sys" "(System/getProperty \"user.dir\")")
      (closed repl "inter" "(.toUpperCase \"ab\")")
      (let [events (h/execute repl "alive" "(+ 1 1)")]
        (is (= "2" (get (h/one events "result") "text")))))))

(deftest future-is-closed-hop2-not-security
  ;; Hop 2 안건: 설계 문서는 (future (rlm ...)) 를 병렬 경로로 제안했는데 지금 닫혀 있다.
  ;; 보안 경계가 아니다.
  (h/with-repl
    (fn [repl _]
      (testing "future"
        (closed repl "fut" "(future 1)"))
      (let [events (h/execute repl "alive" "(+ 40 2)")]
        (is (= "42" (get (h/one events "result") "text")))))))
