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
  [session command-facts]
  (reduce
    (fn [session {:keys [device-id]}]
      (o/retract session device-id ::facts/command))
    session
    command-facts))

(defn- process-facts
  "Inserts all facts into the given session and fires rules once.

   Returns the new session and any commands produced as a result, after retracting them from the new session.

   O'Doyle retracts old values overwritten by new facts automatically."
  [session facts]
  (let [session' (o/fire-rules (insert-facts session facts))
        command-facts (o/query-all session' ::rules/get-commands)
        session'' (retract-commands session' command-facts)
        commands (map #(set/rename-keys % {:device-id ::facts/device-id
                                           :command ::facts/command})
                      command-facts)]
    [session'' commands]))

(defn ->mqtt-handler
  "Returns a stateful MQTT handler function that owns an O'Doyle session internally.

   The returned function accepts `[topic data]` and returns a (possibly empty) sequence of `[topic message]`
   command tuples. For each MQTT message, it derives a sequence of facts, inserts those into the O'Doyle
   session, fires the rules, extracts and retracts the commands that are output by the rules, and returns
   those commands in a sequence of zero or more `[topic message]` command tuples.

   The returned function is safe to call from a single thread, which is fine for now because Paho serialises
   its callbacks (see notes in sage.mqtt.client)."
  []
  (let [*session (atom (reduce o/add-rule (o/->session) rules/all-rules))]
    (fn [topic data]
      (if-let [facts (seq (facts/->facts topic data))]
        (do
          (t/log! {:data {:facts facts}} "Received new facts over MQTT")
          (let [[new-session command-facts] (process-facts @*session facts)]
            (reset! *session new-session)
            (map facts/<-fact command-facts)))
        (do
          (t/log! "Received no new facts over MQTT")
          [])))))
