(ns walkable.sql-query-builder
  (:require [walkable.sql-query-builder.filters :as filters]
            [clojure.spec.alpha :as s]
            [com.wsscode.pathom.core :as p]))

(s/def ::namespaced-keyword
  #(and (keyword? %) (namespace %)))

(defn split-keyword
  "Splits a keyword into a tuple of table and column."
  [k]
  {:pre [(s/valid? ::namespaced-keyword k)]
   :post [vector? #(= 2 (count %)) #(every? string? %)]}
  (->> ((juxt namespace name) k)
    (map #(-> % (clojure.string/replace #"-" "_")))))

(defn keyword->column-name
  "Converts a keyword to column name in full form (which means table
  name included) ready to use in an SQL query."
  [k]
  {:pre [(s/valid? ::namespaced-keyword k)]
   :post [string?]}
  (->> (split-keyword k)
    (map #(str "`" % "`"))
    (clojure.string/join ".")))

(defn keyword->alias
  "Converts a keyword to an SQL alias"
  [k]
  {:pre [(s/valid? ::namespaced-keyword k)]
   :post [string?]}
  (subs (str k) 1))

(s/def ::keyword-string-map
  (s/coll-of (s/tuple ::namespaced-keyword string?)))

(s/def ::keyword-keyword-map
  (s/coll-of (s/tuple ::namespaced-keyword string?)))

(defn ->column-names
  "Makes a hash-map of keywords and their equivalent column names"
  [ks]
  {:pre [(s/valid? (s/coll-of ::namespaced-keyword) ks)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (zipmap ks
    (map keyword->column-name ks)))

(defn ->column-aliases
  "Makes a hash-map of keywords and their equivalent aliases"
  [ks]
  {:pre [(s/valid? (s/coll-of ::namespaced-keyword) ks)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (zipmap ks
    (map keyword->alias ks)))

(defn selection-with-aliases
  "Produces the part after `SELECT` and before `FROM <sometable>` of
  an SQL query"
  [columns-to-query column-names column-aliases]
  {:pre [(s/valid? (s/coll-of ::namespaced-keyword) columns-to-query)
         (s/valid? ::keyword-string-map column-names)
         (s/valid? ::keyword-string-map column-aliases)]
   :post [string?]}
  (->> columns-to-query
    (map #(str (get column-names %)
            " AS \""
            (get column-aliases %)
            "\""))
    (clojure.string/join ", ")))

(defn ->join-statement
  "Produces a SQL JOIN statement (of type string) given two pairs of
  table/column"
  [[[table-1 column-1] [table-2 column-2]]]
  {:pre [(every? string? [table-1 column-1 table-2 column-2])]
   :post [string?]}
  (str
    " JOIN `" table-2
    "` ON `"   table-1 "`.`" column-1 "` = `" table-2 "`.`" column-2 "`"))

(defn ->join-statement-with-alias
  [[[table-1 column-1] [table-2 column-2]]
   table-1-alias]
  {:pre [(every? string? [table-1 column-1 table-2 column-2 table-1-alias])]
   :post [string?]}
  (str
    " JOIN `" table-2
    "` ON `"   table-1-alias "`.`" column-1 "` = `" table-2 "`.`" column-2 "`"))

(s/def ::join-seq
  (s/and (s/coll-of ::namespaced-keyword
           :min-count 2)
    #(even? (count %))))

(defn split-join-seq
  [join-seq]
  {:pre [(s/valid? ::join-seq join-seq)]}
  (map split-keyword join-seq))

(defn ->join-pairs
  "Breaks a join-seq into pairs of table/column"
  [join-seq]
  {:pre [(s/valid? ::join-seq join-seq)]}
  (partition 2 (split-join-seq join-seq)))

(defn ->join-tables
  [join-seq]
  {:pre [(s/valid? ::join-seq join-seq)]}
  (map first (split-join-seq join-seq)))

(defn self-join?
  "Checks if a join sequence is a self-join which means joining the
  same table as the original one."
  [join-seq]
  {:pre [(s/valid? ::join-seq join-seq)]
   :post [boolean?]}
  (let [[from-table & other-tables] (->join-tables join-seq)]
    (contains? (set other-tables) from-table)))

(defn ->table-1-alias
  "In case of self-join, use the column name in the relation table as
  alias for the original table."
  [[[table-1 column-1] [table-2 column-2]]]
  {:pre [(every? string? [table-1 column-1 table-2 column-2])]
   :post [string?]}
  column-2)

(defn ->column-1-alias
  "In case of self-join, use the column name in the relation table as
  alias for the original table. Returns the original column name with
  alias table."
  [join-seq]
  {:pre  [(s/valid? ::join-seq join-seq)]
   :post [#(s/valid? ::namespaced-keyword %)]}
  (let [[[table-1 column-1] [table-2 column-2]] (map (juxt namespace name) (take 2 join-seq))]
    (keyword column-2 column-1)))

(defn ->join-statements
  "Helper for compile-schema. Generates JOIN statement strings for all
  join keys given their join sequence."
  [join-seq]
  {:pre [(s/valid? ::join-seq join-seq)]
   :post [string?]}
  (if (self-join? join-seq)
    (let [[p1 p2] (->join-pairs join-seq)]
      (str
        (->join-statement-with-alias p1 (->table-1-alias p1))
        (->join-statement p2)))
    (apply str (map ->join-statement (->join-pairs join-seq)))))

(s/def ::joins
  (s/coll-of (s/tuple ::namespaced-keyword ::join-seq)))

(defn joins->self-join-source-table-aliases
  "Helper for compile-schema. Generates source table aliases for
  self-join keys."
  [joins]
  {:pre [(s/valid? ::joins joins)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (reduce (fn [result [k join-seq]]
            (if (self-join? join-seq)
              (assoc result k (->table-1-alias (first (->join-pairs join-seq))))
              result))
    {} joins))

(defn joins->self-join-source-column-aliases
  "Helper for compile-schema. Generates source column aliases for
  self-join keys."
  [joins]
  {:pre  [(s/valid? ::joins joins)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (reduce (fn [result [k join-seq]]
            (if (self-join? join-seq)
              (assoc result k (->column-1-alias join-seq))
              result))
    {} joins))

(s/def ::query-string-input
  (s/keys :req-un [::columns-to-query ::column-names ::column-aliases ::source-table]
    :opt-un [::source-table-alias ::join-statement ::where-conditions
             ::offset ::limit ::order-by]))

(defn ->query-string
  "Builds the final query string ready for SQL server."
  [{:keys [columns-to-query column-names column-aliases source-table
           source-table-alias join-statement where-conditions
           offset limit order-by] :as input}]
  {:pre [(s/valid? ::query-string-input input)]
   :post [string?]}
  (str "SELECT " (selection-with-aliases columns-to-query column-names column-aliases)
    " FROM `" source-table "`"

    (when source-table-alias
      (str " `" source-table-alias "`"))

    join-statement

    (when where-conditions
      (str " WHERE "
        where-conditions))
    (when order-by
      (str " ORDER BY " order-by))
    (when limit
      (str " LIMIT " limit))
    (when offset
      (str " OFFSET " offset))))

(s/def ::sql-schema
  (s/keys :req []
    :opt []))

(defn columns-to-query
  "Infers which columns to include in SQL query from child keys in env ast"
  [{::keys [sql-schema] :as env}]
  {:pre  [(s/valid? ::sql-schema sql-schema)]
   :post [set?]}
  (let [{::keys [column-keywords required-columns source-columns]} sql-schema

        all-child-keys
        (->> env :ast :children (map :dispatch-key))

        child-column-keys
        (->> all-child-keys (filter #(contains? column-keywords %)) (into #{}))

        child-required-keys
        (->> all-child-keys (map #(get required-columns %)) (apply clojure.set/union))

        child-join-keys
        (filter #(contains? source-columns %) all-child-keys)

        child-source-columns
        (->> child-join-keys (map #(get source-columns %)) (into #{}))]
    (clojure.set/union
      child-column-keys
      child-required-keys
      child-source-columns)))

(s/def ::conditional-ident
  (s/tuple keyword? (s/tuple ::filters/operators ::namespaced-keyword)))

(defn conditional-idents->source-tables
  "Produces map of ident keys to their corresponding source table name."
  [idents]
  {:pre  [(s/valid? (s/coll-of ::conditional-ident) idents)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (reduce (fn [result [ident-key [_operator column-keyword]]]
            (assoc result ident-key
              (first (split-keyword column-keyword))))
    {} idents))

(defn joins->source-tables
  "Produces map of join keys to their corresponding source table name."
  [joins]
  {:pre  [(s/valid? ::joins joins)]
   :post [#(s/valid? ::keyword-string-map %)]}
  (reduce (fn [result [k join-seq]]
            (assoc result k
              (first (split-keyword (first join-seq)))))
    {} joins))

(defn joins->source-columns
  "Produces map of join keys to their corresponding source column keyword."
  [joins]
  {:pre  [(s/valid? ::joins joins)]
   :post [#(s/valid? ::keyword-keyword-map %)]}
  (reduce (fn [result [k join-seq]]
            (assoc result k
              (first join-seq)))
    {} joins))

(s/def ::multi-keys
  (s/coll-of (s/tuple (s/or :single-key keyword?
                        :multiple-keys (s/coll-of keyword))
               (constantly true))))

(s/def ::single-keys
  (s/coll-of (s/tuple keyword? (constantly true))))

(defn expand-multi-keys
  "Expands a map where keys can be a vector of keywords to pairs where
  each keyword has its own entry."
  [m]
  {:pre  [(s/valid? ::multi-keys m)]
   :post [#(s/valid? ::single-keys %)]}
  (reduce (fn [result [ks v]]
            (if (sequential? ks)
              (let [new-pairs (mapv (fn [k] [k v]) ks)]
                (vec (concat result new-pairs)))
              (conj result [ks v])))
    [] m))

(defn flatten-multi-keys
  "Expands multiple keys then group values of the same key"
  [m]
  {:pre  [(s/valid? ::multi-keys m)]
   :post [#(s/valid? ::single-keys %)]}
  (let [expanded  (expand-multi-keys m)
        key-set   (set (map first expanded))
        keys+vals (mapv (fn [current-key]
                          (let [current-vals (mapv second (filter (fn [[k v]] (= current-key k)) expanded))]
                            [current-key (if (= 1 (count current-vals))
                                           (first current-vals)
                                           current-vals)]))
                    key-set)]
    (into {} keys+vals)))

(s/def ::ident-condition
  (s/tuple ::filters/operators ::namespaced-keyword))

(defn ident->condition
  "Converts given ident key in env to equivalent condition dsl."
  [env condition]
  {:pre  [(s/valid? ::ident-condition condition)
          (s/valid? (s/keys :req-un [::ast]) env)]
   :post [#(s/valid? ::filters/clauses %)]}
  (let [params         (-> env :ast :key rest)
        [operator key] condition]
    {key (cons operator params)}))

(defn compile-extra-conditions
  [extra-conditions]
  (reduce (fn [result [k v]]
            (assoc result k
              (if (fn? v)
                v
                (fn [env] v))))
    {} extra-conditions))

(defn compile-join-statements
  [joins]
  (reduce (fn [result [k v]]
            (assoc result k
              (->join-statements v)))
    {} joins))

(defn expand-reversed-joins [reversed-joins joins]
  (let [more (reduce (fn [result [backward forward]]
                       (assoc result backward
                         (reverse (get joins forward))))
               {} reversed-joins)]
    ;; if a join exists in joins, keep it instead of
    ;; reversing associated backward join
    (merge more joins)))

(defn expand-denpendencies* [m]
  (reduce (fn [result [k vs]]
            (assoc result k
              (set (flatten (mapv #(if-let [deps (get m %)]
                                     (vec deps)
                                     %)
                              vs)))))
    {} m))

(defn expand-denpendencies [m]
  (let [m' (expand-denpendencies* m)]
    (if (= m m')
      m
      (expand-denpendencies m'))))

(defn separate-idents [idents]
  (reduce (fn [result [k v]]
            (if (string? v)
              (assoc-in result [:unconditional-idents k] v)
              (assoc-in result [:conditional-idents k] v)))
    {:unconditional-idents {}
     :conditional-idents   {}}
    idents))

(defn compile-schema
  [{:keys [columns pseudo-columns required-columns idents extra-conditions
           reversed-joins joins join-cardinality]}]
  (let [idents                                            (flatten-multi-keys idents)
        {:keys [unconditional-idents conditional-idents]} (separate-idents idents)
        extra-conditions                                  (flatten-multi-keys extra-conditions)
        joins                                             (->> (flatten-multi-keys joins)
                                                            (expand-reversed-joins reversed-joins))
        join-cardinality                                  (flatten-multi-keys join-cardinality)
        self-join-source-table-aliases                    (joins->self-join-source-table-aliases joins)
        self-join-source-column-aliases                   (joins->self-join-source-column-aliases joins)
        true-columns                                      (set (concat columns
                                                                 (vals self-join-source-column-aliases)))
        columns                                           (set (concat true-columns
                                                                 (keys pseudo-columns)))]
    #::{:column-keywords  columns
        :required-columns (expand-denpendencies required-columns)
        ;; SELECT ... FROM ?
        ;; derive from idents and joins
        :source-tables    (merge (conditional-idents->source-tables conditional-idents)
                            unconditional-idents
                            (joins->source-tables joins))
        :source-columns   (joins->source-columns joins)

        :self-join-source-table-aliases  self-join-source-table-aliases
        :self-join-source-column-aliases self-join-source-column-aliases

        :join-cardinality join-cardinality
        :ident-conditions conditional-idents
        :extra-conditions (compile-extra-conditions extra-conditions)
        :column-names     (merge (->column-names true-columns)
                            pseudo-columns)
        :column-aliases   (->column-aliases columns)
        :join-statements  (compile-join-statements joins)}))

(defn clean-up-all-conditions
  [all-conditions]
  (let [all-conditions (remove nil? all-conditions)]
    (case (count all-conditions)
      0 nil
      1 (first all-conditions)
      all-conditions)))

(defn process-pagination
  [{::keys [sql-schema] :as env}]
  {:pre [(s/valid? (s/keys :req [::column-names]) sql-schema)]
   :post [#(s/valid? (s/keys :req-un [::offset ::limit ::order-by]))]}
  (let [{::keys [column-names]} sql-schema]
    {:offset
     (when-let [offset (get-in env [:ast :params ::offset])]
       (when (integer? offset)
         offset))
     :limit
     (when-let [limit (get-in env [:ast :params ::limit])]
       (when (integer? limit)
         limit))
     :order-by
     (when-let [order-by (get-in env [:ast :params ::order-by])]
       (filters/->order-by-string column-names order-by))}))

(defn process-conditions
  [{::keys [sql-schema] :as env}]
  (let [{::keys [ident-conditions extra-conditions source-columns
                 self-join-source-column-aliases]}
        sql-schema
        e (p/entity env)
        k (get-in env [:ast :dispatch-key])

        ident-condition
        (when-let [condition (get ident-conditions k)]
          (ident->condition env condition))

        source-column
        (get source-columns k)

        source-column-alias
        (get self-join-source-column-aliases k)

        source-condition
        (when source-column
          {(or source-column-alias source-column)
           [:= (get e source-column)]})

        extra-condition
        (when-let [->condition (get extra-conditions k)]
          (->condition env))

        supplied-condition
        (get-in env [:ast :params ::filters])

        supplied-condition
        (when (s/valid? ::filters/clauses supplied-condition)
          supplied-condition)]
    [ident-condition source-condition extra-condition supplied-condition]))

(defn parameterize-all-conditions
  [{::keys [sql-schema] :as env}]
  (let [{::keys [column-names]} sql-schema
        all-conditions          (clean-up-all-conditions (process-conditions env))]
    (when all-conditions
      (filters/parameterize {:key    nil
                             :keymap column-names}
        all-conditions))))

(defn process-query
  [{::keys [sql-schema] :as env}]
  (let [{::keys [column-keywords
                 column-names
                 column-aliases
                 join-statements
                 source-tables
                 self-join-source-table-aliases]} sql-schema
        k                                         (get-in env [:ast :dispatch-key])
        [where-conditions query-params]           (parameterize-all-conditions env)
        {:keys [offset limit order-by]}           (process-pagination env)]
    {:query-string-input {:source-table       (get source-tables k)
                          :source-table-alias (get self-join-source-table-aliases k)
                          :join-statement     (get join-statements k)
                          :columns-to-query   (columns-to-query env)
                          :column-names       column-names
                          :column-aliases     column-aliases
                          :where-conditions   where-conditions
                          :offset             offset
                          :limit              limit
                          :order-by           order-by}
     :query-params       query-params}))

(defn pull-entities
  [{::keys [sql-schema sql-db run-query] :as env}]
  (let [{::keys [source-tables join-cardinality]} sql-schema
        k                                         (get-in env [:ast :dispatch-key])]
    (if (contains? source-tables k)
      ;; this is a join, let's go for data
      (let [{:keys [query-string-input query-params]}
            (process-query env)

            sql-query
            (->query-string query-string-input)

            query-result
            (run-query sql-db
              (if query-params
                (cons sql-query query-params)
                sql-query))

            do-join
            (if (= :one (get join-cardinality k))
              #(p/join (first %2) %1)
              #(p/join-seq %1 %2))]
        (if (seq query-result)
          (do-join env query-result)
          []))

      ::p/continue)))
