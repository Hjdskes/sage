(ns build
  "Sage's build script.

   Run with `clj -T:build uber`

   For more information, run `clj -T:deps:build help/doc`."
  (:require
    [clojure.data.json :as json]
    [clojure.tools.build.api :as b]))

(def project
  "Shared definitions between build.clj and clj-nix."
  (json/read-str (slurp "project.json") :key-fn keyword))

(def lib
  "Sage's groupId and artefactId."
  (symbol (:name project)))

(def version
  "Sage's version."
  (:version project))

(def main-ns
  "Sage's main namespace, which has a -main function and (:gen-class)."
  (symbol (:main-ns project)))

(defn clean
  "Remove all build artefacts."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build an uberjar, containing Sage and all its dependencies as a self-contained jar file."
  [_]
  (clean nil)
  (let [basis (b/create-basis)
        class-dir "target/classes"]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :class-dir class-dir})
    (b/uber {:uber-file (format "target/%s-%s-standalone.jar" (name lib) version)
             :class-dir class-dir
             :basis basis
             :main 'sage.main
             ; Paho reaches into java.net.URI.userInfo.
             :manifest {"Add-Opens" "java.base/java.net"}})))
