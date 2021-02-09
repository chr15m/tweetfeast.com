(ns rsstonews.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

;(def init-state
;  {:search nil})
; 
(defonce state (r/atom {}))

(defn component-main [state]
  (if (@state :login-required)
    [:div "login page"]
    [:div (str @state)]))

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
    (let [data
          (<p!
            (-> (js/fetch "/data")
                (.then (fn [res]
                         (when (= res.status 403) (swap! state assoc :login-required true))
                         (.json res)))))]
      (js/console.log "DATA" data)
      (swap! state assoc :data data)
      (reload!)))
  (js/console.log "hi"))

