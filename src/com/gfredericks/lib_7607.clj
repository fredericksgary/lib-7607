(ns com.gfredericks.lib-7607
  "Trying to write nice generic search controllers."
  (:require [com.gfredericks.lib-7607.managers :refer [job done? run report id]
             :as man]
            [com.gfredericks.lib-7607.persistence :as persistence]
            [com.gfredericks.lib-7607.util :refer [update]]))

(declare info)

(defmethod print-method ::search-state
  [a sw]
  (doto sw
    (.write "#<Searcher ")
    (.write (pr-str (info @a)))
    (.write ">")))

(defn update-job-frequency
  ([m] (update-job-frequency m (System/currentTimeMillis)))
  ([m now]
     (let [{:keys [last-job rolling-avg]
            :or {last-job (- now 5000)
                 rolling-avg 5.0}}
           m

           ;; This could be improved to start out volatile and then
           ;; settle down.
           avg-factor 0.999
           time-since (- now last-job)
           new-avg (+ (* avg-factor rolling-avg)
                      (* (- 1 avg-factor) (/ time-since 1000.0)))]
       (assoc m :last-job now :rolling-avg new-avg))))

(defn update-performance
  "Second arg is the milliseconds taken during the job being
  reported."
  [m milliseconds]
  (-> m
      (update-job-frequency)
      (update :total-jobs (fnil inc 0))
      (update :total-milliseconds (fnil + 0) milliseconds)))

;;
;; Start worker code
;;

(defmacro me [] `(Thread/currentThread))

(defn current-job
  [state]
  (get-in state [:thread-jobs (me)]))

(defn get-job
  "Returns nil if there are none available."
  [state-atom]
  {:pre [(not (current-job @state-atom))]}
  ;; this could be more optimal about pauses
  (swap! state-atom
         (fn [{:keys [search-manager resumable-jobs] :as m}]
           (if-let [[job & jobs] (seq resumable-jobs)]
             (-> m
                 (assoc-in [:thread-jobs (me)] job)
                 (assoc :resumable-jobs jobs))
             (if-let [[job search-manager'] (job search-manager)]
               (-> m
                   (assoc :search-manager search-manager')
                   (assoc-in [:thread-jobs (me)] job))
               m))))
  (or (current-job @state-atom)
      (if (-> @state-atom :search-manager done?)
        nil
        (do (Thread/sleep 100)
            (recur state-atom)))))

(defn crash
  [state throwable]
  (let [me' (me)]
    (swap! state
           #(-> %
                (update :threads dissoc me')
                (update :thread-jobs dissoc me')
                (update :crashed-threads conj
                        {:throwable throwable
                         :job (get-in % [:thread-jobs me'])})))))

(defn do-job
  [state-atom job]
  (let [b (System/currentTimeMillis)
        x (run job)
        t (- (System/currentTimeMillis) b)]
    (swap! state-atom
           (fn [state]
             (-> state
                 (update :performance update-performance t)
                 (update :search-manager report (id job) x)
                 (update :thread-jobs dissoc (me)))))))

(defn should-work?
  [state]
  (and (get-in state [:threads (me)])
       (not (done? (:search-manager state)))))

(defn worker
  [state-atom]
  (try
    (while (should-work? @state-atom)
      (when-let [job (get-job state-atom)]
        (do-job state-atom job)))
    (catch Throwable t
      (crash state-atom t)
      (throw t)))
  ;; pausing or done
  (swap! state-atom update :threads dissoc (me)))

;;
;; End worker code
;;


(defn add-thread
  "Starts a worker thread."
  [state]
  (let [t (Thread. (bound-fn [] (worker state)))]
    (swap! state #(update % :threads assoc t true))
    (.start t)))

(defn resume
  "Starts up as many threads as are needed to meet the :thread-count."
  [state]
  (let [{:keys [thread-count threads]} @state
        threads-needed (- thread-count (count threads))]
    (dotimes [_ threads-needed]
      (add-thread state))))

(defn ^:private assoc-when-v
  "Returns (assoc m k v) when v is truthy."
  [m k v]
  (cond-> m v (assoc k v)))

(def default-opts
  {:thread-count 4
   :show-results? true})

(defn initial-searcher-state
  [search-manager opts]
  (let [persistence-file (get-in opts [:persistence :file])
        search-manager (if (and persistence-file
                                (.exists persistence-file))
                         (persistence/read persistence-file)
                         search-manager)
        atts (merge default-opts opts)
        resumable-jobs (-> search-manager :jobs vals seq)]
    (-> atts
        (assoc-when-v :resumable-jobs resumable-jobs)
        (assoc :threads {}
               :thread-jobs {}
               :search-manager search-manager)
        (assoc-in [:persistence :agent] (agent 0 :validator number?)))))

(defn persist
  [last-saved file sm]
  (persistence/write file sm)
  (System/currentTimeMillis))

(defn maybe-persist
  [last-saved last-saved' file sm]
  (if (= last-saved last-saved')
    (persist last-saved file sm)
    last-saved))

(defn ^:private persistence-watcher
  [_ state-atom old-state new-state]
  (let [{{:keys [interval file agent]}
         :persistence
         sm :search-manager}
        new-state
        last-saved @agent]
    (when file
      (cond (and interval
                 (> (- (System/currentTimeMillis) last-saved)
                    interval))
            (send-off agent maybe-persist last-saved file sm)

            ;; If we just finished, save regardless of whether
            ;; it's time or not.
            (and (done? sm)
                 (not (done? (:search-manager old-state))))
            (send-off agent persist file sm)))))

(defn searcher
  "The main entry point. Given a search manager and an optional options map,
  returns an atom representing the computational process, and starts
  some worker threads.

  If a :persistence map is provided in the opts and the :file therein already
  exists, it will be read and its contents used as the search-manager intsead
  of the provided first arg.

  Please you come up with a better API for this."
  ([search-manager] (searcher search-manager {}))
  ([search-manager opts]
     (doto (atom (initial-searcher-state search-manager opts)
                 :meta {:type ::search-state})
       (add-watch ::persister persistence-watcher)
       (resume))))

(defn info
  "The map that gets shown in a computation's printed representation."
  [{:keys [search-manager crashed-threads total domain threads
           show-results? extra-info]
    {avg-between :rolling-avg} :performance
    :as m}]
  (let [zombies (->> threads keys (remove (memfn isAlive)))]
    (cond-> {:running-threads (count threads)
             :jobs-finished (:report-count search-manager)
             :done? (done? search-manager)}

            avg-between
            (assoc :frequency
              ;; poor man's rounding?
              (-> avg-between / (->> (format "%.2f")) (read-string)))

            (seq crashed-threads)
            (assoc :crashed-thread-count (count crashed-threads))

            show-results?
            (assoc :results (man/results search-manager))

            extra-info
            (merge (extra-info m))

            (seq zombies)
            (assoc :zombie-threads (count zombies)))))

(defn pause
  "Flags all worker threads to quit after completion of their current
  job."
  [state]
  (swap! state update :threads
         (fn [m]
           (zipmap (keys m) (repeat false))))
  :ok)

(defn set-thread-count
  "Changes the :thread-count entry on the computation and starts/stops
  threads as appropriate."
  [state-atom n]
  (swap! state-atom assoc :thread-count n)
  (let [threads-running (-> @state-atom :threads count)]
    (cond (< threads-running n)
          (resume state-atom)

          (> threads-running n)
          (swap! state-atom
                 (fn [m]
                   (let [threads-to-kill (->> (:threads m)
                                              (filter val)
                                              (map key)
                                              (drop n))]
                     (assoc m :threads
                            (reduce #(assoc %1 %2 false)
                                    (:threads m)
                                    threads-to-kill)))))))
  :ok)
