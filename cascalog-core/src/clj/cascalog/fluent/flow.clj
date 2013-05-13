(ns cascalog.fluent.flow
  (:require [clojure.set :refer (subset?)]
            [cascalog.util :as u]
            [hadoop-util.core :as hadoop]
            [cascalog.fluent.algebra :refer (plus Semigroup)]
            [cascalog.fluent.conf :as conf]
            [cascalog.fluent.tap :as tap]
            [cascalog.fluent.io :as io]
            [cascalog.fluent.operations :as ops])
  (:import [cascalog Util]
           [cascalog.fluent.tap CascalogTap]
           [cascading.pipe Pipe Merge]
           [cascading.tap Tap]
           [cascading.tuple Fields]
           [cascading.flow FlowDef]
           [cascading.flow.hadoop HadoopFlow HadoopFlowConnector]
           [com.twitter.maple.tap MemorySourceTap]))

;; ## Tuple Methods

(defprotocol ITuple
  (to-tuple [this]
    "Returns a tupled representation of the supplied thing."))

(extend-protocol ITuple
  clojure.lang.IPersistentVector
  (to-tuple [v] (Util/coerceToTuple v)) ;; TODO: do this in clojure.

  Object
  (to-tuple [v] (to-tuple [v])))

;; ## Source Methods

;; TODO: Does this type hint do anything?

(defprotocol ISource
  (to-source ^Tap [this]
    "Returns a Cascading tap that allows access to the supplied
    data."))

(extend-protocol ISource
  Tap
  (to-source [tap] tap)

  CascalogTap
  (to-source [tap] (to-source (:source tap)))

  clojure.lang.IPersistentVector
  (to-source [v]
    (MemorySourceTap. (map to-tuple v)
                      Fields/ALL))

  java.util.ArrayList
  (to-source [coll]
    (to-source (into [] coll))))

(defprotocol ISink
  (to-sink [this]
    "Returns a Cascading tap into which Cascalog can sink the supplied
    data."))

;; => Tap, Tap => T

(extend-protocol ISink
  Tap
  (to-sink [tap] tap)

  ;; old cascalog-tap.
  clojure.lang.PersistentStructMap
  (to-sink [tap] (to-sink (:sink tap)))

  CascalogTap
  (to-sink [tap] (to-sink (:sink tap))))

;; Note that we need to use getIdentifier on the taps.

;; source-map is a map of identifier to tap, or source. Pipe is the
;; current pipe that the user needs to operate on.

(defrecord ClojureFlow [source-map sink-map trap-map tails pipe])

(extend-protocol Semigroup
  Pipe
  (plus [l r]
    (Merge. (into-array Pipe [(Pipe. (u/uuid) l)
                              (Pipe. (u/uuid) r)])))

  ClojureFlow
  (plus [l r]
    (letfn [(merge-k [k] (merge (k l) (k r)))
            (plus-k [k] (plus (k l) (k r)))]
      (->ClojureFlow (merge-k :source-map)
                     (plus-k :sink-map)
                     (plus-k :trap-map)
                     (plus-k :tails)
                     (plus-k :pipe)))))

;; ## Flow Building

(defn begin-flow
  "Accepts a tappable thing and returns a ClojureFlow."
  [source]
  (let [tap (to-source source)
        id  (.getIdentifier tap)]
    (map->ClojureFlow {:source-map {id tap}
                       :pipe (Pipe. id)})))

;; TODO: Make this work by adding a field to the ClojureFlow and add
;; the proper information to the flowdef.

(defn name*
  "Assigns the supplied name to the flow."
  [m name]
  (assoc m :name name))

(defn strip-pipe
  "Strip the leaf pipe from the supplied flow."
  [m]
  (assoc m :pipe nil))

(defn flow-def
  "Generates an instance of FlowDef off of the supplied ClojureFlow."
  [{:keys [source-map sink-map trap-map tails]}]
  (doto (FlowDef.)
    (.addSources source-map)
    (.addSinks sink-map)
    (.addTraps trap-map)
    (.addTails (into-array Pipe tails))))

(defn graph
  "Writes a dotfile for the flow at hand to the supplied path."
  [flow path]
  (-> flow compile* (.writeDOT path))
  flow)

(defprotocol IRunnable
  "All runnable items should implement this function."
  (run! [x]))

(defn compile-hadoop
  "Compiles the supplied FlowDef into a Hadoop flow."
  [fd]
  (-> (HadoopFlowConnector.
       (u/project-merge (conf/project-conf)
                        {"cascading.flow.job.pollinginterval" 10}))
      (.connect fd)))

(extend-protocol IRunnable
  HadoopFlow
  (run! [flow]
    (.complete flow)
    (when-not (-> flow .getFlowStats .isSuccessful)
      (throw (RuntimeException. "Flow failed to complete."))))

  FlowDef
  (run! [fd]
    (run! (compile-hadoop fd)))

  ClojureFlow
  (run! [flow]
    (run! (flow-def flow))))

(defn parse-exec-args
  "Accept a sequence of (maybe) string and other items and returns a
  vector of [theString or \"\", [other items]]."
  [[f & rest :as args]]
  (if (string? f)
    [f rest]
    ["" args]))

(defn all-to-memory
  "Return the results of the supplied workflows as data
  structures. Accepts many workflows, and (optionally) a flow name as
  the first argument."
  [& args]
  (let [[name flows] (parse-exec-args args)]
    (io/with-fs-tmp [fs tmp]
      (hadoop/mkdirs fs tmp)
      (let [taps (->> (u/unique-rooted-paths tmp)
                      (map tap/hfs-seqfile)
                      (take (count flows)))]
        (->> (map (comp strip-pipe ops/write*)
                  flows taps)
             (apply ops/merge*)
             (run!))
        (doall (map tap/get-sink-tuples taps))))))

(defn to-memory
  "Executes the supplied flow and returns the results as a sequence of
  tuples."
  [m]
  (first (all-to-memory m)))
