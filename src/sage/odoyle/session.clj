(ns sage.odoyle.session
  (:require
    [clojure.data.json :as json]
    [clojure.set :as set]
    [odoyle.rules :as o]
    [sage.mqtt.client :as mqtt.client]
    [sage.odoyle.facts :as facts]
    [sage.odoyle.rules :as rules]
    [taoensso.telemere :as t]))

(defn- insert-facts
  [session facts]
  (reduce
    (fn [session {::facts/keys [device-id] :as fact}]
      (reduce-kv (fn [s k v]
                   (o/insert s device-id {k v}))
                 session
                 (dissoc fact ::facts/device-id)))
    session
    facts))

(defn- retract-commands
  "Retracts all the given command facts from the given session."
  [session command-facts]
  (reduce
    (fn [session {:keys [device-id]}]
      (o/retract session device-id ::facts/command))
    session
    command-facts))

(def ^:private *session
  "The global O'Doyle session atom."
  (atom (reduce o/add-rule (o/->session) rules/all-rules)))

(defn process-facts!
  "Insert all facts into the rules session and fire rules once.

   Returns any commands produced as a result, retracting them from the session afterwards.
   O'Doyle retracts old values overwritten by new facts automatically."
  [facts]
  (let [session (swap! *session #(o/fire-rules (insert-facts % facts)))
        command-facts (o/query-all session ::rules/get-commands)]
    ;; Two separate swaps are intentional: fire-rules must complete before
    ;; we can query which commands were produced, and retraction comes after.
    (swap! *session #(retract-commands % command-facts))
    (map #(set/rename-keys % {:device-id ::facts/device-id :command ::facts/command}) command-facts)))

;; TODO: should publishing happen here, or inside start-system!?
;; TODO: when publishing, should we block or just scream into the void?
;;       can we register a callback and alert on an error?
(defn mqtt-handler
  "Bridges MQTT and O'Doyle.

   For each MQTT message, derive a sequence of facts, insert those into the O'Doyle session, fire
   the rules, extract and retract the commands that are output by the rules, and emit those commands over
   MQTT."
  [conn topic data]
  (let [facts (facts/->facts topic data)]
    (if (seq facts)
      (do
        (t/log! {:data {:facts facts}} "Received new facts over MQTT")
        (let [command-facts (process-facts! facts)]
          (doseq [fact command-facts
                  :let [[topic mqtt-message] (facts/<-fact fact)]]
            (t/trace! {:id :mqtt/publish :data {:mqtt/topic topic :mqtt/message mqtt-message}})
            (mqtt.client/publish! conn topic (json/write-str mqtt-message)))))
      (t/log! {:data {:mqtt/message data}} "Received no new facts over MQTT"))))
