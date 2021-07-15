(ns twentiments.admin
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clast.ui :refer [time-since simple-date-time]]))

(def initial-state {})

(defonce state (r/atom initial-state))

; *** functions *** ;

(defn get-logins [state]
  (swap! state assoc-in [:progress :search] :loading)
  (go
    (let [json (<p! (-> (js/fetch "/search" #js {:method "POST"
                                                 :body (js/JSON.stringify #js {:q (@state :q)})
                                                 :headers #js {:content-type "application/json"}})
                        (.then (fn [response] (.json response)))
                        (.catch (fn [err]
                                  (clj->js {"error" {"error" err}})))))]
      (js/console.log "Results:" json)
      (swap! state #(-> %
                        (assoc :results json)
                        (assoc :results-q (@state :q))
                        (update-in [:progress] dissoc :search))))))

(defn fetch-admin-data []
  (->
    (js/fetch "/admin/data"
              #js {:method "GET"
                   :headers #js {:content-type "application/json"}})
    (.then (fn [response] (.json response)))
    (.catch #(js/alert (str %)))))

; *** components *** ;

(defn component-main [state data]
  (js/console.log "Admin data:" data)
  [:main#app
   [:section
    [:h3 "Logins"]
    [:table
     [:thead
      [:tr
       [:th "user"]
       [:td "date"]
       [:td "when"]]]
     [:tbody
      (for [row (reverse (sort-by #(aget % "t") data))]
        [:tr {:key (aget row "id")}
         [:td [:a {:href (str "https://twitter.com/" (aget row "username"))
                   :target "_BLANK"}
               (aget row "username")]]
         [:td (simple-date-time (aget row "t"))]
         [:td (time-since (aget row "t"))]])]]]])

; *** startup *** ;

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (go
    (let [data (<p! (fetch-admin-data))]
      (rd/render [component-main state data] (js/document.querySelector "main")))))

(defn main! []
  (js/console.log "main!") 
  (reload!))

