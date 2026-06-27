(ns sage.util)

(defn select-rename-keys
  "Composition of set/rename-keys and select-keys.

   Example: (select-rename-keys {:a 1 :b 2} {:a :c}) => {:c 1}"
  [m kmap]
  (reduce-kv (fn [m' from to] (if (contains? m from) (assoc m' to (get m from)) m')) {} kmap))
