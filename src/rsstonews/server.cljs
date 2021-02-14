(ns rsstonews.server
  (:require
    [rsstonews.web :as web]
    ["keyv" :as Keyv]
    ["path" :as path]
    ["node-fetch" :as fetch]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(set! *warn-on-infer* false)

(defonce app (web/create))
(defonce keyv (Keyv. (web/env "DATABASE" "sqlite://./rsstonews.sqlite")))

(defn authenticate [req res pass]
  (if (nil? (aget req "session" "authenticated"))
    (-> res (.status 403) (.json #js {:error "Not authenticated."}))
    (pass)))

(defn get-data [req res]
  (go (.json res (<p! (.get keyv "user-data")))))

(defn set-data [req res]
  (go (.json res (<p! (.set keyv "user-data" (aget req "body"))))))

(defn login [req res]
  (if (= (aget req.body "password") (web/env "PASSWORD" "password"))
    (do
      (aset req.session "authenticated" true)
      (.json res true))
    (-> res (.status 403) (.json #js {:error "Incorrect password"}))))

(defn cors-proxy [req res]
  (let [url (aget req.query "url")]
    (-> (fetch url)
        (.then (fn [r] (-> (.text r)
                           (.then (fn [text] #js [text r])))))
        (.then (fn [text f]
                 (for [[k v] (.entries f.headers)]
                   (.header res k v))
                 (.status res f.status)
                 (.send res text))))))

(defn setup-routes [app]
  (.post app "/login" login)
  (.use app authenticate)
  (.get app "/proxy" cors-proxy)
  (.get app "/data" get-data)
  (.post app "/save" set-data))

(defn reload! []
  (web/reset-routes app)
  (setup-routes app)
  (println "Fresh routes loaded: " (aget app "_router" "stack" "length")))

(defn main! []
  (go
    (let [[host port] (<p! (web/serve app))]
      (reload!)
      (println "Servers started."))))
