(ns jepsen.history.sim.gen
  "A minimal recreation of generators for simulated DB ops. We're not doing
  anything fancy with context or causality here; generators are just plain old
  seqs.

  These versions use clojure.data.gen for RNGs, so they're deterministic."
  (:require [clojure.data.generators :as data.gen]))

;; Adapted directly from Elle.

(defn key-dist-scale
  "Takes a key-dist-base and a key count. Computes the scale factor used for
  random number selection used in rand-key."
  [key-dist-base key-count]
  (-> (Math/pow key-dist-base key-count)
      (- 1)
      (* key-dist-base)
      (/ (- key-dist-base 1))))

(defn rand-key
  "Helper for generators. Takes a key distribution (e.g. :uniform or
  :exponential), a key distribution scale, a key distribution base, and a
  vector of active keys. Returns a random active key."
  [key-dist key-dist-base key-dist-scale active-keys]
  (case key-dist
    :uniform     (data.gen/rand-nth active-keys)
    :exponential (let [ki (-> (data.gen/double)
                              (* key-dist-scale)
                              (+ key-dist-base)
                              Math/log
                              (/ (Math/log key-dist-base))
                              (- 1)
                              Math/floor
                              long)]
                   (nth active-keys ki))))

(defn fresh-key
  "Takes a key and a vector of active keys. Returns the vector with that key
  replaced by a fresh key."
  [^java.util.List active-keys k]
  (let [i (.indexOf active-keys k)
        k' (inc (reduce max active-keys))]
    (assoc active-keys i k')))

(defn wr-txns
  "A lazy sequence of write and read transactions over a pool of n numeric
  keys; every write is unique per key. Options:

    :key-dist             Controls probability distribution for keys being
                          selected for a given operation. Choosing :uniform
                          means every key has an equal probability of appearing.
                          :exponential means that key i in the current key pool
                          is k^i times more likely than the first key to be
                          chosen. Defaults to :exponential.

    :key-dist-base        The base for an exponential distribution. Defaults
                          to 2, so the first key is twice as likely as the
                          second, which is twice as likely as the third, etc.

    :key-count            Number of distinct keys at any point. Defaults to
                          10 for exponential, 3 for uniform.

    :min-txn-length       Minimum number of operations per txn

    :max-txn-length       Maximum number of operations per txn

    :max-writes-per-key   Maximum number of operations per key"
  ([opts]
   (let [key-dist  (:key-dist  opts :exponential)
         key-count (:key-count opts (case key-dist
                                      :exponential 10
                                      :uniform     3))]
     (wr-txns (assoc opts
                     :key-dist  key-dist
                     :key-count key-count)
              {:active-keys (vec (range key-count))})))
  ([opts state]
   (lazy-seq
     (let [min-length           (:min-txn-length      opts 1)
           max-length           (:max-txn-length      opts 2)
           max-writes-per-key   (:max-writes-per-key  opts 32)
           key-dist             (:key-dist            opts :exponential)
           key-dist-base        (:key-dist-base       opts 2)
           key-count            (:key-count           opts)
           ; Choosing our random numbers from this range converts them to an
           ; index in the range [0, key-count).
           key-dist-scale       (key-dist-scale key-dist-base key-count)
           length               (data.gen/uniform min-length (inc max-length))
           [txn state] (loop [length  length
                              txn     []
                              state   state]
                         (let [^java.util.List active-keys
                               (:active-keys state)]
                           (if (zero? length)
                             ; All done!
                             [txn state]
                             ; Add an op
                             (let [f (data.gen/rand-nth [:r :w])
                                   k (rand-key key-dist key-dist-base
                                               key-dist-scale active-keys)
                                   v (when (= f :w) (get state k 1))]
                               (if (and (= :w f)
                                        (< max-writes-per-key v))
                                 ; We've updated this key too many times!
                                 (let [state' (update state :active-keys
                                                      fresh-key k)]
                                   (recur length txn state'))
                                 ; Key is valid, OK
                                 (let [state' (if (= f :w)
                                                (assoc state k (inc v))
                                                state)]
                                   (recur (dec length)
                                          (conj txn [f k v])
                                          state')))))))]
       (cons txn (wr-txns opts state))))))

(defn txn-gen
  "Takes a sequence of transactions and returns a sequence of invocation
  operations."
  [txns]
  (map (fn [txn] {:type :invoke, :f :txn, :value txn}) txns))

(defn append-txns
  "Like wr-txns, we just rewrite writes to be appends."
  [opts]
  (->> (wr-txns opts)
       (map (partial mapv (fn [[f k v]] [(case f :w :append f) k v])))))

(defn list-append
  "A generator for operations where values are transactions made up of reads
  and appends to various integer keys. Takes options:

    :key-count            Number of distinct keys at any point
    :min-txn-length       Minimum number of operations per txn
    :max-txn-length       Maximum number of operations per txn
    :max-writes-per-key   Maximum number of operations per key

 For defaults, see wr-txns."
  ([]
   (list-append {}))
  ([opts]
   (txn-gen (append-txns opts))))

(def generators
  "A map of generator names to constructor fns."
  {:list-append list-append})
