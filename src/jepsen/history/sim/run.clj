(ns jepsen.history.sim.run
  "Takes databases and generators and runs them, producing histories."
  (:require [bifurcan-clj [core :as b]
                          [map :as bm]
                          [set :as bs]]
            [clojure.data.generators :as data.gen]
            [jepsen [history :as h]]
            [jepsen.history.sim [db :as db]])
  (:import (java.util Random)))

(defn brand-nth
  "Rand-nth on a Bifurcan collection. Returns nil on empty colls."
  [xs]
  (let [n (b/size xs)]
    (when (< 0 n)
      (let [i (mod (data.gen/long) n)]
        (b/nth xs i)))))

(defn simulate
  "Takes a map with the following keys:

  {:concurrency   The number of concurrent clients
   :db            The database to execute against
   :generator     A sequence of invocations to perform}

  Launches simulated clients with the given concurrency, which draw operations
  from the generator and apply them to the DB. Returns a Jepsen IHistory.

  Deterministically random; uses data.generators/*rnd*."
  ; We track that client state in a map of processes to {:state state, :applied
  ; [& micro-ops], :pending [& micro-ops]}, corresponding to those operations
  ; we've completed, and those which have yet to be applied. At each moment in
  ; time, we pick a random process to step forward, and have that client
  ; perform one of its pending micro-operations against the database, creating
  ; a corresponding micro-op in that process' :applied. When a client has
  ; applied all of its operations, it can try to commit. The results of that
  ; commit are used to construct a completion operation, which is appended to
  ; the history.
  [test]
  (loop [gen      (:generator test) ; Remaining sequence of invocations
         db       (:db test)        ; DB state
         ; The history is initially empty
         history  []
         ; Bifurcan map of all busy process IDs to client state
         busy     bm/empty
         ; Bifurcan set of free processes
         free     (bs/from (range (:concurrency test)))]
    (cond ; A process is idle.
          (pos? (b/size free))
          (let [process (brand-nth free)]
            ; Can we begin an operation?
            (if-let [op (first gen)]
              (let [op (h/op (assoc op
                                    :process process
                                    :index   (long (count history))))]
                ; We have an invocation. Journal it to the history, and set up
                ; the client.
                (recur (next gen)
                       db
                       (conj history op)
                       (bm/put busy process {:state    :beginning
                                             :invoke   op
                                             :pending  (:value op)
                                             :applied  []})
                       (bs/remove free process)))
              ; Out of operations; shut down this process.
              (recur gen
                     db
                     history
                     busy
                     (bs/remove free process))))

          ; No processes idle, and one is busy. Pick a random busy process
          ; and advance it.
          (pos? (b/size busy))
          (let [pair    (brand-nth busy)
                process (bm/key pair)
                {:keys [state invoke pending applied] :as pstate}
                (bm/value pair)]
            (case state
              ; We need to begin a transaction on the DB, then we can
              ; transition to in-txn.
              :beginning
              (recur gen
                     (db/begin db process)
                     history
                     (bm/put busy process (assoc pstate :state :in-txn))
                     free)

              ; We're applying operations from the transaction.
              :in-txn
              (if-not (seq pending)
                ; Out of ops; commit
                (let [[db' committed?] (db/commit db process)
                      op' (assoc invoke
                                 :index (count history)
                                 :type (if committed? :ok :fail)
                                 :value (if committed?
                                          applied
                                          (:value invoke)))]
                  (recur gen
                         db'
                         (conj history op')
                         (bm/remove busy process)
                         (bs/add free process)))
                ; Apply an op
                (let [[f k v]  (first pending)
                      [db' v'] (case f
                                 :r      (db/read   db process k)
                                 :append (db/append db process k v))]
                  (recur gen
                         db'
                         history
                         (bm/put busy process
                                 {:state   :in-txn
                                  :invoke  invoke
                                  :pending (next pending)
                                  :applied (conj applied [f k v'])})
                         free)))))

          ; No processes free or busy. Done!
          true history)))
