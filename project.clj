(defproject io.jepsen/history.sim "0.1.1-SNAPSHOT"
  :description "Generates Jepsen histories from simulated databases, for testing safety checkers"
  :url "https://github.com/jepsen-io/history.sim"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[cheshire "5.12.0"]
                 [com.aphyr/bifurcan-clj "0.1.1"]
                 [io.jepsen/history "0.1.3"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/data.fressian "1.0.0"]
                 [org.clojure/data.generators "1.0.0"]
                 [org.clojure/tools.cli "1.0.219"]]
  :java-source-paths ["src/"]
  :main         jepsen.history.sim.cli
  :repl-options {:init-ns jepsen.history.sim}
  :test-selectors {:default (fn [m] (not (:perf m)))
                   :all         (fn [m] true)
                   :perf        :perf
                   :focus       :focus}
  :profiles {:uberjar {:aot :all}})
