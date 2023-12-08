(ns jepsen.history.sim.cache
  "Caches histories as EDN on disk."
  (:refer-clojure :exclude [load])
  (:require [clojure [edn :as edn]
                     [pprint :refer [pprint]]]
            [clojure.data [fressian :as fress]]
            [clojure.java [io :as io]]
            [dom-top.core :refer [loopr]]
            [jepsen [history :as h]]
            [jepsen.history [core :as hc]])
  (:import (java.io Closeable
                    EOFException
                    File
                    PushbackReader)
           (jepsen.history Op)
           (jepsen.history.sim FressianReader)
           (org.fressian.handlers ConvertList
                                  WriteHandler
                                  ReadHandler)))

; Adapted from jepsen.store.fressian
(def write-handlers*
  (-> {clojure.lang.PersistentHashSet
       {"persistent-hash-set" (reify WriteHandler
                                (write [_ w set]
                                  (.writeTag w "persistent-hash-set" 1)
                                  (.writeObject w (seq set))))}

       clojure.lang.PersistentTreeSet
       {"persistent-sorted-set" (reify WriteHandler
                                  (write [_ w set]
                                    (.writeTag w "persistent-sorted-set" 1)
                                    (.writeObject w (seq set))))}

       clojure.lang.MapEntry
       {"map-entry" (reify WriteHandler
                      (write [_ w e]
                        (.writeTag    w "map-entry" 2)
                        (.writeObject w (key e))
                        (.writeObject w (val e))))}

       jepsen.history.Op
       {"jepsen.history.Op" (reify WriteHandler
                              (write [_ w op]
                                ; We cache type and f. Thought about process,
                                ; but I think they might be too
                                ; high-cardinality.
                                (.writeTag    w "jepsen.history.Op" 7)
                                (.writeInt    w (.index    ^Op op))
                                (.writeInt    w (.time     ^Op op))
                                (.writeObject w (.type     ^Op op) true)
                                (.writeObject w (.process  ^Op op))
                                (.writeObject w (.f        ^Op op) true)
                                (.writeObject w (.value    ^Op op))
                                (.writeObject w (.__extmap ^Op op))))}}
      (merge fress/clojure-write-handlers)))

(def write-handlers
  (-> write-handlers*
      fress/associative-lookup
      fress/inheritance-lookup))

(def read-handlers*
  (-> {
       "persistent-hash-set" (reify ReadHandler
                               (read [_ rdr tag component-count]
                                 (assert (= 1 component-count))
                                 (into #{} (.readObject rdr))))

       "persistent-sorted-set" (reify ReadHandler
                                 (read [_ rdr tag component-count]
                                   (assert (= 1 component-count))
                                   (into (sorted-set) (.readObject rdr))))

       "jepsen.history.Op" (reify ReadHandler
                             (read [_ r tag component-count]
                               (assert (= 7 component-count))
                               (Op. (.readInt r)    ; index
                                    (.readInt r)    ; time
                                    (.readObject r) ; type
                                    (.readObject r) ; process
                                    (.readObject r) ; f
                                    (.readObject r) ; value
                                    nil             ; meta
                                    (.readObject r) ; extmap
                                    )))

       "map-entry" (reify ReadHandler
                     (read [_ rdr tag component-count]
                       (clojure.lang.MapEntry. (.readObject rdr)
                                               (.readObject rdr))))

       "vec" (reify ReadHandler
               (read [_ rdr tag component-count]
                 (vec (.readObject rdr))))}

      (merge fress/clojure-read-handlers)))

(def read-handlers
  (fress/associative-lookup read-handlers*))

(def chunk-size
  "How big is each chunk of the history?"
  16384)

(def base-dir
  "Directory in which we store cached histories."
  (io/file (System/getProperty "java.io.tmpdir")
           "jepsen"
           "history.sim"))

(defn rm-r!
  "Delete file recursively"
  [^File f]
  (when (.isDirectory f)
    (run! rm-r! (.listFiles f)))
  (io/delete-file f true))

(defn clear!
  "Clears out the whole cache."
  []
  (rm-r! base-dir))

(defn dir
  "Takes a test map and computes the File for the directory we store it in."
  [test]
  (let [k (-> test
              (dissoc :history)
              hash)]
    (io/file base-dir (str k))))

(defn chunk-file!
  "Returns the file for a test and chunk number. Creates parent directory if
  necessary."
  [test chunk-number]
  (let [f (io/file (dir test) (str chunk-number ".fressian"))]
    (io/make-parents f)
    f))

(defn ^Closeable writer!
  "Opens a Fressian writer for a test and chunk number."
  [test chunk-number]
  (-> (chunk-file! test chunk-number)
      io/output-stream
      (fress/create-writer :handlers write-handlers)))

(defn ^Closeable reader!
  "Opens a Fressian reader for a test and chunk number."
  [test chunk-number]
  (-> (chunk-file! test chunk-number)
      io/input-stream
      (FressianReader. read-handlers)))

(defn write!
  "Writes a completed test (with :history) to disk. Deletes all test files
  first. Returns test."
  [test]
  (rm-r! (dir test))
  (loopr [writer       (writer! test 0)
          chunk-number 0  ; Number of chunk
          i            0] ; Index in chunk
         [op (:history test)]
         (if (< i chunk-size)
           ; Write in chunk
           (do (fress/write-object writer op)
               (recur writer chunk-number (inc i)))
           ; New chunk
           (do (.close writer)
               (let [chunk-number (inc chunk-number)
                     writer (writer! test chunk-number)]
                 (fress/write-object writer op)
                 (recur writer chunk-number 1))))
				 (do (.close writer)
             test)))

(defn load-chunk
  "Loads a chunk of a test's history into a vector."
  [test chunk-number]
  (with-open [r (reader! test chunk-number)]
    (loop [h (transient [])]
      (let [op (try (fress/read-object r)
                    (catch EOFException _ ::eof))]
        (if (identical? op ::eof)
          (persistent! h)
          (recur (conj! h op)))))))

(defn load
  "Takes a test, returns test with :history lazily loaded from disk. Returns
  nil if not cached."
  [test]
  ; TODO: should have some way to tell if a test completed writing successfully
  (let [dir (dir test)]
    (when (.exists ^File dir)
      (let [; Oh gosh this will probably break, hope and pray
            n (* 2 (:limit test))
            starting-indices (into [] (range 0 n chunk-size))
            ops (hc/soft-chunked-vector n starting-indices
                                        (partial load-chunk test))
            history (h/history ops
                               {:dense-indices? true
                                :have-indices? true
                                :already-ops? true})]
        (assoc test :history history)))))
