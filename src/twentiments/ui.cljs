(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def initial-state {})

(defonce state (r/atom initial-state))

; *** functions *** ;

(defn auth []
  (let [user-data (->
                    (js/document.querySelector "#app")
                    (.getAttribute "data-user"))]
    (when user-data
      (-> user-data
          js/atob
          js/JSON.parse
          (js->clj :keywordize-keys true)))))

; *** components *** ;

(defn component-main [state]
  (let [user (auth)]
    [:main
     [:h1 "hello"]
     (if user
       [:div
        [:img {:src (:profile_image_url user)}]
        [:p (:name user)]
        [:pre (pr-str user)]
        [:a {:href "/logout"} "Sign out"]]
       [:a {:href "/login"} "Sign in with Twitter"])]))

; *** startup *** ;

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      ;(<! (fetch-data! state))
      (reload!))
  (js/console.log "main!"))

