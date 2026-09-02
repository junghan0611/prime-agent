(ns rlm.model-search-test
  "H12 — (find-models) as workspace data.

  The host verb rlm.find_models is runtime-neutral (rlm-runtime.ts::
  createRlmFindModelsHostHandler returns {models}), so this hop builds only the
  clj workspace wrapper. What it owes is the oracle's contract: argument checks
  before any frame reaches the host, a shape check on the reply, and four
  non-empty string fields per entry."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(defn- reply!
  "Answer the pending host_request with one payload and return the request that
  was actually sent, so a test can assert on the frame the runtime wrote."
  [repl payload]
  (let [req (h/wait-event repl "host_request")]
    (h/send! repl {"type" "host_reply" "id" (get req "id")
                   "data" (merge {"status" "ok"} payload)})
    req))

;; ---------------------------------------------------------------------------
;; Oracle row: test_subagent_registry.py::RlmSubagentRegistryTest::test_finds_authenticated_models_through_host
;; ---------------------------------------------------------------------------
(deftest find-models-returns-host-entries-as-workspace-data
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "fm"
                     "code" "(find-models \"opus\" 3)"})
      ;; The extra host field is deliberate. The oracle builds a typed RLMModel
      ;; with exactly four fields, so anything else the host sends is dropped.
      ;; Without it a wrapper that simply returned the reply entry would pass:
      ;; the entry arrives keywordized under the same four keys, and the mutant
      ;; that returns it unchanged survived (measured 2026-09-02, M4).
      (let [req (reply! repl {"models" [{"provider" "anthropic"
                                         "id" "claude-opus-4-7"
                                         "name" "Claude Opus 4.7"
                                         "selector" "anthropic/claude-opus-4-7"
                                         "context_window" 200000}]})]
        (testing "the request carries the oracle's payload, not a renamed one"
          (is (= "rlm.find_models" (get-in req ["data" "type"])))
          (is (= "opus" (get-in req ["data" "query"])))
          (is (= 3 (get-in req ["data" "limit"])))))
      (let [events (h/until-done repl "fm")]
        (is (= "ok" (get (h/one events "done") "status")))
        (is (empty? (filterv #(= "error" (get % "event")) events)))
        (let [models (edn/read-string (get (h/one events "result") "text"))]
          (testing "workspace data, not a typed handle: a vector of plain maps"
            (is (vector? models))
            (is (= 1 (count models)))
            (is (= {:provider "anthropic"
                    :id "claude-opus-4-7"
                    :name "Claude Opus 4.7"
                    :selector "anthropic/claude-opus-4-7"}
                   (first models)))
            (is (= #{:provider :id :name :selector} (set (keys (first models))))
                "exactly the contract fields: the host's extra key is dropped, as the oracle's record drops it")))))))

;; ---------------------------------------------------------------------------
;; Oracle row: test_subagent_registry.py::RlmSubagentRegistryTest::test_rejects_invalid_model_search_input_and_response
;; Three refusals, and the first two must never reach the host at all.
;; ---------------------------------------------------------------------------
(deftest find-models-refuses-bad-input-before-the-host-and-bad-entries-after
  (h/with-repl
    (fn [repl _]
      (testing "argument checks fire in the cell, before any frame is written"
        (let [events (h/execute repl "bad-query" "(find-models 123)")
              err (h/one events "error")]
          (is (re-find #"query must be str" (str (get err "evalue"))))
          (is (= "bad-query" (get err "id")))
          (is (= "error" (get (h/one events "done") "status")))
          (is (nil? (h/one events "host_request"))
              "a rejected argument must not spend a host round trip"))
        (let [events (h/execute repl "bad-limit" "(find-models \"opus\" \"3\")")
              err (h/one events "error")]
          (is (re-find #"limit must be int" (str (get err "evalue"))))
          (is (nil? (h/one events "host_request")))))
      (testing "an entry missing fields is refused after the reply"
        (h/send! repl {"type" "execute" "id" "bad-entry" "code" "(find-models \"opus\")"})
        (reply! repl {"models" [{"provider" "anthropic"}]})
        (let [events (h/until-done repl "bad-entry")]
          (is (re-find #"invalid model entry" (str (get (h/one events "error") "evalue"))))
          (is (= "error" (get (h/one events "done") "status")))))
      (testing "and a non-list models field is refused as its own contract"
        (h/send! repl {"type" "execute" "id" "bad-list" "code" "(find-models)"})
        (reply! repl {"models" "not-a-list"})
        (let [events (h/until-done repl "bad-list")]
          (is (re-find #"invalid models list" (str (get (h/one events "error") "evalue"))))
          (is (= "error" (get (h/one events "done") "status")))))
      (testing "the runtime kept serving through all four refusals"
        (is (= "2" (get (h/one (h/execute repl "after" "(+ 1 1)") "result") "text")))))))
