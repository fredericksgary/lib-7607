(ns com.gfredericks.lib-7607.results
  "Specialized data structures for holding results of searches."
  (:refer-clojure :exclude [type])
  (:require [com.gfredericks.lib-7607.util :refer [update]]))

;; TODO: print-these with metadata? or let that be set elsewhere?
;;
;; I'm starting to lean toward doing this with only maps, and explicit
;; :type entries (rather than on the metadata). Solves the
;; serialization problems and is less magical.

(defn ^:private type
  [coll]
  (or (if (map? coll) (:type coll))
      (class coll)))

(defmulti add-result
  "Multimethod to add a result to a collection."
  (fn [coll res] (type coll)))

(defmethod add-result clojure.lang.IPersistentCollection
  [coll x]
  (conj coll x))

(defmulti results-seq type)

(defmethod results-seq clojure.lang.IPersistentCollection
  [coll]
  (seq coll))

(defn single-result
  "Given a result holder, checks that there is exactly one result and
  returns it."
  [m]
  (let [[x :as xs] (results-seq m)]
    (assert (= 1 (count xs)) "single-result requires exactly one result")
    x))


(defmethod add-result ::best-result-keeper
  [coll x]
  (if-let [[data score] (:result coll)]
    (let [[data' score'] x]
      (if (neg? (compare score score'))
        (assoc coll :result x)
        coll))
    (assoc coll :result x)))

(defmethod results-seq ::best-result-keeper [m] (if (contains? m :result) (list (:result m))))

(def best-result-keeper
  "A vector that expects entries like [data score] and will only keep
   the best result. Earlier entries win tiebreaks."
  {:type ::best-result-keeper})


(defmethod add-result ::grouper-by
  [{:keys [group-fn empty] :as m} x]
  (let [group-key (group-fn x)]
    (update-in m [:results group-key]
               (fnil add-result empty)
               x)))

(defn grouper-by
  [group-fn empty-nested-coll]
  {:type ::grouper-by
   :group-fn group-fn
   :empty empty-nested-coll
   :results {}})


(defmethod add-result ::sampler
  [{:keys [set max] :as m} x]
  (let [r (rand)
        set' (cond-> set
                     (or (< (count set) max)
                         (> r (ffirst set)))
                     (conj [r x]))
        set'' (if (> (count set') max)
                (disj set' (first set'))
                set')]
    (-> m
        (assoc :set set'')
        (update :total (fnil inc 0)))))

(defmethod results-seq ::sampler
  [{:keys [set]}]
  (map second set))

(defn sampler
  [max-size]
  {:type ::sampler
   :max max-size
   :set (sorted-set-by #(compare (first %1) (first %2)))})


(defmethod add-result ::batcher
  [coll xs]
  (update coll :results #(reduce add-result % xs)))

(defmethod results-seq ::batcher [coll] (results-seq (:results coll)))

(defn batcher
  "Given a result collector, returns a new result collector that expects
  jobs will be sequences and adds them individually to the nested thinger."
  [nested-coll]
  {:type ::batcher
   :results nested-coll})


(defn ^:private <'
  "Like < but works on anything comparable (not just numbers)."
  [a b]
  (neg? (compare a b)))

(defn hall-of-fame-sampler
  "Keeps a sample of the best items seen so far, as well as a history
  of the last sample of previous best scores. Assumes the results are
  of the form [x score]."
  [sampler-size]
  {:type         ::hall-of-fame-sampler
   :sampler-size sampler-size
   :results      (sorted-map)})

;; Only keeps things that are at least as good as the best seen so far
(defmethod add-result ::hall-of-fame-sampler
  [me [x score]]
  (cond (or (not (contains? me :score))
            (<' (:score me) score))
        (-> me
            (assoc :score score)
            (assoc-in [:results score]
                      (add-result (sampler (:sampler-size me))
                                  x)))

        (= (:score me) score)
        (update-in me [:results score] add-result x)

        :else
        me))
