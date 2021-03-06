(ns edmondson.survey-analysis
  (:require [incanter.stats :as istats]
            [clojure.string :as string]
            [edmondson.config :as cfg]
            [edmondson.utils :as u]))

(defn score-answers
  [model-index {answers :answers :as normalized-response}]
  (let [question-prefix (fn [qname];; :DEMO_FUNCTION_6_TEXT -> :DEMO_FUNCTION
                          (->> (string/split (name  qname) #"_")
                               (take 2)
                               (string/join "_")
                               keyword))
        score-answer
        (fn [acc [question-name answer]]
          (if-let [q-score-model (get model-index question-name)]
            (let [scale-fn (get q-score-model :scale)
                  score-map (get q-score-model :scoring)
                  scored-answer (get score-map answer :unscored)
                  excludes (into #{} (get q-score-model :exclude []))]
              (cond (contains? excludes answer)
                    (assoc acc question-name :excluded)

                    (= scored-answer :unscored)
                    (assoc acc question-name :unscored)
                    ;; (throw (new RuntimeException
                    ;;             (str "Answer " answer " to " question-name " is not supported.")))


                    :else
                    (assoc acc question-name
                           (scale-fn scored-answer (:scoring q-score-model)))))
            acc));;if q-score-model empty

        verbatim-answer (fn [acc [question-name answer]]
                          (let [question-prefix (question-prefix question-name)
                                q-score-model (or (get model-index question-prefix)
                                                  (get model-index question-name))]
                            (if (contains? q-score-model :verbatim)
                              (assoc acc question-name answer)
                              acc)))

        group-answer (fn [acc [question-name answer]]
                       (let [question-prefix (question-prefix question-name)
                             q-score-model (get model-index question-name)]
                         (if (contains? q-score-model :group)
                           (assoc acc question-name answer)
                           acc)))]
    (-> normalized-response
        (assoc :scores
               (reduce score-answer {} (:answers normalized-response)))
        (assoc :verbatims
               (reduce verbatim-answer {} (:answers normalized-response)))
        (assoc :groups
               (reduce group-answer {} (:answers normalized-response))))))

(defn score-responses
  [model-index normalized-responses]
  (map #(score-answers model-index %) normalized-responses))


(defn- aggregate-model-construct
  [[construct model] scored-responses]
  (let [{qs :questions
         verbatim-prefixes :verbatims} model
        qs-keys (map #(if (coll? %) (first %) %) qs) ;; just question keys in model

        normalized-scores (map #(with-meta
                       (->> qs-keys
                            (select-keys (:scores %))
                            (remove (fn [[k v]] (= :excluded v)))
                            (into {}))
                       (-> (:meta-data %)
                           (assoc :responseId (:responseId %))))
                    scored-responses)

        ;; compute aggregate scores across questions

        aggregate-scores (map #(let [vs (vals %)
                                     num-answers (count vs)]
                                 ;;#dbg ^{:break/when (some keyword? vs)}
                                 (with-meta
                                   {:mean-score (istats/mean vs)
                                    :num-answers num-answers
                                    :num-questions (count (keys %))}
                                   (meta %)))
                              normalized-scores)
        non-empty-scores (remove #(= (:num-answers %) 0)
                                 aggregate-scores)

        worst-scores (sort-by :mean-score non-empty-scores)
        best-scores (reverse worst-scores)
        score-variance (istats/variance (map :mean-score non-empty-scores))
        score-stddev (Math/sqrt score-variance)


        scores-by-question (apply merge-with
                                  (fn [x y]
                                    (if (coll? x)
                                      (conj x y)
                                      [x y]))
                                  normalized-scores)
        ;; ensure everything is a collection, even if only one response
        scores-by-question (u/map-values #(if-not (coll? %) [%] %) scores-by-question)

        question-stats (u/map-values
                        #(let [variance (istats/variance %)]
                           {:score-total (reduce + %)
                            :score-mean  (istats/mean %)
                            :score-variance variance
                            :score-stddev (Math/sqrt variance)})
                        scores-by-question)

        worst-question   (->> question-stats
                              (sort-by (comp :score-total second))
                              (map first))
        best-question    (reverse worst-question)
        stable-question  (->> question-stats
                              (sort-by (comp :score-variance second))
                              (map first))
        varying-question (reverse stable-question)

        sub-construct-scores (map :score-mean (vals question-stats))

        construct-score-total (reduce + 0 sub-construct-scores)
        construct-mean (istats/mean sub-construct-scores)
        construct-var (istats/variance sub-construct-scores)
        construct-stddev (Math/sqrt (istats/variance sub-construct-scores))

        prefix-key? (fn [key prefixes]
                      (some #(.startsWith (name key) (name %)) prefixes))

        verbatims (map #(->> (:verbatims %)
                             (filter (fn [[k _]]
                                       (prefix-key? k verbatim-prefixes)))
                             (into {}))
                       scored-responses)

        agg-verbatims (apply merge-with
                             (fn [x y] (if (coll? x) (conj x y) [x y]))
                             verbatims)]
    [construct
     {:construct-stats
      {:score-total construct-score-total
       :score-mean construct-mean
       :score-variance construct-var
       :score-stddev construct-stddev}

      :response-stats
      {:response-mean-scores aggregate-scores
       :worst-scores worst-scores
       :best-scores best-scores
       :response-score-variance score-variance
       :response-score-stddev score-stddev}

      :question-stats
      {:scores-by-question scores-by-question
       :question-stats question-stats
       :best-questions best-question
       :worst-questions worst-question
       :varying-questions varying-question
       :stable-questions stable-question}

      :verbatims agg-verbatims}]))

(defn aggregate-scores
  [model scored-responses]
  (into {} (map #(aggregate-model-construct % scored-responses)
                model)))
