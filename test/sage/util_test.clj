(ns sage.util-test
  (:require
    [clojure.set :as set]
    [clojure.test :refer [is]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.clojure-test :refer [for-all]]
    [sage.util :as sut]))

(defspec select-rename-keys-is-the-composition-of-rename-keys+select-keys
  (for-all [overlap (gen/set gen/keyword {:min-elements 1 :max-elements 5})
            m-only (gen/set gen/keyword {:max-elements 5})
            km-only (gen/set gen/keyword {:max-elements 5})
            m-vals (gen/vector gen/any (+ (count overlap) (count m-only)))
            km-new-keys (gen/vector gen/keyword (+ (count overlap) (count km-only)))]
    (let [m-keys (concat overlap m-only)
          km-keys (concat overlap km-only)
          m (zipmap m-keys m-vals)
          km (zipmap km-keys km-new-keys)]
      (is (= (-> m (select-keys (keys km)) (set/rename-keys km))
             (sut/select-rename-keys km m))))))
