(ns edn-query-language.core
  (:refer-clojure :exclude [ident?])
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check]
            [clojure.test.check.generators :as gen #?@(:cljs [:include-macros true])]
            [clojure.test.check.properties]))

(def ^:dynamic *shallow-conversion*
  "Dynamic var.  When bound to true in the current thread calls to query->ast will no go past the
  first level of children.  This is useful when you just want the AST for one layer of a query."
  false)

#?(:clj  (def INCLUDE_SPECS true)
   :cljs (goog-define INCLUDE_SPECS true))

(when INCLUDE_SPECS
  ;; query specs

  (def generators
    {::gen-max-depth
     4

     ::gen-property
     (fn gen-property [_] gen/keyword-ns)

     ::gen-special-property
     (fn gen-special-property [_] (gen/return '*))

     ::gen-ident-key
     (fn gen-ident-key [_] gen/keyword-ns)

     ::gen-ident-value
     (fn gen-ident-value [_]
       (gen/frequency [[15 gen/simple-type-printable]
                       [1 (gen/return '_)]]))

     ::gen-ident
     (fn gen-ident [{::keys [gen-ident-key gen-ident-value] :as env}]
       (gen/tuple
         (gen-ident-key env)
         (gen-ident-value env)))

     ::gen-params
     (fn gen-params [_] (gen/map gen/any-printable gen/any-printable))

     ::gen-join-key
     (fn gen-join-key [{::keys [gen-property gen-ident gen-join-key-param-expr] :as env}]
       (gen/frequency [[10 (gen-property env)]
                       [3 (gen-ident env)]
                       [1 (gen-join-key-param-expr env)]]))

     ::gen-join-key-param-key
     (fn gen-join-key-param-key [{::keys [gen-property gen-ident] :as env}]
       (gen/one-of [(gen-property env) (gen-ident env)]))

     ::gen-join-key-param-expr
     (fn gen-join-key-param-expr [{::keys [gen-join-key-param-key gen-params] :as env}]
       (gen/let [q (gen-join-key-param-key env)
                 p (gen-params env)]
         (list q p)))

     ::gen-join
     (fn gen-join [{::keys [gen-join-key gen-join-query] :as env}]
       (gen/map (gen-join-key env) (gen-join-query env) {:num-elements 1}))

     ::gen-join-query
     (fn gen-join-query [{::keys [gen-query gen-union gen-recursion] :as env}]
       (gen/frequency [[10 (gen-query env)]
                       [2 (gen-union env)]
                       [1 (gen-recursion env)]]))

     ::gen-union-key
     (fn gen-union-key [_] gen/keyword-ns)

     ::gen-union
     (fn gen-union [{::keys [gen-union-key gen-query] :as env}]
       (gen/map (gen-union-key env) (gen-query env) {:min-elements 1}))

     ::gen-depth
     (fn gen-depth [_] (gen/large-integer* {:min 1 :max 5}))

     ::gen-recursion
     (fn gen-recursion [{::keys [gen-depth] :as env}]
       (gen/one-of [(gen-depth env) (gen/return '...)]))

     ::gen-param-expr-key
     (fn gen-param-expr-key [{::keys [gen-property gen-join gen-ident] :as env}]
       (gen/frequency [[20 (gen-property env)]
                       [8 (gen-join env)]
                       [4 (gen-ident env)]]))

     ::gen-param-expr
     (fn gen-param-expr [{::keys [gen-param-expr-key gen-params] :as env}]
       (gen/let [q (gen-param-expr-key env)
                 p (gen-params env)]
         (list q p)))

     ::gen-query-expr
     (fn gen-query-expr [{::keys [gen-property gen-join gen-ident gen-param-expr gen-special-property gen-mutation]
                          :as    env}]
       (gen/frequency [[20 (gen-property env)]
                       [6 (gen-join env)]
                       [1 (gen-ident env)]
                       [2 (gen-param-expr env)]
                       [1 (gen-mutation env)]
                       [1 (gen-special-property env)]]))

     ::gen-query
     (fn gen-query [{::keys [gen-property gen-query-expr gen-max-depth] :as env}]
       (if (> gen-max-depth 0)
         (gen/vector (gen-query-expr (update env ::gen-max-depth dec)))
         (gen/vector-distinct (gen-property env))))

     ::gen-mutation-key
     (fn gen-mutation-key [_] gen/symbol)

     ::gen-mutation-expr
     (fn gen-mutation-expr [{::keys [gen-mutation-key gen-params] :as env}]
       (gen/let [key (gen-mutation-key env)
                 val (gen-params env)]
         (list key val)))

     ::gen-mutation-join
     (fn mutation-join [{::keys [gen-mutation-expr gen-query] :as env}]
       (gen/map (gen-mutation-expr env) (gen-query env) {:num-elements 1}))

     ::gen-mutation
     (fn gen-mutation [{::keys [gen-mutation-expr gen-mutation-join] :as env}]
       (gen/frequency [[5 (gen-mutation-expr env)]
                       [1 (gen-mutation-join env)]]))})

  (defn default-gen [name]
    #((get generators name) generators))

  (defn make-gen
    [env name]
    (let [env (merge generators env)
          gen (get env name)]
      (assert gen (str "No generator available for " name))
      ((get env name) env)))

  (s/def ::property keyword?)
  (s/def ::special-property #{'*})
  (s/def ::ident-value (s/with-gen any? (default-gen ::gen-ident-value)))
  (s/def ::ident (s/with-gen (s/tuple ::property ::ident-value) (default-gen ::gen-ident)))
  (s/def ::join-key (s/or :prop ::property, :ident ::ident, :param-exp ::join-key-param-expr))
  (s/def ::join (s/map-of ::join-key ::join-query, :count 1, :conform-keys true))
  (s/def ::union (s/map-of ::property ::query, :min-count 1, :conform-keys true))
  (s/def ::recursion-depth (s/with-gen nat-int? (default-gen ::gen-depth)))
  (s/def ::recursion (s/or :depth ::recursion-depth, :unbounded #{'...}))

  (s/def ::join-query
    (s/with-gen
      (s/or :query ::query
            :union ::union
            :recursion ::recursion)
      (default-gen ::gen-join-query)))

  (s/def ::params
    (s/with-gen map? (default-gen ::gen-params)))

  (s/def ::param-expr-key
    (s/with-gen
      (s/or :prop ::property
            :join ::join
            :ident ::ident)
      (default-gen ::gen-param-expr-key)))

  (s/def ::param-expr
    (s/with-gen
      (s/and seq? (s/cat :expr ::param-expr-key :params (s/? ::params)))
      (default-gen ::gen-param-expr)))

  (s/def ::join-key-param-key (s/or :prop ::property :ident ::ident))

  (s/def ::join-key-param-expr
    (s/with-gen
      (s/and seq? (s/cat :expr ::join-key-param-key :params (s/? ::params)))
      (default-gen ::gen-join-key-param-expr)))

  (s/def ::mutation-key (s/with-gen symbol? (default-gen ::gen-mutation-key)))

  (s/def ::mutation-expr
    (s/with-gen
      (s/and seq? (s/cat :mutate-key ::mutation-key :params (s/? ::params)))
      (default-gen ::gen-mutation-expr)))

  (s/def ::mutation-join
    (s/map-of ::mutation-expr ::query :count 1 :conform-keys true))

  (s/def ::mutation
    (s/or :mutation ::mutation-expr
          :mutation-join ::mutation-join))

  (s/def ::query-expr
    (s/or :prop ::property
          :join ::join
          :ident ::ident
          :mutation ::mutation
          :param-exp ::param-expr
          :special ::special-property))

  (s/def ::query
    (s/coll-of ::query-expr :kind vector? :gen (default-gen ::gen-query)))

  ;; ast specs

  (s/def :edn-query-language.ast/query ::join-query)
  (s/def :edn-query-language.ast/key (s/or :prop ::property :ident ::ident :sym symbol?))
  (s/def :edn-query-language.ast/dispatch-key (s/or :prop ::property :sym symbol?))
  (s/def :edn-query-language.ast/union-key ::property)

  (s/def :edn-query-language.ast/children
    (s/coll-of :edn-query-language.ast/node))

  (s/def :edn-query-language.ast/root
    (s/and (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/children])
      #(= :root (:type %))
      (fn [x] (every? (comp #(contains? #{:prop :join :call nil} %) :type) (:children x)))))

  (defmulti node-type :type)

  (defmethod node-type nil [_]
    (s/keys :req-un [:edn-query-language.ast/key :edn-query-language.ast/dispatch-key]))

  (defmethod node-type :prop [_]
    (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/key :edn-query-language.ast/dispatch-key]))

  (defmethod node-type :join [_]
    (s/and (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/key :edn-query-language.ast/dispatch-key :edn-query-language.ast/query] :opt-un [:edn-query-language.ast/children])
      #(if (-> % :query first (= :recursion)) % (if (contains? % :children) % false))
      (fn [x] (every? (comp #(contains? #{:prop :join :union :call nil} %) :type) (:children x)))))

  (defmethod node-type :union [_]
    (s/and (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/query :edn-query-language.ast/children])
      #(every? (comp #{:union-entry} :type) (:children %))))

  (defmethod node-type :union-entry [_]
    (s/and (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/union-key :edn-query-language.ast/query :edn-query-language.ast/children])
      (fn [x] (every? (comp #(contains? #{:prop :join :call nil} %) :type) (:children x)))))

  (defmethod node-type :call [_]
    (s/and (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/key :edn-query-language.ast/dispatch-key ::params] :opt-un [:edn-query-language.ast/query :edn-query-language.ast/children])
      (fn [x] (every? (comp #(contains? #{:prop :join :call nil} %) :type) (:children x)))))

  (defmethod node-type :root [_]
    (s/spec :edn-query-language.ast/root))

  (s/def :edn-query-language.ast/type (set (keys (methods node-type))))
  (s/def :edn-query-language.ast/node (s/multi-spec node-type :type)))

;; library

(declare expr->ast)

(defn- mark-meta [source target]
  (cond-> target
    (meta source) (assoc :meta (meta source))))

(defn symbol->ast [k]
  {:dispatch-key k
   :key          k})

(defn keyword->ast [k]
  {:type         :prop
   :dispatch-key k
   :key          k})

(defn union-entry->ast [[k v]]
  (let [component (-> v meta :component)]
    (merge
      {:type      :union-entry
       :union-key k
       :query     v
       :children  (into [] (map expr->ast) v)}
      (when-not (nil? component)
        {:component component}))))

(defn union->ast [m]
  {:type     :union
   :query    m
   :children (into [] (map union-entry->ast) m)})

(defn call->ast [[f args :as call]]
  (if (= 'quote f)
    (assoc (expr->ast args) :target (or (-> call meta :target) :remote))
    (let [ast (update-in (expr->ast f) [:params] merge (or args {}))]
      (cond-> (mark-meta call ast)
        (symbol? (:dispatch-key ast)) (assoc :type :call)))))

(defn query->ast
  "Convert a query to its AST representation."
  [query]
  (let [component (-> query meta :component)]
    (merge
      (mark-meta query
        {:type     :root
         :children (into [] (map expr->ast) query)})
      (when-not (nil? component)
        {:component component}))))

(defn query->ast1
  "Call query->ast and return the first children."
  [query-expr]
  (-> (query->ast query-expr) :children first))

(defn join->ast [join]
  (let [query-root? (-> join meta :query-root)
        [k v] (first join)
        ast         (expr->ast k)
        type        (if (= :call (:type ast)) :call :join)
        component   (-> v meta :component)]
    (merge ast
           (mark-meta join {:type type :query v})
           (when-not (nil? component)
             {:component component})
           (when query-root?
             {:query-root true})
      (when-not (or (number? v) (= '... v) *shallow-conversion*)
             (cond
               (vector? v) {:children (into [] (map expr->ast) v)}
               (map? v) {:children [(union->ast v)]}
               :else (throw
                       (ex-info (str "Invalid join, " join)
                         {:type :error/invalid-join})))))))

(defn ident->ast [[k id :as ref]]
  {:type         :prop
   :dispatch-key k
   :key          ref})

(defn expr->ast
  "Given a query expression convert it into an AST."
  [x]
  (cond
    (symbol? x) (symbol->ast x)
    (keyword? x) (keyword->ast x)
    (map? x) (join->ast x)
    (vector? x) (ident->ast x)
    (seq? x) (call->ast x)
    :else (throw
            (ex-info (str "Invalid expression " x)
              {:type :error/invalid-expression}))))

(defn wrap-expr [root? expr]
  (if root?
    (with-meta
      (cond-> expr (keyword? expr) list)
      {:query-root true})
    expr))

(defn parameterize [expr params]
  (if-not (empty? params)
    (list expr params)
    (list expr)))

(defn ast->expr
  "Given a query expression AST convert it back into a query expression."
  ([ast]
   (ast->expr ast false))
  ([{:keys [type component] ast-meta :meta :as ast} unparse?]
   (if (= :root type)
     (cond-> (into (with-meta [] ast-meta) (map #(ast->expr % unparse?)) (:children ast))
       (not (nil? component)) (vary-meta assoc :component component))
     (let [{:keys [key query query-root params]} ast]
       (wrap-expr query-root
         (if (and params (not= :call type))
           (let [expr (ast->expr (dissoc ast :params) unparse?)]
             (parameterize expr params))
           (let [key (if (= :call type) (parameterize key params) key)]
             (if (or (= :join type)
                     (and (= :call type) (:children ast)))
               (if (and (not= '... query) (not (number? query))
                        (or (true? unparse?)
                            (= :call type)))
                 (let [{:keys [children]} ast
                       query-meta (meta query)]
                   (if (and (== 1 (count children))
                            (= :union (:type (first children)))) ;; UNION
                     (with-meta
                       {key (into (cond-> (with-meta {} ast-meta)
                                    component (vary-meta assoc :component component))
                                  (map (fn [{:keys [union-key children component]}]
                                         [union-key
                                          (cond-> (into [] (map #(ast->expr % unparse?)) children)
                                            (not (nil? component)) (vary-meta assoc :component component))]))
                                  (:children (first children)))}
                       ast-meta)
                     (with-meta
                       {key (cond-> (into (with-meta [] query-meta) (map #(ast->expr % unparse?)) children)
                              (not (nil? component)) (vary-meta assoc :component component))}
                       ast-meta)))
                 (with-meta {key query} ast-meta))
               key))))))))

(defn ast->query [query-ast]
  "Given an AST convert it back into a query expression."
  (ast->expr query-ast true))

(defn ident?
  "Check if x is a EQL ident."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (= 2 (count x))))

;; query processing helpers

(declare focus-subquery*)

(defn focus-subquery-union*
  [query-ast sub-ast]
  (let [s-index (into {} (map #(vector (:union-key %) %)) (:children sub-ast))]
    (assoc query-ast
      :children
      (reduce
        (fn [children {:keys [union-key] :as union-entry}]
          (if-let [sub (get s-index union-key)]
            (conj children (focus-subquery* union-entry sub))
            (conj children union-entry)))
        []
        (:children query-ast)))))

(defn focus-subquery*
  "Internal implementation of focus-subquery, you can use this function directly if
  you want to send AST in and get AST out (instead of query in / query out)."
  [query-ast sub-ast]
  (let [q-index (into {} (map #(vector (:key %) %)) (:children query-ast))]
    (assoc query-ast
      :children
      (reduce
        (fn [children {:keys [key type] :as focus}]
          (if-let [source (get q-index key)]
            (cond
              (= :join type (:type source))
              (conj children (focus-subquery* source focus))

              (= :union type (:type source))
              (conj children (focus-subquery-union* source focus))

              :else
              (conj children source))
            children))
        []
        (:children sub-ast)))))

(defn focus-subquery
  "Given a query, focus it along the specified query expression.

  Examples:
    (focus-query [:foo :bar :baz] [:foo])
    => [:foo]

    (fulcro.client.primitives/focus-query [{:foo [:bar :baz]} :woz] [{:foo [:bar]} :woz])
    => [{:foo [:bar]} :woz]"
  [query sub-query]
  (let [query-ast (query->ast query)
        sub-ast   (query->ast sub-query)]
    (ast->expr (focus-subquery* query-ast sub-ast) true)))

(defn transduce-children
  "Recursivelly transduce children on the AST, you can use this to apply filter/transformations
  on a whole AST. Each iteration of the transducer will get a single AST node to process.

  ```
  (->> [:a {:b [:c :d]} :e]
       (p/query->ast)
       (p/transduce-children (remove (comp #{:a :c} :key)))
       (p/ast->query))
  ; => [{:b [:d]} :e]
  ```"
  [xform {:keys [children] :as node}]
  (cond-> node
    (seq children)
    (update :children
      (fn [children]
        (into [] (comp xform (map #(transduce-children xform %))) children)))))

(defn union-children?
  "Given an AST point, check if the children is a union query type."
  [ast]
  (= :union (some-> ast :children first :type)))

(defn update-property-param
  "Add property param, eg:

  ```
  (p/update-property-param :keyword assoc :foo \"bar\") => (:keyword {:foo \"bar\"})
  (p/update-property-param '(:keyword {:param \"prev\"}) assoc :foo \"bar\") => (:keyword {:foo \"bar\" :param \"prev\"})
  ```
  "
  [x f & args]
  (if (seq? x)
    (let [[k p] x]
      (list k (apply f p args)))

    (list x (apply f {} args))))

(defn merge-asts
  "Merges two ast's."
  [qa qb]
  (reduce (fn [ast {:keys [key type params] :as item-b}]
            (if-let [[idx item] (->> ast :children
                                     (keep-indexed #(if (-> %2 :key (= key)) [%1 %2]))
                                     first)]
              (cond
                (or (= :join (:type item) type)
                    (= :prop (:type item) type))
                (if (= (:params item) params)
                  (update-in ast [:children idx] merge-asts item-b)
                  (reduced nil))

                (and (= :prop (:type item))
                     (= :join type))
                (assoc-in ast [:children idx] item-b)

                (= :call type)
                (reduced nil)

                :else ast)
              (update ast :children conj item-b)))
    qa
    (:children qb)))

(defn merge-queries
  "Merges two queries"
  [qa qb]
  (some-> (merge-asts (query->ast qa) (query->ast qb))
    (ast->query)))

(defn normalize-query-variables
  "Converts ident values and param values to ::p/var."
  [query]
  (->> (query->ast query)
       (transduce-children
         (map (fn [x]
                (cond-> x
                  (ident? (:key x))
                  (assoc :key [(first (:key x)) ::var])

                  (:params x)
                  (update :params #(into {} (map (fn [[k _]] [k ::var])) %))))))
       (ast->query)))

(defn query-id
  "Generates a consistent hash from the query. The query first goes to a process to remove any
  variables from idents and params, then we get the Clojure hash of it. You can use this to save
  information about a query that can be used to correlate with the query later."
  [query]
  (hash (normalize-query-variables query)))

(defn query->shallow-ast
  "Like query->ast, but does not follow joins.  Useful for efficiently getting just the top-level entries in
   a large query."
  [query]
  (binding [*shallow-conversion* true]
    (query->ast query)))

(when INCLUDE_SPECS
  (s/fdef query->ast
    :args (s/cat :query (s/nilable ::query))
    :ret :edn-query-language.ast/root)

  (s/fdef query->ast1
    :args (s/cat :query ::query)
    :ret :edn-query-language.ast/root)

  (s/fdef ast->query
    :args (s/cat :ast :edn-query-language.ast/node)
    :ret :edn-query-language.ast/root)

  (s/fdef ident?
    :args (s/cat :x any?)
    :ret boolean?)

  (s/fdef focus-subquery
    :args (s/cat :query ::query :sub-query ::query)
    :ret ::query)

  (s/fdef transduce-children
    :args (s/cat :xform fn? :node :edn-query-language.ast/node)
    :ret :edn-query-language.ast/node)

  (s/fdef union-children?
    :args (s/cat :ast :edn-query-language.ast/node)
    :ret boolean?)

  (s/fdef update-property-param
    :args (s/cat :x (s/or :property ::property
                          :expr ::param-expr)
                 :f fn?
                 :args (s/* any?))
    :ret ::param-expr)

  (s/fdef merge-asts
    :args (s/cat :qa :edn-query-language.ast/node, :qb :edn-query-language.ast/node)
    :ret (s/nilable :edn-query-language.ast/node))

  (s/fdef merge-queries
    :args (s/cat :qa (s/nilable ::query), :qb (s/nilable ::query))
    :ret (s/nilable ::query)))
