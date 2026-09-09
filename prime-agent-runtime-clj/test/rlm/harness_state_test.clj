(ns rlm.harness-state-test
  "H10 continual harness state — the workspace's own notes.

  Ported from the oracle's test_harness.py HarnessStateTest. The oracle
  constructs HarnessState(path) in-process; this arm has no in-process SUT, so
  each scenario drives the native runtime with RLM_HARNESS_STATE_DIR pointed at
  a scratch directory. That is also the shape a real session takes — the host
  sets exactly this env (agent-session.ts _rlmKernelEnv)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(defn- scratch!
  "A directory under target/, which is gitignored, so a run never leaves a file
  in the working tree."
  []
  (let [rel (str "target/h10-" (System/nanoTime))]
    (.mkdirs (jio/file rel))
    rel))

(defn- rm-rf!
  [rel]
  (let [f (jio/file rel)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

(defn- state-file
  [dir]
  (jio/file dir "harness_state.json"))

(defn- bump-mtime!
  "Advance mtime past the runtime's last load — same trick as the oracle's os.utime +5s."
  [file]
  (let [f (jio/file file)]
    (.setLastModified f (+ (System/currentTimeMillis) 5000))))

(defn- inject-memory!
  "Write a memory entry into the store file the way the host /refine does — another process, same path."
  [dir id title content]
  (let [f (state-file dir)
        data (if (.exists f)
               (json/read-str (slurp f))
               {"schema" 1
                "entries" {"prompt" {} "memory" {} "skill" {} "subagent" {}}
                "refinements" []})
        entry {"id" id "kind" "memory" "title" title "content" content
               "path" "general" "scope" "local" "reference" {} "arguments" {} "metadata" {}
               "source" "agent"
               "created_at" "2026-09-09T00:00:00+00:00"
               "updated_at" "2026-09-09T00:00:00+00:00"
               "version" 1}]
    (spit f (json/write-str (assoc-in data ["entries" "memory" id] entry)))
    (bump-mtime! f)))

(defn- result-text
  [events]
  (get (h/one events "result") "text"))

(defn- eval-edn
  [repl id code]
  (let [events (h/execute repl id code)]
    (is (nil? (h/one events "error")) code)
    (edn/read-string (result-text events))))

(defn- refused
  "The cell fails and the runtime keeps serving. Returns the message."
  [repl id code]
  (let [events (h/execute repl id code)
        err (h/one events "error")]
    (is (some? err) code)
    (is (h/error-shape? err) code)
    (is (= "error" (get (h/one events "done") "status")) code)
    (get err "evalue")))

(defn- with-store
  "Run f with [repl dir] against a runtime whose local store is a fresh dir.
  extra-env is merged on top, so a scenario can add or drop the global dir."
  ([f] (with-store {} f))
  ([extra-env f]
   (let [dir (scratch!)
         env (merge {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir))} extra-env)
         env (into {} (remove (fn [[_ v]] (nil? v)) env))
         repl (h/start {:env env})]
     (try
       (h/read-event repl)                                  ; ready
       (f repl dir)
       (finally
         (h/close! repl)
         (rm-rf! dir))))))

;; -- CRUD -------------------------------------------------------------------

(deftest crud-round-trip-for-all-entry-kinds
  ;; oracle: HarnessStateTest::test_crud_for_all_entry_kinds
  (with-store
    (fn [repl _]
      (doseq [kind ["prompt" "memory" "skill" "subagent"]]
        (let [id (str kind "_entry")
              created (eval-edn repl (str "c-" kind)
                                (format "(harness-create %s %s %s {:id %s :path %s :metadata {\"kind\" %s}})"
                                        (pr-str kind) (pr-str (str kind " title")) (pr-str (str kind " content"))
                                        (pr-str id) (pr-str (str kind "/path")) (pr-str kind)))]
          (testing (str kind " create")
            (is (= kind (:kind created)))
            (is (= id (:id created)))
            (is (= 1 (:version created)))
            (is (= "local" (:scope created))))
          (testing (str kind " get and list")
            (is (str/includes? (str/lower-case (:content (eval-edn repl (str "g-" kind)
                                                                   (format "(harness-get %s %s)" (pr-str kind) (pr-str id)))))
                               "content"))
            (is (some #{created} (eval-edn repl (str "l-" kind) (format "(harness-list %s)" (pr-str kind))))))))
      (doseq [kind ["prompt" "memory" "skill" "subagent"]]
        (let [id (str kind "_entry")]
          (eval-edn repl (str "u-" kind)
                    (format "(harness-update %s %s %s %s)"
                            (pr-str kind) (pr-str id) (pr-str (str kind " title"))
                            (pr-str (str kind " content updated"))))
          (let [after (eval-edn repl (str "g2-" kind) (format "(harness-get %s %s)" (pr-str kind) (pr-str id)))]
            (is (= 2 (:version after)) kind)
            (is (str/includes? (:content after) "updated") kind))
          (testing (str kind " delete is true once, false after")
            (is (true? (eval-edn repl (str "d-" kind) (format "(harness-delete %s %s)" (pr-str kind) (pr-str id)))))
            (is (nil? (eval-edn repl (str "g3-" kind) (format "(harness-get %s %s)" (pr-str kind) (pr-str id)))))
            (is (false? (eval-edn repl (str "d2-" kind) (format "(harness-delete %s %s)" (pr-str kind) (pr-str id))))))))
      (is (= [] (eval-edn repl "empty" "(harness-list)"))))))

(deftest explicit-create-and-update-enforce-entry-existence
  ;; oracle: HarnessStateTest::test_explicit_create_and_update_enforce_entry_existence
  (with-store
    (fn [repl _]
      (let [first-entry (eval-edn repl "e1" "(harness-create \"skill\" \"Triage\" \"old\" {:id \"triage\"})")
            dup (refused repl "e2" "(harness-create \"skill\" \"Triage\" \"duplicate\" {:id \"triage\"})")
            missing (refused repl "e3" "(harness-update \"skill\" \"missing\" \"Missing\" \"missing\")")
            second-entry (eval-edn repl "e4" "(harness-update \"skill\" \"triage\" \"Triage\" \"new\")")]
        (is (str/includes? dup "already exists"))
        (is (str/includes? missing "does not exist"))
        (is (= (:id first-entry) (:id second-entry)))
        (is (= "new" (:content second-entry)))
        (is (= 2 (:version second-entry)))))))

(deftest update-preserves-omitted-path
  ;; oracle: HarnessStateTest::test_update_preserves_omitted_path
  (with-store
    (fn [repl _]
      (eval-edn repl "p1" "(harness-create \"memory\" \"Grouped\" \"content\" {:id \"grouped\" :path \"repo/testing\"})")
      (eval-edn repl "p2" "(harness-update \"memory\" \"grouped\" \"Grouped\" \"new content\")")
      (is (= "repo/testing" (:path (eval-edn repl "p3" "(harness-get \"memory\" \"grouped\")")))
          "an update that omits :path keeps the grouping path")
      (eval-edn repl "p4" "(harness-update \"memory\" \"grouped\" \"Grouped\" \"newer\" {:path \"repo/other\"})")
      (is (= "repo/other" (:path (eval-edn repl "p5" "(harness-get \"memory\" \"grouped\")")))
          "an explicit :path still moves it"))))

(deftest unknown-kind-rejected
  ;; oracle: HarnessStateTest::test_unknown_kind_rejected
  (with-store
    (fn [repl _]
      (doseq [[id code] [["k1" "(harness-upsert \"tool\" \"Tool\" \"Tool content\")"]
                         ["k2" "(harness-get \"tool\" \"tool\")"]
                         ["k3" "(harness-delete \"tool\" \"tool\")"]
                         ["k4" "(harness-list \"tool\")"]]]
        (is (str/includes? (refused repl id code) "unknown harness kind") code)))))

(deftest slugs-a-missing-id-from-the-title
  ;; oracle: rlm/harness.py _slug, reached whenever create/upsert omits id
  (with-store
    (fn [repl _]
      (let [entry (eval-edn repl "s1" "(harness-create \"memory\" \"  Prefer Focused Patches!  \" \"body\")")]
        (is (= "prefer_focused_patches" (:id entry)))))))

;; -- persistence ------------------------------------------------------------

(deftest persists-entries-and-refinements-across-a-kernel-restart
  ;; oracle: HarnessStateTest::test_persists_entries_and_refinements
  ;; The oracle reloads by constructing a second HarnessState over the same
  ;; file. Here the second reader is a second runtime process, which is the
  ;; stronger claim: the note outlives the kernel, not just the object.
  (let [dir (scratch!)
        env {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir))}]
    (try
      (let [repl (h/start {:env env})]
        (try
          (h/read-event repl)
          (eval-edn repl "w1" (str "(harness-create \"memory\" \"Prefer focused patches\" "
                                   "\"Small harness updates are easier to validate than broad rewrites.\" "
                                   "{:path \"engineering\"})"))
          (eval-edn repl "w2" (str "(harness-create \"skill\" \"Check failures first\" \"Inspect evidence first.\" "
                                   "{:id \"failure_first\" :arguments {\"failure_log\" {\"type\" \"string\"}}})"))
          (eval-edn repl "w3" "(harness-create \"subagent\" \"Reviewer\" \"Review the patch.\" {:metadata {\"max_turns\" 3}})")
          (eval-edn repl "w4" (str "(harness-record-refinement \"skill failed twice\" "
                                   "[\"updated failure_first skill\" \"added reviewer subagent\"] "
                                   "{:evidence \"two failed validations\" :outcome \"next validation passed\"})"))
          (finally (h/close! repl))))
      (let [repl (h/start {:env env})]
        (try
          (h/read-event repl)
          (is (= "Small harness updates are easier to validate than broad rewrites."
                 (:content (eval-edn repl "r1" "(harness-get \"memory\" \"prefer_focused_patches\")"))))
          (let [skill (eval-edn repl "r2" "(harness-get \"skill\" \"failure_first\")")]
            (is (= 1 (:version skill)))
            (is (= "string" (get-in skill [:arguments "failure_log" "type"]))))
          (is (= 3 (get-in (eval-edn repl "r3" "(harness-get \"subagent\" \"reviewer\")") [:metadata "max_turns"])))
          (let [events (eval-edn repl "r4" "(harness-refinements)")]
            (is (= 1 (count events)))
            (is (= "refine_0001" (:id (first events))))
            (is (= ["updated failure_first skill" "added reviewer subagent"] (:changes (first events)))))
          (finally (h/close! repl))))
      (finally (rm-rf! dir)))))

(deftest the-file-the-host-reads-is-the-oracle-shape
  ;; The host builds its prompt block from this file (refinement.ts
  ;; loadHarnessState). The value the workspace sees is Clojure-shaped; the
  ;; file is not, and this is the assertion that keeps those two apart.
  (with-store
    (fn [repl dir]
      (eval-edn repl "f1" "(harness-create \"memory\" \"Note\" \"body\" {:id \"note\" :path \"p\"})")
      (let [data (json/read-str (slurp (state-file dir)))
            entry (get-in data ["entries" "memory" "note"])]
        (is (= 1 (get data "schema")))
        (is (= #{"schema" "entries" "refinements"} (set (keys data))))
        (is (= #{"prompt" "memory" "skill" "subagent"} (set (keys (get data "entries")))))
        (is (= "Note" (get entry "title")))
        (is (= "body" (get entry "content")))
        (is (= "p" (get entry "path")))
        (is (= "local" (get entry "scope")))
        (is (= "agent" (get entry "source")))
        (is (= 1 (get entry "version")))
        (is (string? (get entry "created_at")) "snake_case on disk, kebab in the workspace")
        (is (string? (get entry "updated_at")))))))

;; -- tolerant load ----------------------------------------------------------

(deftest load-ignores-unknown-json-keys-and-coerces-bad-types
  ;; oracle: HarnessStateTest::test_load_ignores_unknown_json_keys
  (let [dir (scratch!)]
    (try
      (spit (state-file dir)
            (json/write-str
             {"schema" 1
              "entries" {"memory" {"known" {"id" "mismatched"
                                            "kind" "skill"
                                            "title" "Known memory"
                                            "content" "Loaded despite extra keys."
                                            "path" 123
                                            "source" nil
                                            "version" "2"
                                            "metadata" "not a dict"
                                            "unexpected" true}
                                   "missing_content" {"title" "Missing content"}}}
              "refinements" [{"id" "refine_extra" "trigger" "extra keys"
                              "changes" [1 "loaded"] "ignored" "value"}
                             {"id" "refine_missing_changes" "trigger" "missing changes"}]}))
      (let [repl (h/start {:env {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir))}})]
        (try
          (h/read-event repl)
          (let [known (eval-edn repl "j1" "(harness-get \"memory\" \"known\")")]
            (is (= "Loaded despite extra keys." (:content known)))
            (is (= "known" (:id known)) "the map key wins over a stored id")
            (is (= "memory" (:kind known)) "the containing kind wins over a stored kind")
            (is (= "general" (:path known)) "a non-string path falls back")
            (is (= "agent" (:source known)) "a null source falls back")
            (is (= 2 (:version known)) "a string version is parsed")
            (is (= {} (:metadata known)) "a non-map metadata falls back")
            (is (not (contains? known :unexpected)) "unknown keys do not survive the load"))
          (is (nil? (eval-edn repl "j2" "(harness-get \"memory\" \"mismatched\")")))
          (is (nil? (eval-edn repl "j3" "(harness-get \"memory\" \"missing_content\")"))
              "an entry with no content is dropped, not half-loaded")
          (let [events (eval-edn repl "j4" "(harness-refinements)")]
            (is (= 1 (count events)) "a refinement with no changes is dropped")
            (is (= "refine_extra" (:id (first events))))
            (is (= ["1" "loaded"] (:changes (first events))) "changes are stringified"))
          (is (= 3 (:version (eval-edn repl "j5" "(harness-update \"memory\" \"known\" \"Known memory\" \"Updated content.\")")))
              "the loaded version is the base the next update increments")
          (finally (h/close! repl))))
      (finally (rm-rf! dir)))))

(deftest load-tolerates-corrupt-or-non-object-state-and-self-heals
  ;; oracle: HarnessStateTest::test_load_tolerates_corrupt_or_non_object_state
  (doseq [payload ["not json at all" "null" "[]" "\"a string\"" "123"]]
    (let [dir (scratch!)]
      (try
        (spit (state-file dir) payload)
        (let [repl (h/start {:env {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir))}})]
          (try
            (h/read-event repl)
            (is (= [] (eval-edn repl "c1" "(harness-list)")) payload)
            (is (= [] (eval-edn repl "c2" "(harness-refinements)")) payload)
            (let [created (eval-edn repl "c3" "(harness-create \"memory\" \"Recovered\" \"Works after corruption.\" {:id \"recovered\"})")]
              (is (= "Works after corruption." (:content created)) payload))
            (finally (h/close! repl))))
        ;; The next reader sees the healed file, not the corrupt bytes.
        (let [repl (h/start {:env {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir))}})]
          (try
            (h/read-event repl)
            (is (= "Works after corruption."
                   (:content (eval-edn repl "c4" "(harness-get \"memory\" \"recovered\")")))
                payload)
            (finally (h/close! repl))))
        (finally (rm-rf! dir))))))

;; -- refinements ------------------------------------------------------------

(deftest record-refinement-accepts-a-single-change-string
  ;; oracle: HarnessStateTest::test_record_refinement_accepts_single_change_string
  (with-store
    (fn [repl _]
      (let [event (eval-edn repl "rf1" "(harness-record-refinement \"manual cli test\" \"single change\")")]
        (is (= ["single change"] (:changes event)))
        (is (= [["single change"]] (mapv :changes (eval-edn repl "rf2" "(harness-refinements)"))))))))

;; -- scope ------------------------------------------------------------------

(deftest a-local-store-with-no-directory-refuses-writes-out-loud-and-still-reads
  ;; oracle: HarnessStateTest::test_module_harness_without_env_raises_on_local_writes_and_reads_work
  ;; Wording deviation (recorded in docs/clojure-runtime.md): the oracle's
  ;; message ends in `global_=True`; this arm names `:global true`, because a
  ;; Python call shape inside the Clojure arm is the leak this fork measures.
  (let [dir (scratch!)
        repl (h/start {:env {"RLM_GLOBAL_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir "global"))}})]
    (try
      (h/read-event repl)
      (doseq [[id code] [["n1" "(harness-create \"memory\" \"Lost\" \"content\" {:id \"lost\"})"]
                         ["n2" "(harness-update \"memory\" \"lost\" \"Lost\" \"content\")"]
                         ["n3" "(harness-delete \"memory\" \"lost\")"]
                         ["n4" "(harness-upsert \"memory\" \"Lost\" \"content\" {:id \"lost\"})"]
                         ["n5" "(harness-record-refinement \"trigger\" [\"change\"])"]]]
        (let [msg (refused repl id code)]
          (is (str/includes? msg "Local harness state requires") code)
          (is (str/includes? msg ":global true") code)))
      (is (nil? (eval-edn repl "n6" "(harness-get \"memory\" \"lost\")")) "reads answer against an empty view")
      (is (= [] (eval-edn repl "n7" "(harness-list)")))
      (is (= [] (eval-edn repl "n8" "(harness-refinements)")))
      (finally
        (h/close! repl)
        (rm-rf! dir)))))

(deftest a-local-store-with-no-directory-still-routes-global-writes
  ;; oracle: HarnessStateTest::test_module_harness_without_env_still_routes_global_writes
  (let [dir (scratch!)
        global-dir (jio/file dir "global")
        repl (h/start {:env {"RLM_GLOBAL_HARNESS_STATE_DIR" (.getAbsolutePath global-dir)}})]
    (try
      (h/read-event repl)
      (let [entry (eval-edn repl "g1" "(harness-create \"memory\" \"Lesson\" \"keep me\" {:id \"no_session_lesson\" :global true})")]
        (is (= "global" (:scope entry)))
        (is (= "keep me"
               (get-in (json/read-str (slurp (jio/file global-dir "harness_state.json")))
                       ["entries" "memory" "no_session_lesson" "content"]))
            "the global store is the file the host reads for cross-session state"))
      (finally
        (h/close! repl)
        (rm-rf! dir)))))

(deftest a-scope-prefixed-id-routes-to-that-store
  ;; oracle: rlm/harness.py _strip_scope_prefix -- overview() renders ids as
  ;; [local:id]/[global:id] and every verb accepts them back verbatim.
  (let [dir (scratch!)
        global-dir (jio/file dir "global")]
    (try
      (let [repl (h/start {:env {"RLM_HARNESS_STATE_DIR" (.getAbsolutePath (jio/file dir "local"))
                                 "RLM_GLOBAL_HARNESS_STATE_DIR" (.getAbsolutePath global-dir)}})]
        (try
          (h/read-event repl)
          (let [entry (eval-edn repl "sp1" "(harness-create \"memory\" \"Prefixed\" \"body\" {:id \"global:pref\"})")]
            (is (= "global" (:scope entry)) "a global: prefix routes the write")
            (is (= "pref" (:id entry)) "the prefix is stripped from the stored id"))
          (is (nil? (eval-edn repl "sp2" "(harness-get \"memory\" \"pref\")")) "it is not in the local store")
          (is (= "body" (:content (eval-edn repl "sp3" "(harness-get \"memory\" \"global:pref\")"))))
          (finally (h/close! repl))))
      (finally (rm-rf! dir)))))

;; -- sandbox boundary -------------------------------------------------------

(deftest a-cell-cannot-choose-where-the-store-lives
  ;; negative contract, no parity credit: :state-dir exists on the Clojure API
  ;; so the SUT can drive several stores, and is stripped before a cell's opts
  ;; reach it. Letting it through would reopen the file-path escape hatch that
  ;; closing spit and slurp closed.
  (with-store
    (fn [repl dir]
      (let [escape (.getAbsolutePath (jio/file dir "escape"))]
        (eval-edn repl "b1" (format "(harness-create \"memory\" \"Boundary\" \"body\" {:id \"boundary\" :state-dir %s})"
                                    (pr-str escape)))
        (is (not (.exists (jio/file escape "harness_state.json")))
            ":state-dir from a cell must not create a store")
        (is (= "body" (:content (eval-edn repl "b2" "(harness-get \"memory\" \"boundary\")")))
            "the write landed in the store the host chose")))))

;; -- external write (host /refine on the same file) -------------------------

(deftest reloads-external-writes-before-mutating
  ;; oracle: HarnessStateTest::test_reloads_external_writes_before_mutating
  (with-store
    (fn [repl dir]
      (eval-edn repl "x1" "(harness-create \"memory\" \"Kernel note\" \"Written from the kernel.\" {:id \"kernel\"})")
      (inject-memory! dir "host" "Host note" "Written by /refine.")
      (is (= "Written by /refine."
             (:content (eval-edn repl "x2" "(harness-get \"memory\" \"host\")")))
          "a read on the long-lived runtime observes the host write")
      (eval-edn repl "x3" "(harness-create \"memory\" \"Second kernel note\" \"Written later.\" {:id \"kernel_2\"})")
      (let [mem (get-in (json/read-str (slurp (state-file dir))) ["entries" "memory"])]
        (is (contains? mem "kernel"))
        (is (contains? mem "host"))
        (is (contains? mem "kernel_2")
            "a mutation merges onto the host write instead of clobbering it")))))

(deftest create-detects-externally-written-entry
  ;; oracle: HarnessStateTest::test_create_detects_externally_written_entry
  (with-store
    (fn [repl dir]
      ;; Cache a live store the way the oracle constructs HarnessState first.
      ;; A bare harness-list on a missing file leaves loaded-mtime nil and does
      ;; not isolate sync-from-disk from new-store's initial read-state.
      (eval-edn repl "y0" "(harness-create \"memory\" \"Anchor\" \"cached\" {:id \"anchor\"})")
      (inject-memory! dir "dup" "External" "Written elsewhere.")
      (let [msg (refused repl "y1" "(harness-create \"memory\" \"Local\" \"Should not overwrite.\" {:id \"dup\"})")]
        (is (str/includes? msg "already exists")))
      (is (= "Written elsewhere."
             (:content (eval-edn repl "y2" "(harness-get \"memory\" \"dup\")")))
          "create-or-fail leaves the external entry intact"))))

;; -- scope routing: which env dir owns which scope ---------------------------

(defn- with-env
  "Run f with [repl dir] against a runtime started with exactly this env on top
  of the parent's. Nothing here sets RLM_HARNESS_STATE_DIR for you — these
  scenarios are about which env var the runtime reaches for."
  [env-fn f]
  (let [dir (scratch!)
        repl (h/start {:env (env-fn dir)})]
    (try
      (h/read-event repl)                                   ; ready
      (f repl dir)
      (finally
        (h/close! repl)
        (rm-rf! dir)))))

(defn- apath
  [& parts]
  (.getAbsolutePath ^java.io.File (apply jio/file parts)))

(deftest the-local-env-dir-is-the-local-store
  ;; oracle: HarnessStateTest::test_default_state_uses_global_harness_env_dir
  ;; (the name says global; the assertion is that a DEFAULT store resolves to
  ;; RLM_HARNESS_STATE_DIR/harness_state.json.)
  (with-env
    (fn [dir] {"RLM_HARNESS_STATE_DIR" (apath dir "local")
               "RLM_GLOBAL_HARNESS_STATE_DIR" (apath dir "global")})
    (fn [repl dir]
      (eval-edn repl "le1" "(harness-create \"memory\" \"Session note\" \"local body\" {:id \"session_note\"})")
      (is (= "local body"
             (get-in (json/read-str (slurp (jio/file dir "local" "harness_state.json")))
                     ["entries" "memory" "session_note" "content"]))
          "a default write lands in RLM_HARNESS_STATE_DIR/harness_state.json")
      (is (not (.exists (jio/file dir "global" "harness_state.json")))
          "and the global env dir is untouched by a default write"))))

(deftest a-global-write-lands-in-the-global-env-dir
  ;; oracle: HarnessStateTest::test_global_scope_default_state_uses_global_harness_env_dir
  (with-env
    (fn [dir] {"RLM_HARNESS_STATE_DIR" (apath dir "local")
               "RLM_GLOBAL_HARNESS_STATE_DIR" (apath dir "global")})
    (fn [repl dir]
      (let [entry (eval-edn repl "ge1" "(harness-create \"memory\" \"Cross-session\" \"global body\" {:id \"cross\" :global true})")]
        (is (= "global" (:scope entry))))
      (is (= "global body"
             (get-in (json/read-str (slurp (jio/file dir "global" "harness_state.json")))
                     ["entries" "memory" "cross" "content"]))
          "a :global write lands in RLM_GLOBAL_HARNESS_STATE_DIR, not the local one")
      (is (not (.exists (jio/file dir "local" "harness_state.json")))
          "the local store is not even created"))))

(deftest default-scope-is-local-and-the-global-flag-routes-across
  ;; oracle: HarnessStateTest::test_default_state_is_local_and_global_flag_targets_global_store
  (with-env
    (fn [dir] {"RLM_HARNESS_STATE_DIR" (apath dir "local")
               "RLM_GLOBAL_HARNESS_STATE_DIR" (apath dir "global")})
    (fn [repl dir]
      (is (= "local" (:scope (eval-edn repl "ds1" "(harness-create \"memory\" \"Local note\" \"only this session\" {:id \"local_note\"})"))))
      (is (= "global" (:scope (eval-edn repl "ds2" "(harness-create \"memory\" \"Global note\" \"all sessions\" {:id \"global_note\" :global true})"))))
      (let [local-mem (get-in (json/read-str (slurp (jio/file dir "local" "harness_state.json"))) ["entries" "memory"])
            global-mem (get-in (json/read-str (slurp (jio/file dir "global" "harness_state.json"))) ["entries" "memory"])]
        (is (= #{"local_note"} (set (keys local-mem))) "the local store holds only the local note")
        (is (= #{"global_note"} (set (keys global-mem))) "the global store holds only the global note"))
      (is (nil? (eval-edn repl "ds3" "(harness-get \"memory\" \"global_note\")"))
          "a default read does not see the global entry")
      (is (= "all sessions" (:content (eval-edn repl "ds4" "(harness-get \"memory\" \"global_note\" {:global true})")))
          "and the same id read with :global true does"))))

(deftest an-empty-or-blank-local-env-dir-is-unset
  ;; oracle: HarnessStateTest::test_empty_local_state_dir_env_is_treated_as_unset
  ;; Three runtimes, because a native process cannot rewrite its own env: an
  ;; empty local dir must not fall through to the global default, a session dir
  ;; is the documented fallback, and whitespace is unset too.
  (testing "an empty local dir refuses rather than falling through to the global default"
    (with-env
      (fn [_] {"RLM_HARNESS_STATE_DIR" ""})
      (fn [repl _]
        (is (str/includes? (refused repl "ee1" "(harness-create \"memory\" \"Lost\" \"body\" {:id \"lost\"})")
                           "Local harness state requires")))))
  (testing "with a session dir it takes the session fallback"
    (with-env
      (fn [dir] {"RLM_HARNESS_STATE_DIR" "" "RLM_SESSION_DIR" (apath dir "session")})
      (fn [repl dir]
        (eval-edn repl "ee2" "(harness-create \"memory\" \"Session\" \"body\" {:id \"sess\"})")
        (is (.exists (jio/file dir "session" "harness" "harness_state.json"))
            "the session fallback is RLM_SESSION_DIR/harness/harness_state.json"))))
  (testing "a whitespace-only session dir is unset too"
    (with-env
      (fn [_] {"RLM_HARNESS_STATE_DIR" "" "RLM_SESSION_DIR" "   "})
      (fn [repl _]
        (is (str/includes? (refused repl "ee3" "(harness-create \"memory\" \"Lost\" \"body\" {:id \"lost\"})")
                           "Local harness state requires"))))))

(deftest local-and-global-stay-distinct-when-they-share-one-file
  ;; oracle: HarnessStateTest::test_state_cache_keeps_scope_distinct_when_local_and_global_share_a_file
  ;; The oracle keys its cache on (path, scope) and asserts the two stores are
  ;; not the same object. This arm has no object to hand a cell, so the same
  ;; contract is observed where it matters: the scope each entry carries.
  (with-env
    (fn [dir] {"RLM_HARNESS_STATE_DIR" (apath dir "shared")
               "RLM_GLOBAL_HARNESS_STATE_DIR" (apath dir "shared")})
    (fn [repl dir]
      (is (= "local" (:scope (eval-edn repl "sh1" "(harness-create \"memory\" \"Local note\" \"only this session\" {:id \"local_note\"})"))))
      (is (= "global" (:scope (eval-edn repl "sh2" "(harness-create \"memory\" \"Global note\" \"all sessions\" {:id \"global_note\" :global true})"))))
      (let [mem (get-in (json/read-str (slurp (jio/file dir "shared" "harness_state.json"))) ["entries" "memory"])]
        (is (= "local" (get-in mem ["local_note" "scope"])))
        (is (= "global" (get-in mem ["global_note" "scope"]))
            "one file, two scopes -- the global write did not clobber the local store"))
      (is (= "only this session" (:content (eval-edn repl "sh3" "(harness-get \"memory\" \"local_note\")")))
          "both entries survive the shared file"))))

(deftest a-displayed-scope-prefix-round-trips-through-update-and-delete
  ;; oracle: HarnessStateTest::test_scope_prefixed_ids_route_to_the_displayed_scope
  ;; H10.11 covers create; this covers the rest of the round trip -- the id the
  ;; host displays has to be usable on update, get and delete without :global.
  (with-env
    (fn [dir] {"RLM_HARNESS_STATE_DIR" (apath dir "local")
               "RLM_GLOBAL_HARNESS_STATE_DIR" (apath dir "global")})
    (fn [repl _]
      (eval-edn repl "rt1" "(harness-create \"memory\" \"Global note\" \"v1\" {:id \"routed\" :global true})")
      (is (= "global" (:scope (eval-edn repl "rt2" "(harness-update \"memory\" \"global:routed\" \"Global note\" \"v2\")")))
          "a global: prefix carries the scope on update without :global true")
      (is (= "v2" (:content (eval-edn repl "rt3" "(harness-get \"memory\" \"global:routed\")"))))
      (is (nil? (eval-edn repl "rt4" "(harness-get \"memory\" \"routed\")"))
          "the bare id still means the local store")
      (eval-edn repl "rt5" "(harness-create \"memory\" \"Local note\" \"local\" {:id \"local_note\"})")
      (is (= "local" (:content (eval-edn repl "rt6" "(harness-get \"memory\" \"local:local_note\")"))))
      (is (true? (eval-edn repl "rt7" "(harness-delete \"memory\" \"local:local_note\")")))
      (is (nil? (eval-edn repl "rt8" "(harness-get \"memory\" \"local_note\")"))
          "a local: prefixed delete removes the local entry"))))

;; -- skills: arguments and reference are data -------------------------------

(def ^:private clj-reference
  ;; Deliberately NOT the oracle's PYTHON_REFERENCE. The oracle enforces
  ;; reference.type == 'python'; this arm stores whatever shape the workspace
  ;; hands it, because planting a Python call shape inside the Clojure arm is
  ;; the leak this fork exists to measure.
  "{\"type\" \"clojure\" \"var\" \"agent-skills.file-edit/edit-file\" \"call-pattern\" \"(edit-file {:path ... :find ... :replace ...})\"}")

(deftest skill-arguments-and-reference-are-first-class-data
  ;; oracle: HarnessStateTest::test_skill_arguments_are_first_class
  ;; The oracle's last two assertions read overview(); in this arm the prompt
  ;; block is rendered by the HOST from the file (refinement.ts
  ;; loadHarnessState), so the same contract is read there instead -- the same
  ;; split row H10.9 draws between the workspace value and the file.
  (with-store
    (fn [repl dir]
      (let [created (eval-edn repl "sk1"
                              (format (str "(harness-create \"skill\" \"Edit file\" \"Apply a targeted edit.\" "
                                           "{:id \"edit_file\" :reference %s "
                                           ":arguments {\"path\" {\"type\" \"string\" \"required\" true} "
                                           "\"find\" {\"type\" \"string\" \"required\" true} "
                                           "\"replace\" {\"type\" \"string\" \"required\" true}}})")
                                      clj-reference))]
        (is (true? (get-in created [:arguments "path" "required"])) "an argument spec survives as a nested map")
        (is (= "clojure" (get-in created [:reference "type"])) "the reference is stored as the arm handed it over"))
      (let [updated (eval-edn repl "sk2"
                              (format (str "(harness-update \"skill\" \"edit_file\" \"Edit file\" "
                                           "\"Apply a targeted edit after reading context.\" "
                                           "{:reference %s "
                                           ":arguments {\"path\" {\"type\" \"string\" \"required\" true} "
                                           "\"find\" {\"type\" \"string\" \"required\" true} "
                                           "\"replace\" {\"type\" \"string\" \"required\" true} "
                                           "\"validate\" {\"type\" \"boolean\" \"default\" true}}})")
                                      clj-reference))]
        (is (= 2 (:version updated))))
      (let [reloaded (eval-edn repl "sk3" "(harness-get \"skill\" \"edit_file\")")]
        (is (true? (get-in reloaded [:arguments "validate" "default"])) "the added argument survives the round trip")
        (is (= "agent-skills.file-edit/edit-file" (get-in reloaded [:reference "var"]))))
      (let [text (slurp (state-file dir))]
        ;; NOT "path": every entry carries a path field, so that string is
        ;; satisfied by the grouping path and the assertion would not bite.
        (is (str/includes? text "\"validate\"") "the file the host renders its prompt block from carries the argument names")
        (is (str/includes? text "agent-skills.file-edit") "and the reference the arm chose, not a python import")))))

(deftest an-update-that-omits-arguments-keeps-them-and-an-empty-map-clears-them
  ;; oracle: HarnessStateTest::test_update_skill_preserves_omitted_arguments
  (with-store
    (fn [repl _]
      (eval-edn repl "sa1"
                (format (str "(harness-create \"skill\" \"Edit file\" \"Apply an edit.\" "
                             "{:id \"edit_file\" :reference %s "
                             ":arguments {\"path\" {\"type\" \"string\" \"required\" true}}})")
                        clj-reference))
      (eval-edn repl "sa2"
                (format "(harness-update \"skill\" \"edit_file\" \"Edit file\" \"Apply an edit carefully.\" {:reference %s})"
                        clj-reference))
      (is (= {"path" {"type" "string" "required" true}}
             (:arguments (eval-edn repl "sa3" "(harness-get \"skill\" \"edit_file\")")))
          "omitting :arguments keeps the contract")
      (eval-edn repl "sa4"
                (format "(harness-update \"skill\" \"edit_file\" \"Edit file\" \"Now argument-free.\" {:reference %s :arguments {}})"
                        clj-reference))
      (is (= {} (:arguments (eval-edn repl "sa5" "(harness-get \"skill\" \"edit_file\")")))
          "an explicit empty map still clears it"))))

(deftest an-update-that-omits-the-reference-keeps-the-whole-contract
  ;; oracle: HarnessStateTest::test_update_skill_without_reference_preserves_contract
  (with-store
    (fn [repl _]
      (eval-edn repl "sr1"
                (format (str "(harness-create \"skill\" \"Edit file\" \"Apply an edit.\" "
                             "{:id \"edit_file\" :reference %s "
                             ":arguments {\"path\" {\"type\" \"string\" \"required\" true}}})")
                        clj-reference))
      (let [updated (eval-edn repl "sr2" "(harness-update \"skill\" \"edit_file\" \"Edit file\" \"Apply an edit carefully.\")")]
        (is (= 2 (:version updated)))
        (is (= "Apply an edit carefully." (:content updated)))
        (is (= "agent-skills.file-edit/edit-file" (get-in updated [:reference "var"]))
            "a title/content-only update does not require re-sending the reference")
        (is (= {"path" {"type" "string" "required" true}} (:arguments updated))
            "and it does not silently drop the arguments")))))
