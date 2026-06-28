(ns sage.odoyle.session
  (:require
    [clojure.set :as set]
    [odoyle.rules :as o]
    [sage.odoyle.facts :as facts]
    [sage.odoyle.rules :as rules]
    [taoensso.telemere :as t]))

(defn- insert-facts
  [session facts]
  (reduce
    (fn [session {::facts/keys [device-id] :as fact}]
      ;; Each fact from ->facts has exactly one attribute key beyond ::facts/device-id
      ;; (e.g. {::facts/device-id "topic" ::facts/temperature 18}), but we use reduce-kv
      ;; over the remainder to handle multiple attributes uniformly if that ever changes.
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

   Returns any commands produced as a result, after retracting them from the session atomically.
   O'Doyle retracts old values overwritten by new facts automatically."
  [facts]
  (let [*command-facts (atom nil)]
    (swap! *session (fn [session]
                      (let [session (o/fire-rules (insert-facts session facts))
                            command-facts (o/query-all session ::rules/get-commands)]
                        (reset! *command-facts command-facts)
                        (retract-commands session command-facts))))
    (map #(set/rename-keys % {:device-id ::facts/device-id :command ::facts/command}) @*command-facts)))

(defn mqtt-handler
  "Bridges MQTT and O'Doyle.

   For each MQTT message, derive a sequence of facts, insert those into the O'Doyle session, fire
   the rules, extract and retract the commands that are output by the rules, and return those commands
   in a sequence of zero or more [topic message] tuples."
  [topic data]
  (if-let [facts (seq (facts/->facts topic data))]
    (do
      (t/log! {:data {:facts facts}} "Received new facts over MQTT")
      (map facts/<-fact (process-facts! facts)))
    (do
      (t/log! "Received no new facts over MQTT")
      [])))
