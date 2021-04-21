(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def initial-state {})

(defonce state (r/atom initial-state))

; *** functions *** ;

(defn component-main [state]
  [:main
   [:h1 "hello"]])

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      ;(<! (fetch-data! state))
      (reload!))
  (js/console.log "main!"))

