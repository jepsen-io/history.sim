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
    (is (= {:concurrency 3,
           :limit 4,
           :db :si,
           :generator :list-append,
           :seed 69
           :history (h/history
           [{:process 0,
             :type :invoke,
             :f :txn,
             :value [[:append 9 1] [:r 9 nil]],
             :index 0,
             :time -1}
            {:process 1,
             :type :invoke,
             :f :txn,
             :value [[:append 9 2]],
             :index 1,
             :time -1}
            {:process 2,
             :type :invoke,
             :f :txn,
             :value [[:r 8 nil] [:r 9 nil]],
             :index 2,
             :time -1}
            {:process 0,
             :type :ok,
             :f :txn,
             :value [[:append 9 1] [:r 9 [1]]],
             :index 3,
             :time -1}
            {:process 0,
             :type :invoke,
             :f :txn,
             :value [[:r 7 nil]],
             :index 4,
             :time -1}
            {:process 1,
             :type :fail,
             :f :txn,
             :value [[:append 9 2]],
             :index 5,
             :time -1}
            {:process 0,
             :type :ok,
             :f :txn,
             :value [[:r 7 nil]],
             :index 6,
             :time -1}
            {:process 2,
             :type :ok,
             :f :txn,
             :value [[:r 8 nil] [:r 9 nil]],
             :index 7,
             :time -1}])}
           t))))

(deftest brat-test
  (is (= (h/history
           [{:process 0,
             :type :invoke,
             :f :txn,
             :value [[:r 9 nil] [:append 9 1]],
             :index 0,
             :time -1}
            {:process 1,
             :type :invoke,
             :f :txn,
             :value [[:r 9 nil] [:append 9 2]],
             :index 1,
             :time -1}
            {:process 2,
             :type :invoke,
             :f :txn,
             :value [[:r 6 nil] [:r 8 nil]],
             :index 2,
             :time -1}
            {:process 2,
             :type :ok,
             :f :txn,
             :value [[:r 6 nil] [:r 8 nil]],
             :index 3,
             :time -1}
            {:process 0,
             :type :ok,
             :f :txn,
             :value [[:r 9 nil] [:append 9 [1]]],
             :index 4,
             :time -1}
            {:process 1,
             :type :ok,
             :f :txn,
             :value [[:r 9 [1]] [:append 9 [1 2]]],
             :index 5,
             :time -1}])
         (:history (run {:db :brat, :limit 3, :seed 123})))))

(defn ms
  "Helper for friendly nanos -> milliseconds"
  [nanos]
  (float (* 1e-6 nanos)))

(deftest ^:perf cache-test
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
