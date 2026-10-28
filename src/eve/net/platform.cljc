(ns eve.net.platform
  "The platform eve.net runs over (eve.net.run), composed from eve's adapters (adapters/README.md): the host's http
   feature (eve.http: a request that answers synchronously on every host) and its process feature (eve.process: the
   clock and a blocking sleep). A Clojure-like host gets the whole network atom by putting its http
   adapter on its classpath (eve/hosts.edn); a caller may still pass its own map, or override any key."
  (:require [eve.http :as http]
            [eve.process :as process]))

(defn platform
  "the host's platform: {:request :sleep :now-ms :seed}, overrides merged last"
  ([] (platform {}))
  ([overrides]
   (merge {:sleep process/sleep-ms! :now-ms process/now-ms :seed (process/now-ms)}
          (http/platform)
          overrides)))
