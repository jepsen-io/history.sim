# jepsen.history.sim

When building a database checker, you need histories to check. These histories
should exhibit certain classes of anomalies, so that you can test whether the
checker is correct. They should be determistic, so you can build regression
tests. They should be large enough to serve as performance benchmarks. This library generates those histories.

As a Clojure library, jepsen.history.sim provides histories to JVM programs
which ask for them. These histories are cached in your local tmpdir, so they're
fast on repeated access. Histories are read lazily from chunks, wrapped in a
jepsen.history.IHistory, so they work with parallel folds out of the box.

As an executable jar, this program can emit histories in EDN or JSON to stdout.
You can use this to benchmark checkers written in other languages.

## Library use

The main entry point for the library is `jepsen.history.sim`. Here's how to get a quick history from a snapshot isolated database:

```clj
=> (require '[jepsen.history.sim :as sim])
=> (pprint (sim/run {}))
{:concurrency 3,
 :limit 16,
 :db :si,
 :generator :list-append,
 :history
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
```

`run` takes and returns a test map. This map includes several parameters: the
db, the generator, concurrency, and so on. `run` fills in defaults if you don't
provide any. Once run, a test has a `:history` field with a Jepsen history of
operations.

Let's say we wanted a prefix-consistent history of 2 processes running 3 operations:

```clj
=> (->> (sim/run {:db :prefix, :concurrency 2, :limit 3}) :history (mapv prn))
{:index 0, :time -1, :type :invoke, :process 1, :f :txn, :value [[:append 9 1] [:r 9 nil]]}
{:index 1, :time -1, :type :invoke, :process 0, :f :txn, :value [[:append 9 2]]}
{:index 2, :time -1, :type :ok, :process 0, :f :txn, :value [[:append 9 2]]}
{:index 3, :time -1, :type :invoke, :process 0, :f :txn, :value [[:r 8 nil] [:r 9 nil]]}
{:index 4, :time -1, :type :ok, :process 0, :f :txn, :value [[:r 8 nil] [:r 9 [2]]]}
{:index 5, :time -1, :type :ok, :process 1, :f :txn, :value [[:append 9 1] [:r 9 [2 1]]]}
```

Runs are fully deterministic. Ask for the same parameters again and you'll get
an identical history.

## CLI Use

To build a fat jar, install a JDK, leiningen, and run `lein uberjar`. This will
emit a jar in `target/history.sim-...-standalone.jar` with an executable
binary. Then try

```
java -jar target/history.sim-0.1.0-SNAPSHOT-standalone.jar history
```

This emits a short history of sixteen operations (16 invokes, 16 completions)
like so. These are list-append operations from a simulated snapshot isolated
database, and can be fed directly to Elle's list-append checker.

```clj
{:index 0, :time -1, :type :invoke, :process 0, :f :txn, :value [[:append 9 1] [:r 9 nil]]}
{:index 1, :time -1, :type :invoke, :process 1, :f :txn, :value [[:append 9 2]]}
{:index 2, :time -1, :type :invoke, :process 2, :f :txn, :value [[:r 8 nil] [:r 9 nil]]}
{:index 3, :time -1, :type :ok, :process 0, :f :txn, :value [[:append 9 1] [:r 9 [1]]]}
{:index 4, :time -1, :type :invoke, :process 0, :f :txn, :value [[:r 7 nil]]}
{:index 5, :time -1, :type :fail, :process 1, :f :txn, :value [[:append 9 2]]}
...
```

You can tune the number of operations with `--limit`, the concurrency with
`--concurrency`, the database with `--db`, and the output format with
`--format`. Here's a million operations by ten concurrent clients on a
serializable-snapshot-isolated database, printed as lines of JSON.

```
java -jar target/history.sim-0.1.0-SNAPSHOT-standalone.jar history --limit 1000000 --concurrency 10 --db prefix --format json
```

```json
{"index":0,"time":-1,"type":"invoke","process":7,"f":"txn","value":[["append",9,1],["r",9,null]]}
{"index":1,"time":-1,"type":"invoke","process":2,"f":"txn","value":[["append",9,2]]}
{"index":2,"time":-1,"type":"invoke","process":5,"f":"txn","value":[["r",8,null],["r",9,null]]}
{"index":3,"time":-1,"type":"invoke","process":4,"f":"txn","value":[["r",7,null]]}
{"index":4,"time":-1,"type":"invoke","process":6,"f":"txn","value":[["r",9,null],["r",9,null]]}
{"index":5,"time":-1,"type":"invoke","process":0,"f":"txn","value":[["r",9,null]]}
{"index":6,"time":-1,"type":"invoke","process":1,"f":"txn","value":[["append",6,1]]}
{"index":7,"time":-1,"type":"invoke","process":3,"f":"txn","value":[["append",8,1],["r",7,null]]}
{"index":8,"time":-1,"type":"invoke","process":8,"f":"txn","value":[["r",9,null],["r",6,null]]}
{"index":9,"time":-1,"type":"invoke","process":9,"f":"txn","value":[["append",8,2],["append",8,3]]}
```

This takes a while the first time, but run the command again and it'll start
printing immediately: the simulator will re-use its cached copy of the history
on disk. Takes about 35 seconds to finish printing all million ops on my aging
Xeon.

For full usage, run jepsen.history.sim without any arguments.

```
$ java -jar target/history.sim-0.1.0-SNAPSHOT-standalone.jar
Usage: java -jar JAR_FILE [command] [args ...]

Commands:
  clear-cache  Clears the disk cache of histories.
  history      Emits a history to stdout

Options:

  -c, --concurrency NUM  3             How many concurrent clients?
      --db DB            :si           What kind of database are we simulating?
      --format FORMAT    :edn          What format to emit operations in
  -g, --gen GENERATOR    :list-append  What kind of workload would you like to generate?
  -l, --limit NUM        16            How many operations would you like to invoke?
      --seed NUM         69            The random seed for this history
```

## License

Copyright © 2023 Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
