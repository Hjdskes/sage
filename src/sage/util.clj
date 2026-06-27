(ns sage.util)

(defn select-rename-keys
  "Composition of set/rename-keys and select-keys.

   Example: (select-rename-keys {:a :c} {:a 1 :b 2}) => {:c 1}"
  [kmap m]
  (reduce-kv (fn [m' from to] (if (contains? m from) (assoc m' to (get m from)) m')) {} kmap))
