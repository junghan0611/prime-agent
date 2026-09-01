(ns rlm.continuity-test
  "H6 compaction / restart continuity.

  Two different survivals, and the runtime must not confuse them:

  - Host compaction never touches this process, so the workspace keeps every var
    it had. What the model loses is the transcript that named them, so the forms
    the host's post-compaction notice points at have to actually evaluate here.
  - A kernel restart is a new process. Vars and the process registry are gone and
    no snapshot revives them. The child registry is the host's, so it outlives the
    restart — (rlm-children) is how the empty workspace gets its handles back."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(defn- result-text
  [events]
  (get (h/one events "result") "text"))

(defn- eval-edn
  [repl id code]
  (let [events (h/execute repl id code)]
    (is (nil? (h/one events "error")) code)
    (edn/read-string (result-text events))))

(defn- answered
  "Run a cell, answer its one host_request with data, and collect every event."
  [repl id code data]
  (h/send! repl {"type" "execute" "id" id "code" code})
  (let [req (h/wait-event repl "host_request")]
    (h/send! repl {"type" "host_reply" "id" (get req "id") "data" data})
    {:request (get req "data")
     :events (h/until-done repl id)}))

(def ^:private registry-entry
  {"rlm_child_id" "c1"
   "active_session_id" nil
   "session_id" "s1"
   "session_name" "subagent-worker-ab12"
   "session_dir" "/tmp/child"
   "status" "running"})

;; ---------------------------------------------------------------------------
;; child registry — the one thing that survives a restart

(deftest recovered-child-keys-match-the-spawn-handle
  ;; The defect this closes: host JSON spells the field rlm_child_id, so a raw
  ;; (host-request {:type "rlm.list_subagents"}) handed the workspace
  ;; :rlm_child_id while (rlm ...) had already taught it :rlm-child-id. One
  ;; object, two key shapes — and the mismatch only shows up after a compaction
  ;; or restart, exactly when the model is reading a recovered handle.
  (h/with-repl
    (fn [repl _]
      (let [spawn (answered repl "s1" "(rlm \"child task\")"
                            {"status" "ok"
                             "rlm_child_id" "c1"
                             "name" "subagent-worker-ab12"
                             "session_dir" "/tmp/child"
                             "model" "test"})
            handle (edn/read-string (result-text (:events spawn)))
            listed (answered repl "l1" "(rlm-children)"
                             {"status" "ok" "subagents" [registry-entry]})
            children (edn/read-string (result-text (:events listed)))
            child (first children)]
        (is (= {:type "rlm.list_subagents"} (update-keys (:request listed) keyword)))
        (is (= 1 (count children)))
        (is (= (:rlm-child-id handle) (:rlm-child-id child)))
        (is (= "subagent-worker-ab12" (:session-name child)))
        (is (= "/tmp/child" (:session-dir child)))
        (is (= "running" (:status child)))
        (is (nil? (:active-session-id child)))
        ;; No snake_case key reaches the workspace.
        (is (every? #(not (re-find #"_" (name %))) (keys child))
            (pr-str (keys child)))))))

(deftest empty-registry-is-an-empty-vector
  (h/with-repl
    (fn [repl _]
      (let [{:keys [events]} (answered repl "l0" "(rlm-children)"
                                       {"status" "ok" "subagents" []})]
        (is (= "[]" (result-text events)))
        (is (= "ok" (get (h/one events "done") "status")))))))

(deftest registry-failures-raise-instead-of-returning-a-hollow-map
  (testing "host error status"
    (h/with-repl
      (fn [repl _]
        (let [{:keys [events]} (answered repl "le" "(rlm-children)"
                                         {"status" "error" "error" "registry unavailable"})
              err (h/one events "error")]
          (is (h/error-shape? err))
          (is (re-find #"registry unavailable" (str (get err "evalue"))))
          (is (= "error" (get (h/one events "done") "status")))
          (is (= 1 (h/done-count events)))))))
  (testing "ok reply with no subagents list"
    (h/with-repl
      (fn [repl _]
        (let [{:keys [events]} (answered repl "lm" "(rlm-children)" {"status" "ok"})
              err (h/one events "error")]
          (is (h/error-shape? err))
          (is (re-find #"subagents list" (str (get err "evalue"))))
          (is (= "error" (get (h/one events "done") "status"))))))))

(deftest delete-takes-an-id-or-the-handle-itself
  (h/with-repl
    (fn [repl _]
      (let [by-id (answered repl "d1" "(rlm-delete-child \"c1\")"
                            {"status" "ok"
                             "subagent" registry-entry
                             "outcome" "deleted"})
            reply (edn/read-string (result-text (:events by-id)))]
        (is (= {:type "rlm.delete_subagent" :target "c1"}
               (update-keys (:request by-id) keyword)))
        (is (= "deleted" (:outcome reply)))
        (is (= "c1" (get-in reply [:subagent :rlm-child-id])))
        ;; status carried the throw, so it does not clutter the receipt.
        (is (nil? (:status reply))))
      (let [by-handle (answered repl "d2" "(rlm-delete-child {:rlm-child-id \"c2\"})"
                                {"status" "ok" "subagent" {"rlm_child_id" "c2"}})]
        (is (= {:type "rlm.delete_subagent" :target "c2"}
               (update-keys (:request by-handle) keyword)))))))

(deftest delete-rejects-a-targetless-map-before-reaching-the-host
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "d3" "(rlm-delete-child {:name \"api-reviewer\"})")
            err (h/one events "error")]
        (is (h/error-shape? err))
        (is (re-find #":rlm-child-id" (str (get err "evalue"))))
        ;; The whole point: no frame went out, so the host never saw a delete.
        (is (nil? (h/one events "host_request")))
        (is (= "error" (get (h/one events "done") "status"))))
      (let [events (h/execute repl "d4" "(+ 1 1)")]
        (is (= "2" (result-text events)))))))

(deftest a-failed-child-is-listed-not-filtered
  ;; The oracle pins that a subagent whose run ended in error still appears in
  ;; the registry (test_subagent_registry.py::RlmSubagentRegistryTest::
  ;; test_lists_failed_subagents_from_host, subagents[0].status == "error").
  ;; Every other clj fixture here carries "status" "running", so nothing said
  ;; whether this arm lists a failed child or quietly drops it. It lists it:
  ;; rlm.core/rlm-children is a dash-keys passthrough with no status filter.
  (h/with-repl
    (fn [repl _]
      (let [{:keys [events]} (answered repl "lf" "(rlm-children)"
                                       {"status" "ok"
                                        "subagents" [(assoc registry-entry
                                                            "rlm_child_id" "c-failed"
                                                            "session_id" nil
                                                            "status" "error")]})
            children (edn/read-string (result-text events))
            child (first children)]
        (is (= 1 (count children)) "a failed child is not filtered out of the registry")
        (is (= "error" (:status child)))
        (is (= "c-failed" (:rlm-child-id child)))
        (is (nil? (:session-id child)))
        (is (= "ok" (get (h/one events "done") "status")))))))

(deftest spawn-forwards-the-orchestrator-kwargs-to-the-host
  ;; The oracle pins that a chosen name and model reach the host
  ;; (test_subagent_registry.py::...::test_forwards_orchestrator_chosen_name_and_model_to_host).
  ;; rlm.core/rlm passes its kwargs map through as :kwargs, but until now the
  ;; only clj assertion on a spawn frame was its :prompt.
  (h/with-repl
    (fn [repl _]
      (let [spawn (answered repl "kw1"
                            "(rlm \"child task\" {:model \"deepseek/deepseek-v4-pro\" :name \"api-reviewer\"})"
                            {"status" "ok" "rlm_child_id" "c9" "name" "api-reviewer"
                             "session_dir" "/tmp/c9" "model" "deepseek/deepseek-v4-pro"})
            req (:request spawn)
            handle (edn/read-string (result-text (:events spawn)))]
        (is (= "rlm.run" (get req "type")))
        (is (= "child task" (get req "prompt")))
        (is (= {"model" "deepseek/deepseek-v4-pro" "name" "api-reviewer"} (get req "kwargs"))
            "the orchestrator's choices ride the frame, not just the prompt")
        (is (= "api-reviewer" (:name handle)))
        (is (= "deepseek/deepseek-v4-pro" (:model handle))))
      (testing "an omitted kwargs map is an empty map, never a missing key"
        (let [spawn (answered repl "kw2" "(rlm \"bare\")"
                              {"status" "ok" "rlm_child_id" "c10" "name" "n"
                               "session_dir" "/tmp/c10" "model" "m"})]
          (is (= {} (get (:request spawn) "kwargs"))))))))

(deftest registry-entries-pass-through-without-a-minted-default
  ;; Divergence made observable rather than assumed. The oracle's RLMSubagent is
  ;; a typed record that REQUIRES session_name and mints a default
  ;; (test_subagent_registry.py::...::test_requires_a_default_session_name).
  ;; This arm has no such record: rlm-children hands the workspace what the host
  ;; sent. A missing key stays missing -- it is neither invented nor an error.
  (h/with-repl
    (fn [repl _]
      (let [{:keys [events]} (answered repl "nm" "(rlm-children)"
                                       {"status" "ok"
                                        "subagents" [(dissoc registry-entry "session_name")]})
            child (first (edn/read-string (result-text events)))]
        (is (= "ok" (get (h/one events "done") "status"))
            "a missing session_name is not an error on this arm")
        (is (nil? (:session-name child))
            "and no default is minted -- the workspace sees the host's own shape")
        (is (= "c1" (:rlm-child-id child)) "the rest of the entry is untouched")))))

;; ---------------------------------------------------------------------------
;; compaction — the process is untouched, so the recovery forms must evaluate

(deftest workspace-and-registries-outlive-a-gap-between-cells
  ;; Compaction happens entirely in the host: from here it is only a pause
  ;; between executes. What has to hold is that the forms the post-compaction
  ;; notice names still answer on the far side of one.
  (h/with-repl
    (fn [repl _]
      (h/execute repl "c1" "(def report {:rows 3})")
      (h/execute repl "c2" "(defn summarize [m] (:rows m))")
      (let [start (eval-edn repl "c3" "(process-start \"sleep 30\")")]
        (is (= :running (:status start)))
        (testing "names the model defined are still listed by the runtime"
          (let [names (set (eval-edn repl "c4" "(vec (keys (ns-publics 'user)))"))]
            (is (contains? names 'report))
            (is (contains? names 'summarize))
            ;; The listing is the namespace, so it also carries the runtime's own
            ;; bindings. The notice says so rather than pretending otherwise.
            (is (contains? names 'rlm-children))))
        (testing "the values themselves, not just the names"
          (is (= 3 (eval-edn repl "c5" "(summarize report)"))))
        (testing "the process registry is still the same registry"
          (let [listed (eval-edn repl "c6" "(process-list)")]
            (is (= 1 (count listed)))
            (is (= (:process-id start) (:process-id (first listed))))))
        (h/execute repl "c7" (str "(process-kill \"" (:process-id start) "\")"))))))

;; ---------------------------------------------------------------------------
;; restart — a new process, and it says so honestly

(deftest a-restarted-workspace-is-empty-and-serves-normally
  (let [first-run (h/start)]
    (try
      (h/read-event first-run)
      (h/execute first-run "r1" "(def survivor 41)")
      (is (= "41" (result-text (h/execute first-run "r2" "survivor"))))
      (finally
        (h/close! first-run)))
    (h/with-repl
      (fn [repl ready]
        (is (= "ready" (get ready "event")))
        (testing "no var crossed the restart"
          (let [events (h/execute repl "r3" "survivor")
                err (h/one events "error")]
            (is (h/error-shape? err))
            (is (re-find #"survivor" (str (get err "evalue"))))))
        (testing "no snapshot frame is involved — the workspace is simply new"
          (let [names (set (eval-edn repl "r4" "(vec (keys (ns-publics 'user)))"))]
            (is (not (contains? names 'survivor)))
            (is (contains? names 'rlm-children))))
        (testing "the registries start empty, and the runtime keeps working"
          (is (= [] (eval-edn repl "r5" "(process-list)")))
          (is (= 2 (eval-edn repl "r6" "(+ 1 1)"))))))))

(deftest a-restart-leaves-no-command-running
  ;; The registry cannot survive the process, so a command that did would be
  ;; unreachable: no id, no way to poll it, no way to kill it.
  (let [repl (h/start)
        pid (atom nil)]
    (try
      (h/read-event repl)
      (let [start (eval-edn repl "p1" "(process-start \"sleep 300\")")]
        (reset! pid (:pid start))
        (is (pos? (long @pid))))
      (finally
        (h/close! repl)))
    (let [deadline (+ (System/currentTimeMillis) 5000)
          gone? (loop []
                  (let [ph (.orElse (java.lang.ProcessHandle/of (long @pid)) nil)]
                    (cond
                      (or (nil? ph) (not (.isAlive ^java.lang.ProcessHandle ph))) true
                      (>= (System/currentTimeMillis) deadline) false
                      :else (do (Thread/sleep 25) (recur)))))]
      (is gone? (str "pid " @pid " outlived the runtime it was started from")))
    (h/with-repl
      (fn [repl _]
        (is (= [] (eval-edn repl "p2" "(process-list)")))))))
