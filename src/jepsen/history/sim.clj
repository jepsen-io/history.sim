(ns jepsen.history.sim
  "Top-level namespace for running simulations."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.generators :as data.gen]
            [jepsen.history.sim [cache :as cache]
                                [db :as db]
                                [gen :as gen]
                                [run :as run]])
  (:import (java.util Random)))

(defmacro with-rand
  "Takes a seed long and evaluates body with an RNG initialized to that seed."
  [seed & body]
  `(binding [data.gen/*rnd* (Random. ~seed)]
     ~@body))

(defn expand-test
  "Expands a test with default options."
  [test]
  (merge {:seed        69
          :concurrency 3
          :limit       16
          :db          :si
          :generator   :list-append}
         test))

(defn run!
  "Like run, but actually runs the simulator instead of potentially loading
  from cache. Saves to cache as a side effect."
  [test]
  (with-rand (:seed test)
    (let [gen-f (get gen/generators (:generator test))
          gen   (take (:limit test) (gen-f test))
          db-f  (get db/dbs (:db test))
          db    (db-f test)
          history (run/simulate (assoc test :generator gen :db db))]
      (-> (assoc test :history history)
          cache/write!
          cache/load))))

(defn run
  "Takes test parameters and returns a test with a completed :history field.
  Parameters are a map of:

    {; Database parameters
     :db        One of :si, :prefix, etc: the name of a DB

     ; Generator parameters
     :generator            The name of the generator to use, e.g. :list-append
     :key-count            Number of distinct keys at any point
     :min-txn-length       Minimum number of operations per txn
     :max-txn-length       Maximum number of operations per txn
     :max-writes-per-key   Maximum number of operations per key
     :concurrency          Number of concurrent clients
     :limit                Number of invocations to generate

     ; Randomness
     :seed                 A long: the random seed to use}

  This function will load an earlier run from disk cache, if possible."
  [test]
  (let [test (expand-test test)]
    (or (cache/load test)
        (run! test))))
