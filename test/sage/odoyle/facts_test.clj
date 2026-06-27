(ns sage.odoyle.facts-test
  (:require
    [clojure.test :refer [deftest is]]
    [sage.odoyle.facts :as sut]))

(deftest ->facts
  (let [topic "zigbee2mqtt/FRIENDLY_NAME"
        message {:humidity 42 :temperature 18}
        expected [{::sut/device-id topic ::sut/temperature 18}
                  {::sut/device-id topic ::sut/humidity 42}]]
    (is (= expected (sut/->facts topic message)))))

(deftest <-facts
  (let [topic "zigbee2mqtt/FRIENDLY_NAME"
        command {::sut/device-id topic ::sut/command {:state "ON"}}
        expected [topic {:state "ON"}]]
    (is (= expected (sut/<-fact command)))))
