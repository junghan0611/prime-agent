(ns rlm.io
  "Bounded read. Called only from host IFns interned into SCI — never as SCI source."
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Paths]))

(def ^:private max-bytes (* 1024 1024))

(defn- as-path
  ^java.nio.file.Path [^String first & more]
  (Paths/get first (into-array String more)))

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
