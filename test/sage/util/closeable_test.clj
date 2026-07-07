(ns sage.util.closeable-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [sage.util.closeable :as sut])
  (:import
    [clojure.lang ExceptionInfo]
    [java.lang AutoCloseable]))

(set! *warn-on-reflection* true)

(deftest closeable
  (testing "1-arity"
    (testing "derefs to the given value"
      (is (= :value (deref (sut/closeable :value)))))
    (testing "close is a no-op"
      (is (nil? (.close ^AutoCloseable (sut/closeable :value))))))
  (testing "2-arity"
    (testing "derefs to the given value"
      (is (= :value (deref (sut/closeable :value (fn [_] nil))))))
    (testing "close applies close-fn to the value exactly once"
      (let [calls (atom [])
            c (sut/closeable :value (fn [v] (swap! calls conj v)))]
        (.close ^AutoCloseable c)
        (is (= [:value] @calls))))
    (testing "does not protect against exceptions thrown during close-fn"
      (let [c (sut/closeable :value (fn [_] (throw (ex-info "boom" {}))))]
        (is (thrown-with-msg? ExceptionInfo #"boom" (.close ^AutoCloseable c)))))))

(deftest closeable-with-open
  (testing "works with with-open, calling close-fn on scope exit"
    (let [calls (atom [])]
      (with-open [^AutoCloseable c (sut/closeable :value (fn [v] (swap! calls conj v)))]
        (is (= :value @c))
        (is (empty? @calls)))
      (is (= [:value] @calls)))))
