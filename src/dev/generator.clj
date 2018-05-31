(ns dev.generator
  (:require [t6.from-scala.core :as $]
            [clojure.reflect :as r]
            [org.apache.clojure-mxnet.util :as util]
            [clojure.pprint])
  (:import (org.apache.mxnet NDArray Symbol))
  (:gen-class))


(defn clojure-case
  [string]
  (-> string
      (clojure.string/replace #"(.)([A-Z][a-z]+)" "$1-$2")
      (clojure.string/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (clojure.string/lower-case)
      (clojure.string/replace #"\_" "-")
      (clojure.string/replace #"\/" "div")))

(defn symbol-transform-param-name [parameter-types]
  (->> parameter-types
       (map str)
       (map (fn [x] (or (util/symbol-param-coerce x) x)))
       (map (fn [x] (last (clojure.string/split x #"\."))))))

(defn ndarray-transform-param-name [parameter-types]
  (->> parameter-types
       (map str)
       (map (fn [x] (or (util/ndarray-param-coerce x) x)))
       (map (fn [x] (last (clojure.string/split x #"\."))))))

(defn has-variadic? [params]
  (->> params
       (map str)
       (filter (fn [s] (re-find #"\&" s)))
       count
       pos?))


(defn increment-param-name [pname]
  (if-let [num-str (re-find #"-\d" pname)]
    (str (first (clojure.string/split pname #"-")) "-" (inc (Integer/parseInt (last (clojure.string/split num-str #"-")))))
    (str pname "-" 1)))

(defn rename-duplicate-params [params]
  (reduce (fn [known-names n] (conj known-names (if (contains? (set known-names) n)
                                                  (increment-param-name n)
                                                  n)))
          []
          params))


;;;;;;; symbol

(def symbol-reflect-info (->> (:members (r/reflect Symbol))
                              (map #(into {} %))))

(def symbol-public (filter (fn [x] (-> x :flags :public)) symbol-reflect-info))

(def symbol-public-no-default (->> symbol-public
                                   (filter #(not (re-find #"\$default" (str (:name %)))))))

(into #{} (mapcat :parameter-types symbol-public-no-default))
                                        ;#{java.lang.Object scala.collection.Seq scala.Option long double scala.collection.immutable.Map int ml.dmlc.mxnet.Executor float ml.dmlc.mxnet.Context java.lang.String scala.Enumeration$Value ml.dmlc.mxnet.Symbol int<> ml.dmlc.mxnet.Symbol<> ml.dmlc.mxnet.Shape java.lang.String<>}

(def symbol-hand-gen-set  #{"scala.Option"
                            "int org.apache.mxnet.Executor"
                            "scala.Enumeration$Value"
                            "org.apache.mxnet.Context"
                            "scala.Tuple2"
                            "scala.collection.Traversable"} )

;;; min and max have a conflicting arity of 2 with the auto gen signatures
(def symbol-filter-name-set #{"max" "min"})

(defn is-symbol-hand-gen? [info]
  (or
   (->> (:name info)
        str
        (get symbol-filter-name-set))
   (->> (map str (:parameter-types info))
        (into #{})
        (clojure.set/intersection symbol-hand-gen-set)
        count
        pos?)))

(def symbol-public-to-hand-gen (filter is-symbol-hand-gen? symbol-public-no-default))
(def symbol-public-to-gen (->> (remove  #(contains?(->>  symbol-public-to-hand-gen
                                                          (mapv :name)
                                                          (mapv str)
                                                          (set)) (str (:name %))) symbol-public-no-default)))


(count symbol-public-to-hand-gen) ;=> 35 mostly bind!
(count symbol-public-to-gen) ;=> 307

(into #{} (map :name symbol-public-to-hand-gen));=>  #{arange bind ones zeros simpleBind Variable}

(defn public-by-name-and-param-count [public-reflect-info]
 (->> public-reflect-info
      (group-by :name)
      (map (fn [[k v]] [k (group-by #(count (:parameter-types %)) v)]))
      (into {})))


(defn symbol-vector-args []
  `(if (map? ~'kwargs-map-or-vec-or-sym) (~'util/empty-list) (~'util/coerce-param ~'kwargs-map-or-vec-or-sym #{"scala.collection.Seq"})))

(defn symbol-map-args []
  `(if (map? ~'kwargs-map-or-vec-or-sym) (util/convert-symbol-map ~'kwargs-map-or-vec-or-sym) nil))


(defn add-symbol-arities [params function-name]
  (if (= ["sym-name" "kwargs-map" "symbol-list" "kwargs-map-1"] (mapv str params))
    [`([~'sym-name ~'attr-map ~'kwargs-map]
       (~function-name ~'sym-name (~'util/convert-symbol-map ~'attr-map) (~'util/empty-list) (~'util/convert-symbol-map ~'kwargs-map)))
     `([~'sym-name ~'kwargs-map-or-vec-or-sym]
       (~function-name ~'sym-name nil ~(symbol-vector-args) ~(symbol-map-args)))
     `([~'kwargs-map-or-vec-or-sym]
       (~function-name nil nil  ~(symbol-vector-args) ~(symbol-map-args)))]))

(defn gen-symbol-function-arity [op-name op-values function-name]
  (mapcat
   (fn [[param-count info]]
     (let [targets (->> (mapv :parameter-types info)
                        (apply interleave)
                        (mapv str)
                        (partition (count info))
                        (mapv set))
           pnames (->> (mapv :parameter-types info)
                       (mapv symbol-transform-param-name)
                       (apply interleave)
                       (partition (count info))
                       (mapv #(clojure.string/join "-or-" %))
                       (rename-duplicate-params)
                       (mapv symbol))
           coerced-params (mapv (fn [p t] `(~'util/nil-or-coerce-param ~(symbol (clojure.string/replace p #"\& " "")) ~t)) pnames targets)
           params (if (= #{:public :static} (:flags (first info)))
                    pnames
                    (into ['sym] pnames))
           function-body (if (= #{:public :static} (:flags (first info)))
                           `(~'util/coerce-return (~(symbol (str "Symbol/" op-name)) ~@coerced-params))
                           `(~'util/coerce-return (~(symbol (str  "." op-name)) ~'sym ~@coerced-params)
                             ))]
       (when (not (and (> param-count 1) (has-variadic? params)))
         `[(
            ~params
            ~function-body
            )
           ~@(add-symbol-arities params function-name)])))
   op-values))


(def all-symbol-functions
 (for [operation  (sort (public-by-name-and-param-count symbol-public-to-gen))]
   (let [[op-name op-values] operation
         function-name (-> op-name
                           str
                           $/decode-scala-symbol
                           clojure-case
                           symbol)]
     `(~'defn ~function-name
       ~@(remove nil? (gen-symbol-function-arity op-name op-values function-name))))))

(defn write-to-file [functions ns-gen require-import-writer-fn fname]
  (with-open [w (clojure.java.io/writer fname)]
    (.write w ns-gen)
    (.write w "\n\n")
    (.write w ";; Do not edit - this is auto-generated")
    (.write w "\n\n")
    (require-import-writer-fn w)
    (.write w "\n\n")
  (doseq [f functions]
    (clojure.pprint/pprint f w)
    (.write w "\n"))))

(def symbol-gen-ns "(ns org.apache.clojure-mxnet.gen.symbol)")

(defn write-symbol-ns-require-import [w]
  (.write w "(in-ns 'org.apache.clojure-mxnet.symbol)\n")
  (.write w "(require '[org.apache.clojure-mxnet.util :as util])\n")
  (.write w "(import '(org.apache.mxnet Symbol))\n"))


(defn generate-symbol-file []
  (write-to-file all-symbol-functions symbol-gen-ns  write-symbol-ns-require-import "src/org/apache/clojure_mxnet/gen/symbol.clj"))


;;;;;;;;NDARRAY


(def ndarray-reflect-info (->> (:members (r/reflect NDArray))
                                (map #(into {} %))))


(def ndarray-public (filter (fn [x] (-> x :flags :public)) ndarray-reflect-info))

(def ndarray-public-no-default (->> ndarray-public
                             (filter #(not (re-find #"\$default" (str (:name %)))))))

(def ndarray-hand-gen-set  #{"org.apache.mxnet.NDArrayFuncReturn"
                             "org.apache.mxnet.Context"
                             "scala.Enumeration$Value"
                             "scala.Tuple2"
                             "scala.collection.Traversable"} )

(defn is-ndarray-hand-gen? [info]
  (->> (map str (:parameter-types info))
       (into #{})
       (clojure.set/intersection ndarray-hand-gen-set)
       count
       pos?))


(def ndarray-public-to-hand-gen (filter is-ndarray-hand-gen? ndarray-public-no-default))
(def ndarray-public-to-gen (->> (remove  #(contains?(->>  ndarray-public-to-hand-gen
                                                          (mapv :name)
                                                          (mapv str)
                                                          (set)) (str (:name %))) ndarray-public-no-default)))


(count ndarray-public-to-hand-gen) ;=> 15
(count ndarray-public-to-gen) ;=> 486

(map :name ndarray-public-to-hand-gen)



(defn gen-ndarray-function-arity [op-name op-values]
  (for [[param-count info] op-values]
    (let [targets (->> (mapv :parameter-types info)
                       (apply interleave)
                       (mapv str)
                       (partition (count info))
                       (mapv set))
          pnames (->> (mapv :parameter-types info)
                      (mapv ndarray-transform-param-name)
                      (apply interleave)
                      (partition (count info))
                      (mapv #(clojure.string/join "-or-" %))
                      (rename-duplicate-params)
                      (mapv symbol))
          coerced-params (mapv (fn [p t] `(~'util/coerce-param ~(symbol (clojure.string/replace p #"\& " "")) ~t)) pnames targets)
          params (if (= #{:public :static} (:flags (first info)))
                   pnames
                   (into ['ndarray] pnames))
          function-body (if (= #{:public :static} (:flags (first info)))
                          `(~'util/coerce-return (~(symbol (str "NDArray/" op-name)) ~@coerced-params))
                          `(~'util/coerce-return (~(symbol (str  "." op-name)) ~'ndarray ~@coerced-params)
                            ))]
      (when (not (and (> param-count 1) (has-variadic? params)))
        `(
          ~params
          ~function-body
          )))))


(def all-ndarray-functions
 (for [operation  (sort (public-by-name-and-param-count ndarray-public-to-gen))]
   (let [[op-name op-values] operation
         function-name (-> op-name
                           str
                           $/decode-scala-symbol
                           clojure-case
                           symbol)]
     `(~'defn ~function-name
       ~@(remove nil? (gen-ndarray-function-arity op-name op-values))))))

(def ndarray-gen-ns "(ns org.apache.clojure-mxnet.gen.ndarray)")

(defn write-ndarray-ns-require-import [w]
  (.write w "(in-ns 'org.apache.clojure-mxnet.ndarray)\n")
  (.write w "(require '[org.apache.clojure-mxnet.util :as util])\n")
  (.write w "(import '(org.apache.mxnet NDArray Shape))\n"))


(defn generate-ndarray-file []
  (write-to-file all-ndarray-functions ndarray-gen-ns  write-ndarray-ns-require-import "src/org/apache/clojure_mxnet/gen/ndarray.clj"))

(defn -main [& args]
  (do
    (println "Generating the core ndarray api from the Scala classes")
    (generate-ndarray-file)
    (println "Generating the core symbol api from the Scala classes")
    (generate-symbol-file)))

(comment

  ;; This generates a file with the bulk of the nd-array functions
  (generate-ndarray-file)

  ;; This generates a file with the bulk of the symbol functions
  (generate-symbol-file)  )
