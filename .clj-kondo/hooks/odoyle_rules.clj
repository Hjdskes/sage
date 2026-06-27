(ns hooks.odoyle-rules
  "Allow clj-kondo to lint odoyle.rules/ruleset.

   Taken from https://github.com/oakes/odoyle-rules/pull/38."
  (:require
    [clj-kondo.hooks-api :as api]))

(def rule-delimiter #{:what :when :then :then-finally})
(def reserved-syms #{'session 'match})

(defn let-one-and-fake-use [sym-nodes]
  (let [per-symbol-def (transduce (map #(map-indexed vector %)) concat (vals (group-by api/sexpr sym-nodes)))
        let-symbols (transduce
                      (map (fn [[idx node]]
                             (if (= idx 0)
                               [node (api/token-node '[]) (api/token-node '_) (api/token-node (api/sexpr node))]
                               [(api/token-node '_) node])))
                      concat per-symbol-def)]
    (into [] let-symbols)))

(defn ruleset-node->lets-node [ruleset-node]
  (eduction
    (map (fn [[rule-name-node rule-block-node]]
           [rule-name-node
            (partition-by (fn [node] (rule-delimiter (api/sexpr node)))
                          (:children rule-block-node))]))
    (map (fn [[rule-name-node blocks]]
           (loop [[node & remaining] blocks
                  rule-block {:rule-name-node rule-name-node}
                  looking-at nil]
             (if node
               (if looking-at
                 (recur remaining (assoc rule-block looking-at node) nil)
                 (recur remaining rule-block (keyword (str (name (api/sexpr (first node))) "-block"))))
               rule-block))))
    (map (fn [{:keys [rule-name-node what-block when-block then-block then-finally-block]}]
           (let [what-nodes (flatten (map :children what-block))
                 sym-nodes (into [] (filter (comp symbol? api/sexpr)) what-nodes)
                 _ (doseq [reserved-node (into [] (filter (comp reserved-syms api/sexpr)) sym-nodes)]
                     (api/reg-finding!
                       {:row (:row (meta reserved-node))
                        :col (:col (meta reserved-node))
                        :message "reserved symbol usage in :what block!"
                        :type :odoyle/reserved}))
                 sym-nodes (let-one-and-fake-use sym-nodes)
                 keyword-nodes (into [] (filter (comp keyword? api/sexpr)) what-nodes)
                 keyword-nodes (conj keyword-nodes rule-name-node)
                 session-scope (let-one-and-fake-use [(api/token-node 'session)])
                 match-scope (let-one-and-fake-use [(api/token-node 'match)])]
             (api/list-node
               (list*
                 (api/token-node 'let)
                 (api/vector-node (conj session-scope
                                        (api/token-node '_)
                                        (api/list-node (list* (api/token-node 'vector) keyword-nodes))))
                 (api/list-node (list (api/token-node 'prn) (api/token-node :separator)))
                 (api/list-node
                   (list
                     (api/token-node 'let)
                     (api/vector-node match-scope)
                     (api/list-node
                       (list*
                         (api/token-node 'let)
                         (api/vector-node (cond-> sym-nodes
                                            when-block (conj (api/token-node '_)
                                                             (api/list-node (list* (api/token-node 'vector) when-block)))))
                         then-block))))
                 then-finally-block)))))
    (partition 2 (:children ruleset-node))))

(defn ruleset-hook
  [{:keys [node]}]
  (try
    (let [ruleset-node (nth (:children node) 1)
          rule-nodes-as-lets (ruleset-node->lets-node ruleset-node)]
      {:node (api/list-node (list* (api/token-node 'vector) rule-nodes-as-lets))})
    (catch Exception e
      (api/reg-finding!
        {:row (:row (meta node))
         :col (:col (meta node))
         :message (str "hook error: " e)
         :type :odoyle/hook-error}))))
