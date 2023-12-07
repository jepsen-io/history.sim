(ns jepsen.history.sim-test
  (:refer-clojure :exclude [run!])
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [jepsen [history :as h]]
            [jepsen.history.sim :refer :all]
            [jepsen.history.sim [cache :as cache]]))

(deftest si-history-test
  (let [t (run {:db    :si
                :limit 4})]
    (is (= {:max-txn-length 4,
           :concurrency 3,
           :limit 4,
           :key-count 10,
           :db :si,
           :max-writes-per-key 32,
           :key-dist :exponential,
           :history
           (h/history
           [{:process 0,
             :type :invoke,
             :f :txn,
             :value [[:append 9 1] [:r 9 nil] [:r 9 nil] [:append 9 2]],
             :index 0,
             :time -1}
            {:process 1,
             :type :invoke,
             :f :txn,
             :value [[:r 8 nil] [:r 9 nil] [:append 8 1]],
             :index 1,
             :time -1}
            {:process 2,
             :type :invoke,
             :f :txn,
             :value [[:append 9 3]],
             :index 2,
             :time -1}
            {:process 1,
             :type :ok,
             :f :txn,
             :value [[:r 8 nil] [:r 9 nil] [:append 8 1]],
             :index 3,
             :time -1}
            {:process 1,
             :type :invoke,
             :f :txn,
             :value [[:r 9 nil] [:append 8 2] [:r 9 nil]],
             :index 4,
             :time -1}
            {:process 2,
             :type :ok,
             :f :txn,
             :value [[:append 9 3]],
             :index 5,
             :time -1}
            {:process 1,
             :type :ok,
             :f :txn,
             :value [[:r 9 [3]] [:append 8 2] [:r 9 [3]]],
             :index 6,
             :time -1}
            {:process 0,
             :type :fail,
             :f :txn,
             :value [[:append 9 1] [:r 9 nil] [:r 9 nil] [:append 9 2]],
             :index 7,
             :time -1}]),
           :seed 69,
           :generator :list-append,
           :key-dist-base 2,
           :min-txn-length 1}
           t))))

(deftest brat-test
  (is (= (h/history
           [
            {:process 0,
            :type :invoke,
            :f :txn,
            :value [[:r 9 nil] [:append 9 1] [:append 6 1] [:append 9 2]],
            :index 0,
            :time -1}
           {:process 1,
            :type :invoke,
            :f :txn,
            :value [[:r 9 nil] [:r 6 nil] [:r 8 nil] [:append 9 3]],
            :index 1,
            :time -1}
           {:process 2,
            :type :invoke,
            :f :txn,
            :value [[:r 8 nil] [:r 8 nil]],
            :index 2,
            :time -1}
           {:process 1,
            :type :ok,
            :f :txn,
            :value [[:r 9 nil] [:r 6 nil] [:r 8 nil] [:append 9 [1 3]]],
            :index 3,
            :time -1}
           {:process 0,
            :type :ok,
            :f :txn,
            :value [[:r 9 nil] [:append 9 [1]] [:append 6 [1]] [:append 9 [1 3 2]]],
            :index 4,
            :time -1}
           {:process 2,
            :type :ok,
            :f :txn,
            :value [[:r 8 nil] [:r 8 nil]],
            :index 5,
            :time -1}
            ])
         (:history (run {:db :brat, :limit 3, :seed 123})))))

(defn ms
  "Helper for friendly nanos -> milliseconds"
  [nanos]
  (float (* 1e-6 nanos)))

(deftest cache-test
  ; Very helpful to lower this for debugging
  (with-redefs [cache/chunk-size 16384]
    (cache/clear!)
    (let [n    20000
          test (expand-test {:db :prefix, :limit n})]
      (is (nil? (cache/load test)))
      ; Uncached run
      (let [t0      (System/nanoTime)
            test0   (run! test)
            _       (is (= (* 2 n) (count (:history test0))))
            ; Now load cached copy
            t1      (System/nanoTime)
            test1   (run test)
            t2      (System/nanoTime)
            ; Comparing forces the test to load chunks
            _       (is (= test0 test1))
            t3      (System/nanoTime)]
        (println "Run took" (ms (- t1 t0))
                 "ms; load took" (ms (- t2 t1))
                 "ms; full read took" (ms (- t3 t1)) "ms")))))
