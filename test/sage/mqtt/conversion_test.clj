(ns sage.mqtt.conversion-test
  (:require
    [clojure.test :refer [deftest is]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.clojure-test :refer [for-all]]
    [sage.mqtt.conversion :as sut])
  (:import
    [clojure.lang ExceptionInfo]
    [java.util Arrays]))

(set! *warn-on-reflection* true)

(defspec bytesource-string
  (for-all [^String s gen/string]
    (is (Arrays/equals (.getBytes s "UTF-8") (sut/->byte-array s)))))

(defspec bytesource-bytes
  (for-all [^"[B" bs gen/bytes]
    (is (Arrays/equals bs (sut/->byte-array bs)))))

(deftest bytesource-nil
  (is (Arrays/equals (^"[B" make-array Byte/TYPE 0) (sut/->byte-array nil))))

(defspec bytesource-fallback
  (for-all [o (gen/one-of [gen/boolean gen/byte gen/char gen/double])]
    (is (thrown-with-msg? ExceptionInfo #"No ByteSource implementation for type" (sut/->byte-array o)))))
