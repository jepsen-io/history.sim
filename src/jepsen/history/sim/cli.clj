(ns jepsen.history.sim.cli
  "Command-line interface for history.sim."
  (:require [cheshire.core :as json]
            [clojure.tools.cli :as cli]
            [jepsen.history.sim :as sim]
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
