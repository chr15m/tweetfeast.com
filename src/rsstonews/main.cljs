(ns rsstonews.main
  (:require
    [rsstonews.web :as web]
    ["keyv" :as Keyv]
    ["path" :as path]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defonce app (web/create))
(defonce keyv (Keyv. (or (aget js/process.env "DATABASE") "sqlite://./rsstonews.sqlite")))

(defn authenticate [req res pass]
  (if (nil? (aget req "session" "authenticated"))
    (-> res (.status 403) (.json #js {:error "Not authenticated."}))
    (pass)))

(defn get-data [req res]
  (go (.json res (<p! (.get keyv "user-data")))))

(defn set-data [req res]
  (go (.json res (<p! (.set keyv "user-data" (aget req "body"))))))

(defn login [req res]
  (if (= req.body (web/env "PASSWORD"))
    (aset req.session "authenticated" true)))

(defn setup-routes [app]
  (.use app authenticate)
  (.get app "/data" get-data)
  (.post app "/save" set-data)
  (.post app "/login" login))

(defn reload! []
  (web/reset-routes app)
  (setup-routes app)
  (println "Fresh routes loaded: " (aget app "_router" "stack" "length")))

(defn main! []
  (go
    (let [[host port] (<p! (web/serve app))]
      (reload!)
      (println "Servers started."))))
