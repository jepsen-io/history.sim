(ns jepsen.history.sim.db-test
  (:refer-clojure :exclude [read])
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [jepsen.history.sim.db :refer :all]))

(deftest si-write-skew-test
  ; We should be able to reproduce write skew with our SI DB
  (let [db (si-db)
        ; Start two transactions
        db (begin db 0)
        db (begin db 1)
        ; Both read intersecting keys
        [db r] (read db 0 :x)
        _ (is (= nil r))
        [db r] (read db 0 :y)
        _ (is (= nil r))
        [db r] (read db 1 :x)
        _ (is (= nil r))
        [db r] (read db 1 :y)
        _ (is (= nil r))
        ; And write disjoint keys
        [db _] (append db 0 :x 0)
        [db _] (append db 1 :y 1)
        ; Both may commit
        [db r] (commit db 0)
        _ (is (= true r))
        [db r] (commit db 1)
        _ (is (= true r))
        ; Which leaves the DB with both writes
        _ (is (= {:jepsen.history.sim.db/ts 2, :x [1 [0]], :y [2 [1]]} (:main db)))]
    ))
