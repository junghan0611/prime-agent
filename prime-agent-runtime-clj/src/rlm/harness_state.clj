(ns rlm.harness-state
  "Continual harness state — the workspace's own notes, ported from the oracle's
  rlm/harness.py HarnessState.

  Called only from host IFns interned into SCI, never as SCI source: the
  sandbox has no file access, and this is the one store that has to outlive a
  cell. The file format is the oracle's byte-for-byte in shape (snake_case keys,
  schema 1) because the HOST reads the same file to build the prompt block —
  the value handed back to the workspace is Clojure-shaped instead, the same
  split write-text already makes between the file and its receipt.

  Two deviations are recorded in docs/clojure-runtime.md rather than hidden:
  the entry map the workspace sees uses kebab keywords, and timestamps are
  java.time.Instant (…Z) where the oracle writes +00:00."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path Paths]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def kinds
  "Entry kinds, in the oracle's declaration order (rlm/harness.py HarnessKind)."
  ["prompt" "memory" "skill" "subagent"])

(def ^:private kind-set (set kinds))

(def ^:private file-name "harness_state.json")
(def ^:private harness-dir-name "harness")

(def ^:private local-dir-missing
  "Local harness state requires RLM_HARNESS_STATE_DIR or RLM_SESSION_DIR. Pass :global true for global state.")

;; -- paths ------------------------------------------------------------------

(defn- links
  ^"[Ljava.nio.file.LinkOption;" []
  (into-array LinkOption []))

(defn- open-opts
  "Typed on purpose — an untyped varargs array makes Files/write reflective, and
  a reflective call dies in the native image (rlm.io says the same thing)."
  ^"[Ljava.nio.file.OpenOption;" []
  (into-array OpenOption []))

(defn- no-attrs
  ^"[Ljava.nio.file.attribute.FileAttribute;" []
  (into-array FileAttribute []))

(defn- as-path
  ^Path [^String p]
  (Paths/get p (into-array String [])))

(defn- expand-user
  ^String [^String p]
  (if (str/starts-with? p "~")
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn- absolute
  "Python resolves with Path.resolve(); on a path that does not exist yet that is
  normalization, which is what this is. Symlink resolution of existing parents is
  the recorded gap, not a silent one."
  ^Path [^String p]
  (.normalize (.toAbsolutePath (as-path (expand-user p)))))

(defn- env-dir
  "Set-but-empty behaves as unset. A bare \"\" would skip the session-dir
  fallback and land local writes in the global default — the oracle's comment."
  ^String [^String name]
  (let [v (System/getenv name)]
    (when (some? v)
      (let [t (str/trim v)]
        (when (pos? (count t)) t)))))

(defn- agent-dir
  ^Path []
  (absolute (or (env-dir "PRIME_AGENT_CODING_AGENT_DIR")
                (env-dir "PI_CODING_AGENT_DIR")
                (str (System/getProperty "user.home") "/.prime/agent"))))

(defn state-file
  "Resolve the state file. Throws when a local store has no directory to live in."
  ^Path [state-dir global?]
  (let [root (or state-dir
                 (env-dir (if global? "RLM_GLOBAL_HARNESS_STATE_DIR" "RLM_HARNESS_STATE_DIR"))
                 (when-not global?
                   (when-let [sd (env-dir "RLM_SESSION_DIR")]
                     (str sd "/" harness-dir-name))))]
    (cond
      ;; Every argument below is hinted even though the def it comes from is a
      ;; String: a var deref leaves the Path.resolve / RuntimeException overload
      ;; unresolved, and the build gate turns that warning into a failure.
      (some? root) (.resolve (absolute root) ^String file-name)
      global? (.resolve (.resolve (agent-dir) ^String harness-dir-name) ^String file-name)
      :else (throw (RuntimeException. ^String local-dir-missing)))))

;; -- values -----------------------------------------------------------------

(defn- now-iso
  ^String []
  (str (Instant/now)))

(defn slug
  "Oracle _slug: alnum lowercased, everything else a separator, runs collapsed,
  empty falls back, capped at 80."
  [^String raw ^String fallback]
  (let [normalized (->> (str/trim raw)
                        (map (fn [^Character ch]
                               (if (Character/isLetterOrDigit ch)
                                 (Character/toLowerCase ch)
                                 \_)))
                        (apply str))
        joined (str/join "_" (remove empty? (str/split normalized #"_")))
        chosen (if (empty? joined) fallback joined)]
    (subs chosen 0 (min 80 (count chosen)))))

(defn- strip-scope-prefix
  "overview() renders ids as [local:id]/[global:id]; accept those verbatim."
  [id global?]
  (if (string? id)
    (let [i (.indexOf ^String id ":")]
      (if (pos? i)
        (let [scope (subs id 0 i)
              rest (subs id (inc i))]
          (if (and (pos? (count rest)) (contains? #{"local" "global"} scope))
            [rest (or global? (= "global" scope))]
            [id global?]))
        [id global?]))
    [id global?]))

(defn- str-map
  "A map of string->value, or {} — the oracle's `if not isinstance(x, dict)` guard."
  [v]
  (if (map? v) v {}))

(defn- as-version
  [v]
  (cond
    (integer? v) v
    (string? v) (try (Long/parseLong ^String v) (catch NumberFormatException _ 1))
    :else 1))

(defn- entry<-json
  "One stored entry, or nil when the oracle would have dropped it."
  [scope kind entry-id raw]
  (when (map? raw)
    (let [title (get raw "title")
          content (get raw "content")]
      (when (and (string? title) (string? content))
        (let [path (get raw "path")
              stored-scope (get raw "scope")
              source (get raw "source")]
          {:id (str entry-id)
           :kind kind
           :title title
           :content content
           :path (if (string? path) path "general")
           :scope (if (contains? #{"local" "global"} stored-scope) stored-scope scope)
           :reference (str-map (get raw "reference"))
           :arguments (str-map (get raw "arguments"))
           :metadata (str-map (get raw "metadata"))
           :source (if (string? source) source "agent")
           :created-at (let [v (get raw "created_at")] (if (string? v) v (now-iso)))
           :updated-at (let [v (get raw "updated_at")] (if (string? v) v (now-iso)))
           :version (as-version (get raw "version" 1))})))))

(defn- entry->json
  [entry]
  {"id" (:id entry)
   "kind" (:kind entry)
   "title" (:title entry)
   "content" (:content entry)
   "path" (:path entry)
   "scope" (:scope entry)
   "reference" (:reference entry)
   "arguments" (:arguments entry)
   "metadata" (:metadata entry)
   "source" (:source entry)
   "created_at" (:created-at entry)
   "updated_at" (:updated-at entry)
   "version" (:version entry)})

(defn- refinement<-json
  [raw]
  (when (map? raw)
    (let [id (get raw "id")
          trigger (get raw "trigger")
          changes (get raw "changes")]
      (when (and (string? id) (string? trigger))
        (let [normalized (cond
                           (string? changes) [changes]
                           (sequential? changes) (mapv str changes)
                           :else ::drop)]
          (when-not (= ::drop normalized)
            (let [evidence (get raw "evidence")
                  outcome (get raw "outcome")]
              {:id id
               :trigger trigger
               :changes normalized
               :evidence (if (string? evidence) evidence "")
               :outcome (if (string? outcome) outcome "")
               :created-at (let [v (get raw "created_at")] (if (string? v) v (now-iso)))})))))))

(defn- refinement->json
  [event]
  {"id" (:id event)
   "trigger" (:trigger event)
   "changes" (:changes event)
   "evidence" (:evidence event)
   "outcome" (:outcome event)
   "created_at" (:created-at event)})

;; -- store ------------------------------------------------------------------

(defn- empty-entries [] (into {} (map (fn [k] [k {}]) kinds)))

(defn- disk-mtime
  "Nanoseconds, matching the oracle's st_mtime_ns. Milliseconds would let a
  same-millisecond outside write slip past the out-of-process guard."
  [^Path file]
  (when (and (some? file) (Files/isRegularFile file (links)))
    (try (.to (Files/getLastModifiedTime file (links)) java.util.concurrent.TimeUnit/NANOSECONDS)
         (catch Exception _ nil))))

(defn- read-state
  "Load from disk into a store map. A corrupt or unreadable file is an empty
  store, never a dead kernel — the next save rewrites it cleanly."
  [store]
  (let [^Path file (:file store)
        mtime (disk-mtime file)
        data (if (and (some? file) (Files/isRegularFile file (links)))
               (try
                 (let [parsed (json/read-str (String. ^bytes (Files/readAllBytes file)
                                                      StandardCharsets/UTF_8))]
                   (if (map? parsed) parsed {}))
                 (catch Exception _ {}))
               {})
        raw-entries (str-map (get data "entries"))
        entries (into {}
                      (map (fn [kind]
                             [kind (into {}
                                         (keep (fn [[entry-id raw]]
                                                 (when-let [e (entry<-json (:scope store) kind entry-id raw)]
                                                   [(str entry-id) e]))
                                               (str-map (get raw-entries kind))))])
                           kinds))
        raw-refinements (get data "refinements")
        refinements (if (sequential? raw-refinements)
                      (vec (keep refinement<-json raw-refinements))
                      [])]
    (assoc store :entries entries :refinements refinements :loaded-mtime mtime)))

(defn- write-state!
  [store]
  (let [^Path file (:file store)]
    (if (nil? file)
      store
      (do
        (when-let [^Path parent (.getParent file)]
          (Files/createDirectories parent (no-attrs)))
        (let [payload {"schema" 1
                       "entries" (into {} (map (fn [[kind records]]
                                                 [kind (into {} (map (fn [[id e]] [id (entry->json e)]) records))])
                                               (:entries store)))
                       "refinements" (mapv refinement->json (:refinements store))}
              ^String text (json/write-str payload :indent true)
              ^bytes bytes (.getBytes text StandardCharsets/UTF_8)]
          (Files/write file bytes (open-opts)))
        (assoc store :loaded-mtime (disk-mtime file))))))

(defn- sync-from-disk
  "Reload when another process rewrote the file since we last touched it — the
  host's /refine writes the same file from outside this process."
  [store]
  (if (= (disk-mtime (:file store)) (:loaded-mtime store))
    store
    (read-state store)))

(defonce ^:private cache (atom {}))

(defn reset-cache!
  "Drop every cached store. Exists for the SUT, which drives one process across
  several state dirs; nothing in the workspace calls it."
  []
  (reset! cache {}))

(defn- new-store
  [^Path file scope local-write-error]
  (read-state {:file file
               :scope scope
               :local-write-error local-write-error
               :entries (empty-entries)
               :refinements []
               :loaded-mtime nil}))

(defn- store-atom
  "The cached store for one (file, scope). Local resolution that fails yields a
  fileless store whose local writes raise and whose reads still answer."
  [state-dir global?]
  (let [scope (if global? "global" "local")]
    (if-let [^Path file (try (state-file state-dir global?)
                             (catch RuntimeException e
                               (if global? (throw e) nil)))]
      (let [key [(str file) scope]]
        (or (get @cache key)
            (let [a (atom (new-store file scope nil))]
              (swap! cache assoc key a)
              (get @cache key))))
      (let [key [::no-local-dir scope]]
        (or (get @cache key)
            (let [a (atom (new-store nil scope local-dir-missing))]
              (swap! cache assoc key a)
              (get @cache key)))))))

(defn- ensure-writable!
  [store]
  (when-let [msg (:local-write-error store)]
    (throw (RuntimeException. ^String msg))))

(defn- check-kind!
  [kind]
  (when-not (contains? kind-set kind)
    (throw (IllegalArgumentException.
            (str "unknown harness kind " (pr-str kind) "; expected one of " (pr-str kinds))))))

;; -- operations -------------------------------------------------------------

(defn- opt-global? [opts] (boolean (get opts :global)))

(defn- do-upsert
  "Caller has already synced. create/update sync once and land here so their
  existence check and the write are not separated by a second reload — that gap
  is what turns create-or-fail into a silent update."
  [store kind entry-id title content {:keys [path reference arguments metadata source]
                                      :or {source "agent"}}]
  (let [existing (get-in store [:entries kind entry-id])
        entry (if existing
                (cond-> (assoc existing :title title :content content :source source
                               :updated-at (now-iso) :version (inc (:version existing)))
                  ;; nil means "caller omitted it": keep the grouping path and a
                  ;; skill's reference/arguments contract. An explicit {} still wipes.
                  (some? path) (assoc :path path)
                  (some? reference) (assoc :reference reference)
                  (some? arguments) (assoc :arguments arguments)
                  (some? metadata) (assoc :metadata metadata))
                (let [ts (now-iso)]
                  {:id entry-id :kind kind :title title :content content
                   :path (if (some? path) path "general")
                   :scope (:scope store)
                   :reference (or reference {}) :arguments (or arguments {}) :metadata (or metadata {})
                   :source source :created-at ts :updated-at ts :version 1}))]
    [(write-state! (assoc-in store [:entries kind entry-id] entry)) entry]))

(defn- routed
  "Resolve the store this call belongs to, after scope-prefix stripping."
  [id opts]
  (let [[stripped global?] (strip-scope-prefix id (opt-global? opts))]
    [(store-atom (:state-dir opts) global?) stripped]))

(defn upsert
  ([kind title content] (upsert kind title content {}))
  ([kind title content opts]
   (check-kind! kind)
   (let [[a stripped] (routed (:id opts) opts)
         result (atom nil)]
     (swap! a (fn [store]
                (ensure-writable! store)
                (let [store (sync-from-disk store)
                      entry-id (or stripped (slug title kind))
                      [next entry] (do-upsert store kind entry-id title content opts)]
                  (reset! result entry)
                  next)))
     @result)))

(defn create
  ([kind title content] (create kind title content {}))
  ([kind title content opts]
   (check-kind! kind)
   (let [[a stripped] (routed (:id opts) opts)
         result (atom nil)]
     (swap! a (fn [store]
                (ensure-writable! store)
                (let [store (sync-from-disk store)
                      entry-id (or stripped (slug title kind))]
                  (when (get-in store [:entries kind entry-id])
                    (throw (IllegalArgumentException.
                            (str kind " entry " (pr-str entry-id) " already exists"))))
                  (let [[next entry] (do-upsert store kind entry-id title content opts)]
                    (reset! result entry)
                    next))))
     @result)))

(defn update-entry
  ([kind id title content] (update-entry kind id title content {}))
  ([kind id title content opts]
   (check-kind! kind)
   (let [[a stripped] (routed id opts)
         result (atom nil)]
     (swap! a (fn [store]
                (ensure-writable! store)
                (let [store (sync-from-disk store)]
                  (when-not (get-in store [:entries kind stripped])
                    (throw (IllegalArgumentException.
                            (str kind " entry " (pr-str stripped) " does not exist"))))
                  (let [[next entry] (do-upsert store kind stripped title content opts)]
                    (reset! result entry)
                    next))))
     @result)))

(defn get-entry
  ([kind id] (get-entry kind id {}))
  ([kind id opts]
   (check-kind! kind)
   (let [[a stripped] (routed id opts)]
     (get-in (swap! a sync-from-disk) [:entries kind stripped]))))

(defn delete
  ([kind id] (delete kind id {}))
  ([kind id opts]
   (check-kind! kind)
   (let [[a stripped] (routed id opts)
         result (atom false)]
     (swap! a (fn [store]
                (ensure-writable! store)
                (let [store (sync-from-disk store)]
                  (if-not (get-in store [:entries kind stripped])
                    (do (reset! result false) store)
                    (do (reset! result true)
                        (write-state! (update-in store [:entries kind] dissoc stripped)))))))
     @result)))

(defn entries
  ([] (entries nil {}))
  ([kind] (entries kind {}))
  ([kind opts]
   (when (some? kind) (check-kind! kind))
   (let [a (store-atom (:state-dir opts) (opt-global? opts))
         store (swap! a sync-from-disk)
         wanted (if (some? kind) [kind] kinds)]
     (->> wanted
          (mapcat (fn [k] (vals (get-in store [:entries k]))))
          (sort-by (juxt :kind :path :title :id))
          vec))))

(defn record-refinement
  ([trigger changes] (record-refinement trigger changes {}))
  ([trigger changes opts]
   (let [a (store-atom (:state-dir opts) (opt-global? opts))
         result (atom nil)]
     (swap! a (fn [store]
                (ensure-writable! store)
                (let [store (sync-from-disk store)
                      normalized (if (string? changes) [changes] (mapv str changes))
                      event {:id (or (:id opts)
                                     (format "refine_%04d" (inc (count (:refinements store)))))
                             :trigger trigger
                             :changes normalized
                             :evidence (or (:evidence opts) "")
                             :outcome (or (:outcome opts) "")
                             :created-at (now-iso)}]
                  (reset! result event)
                  (write-state! (update store :refinements conj event)))))
     @result)))

(defn refinements
  ([] (refinements {}))
  ([opts]
   (let [a (store-atom (:state-dir opts) (opt-global? opts))]
     (:refinements (swap! a sync-from-disk)))))

;; -- workspace surface ------------------------------------------------------

(def ^:private workspace-opt-keys
  "The keys a cell may set. :state-dir is deliberately absent: it would be a
  file-path escape hatch around the same sandbox that closed spit and slurp.
  A cell picks local or global; it does not pick where the store lives."
  [:id :path :reference :arguments :metadata :source :global :evidence :outcome])

(defn workspace-opts
  [opts]
  (if (map? opts) (select-keys opts workspace-opt-keys) {}))
