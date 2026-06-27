(ns sage.odoyle.facts
  (:require
    [clojure.spec.alpha :as s]
    [sage.util :refer [select-rename-keys]]))

;; Temperature reading in degrees Celsius, often multiplied by 100 (e.g., 2150 for 21.5°C).
(s/def ::temperature number?)

;; Relative Humidity (RH) in percentage, tracking how much moisture is in the air compared to the
;; maximum amount the air can hold at that specific temperature.
(s/def ::humidity number?)

;; Atmospheric pressure in hPa.
(s/def ::pressure number?)

;; The device identifier that tells where the message came from, or is going to.
;; For MQTT, this maps to the MQTT topic.
(s/def ::device-id string?)

;; A message, always eventually encoded as JSON.
;; TODO: rename mqtt-command or mqtt-message, to future proof for other transports?
(s/def ::message map?)

;; A command to be emitted as a result of the rules firing.
(s/def ::command
  (s/keys :req [::device-id
                ::message]))

(defn ->facts
  "Parse a Zigbee2mqtt JSON payload (all Zigbee2mqtt messages are JSON[1]) into a seq of fact maps.

   For example, the MQTT message {:occupancy true :illuminance_lux 40 :temperature 18} received on topic
   \"zigbee2mqtt/friendly_name\" turns into the sequence
   [{:sage.odoyle.facts/device-id \"zigbee2mqtt/friendly_name\" :occupancy true}
    {:sage.odoyle.facts/device-id \"zigbee2mqtt/friendly_name\" :lux 40}
    {:sage.odoyle.facts/device-id \"zigbee2mqtt/friendly_name\" :temperature 18}].

   This is because O'Doyle rules match on individual facts: each rule pattern matches a map with
   specific keys. If we inserted one big map we'd have to write rules that expect all those keys to
   be present simultaneously, which would mean a rule for \"occupancy AND lux AND temperature\" rather
   than being able to react to each independently. Splitting into one fact per reading lets us write
   focused rules like \"when occupancy changes\" without caring whether lux or temperature are present.

   [1]: https://www.zigbee2mqtt.io/guide/usage/mqtt_topics_and_messages.html#zigbee2mqtt-friendly-name"
  [topic data]
  (->> data
       (select-rename-keys {:temperature ::temperature
                            :humidity ::humidity
                            :pressure ::pressure})
       (map (fn [[k v]] {::device-id topic k v}))))

;; TODO: MQTT QoS and retained flags?
(defn <-fact
  "Turn a fact back into a Zigbee2mqtt JSON message, ready to be published.

   For example, the fact

   {:sage.odoyle.facts/device-id \"zigbee2mqtt/friendly_name\" :command {:state \"ON\"}}

   turns into an MQTT message to topic \"zigbee2mqtt/friendly_name\" with JSON payload {\"state\": \"ON\"}."
  [{::keys [device-id command] :as _command-fact}]
  [device-id command])
