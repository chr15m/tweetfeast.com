(ns rsstonews.main
  (:require
    [rsstonews.web :as web]
    ["keyv" :as Keyv]
    ["path" :as path]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defonce app (web/create))
(defonce keyv (Keyv. (or (aget js/process.env "DATABASE") "sqlite://./rsstonews.sqlite")))

(defn setup-routes [app]
  ; (.use app serve-middleware)
  (.use app "/ip" (fn [req res] (.json res (aget req "ip")))))

(defn reload! []
  (web/reset-routes app)
  (setup-routes app)
  (println "Fresh routes loaded: " (aget app "_router" "stack" "length")))

(defn main! []
  (go
    (let [[host port] (<p! (web/serve app))]
      (reload!)
      (println "Servers started."))))
