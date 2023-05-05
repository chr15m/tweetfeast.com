(ns twentiments.admin
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [cljs.core.async :refer (go) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            [sitefox.ui :refer [time-since simple-date-time]]))

(def initial-state {})

(defonce state (r/atom initial-state))

; *** functions *** ;

(defn fetch-admin-data []
  (->
    (js/fetch "/admin/data"
              #js {:method "GET"
                   :headers #js {:content-type "application/json"}})
    (.then (fn [response] (.json response)))
    (.catch #(js/alert (str %)))))

; *** components *** ;

(defn component-main [_state data]
  (js/console.log "Admin data:" data)
  (let [user-subs (aget data "user-subs")]
    [:main#app
     [:section
      [:h3 "Logins"]
      [:table
       [:thead
        [:tr
         [:th "user"]
         [:td "what"]
         [:td "date"]
         [:td "when"]
         [:td "usage"]
         [:td "sub"]]]
       [:tbody
        (for [row
              (->> (aget data "login-events")
                   (filter (fn [row] (.startsWith (aget row "kind") "last/request")))
                   (sort-by (fn [row] (str (get {false 1 true 0} (nil? (aget user-subs (aget row "id")))) "-" (aget row "t") )))
                   reverse)]
          (let [sub (aget user-subs (aget row "id"))]
            [:tr {:key (str (aget row "id") (aget row "kind"))}
             [:td [:a {:href (str "https://twitter.com/" (aget row "username"))
                       :target "_BLANK"}
                   (aget row "username")]]
             [:td (-> (aget row "kind") (.split ":") first)]
             [:td (simple-date-time (aget row "t"))]
             [:td (time-since (aget row "t"))]
             [:td (str (-> data (aget "user-dates") (aget (aget row "id")) count) " days")]
             [:td (cond (some-> sub (aget "plan") (aget "active")) "active"
                        (some-> sub (aget "plan")) "expired"
                        (some-> sub (aget "object")) "24hr"
                        (some-> sub (aget "lifetime")) "life"
                        sub "?"
                        :else "")]]))]]]]))

; *** startup *** ;

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (go
    (let [data (<p! (fetch-admin-data))]
      (rd/render [component-main state data] (js/document.querySelector "main")))))

(defn main! []
  (js/console.log "main!") 
  (reload!))

