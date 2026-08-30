(ns rlm.write-test
  "H5 write / edit receipts. The workspace gets data maps back, never a file
  handle, and every path stays under the root read-text already fixed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

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

(defn- scratch!
  "A directory under the runtime's workspace root. target/ is gitignored, so a
  test never leaves a file in the working tree."
  []
  (let [rel (str "target/h5-" (System/nanoTime))]
    (.mkdirs (jio/file rel))
    rel))

(defn- rm-rf!
  [rel]
  (let [f (jio/file rel)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^java.io.File child)))))

(defn- slurp-rel
  [rel]
  (slurp (jio/file rel)))

(defn- with-scratch
  "Same shape as h/with-repl: hand the body its directory, clean up after."
  [f]
  (let [dir (scratch!)]
    (try (f dir) (finally (rm-rf! dir)))))

(defn- no-attrs
  ^"[Ljava.nio.file.attribute.FileAttribute;" []
  (into-array FileAttribute []))

(deftest write-creates-then-replaces
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (let [path (str dir "/a.txt")
                receipt (eval-edn repl "w1" (str "(write-text \"" path "\" \"hello\\nworld\\n\")"))]
            (is (= {:path path :action :created :bytes 12 :lines 2} receipt))
            (is (= "hello\nworld\n" (slurp-rel path))))
          (let [path (str dir "/a.txt")
                receipt (eval-edn repl "w2" (str "(write-text \"" path "\" \"bye\")"))]
            (is (= :replaced (:action receipt)))
            (is (= 3 (:bytes receipt)))
            (is (= 1 (:lines receipt)))
            (is (= "bye" (slurp-rel path))))
          (testing "the receipt is plain data, not a handle"
            (let [text (result-text
                        (h/execute repl "w3" (str "(pr-str (write-text \"" dir "/b.txt\" \"x\"))")))]
              (is (not (re-find #"#object" text)))))
          (testing "read-text sees what write-text wrote"
            (is (= "x" (eval-edn repl "w4" (str "(read-text \"" dir "/b.txt\")"))))))))))

(deftest edit-replaces-one-unique-occurrence
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (let [path (str dir "/e.txt")]
            (eval-edn repl "e1" (str "(write-text \"" path "\" \"alpha\\nbeta\\ngamma\\n\")"))
            (let [receipt (eval-edn repl "e2" (str "(edit-text \"" path "\" \"beta\" \"BETA-long\")"))]
              (is (= :edited (:action receipt)))
              (is (= 2 (:line receipt)) "1-based line where the match starts")
              (is (= 17 (:bytes-before receipt)))
              (is (= 22 (:bytes-after receipt)))
              (is (= path (:path receipt))))
            (is (= "alpha\nBETA-long\ngamma\n" (slurp-rel path)))))))))

(deftest edit-refuses-zero-or-many-and-leaves-the-file-alone
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (let [path (str dir "/e.txt")
                original "one\ntwo\ntwo\n"]
            (eval-edn repl "z1" (str "(write-text \"" path "\" \"one\\ntwo\\ntwo\\n\")"))
            (is (= original (slurp-rel path)))
            (testing "no match"
              (is (re-find #"not found"
                           (refused repl "z2" (str "(edit-text \"" path "\" \"missing\" \"x\")"))))
              (is (= original (slurp-rel path))))
            (testing "two matches name the count and ask for a wider snippet"
              (let [msg (refused repl "z3" (str "(edit-text \"" path "\" \"two\" \"x\")"))]
                (is (re-find #"found 2 occurrences" msg))
                (is (re-find #"widen the snippet" msg)))
              (is (= original (slurp-rel path)) "a refused edit must not touch the file"))
            (testing "empty old text is not a wildcard"
              (is (some? (refused repl "z4" (str "(edit-text \"" path "\" \"\" \"x\")"))))
              (is (= original (slurp-rel path))))
            (testing "the file has to exist"
              (is (some? (refused repl "z5" (str "(edit-text \"" dir "/missing.txt\" \"a\" \"b\")")))))))))))

(deftest write-and-edit-stay-under-the-workspace-root
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (doseq [[id code] [["r1" "(write-text \"/tmp/rlm-h5-escape\" \"x\")"]
                             ["r2" "(write-text \"~/rlm-h5-escape\" \"x\")"]
                             ["r3" "(write-text \"../rlm-h5-escape\" \"x\")"]
                             ["r4" "(write-text \"\" \"x\")"]
                             ["r5" "(edit-text \"/etc/passwd\" \"root\" \"x\")"]
                             ["r6" "(edit-text \"../README.md\" \"a\" \"b\")"]]]
            (is (some? (refused repl id code)) code))
          (is (not (.exists (jio/file "/tmp/rlm-h5-escape"))))
          (testing "argument types"
            (is (some? (refused repl "r7" (str "(write-text \"" dir "/t.txt\" 42)"))))
            (is (some? (refused repl "r8" "(write-text 42 \"x\")")))
            (is (some? (refused repl "r9" (str "(edit-text \"" dir "/t.txt\" \"a\" 42)")))))
          (let [events (h/execute repl "r10" "(+ 40 2)")]
            (is (= "42" (result-text events)))))))))

(deftest write-refuses-a-symlink-out-of-the-workspace
  ;; read-text's lexical check would let this through. A verb that destroys
  ;; what it points at does not get to rely on it.
  (with-scratch
    (fn [dir]
      (let [outside (Files/createTempDirectory "rlm-h5-outside" (no-attrs))
            victim (.resolve ^Path outside "victim.txt")
            link-dir (.toPath (jio/file (str dir "/out")))
            link-file (.toPath (jio/file (str dir "/direct")))]
        (try
          (spit (.toFile victim) "untouched")
          (Files/createSymbolicLink link-dir outside (no-attrs))
          (Files/createSymbolicLink link-file victim (no-attrs))
          (h/with-repl
            (fn [repl _]
              (testing "through a symlinked directory"
                (is (re-find #"escapes the workspace root"
                             (refused repl "s1"
                                      (str "(write-text \"" dir "/out/victim.txt\" \"pwned\")")))))
              (testing "the target itself is a symlink"
                (is (re-find #"symlink"
                             (refused repl "s2" (str "(write-text \"" dir "/direct\" \"pwned\")"))))
                (is (re-find #"symlink"
                             (refused repl "s3"
                                      (str "(edit-text \"" dir "/direct\" \"untouched\" \"pwned\")")))))
              (is (= "untouched" (slurp (.toFile victim))))))
          (finally
            (Files/deleteIfExists link-dir)
            (Files/deleteIfExists link-file)
            (Files/deleteIfExists victim)
            (Files/deleteIfExists outside)))))))

(deftest write-needs-an-existing-parent-and-a-regular-target
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (testing "this slice creates files, not directory trees"
            (is (re-find #"parent directory does not exist"
                         (refused repl "p1" (str "(write-text \"" dir "/deep/nested/f.txt\" \"x\")")))))
          (testing "a directory is not a write target"
            (is (re-find #"not a regular file"
                         (refused repl "p2" (str "(write-text \"" dir "\" \"x\")"))))))))))

(deftest oversized-writes-are-refused
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (let [path (str dir "/big.txt")]
            (is (re-find #"exceeds 1 MiB"
                         (refused repl "b1"
                                  (str "(write-text \"" path "\" (apply str (repeat 1100000 \"a\")))"))))
            (is (not (.exists (jio/file path))) "nothing is created when the cap rejects")
            (testing "an edit that would grow past the cap is refused too"
              (eval-edn repl "b2" (str "(write-text \"" path "\" \"seed\")"))
              (is (re-find #"exceeds 1 MiB"
                           (refused repl "b3"
                                    (str "(edit-text \"" path "\" \"seed\" "
                                         "(apply str (repeat 1100000 \"b\")))"))))
              (is (= "seed" (slurp-rel path))))))))))

(deftest utf8-round-trips-through-write-edit-read
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (let [path (str dir "/u.txt")]
            (let [receipt (eval-edn repl "u1" (str "(write-text \"" path "\" \"한글 ✓\\n둘째 줄\\n\")"))]
              (is (= :created (:action receipt)))
              (is (= 2 (:lines receipt)))
              (is (= 22 (:bytes receipt)) "bytes are UTF-8 bytes, not characters"))
            (eval-edn repl "u2" (str "(edit-text \"" path "\" \"둘째\" \"셋째\")"))
            (is (= "한글 ✓\n셋째 줄\n" (eval-edn repl "u3" (str "(read-text \"" path "\")"))))
            (is (= "한글 ✓\n셋째 줄\n" (slurp-rel path)))))))))

(deftest spit-and-slurp-stay-closed-beside-the-new-verbs
  (with-scratch
    (fn [dir]
      (h/with-repl
        (fn [repl _]
          (is (some? (refused repl "c1" (str "(spit \"" dir "/x.txt\" \"x\")"))))
          (is (some? (refused repl "c2" (str "(slurp \"" dir "/x.txt\")"))))
          (is (not (.exists (jio/file (str dir "/x.txt")))))
          (let [receipt (eval-edn repl "c3" (str "(write-text \"" dir "/x.txt\" \"x\")"))]
            (is (= :created (:action receipt)))))))))
