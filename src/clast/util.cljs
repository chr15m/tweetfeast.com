(ns clast.util
  (:require
    ["caller-id" :as caller-id]
    ["chokidar" :as file-watcher]))

(defn bail [msg]
  (js/console.error msg)
  (js/console.error "Server exit.")
  (js/process.exit 1))

(defn env [k & [default]]
  (or (aget js/process.env k) default))

(defn error-to-json [err]
  (let [e (js/JSON.parse (js/JSON.stringify err))]
    (aset e "message" (str err))
    e))

(defn reloader [reload-function]
  (let [caller (.getData caller-id)
        caller-path (aget caller "filePath")]
    (->
      (.watch file-watcher caller-path)
      (.on "change"
           (fn [path]
             (js/console.error (str "Reload triggered by " path))
             (js/setTimeout
               reload-function
               500))))))

(defn build-absolute-uri [req path]
  (let [hostname (aget req "hostname")
        host (aget req.headers "host")]
    (str req.protocol "://"
         (if (not= hostname "localhost") hostname host)
         (if (not= (aget path 0) "/") "/")
         path)))
