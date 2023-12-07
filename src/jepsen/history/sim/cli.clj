(ns jepsen.history.sim.cli
  "Command-line interface for history.sim."
  (:require [cheshire.core :as json]
            [clojure.tools.cli :as cli]
            [jepsen.history.sim :as sim :refer [test-defaults]]
            [jepsen.history.sim [cache :as cache]
                                [db :as db]
                                [gen :as gen]])
  (:gen-class))


(def opt-spec
  "tools.cli options"
  [
   ["-c" "--concurrency NUM" "How many concurrent clients?"
    :default 3
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--db DB" "What kind of database are we simulating?"
    :default :si
    :parse-fn keyword
    :validate [db/dbs (str "must be one of " (sort (keys db/dbs)))]]

   [nil "--format FORMAT" "What format to emit operations in"
    :default :edn
    :parse-fn keyword
    :validate [#{:json :edn} "must be edn or json"]]

   ["-g" "--gen GENERATOR" "What kind of workload would you like to generate?"
    :default :list-append
    :parse-fn keyword
    :validate [gen/generators
               (str "must be one of " (sort (keys gen/generators)))]]

   [nil "--key-dist DISTRIBUTION" "Would you like an exponential or uniform distribution of keys in transactions?"
    :default (:key-dist test-defaults)
    :parse-fn keyword
    :validate [#{:exponential :uniform} "must be either exponential or uniform"]]

   [nil "--key-dist-base NUMBER" "Base for the exponential distribution of keys. 2 means each key is twice as frequent as the last."
    :default (:key-dist-base test-defaults)
    :parse-fn parse-double]

   [nil "--key-count NUMBER" "How many keys should transactions interact with at any given time?"
    :default (:key-count test-defaults)
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--min-txn-length NUMBER" "How many micro-operations should transactions perform, at minimum?"
    :default (:min-txn-length test-defaults)
    :parse-fn parse-long
    :validate [(complement neg?) "must not be negative"]]

   [nil "--max-txn-length NUMBER" "How many micro-operations should transactions perform, at maximum?"
    :default (:max-txn-length test-defaults)
    :parse-fn parse-long
    :validate [(complement neg?) "must not be negative"]]

   [nil "--max-writes-per-key NUMBER" "How many writes should we try against each key before selecting a new one?"
    :default (:max-writes-per-key test-defaults)
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   ["-l" "--limit NUM" "How many operations would you like to invoke?"
    :default 16
    :parse-fn parse-long
    :validate [(complement neg?) "must be non-negative"]]

   [nil "--seed NUM" "The random seed for this history"
    :default 69
    :parse-fn parse-long]
   ])

(defn history!
  "Generates a history and prints it to stdout."
  [options]
  (let [test (sim/run options)
        printer (case (:format options)
                  :json  (comp println json/generate-string)
                  :edn   prn)]
    (run! printer (:history test))))

(defn usage!
  "Prints usage to STDOUT."
  [summary]
  (do (println "Usage: java -jar JAR_FILE [command] [args ...]")
      (println)
      (println "Commands:")
      (println "  clear-cache  Clears the disk cache of histories.")
      (println "  history      Emits a history to stdout")
      (println)
      (println "Options:")
      (println)
      (println summary)))

(defn -main
  ([]
   (usage! (:summary (cli/parse-opts [] opt-spec)))
   (System/exit 0))
  ([cmd & args]
   (try
     (let [{:keys [options arguments summary errors]}
           (cli/parse-opts args opt-spec)]
       (when (seq errors)
         (doseq [e errors]
           (println e))
         (System/exit 2))

       (case cmd
         "clear-cache" (cache/clear!)

         "history" (history! options)

         (do (usage! summary)
             (System/exit 1))))

     (catch Throwable t
       (println t)
       (.printStackTrace t)
       (System/exit 1)))))
