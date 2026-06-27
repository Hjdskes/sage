(ns sage.odoyle.facts-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [sage.odoyle.facts :as sut]))

(deftest ->facts
  (testing "correctly constructs facts from known keys"
    (let [topic "zigbee2mqtt/FRIENDLY_NAME"
          message {:humidity 42 :temperature 18}
          expected #{{::sut/device-id topic ::sut/temperature 18}
                     {::sut/device-id topic ::sut/humidity 42}}]
      (is (= expected (set (sut/->facts topic message))))))
  (testing "ignores unknown keys"
    (let [topic "zigbee2mqtt/FRIENDLY_NAME"
          message {:occupancy true}
          expected #{}]
      (is (= expected (set (sut/->facts topic message))))))
  (testing "accepts the empty map"
    (let [topic "zigbee2mqtt/FRIENDLY_NAME"
          message {}
          expected #{}]
      (is (= expected (set (sut/->facts topic message)))))))

(deftest <-facts
  (let [topic "zigbee2mqtt/FRIENDLY_NAME"
        command {::sut/device-id topic ::sut/command {:state "ON"}}
        expected [topic {:state "ON"}]]
    (is (= expected (sut/<-fact command)))))
