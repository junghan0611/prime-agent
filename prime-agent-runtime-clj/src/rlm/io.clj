(ns rlm.io
  "Bounded read, bounded write. Called only from host IFns interned into SCI —
  never as SCI source.

  read-text keeps H3's lexical root check (the recorded symlink deviation).
  write-text and edit-text resolve the parent's real path and refuse a symlink
  target: the same discipline has to actually hold when the call destroys what
  it points at. Neither verb promotes the read deviation into a claim."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path Paths]))

(def ^:private max-bytes (* 1024 1024))

(defn- as-path
  ^java.nio.file.Path [^String first & more]
  (Paths/get first (into-array String more)))

(defn- links
  ^"[Ljava.nio.file.LinkOption;" []
  (into-array LinkOption []))

(defn- open-opts
  "Typed on purpose: an untyped varargs array makes Files/write reflective, and
  a reflective call dies in the native image."
  ^"[Ljava.nio.file.OpenOption;" []
  (into-array OpenOption []))

(defn- check-relative!
  [^String verb path]
  (when-not (string? path)
    (throw (IllegalArgumentException. (str verb " path must be a string"))))
  (let [^String p path]
    (when (or (zero? (.length p))
              (.startsWith p "/")
              (.startsWith p "\\")
              (.startsWith p "~"))
      (throw (IllegalArgumentException.
              (str verb " path must be relative to the workspace root"))))))

(defn- workspace-root
  ^java.nio.file.Path []
  (.toRealPath (as-path (System/getProperty "user.dir")) (links)))

(defn- resolve-lexically
  ^java.nio.file.Path [^String verb ^java.nio.file.Path root path]
  (let [resolved (.normalize (.resolve root ^String path))]
    (when-not (.startsWith resolved root)
      (throw (IllegalArgumentException. (str verb " path escapes the workspace root"))))
    resolved))

(defn- resolve-write-target
  "Lexical check first, then prove the parent is really inside the root. The
  parent must already exist — this slice creates files, not directory trees."
  ^java.nio.file.Path [^String verb path]
  (let [root (workspace-root)
        resolved (resolve-lexically verb root path)
        ^Path parent (.getParent resolved)]
    (when (nil? parent)
      (throw (IllegalArgumentException. (str verb " path has no parent directory: " path))))
    (when-not (Files/isDirectory parent (links))
      (throw (IllegalArgumentException.
              (str verb " parent directory does not exist: " path))))
    (when-not (.startsWith (.toRealPath parent (links)) root)
      (throw (IllegalArgumentException. (str verb " path escapes the workspace root"))))
    (when (Files/isSymbolicLink resolved)
      (throw (IllegalArgumentException. (str verb " refuses to follow a symlink: " path))))
    (when (and (Files/exists resolved (links))
               (not (Files/isRegularFile resolved (links))))
      (throw (IllegalArgumentException. (str verb " target is not a regular file: " path))))
    resolved))

(defn- occurrences
  "Non-overlapping count, like Python str.count — the oracle skill's contract."
  [^String haystack ^String needle]
  (loop [from 0 n 0]
    (let [i (.indexOf haystack needle from)]
      (if (neg? i)
        n
        (recur (+ i (.length needle)) (inc n))))))

(defn- line-count
  [^String s]
  (if (zero? (.length s))
    0
    (count (str/split-lines s))))

(defn read-text
  "Return file text. `path` must be relative and stay under user.dir."
  [path]
  (when-not (string? path)
    (throw (IllegalArgumentException. "read-text path must be a string")))
  (when (or (zero? (.length ^String path))
            (.startsWith ^String path "/")
            (.startsWith ^String path "\\")
            (.startsWith ^String path "~"))
    (throw (IllegalArgumentException. "read-text path must be relative to the workspace root")))
  (let [root (.toRealPath (as-path (System/getProperty "user.dir")) (into-array LinkOption []))
        resolved (.normalize (.resolve root ^String path))]
    (when-not (.startsWith resolved root)
      (throw (IllegalArgumentException. "read-text path escapes the workspace root")))
    (when-not (Files/isRegularFile resolved (into-array LinkOption []))
      (throw (IllegalArgumentException. (str "read-text: not a file: " path))))
    (when (> (Files/size resolved) max-bytes)
      (throw (IllegalArgumentException. "read-text file exceeds 1 MiB")))
    (String. ^bytes (Files/readAllBytes resolved) StandardCharsets/UTF_8)))

(defn write-text
  "Create or replace a workspace file. Returns a receipt map, not the file."
  [path content]
  (check-relative! "write-text" path)
  (when-not (string? content)
    (throw (IllegalArgumentException. "write-text content must be a string")))
  (let [^String text content
        ^bytes payload (.getBytes text StandardCharsets/UTF_8)]
    (when (> (alength payload) max-bytes)
      (throw (IllegalArgumentException. "write-text content exceeds 1 MiB")))
    (let [target (resolve-write-target "write-text" path)
          existed (Files/isRegularFile target (links))]
      (Files/write target payload (open-opts))
      {:path path
       :action (if existed :replaced :created)
       :bytes (alength payload)
       :lines (line-count text)})))

(defn edit-text
  "Replace one exact, unique occurrence of `old` with `new`. Returns a receipt.

  Every check runs before the write, so a rejected edit leaves the file as it
  was. Uniqueness is the contract the oracle skill fixes: 0 or 2+ matches are
  the caller's problem to narrow, not ours to guess."
  [path old new]
  (check-relative! "edit-text" path)
  (when-not (string? old)
    (throw (IllegalArgumentException. "edit-text old text must be a string")))
  (when-not (string? new)
    (throw (IllegalArgumentException. "edit-text new text must be a string")))
  (when (zero? (.length ^String old))
    (throw (IllegalArgumentException. "edit-text old text must not be empty")))
  (let [target (resolve-write-target "edit-text" path)]
    (when-not (Files/isRegularFile target (links))
      (throw (IllegalArgumentException. (str "edit-text: not a file: " path))))
    (when (> (Files/size target) max-bytes)
      (throw (IllegalArgumentException. "edit-text file exceeds 1 MiB")))
    (let [before-bytes (Files/size target)
          content (String. ^bytes (Files/readAllBytes target) StandardCharsets/UTF_8)
          ^String needle old
          found (occurrences content needle)]
      (when (zero? found)
        (throw (IllegalArgumentException. (str "edit-text: old text not found in " path))))
      (when (> found 1)
        (throw (IllegalArgumentException.
                (str "edit-text: found " found " occurrences in " path
                     ", need exactly 1 — widen the snippet to make it unique"))))
      (let [idx (.indexOf content needle)
            line (inc (occurrences (subs content 0 idx) "\n"))
            updated (str (subs content 0 idx) new (subs content (+ idx (.length needle))))
            ^bytes payload (.getBytes ^String updated StandardCharsets/UTF_8)]
        (when (> (alength payload) max-bytes)
          (throw (IllegalArgumentException. "edit-text result exceeds 1 MiB")))
        (Files/write target payload (open-opts))
        {:path path
         :action :edited
         :line line
         :bytes-before before-bytes
         :bytes-after (alength payload)}))))
