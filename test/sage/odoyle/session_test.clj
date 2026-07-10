(ns sage.odoyle.session-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [odoyle.rules :as o]
    [sage.mqtt.client :as mqtt.client]
    [sage.odoyle.facts :as-alias facts]
    [sage.odoyle.rules :as rules]
    [sage.odoyle.session :as sut]))

(def ^:private *session @#'sut/*session)

(def ^:private ruleset
  (o/ruleset
    {::freeze-alarm
     [:what [device-id ::facts/temperature temp]
      :when (neg? temp)
      :then (o/insert! device-id ::facts/command {:alert "freezing"})]}))

(defn- with-fresh-test-session
  "Test fixture to run each test with their own O'Doyle session.

   process-facts! exercises a global *session atom, so tests must snapshot and restore to avoid sharing
   state."
  [f]
  (let [snapshot @*session]
    (reset! *session (reduce o/add-rule (o/->session) (into rules/rules ruleset)))
    (try
      (f)
      (finally
        (reset! *session snapshot)))))

(use-fixtures :each with-fresh-test-session)

(deftest process-facts!-emits-and-retracts-commands
  (testing "a rule satisfied by fact produces a command"
    (is (= [{::facts/device-id "sensor/outside" ::facts/command {:alert "freezing"}}]
           (sut/process-facts! [{::facts/device-id "sensor/outside" ::facts/temperature -2}]))))

  (testing "the emitted command is retracted, so it isn't returned again"
    (is (empty? (o/query-all @*session ::rules/get-commands)))))

(deftest process-facts!-no-match-emits-nothing
  (testing "a fact that doesn't satisfy any rule produces no commands"
    (is (empty? (sut/process-facts! [{::facts/device-id "sensor/outside" ::facts/temperature 18}])))))

(deftest mqtt-handler-publishes-each-command
  (testing "each command returned by process-facts! is published to its own topic"
    (let [published (atom [])]
      ; mqtt-handler is pure wiring around process-facts! and mqtt.client/publish!.
      ; We stub out both so this tests only checks the wiring, not sensor parsing or rule semantics
      ; or real MQTT I/O: these are all covered elsewhere.
      (with-redefs [sut/process-facts! (constantly [{::facts/device-id "zigbee2mqtt/kitchen/radiator"
                                                     ::facts/command {:state "ON"}}])
                    mqtt.client/publish! (fn [conn topic payload]
                                           (swap! published conj {:conn conn :topic topic :payload payload}))]
        (sut/mqtt-handler ::fake-conn "zigbee2mqtt/kitchen/temperature" {:temperature 18})
        (is (= 1 (count @published)))
        (is (= [{:conn ::fake-conn :topic "zigbee2mqtt/kitchen/radiator" :payload "{\"state\":\"ON\"}"}]
               @published))))))
