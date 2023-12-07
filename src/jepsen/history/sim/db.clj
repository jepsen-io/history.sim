(ns jepsen.history.sim.db
  "Reference implementations of various databases."
  (:refer-clojure :exclude [read]))

; A Database executes transactions by applying read and append operations.
; Begin initiates a transaction for the given process. Commit attempts to
; finalize that transaction. All protocols here are functional, returning a new
; state of the database, followed by return values.
(defprotocol Database
  (begin [database process] "Begins a new transaction for the given process.
                            Returns the new state of the database.")
  (commit [database process] "Commits the transaction for the given process.
                             Returns a tuple: the new state of the database,
                             followed by true if the transaction committed,
                             false otherwise.")
  (abort [database process] "Aborts the transaction for the given process.
                            Returns a tuple: the new state of the database,
                            followed by true if the transaction aborted, which
                            should be always.")
  (read [database process key]
        "Reads the current state of a key. Takes the process performing the
        read. Returns [database', value]. value may be ::block, which indicates
        the given read can't be performed yet, but could be later.")
  (append [database process key element]
          "Appends the given element to the current value of the given key.
          Returns [database', element]. If element is ::block, the given
          append can't be performed yet, but could be later."))

; Straightforward SI without first-committer-wins checks. This should give us
; prefix consistency, which is, to be clear, hot garbage.
;
; main      The main DB snapshot. A map of keys to [ts, value] pairs. A special
;           key, ::ts, identifies the logical timestamp for a snapshot's
;           state.
; snapshots a map of processes to snapshots taken for those txns.
; writes    a map of processes to maps of keys to values which were updated in
;           that txn. Used for conflict detection, and in-txn reads.
(defrecord PrefixDB [main snapshots writes]
  Database
  (begin [db process]
    (assoc db
           :snapshots (assoc snapshots  process (:main db))
           :writes    (assoc writes     process {})))

  (commit [db process]
    (let [start-ts  (::ts (get snapshots process))
          commit-ts (inc (::ts main))
          writes    (get writes process) ; Writes from this txn

          ; Iterate over writes in txn and apply them to main
          main' (reduce (fn [main [k v']]
                          ; Writes are always legal
                          (assoc main k [commit-ts v']))
                        (assoc main ::ts commit-ts)
                        writes)

          ; We'll use abort's logic to clean up transaction state for us.
          aborted-db (first (abort db process))]

        (if (= ::abort main')
          [aborted-db false]
          [(assoc aborted-db :main main') true])))

  (abort [db process]
    ; Just clear out our txn state.
    [(assoc db
            :snapshots (dissoc snapshots process)
            :writes    (dissoc writes process))
     true])

  (read [database process k]
    ; Read from in-txn writes first, fall back to snapshot
    [database (get-in writes [process k]
                      (second (get-in snapshots [process k])))])

  (append [database process k element]
    (let [v  (or (second (read database process k)) [])
          v' (conj v element)]
      [(assoc-in database [:writes process k] v')
       element])))

(defn prefix-db
  "Constructs a new prefix-isolated database"
  ([] (prefix-db {}))
  ([opts]
   (PrefixDB. {::ts 0} {} {})))

; A simple snapshot isolation model. Weirdly, this doesn't seem to emit
; G-singles or G-nonadjacents. Only G2-item. Must be a bug.
;
; main      The main DB snapshot. A map of keys to [ts, value] pairs. A special
;           key, ::ts, identifies the logical timestamp for a snapshot's
;           state.
; snapshots a map of processes to snapshots taken for those txns.
; writes    a map of processes to maps of keys to values which were updated in
;           that txn. Used for conflict detection, and in-txn reads.
(defrecord SIDB [main snapshots writes]
  Database
  (begin [db process]
    (assoc db
           :snapshots (assoc snapshots  process (:main db))
           :writes    (assoc writes     process {})))

  (commit [db process]
    (let [start-ts  (::ts (get snapshots process))
          commit-ts (inc (::ts main))
          writes    (get writes process) ; Writes from this txn

          ; Iterate over writes in txn and apply them to main, checking whether
          ; they were written between our start and commit timestamps.
          main' (reduce (fn [main [k v']]
                          (let [k-ts (first (get main k [0 nil]))]
                            ;(prn :start start-ts :k-ts k-ts :commit-ts commit-ts)
                            (if (< start-ts k-ts commit-ts)
                              ; Oh no, someone else updated!
                              (reduced ::abort)
                              ; Our write is legal
                              (assoc main k [commit-ts v']))))
                        (assoc main ::ts commit-ts)
                        writes)

          ; We'll use abort's logic to clean up transaction state for us.
          aborted-db (first (abort db process))]

        (if (= ::abort main')
          [aborted-db false]
          [(assoc aborted-db :main main') true])))

  (abort [db process]
    ; Just clear out our txn state.
    [(assoc db
            :snapshots (dissoc snapshots process)
            :writes    (dissoc writes process))
     true])

  (read [database process k]
    ; Read from in-txn writes first, fall back to snapshot
    [database (get-in writes [process k]
                      (second (get-in snapshots [process k])))])

  (append [database process k element]
    (let [v  (or (second (read database process k)) [])
          v' (conj v element)]
      [(assoc-in database [:writes process k] v')
       element])))

(defn si-db
  "Construct a fresh snapshot isolation DB."
  ([]
   (si-db {}))
  ([opts]
   (SIDB. {::ts 0} {} {})))

; Wrap a snapshot isolated DB in one that promotes all reads to writes, which
; ensures serializability.
(defrecord SSIDB [db]
  Database
  (begin [this process]
    (assoc this :db (begin db process)))

  (commit [this process]
    (let [[db' committed?] (commit db process)]
      [(assoc this :db db') committed?]))

  (abort [this process]
    (let [[db' ok?] (abort db process)]
      [(assoc this :db db') ok?]))

  (read [this process k]
    (let [[db' v] (read db process k)
          ; Sneak into the underlying SI DB and add a no-op write
          db' (assoc-in db' [:writes process k] v)]
      [(assoc this :db db') v]))

  (append [this process k element]
    (let [[db' r] (append db process k element)]
      [(assoc this :db db') r])))

(defn ssi-db
  "Construct a fresh snapshot serializable isolation database."
  ([] (ssi-db {}))
  ([opts]
  (SSIDB. (si-db))))

(def dbs
  "A map of standard DB types to DB constructor functions."
  {:prefix prefix-db
   :si     si-db
   :ssi    ssi-db})
