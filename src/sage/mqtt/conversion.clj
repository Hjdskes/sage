(ns sage.mqtt.conversion
  "Internal conversion functions transforming Clojure data structures to byte arrays for emission over MQTT.")

(set! *warn-on-reflection* true)

(defprotocol ByteSource
  (^"[B" ->byte-array [input] "Converts the input to a byte array."))

(extend-protocol ByteSource
  String
  (->byte-array [^String input]
    (.getBytes input "UTF-8"))

  Object
  (->byte-array [input]
    (throw (ex-info "No ByteSource implementation for type"
                    {:type (type input)
                     :value input}))))

(extend (Class/forName "[B")
  ByteSource
  {:->byte-array identity})

;; nil produces an empty payload, allowing for MQTT's zero-length messages.
(extend nil
  ByteSource
  {:->byte-array (constantly (make-array Byte/TYPE 0))})
