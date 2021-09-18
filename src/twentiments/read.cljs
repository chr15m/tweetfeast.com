(ns twentiments.read
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [promesa.core :as p]))

(def initial-state {:results-view-table true})

(defonce state (r/atom initial-state))

(defn get-user-feed [username]
  (p/catch
    (p/let [res (js/fetch
                  (str "/api/v1/search/tweets.json?q=from:" username " filter:links -filter:images include:nativeretweets&src=typed_query&result_type=recent&include_entities=1&tweet_mode=extended"))
            json (.json res)]
      json)
    (fn [err]
      (clj->js {"error" {"error" err}}))))

(defn get-url-from-status [status]
  (when status
    (let [entities (or (aget status "entities") #js {})
          urls (aget entities "urls")]
      (clj->js (map
                 #(aget % "expanded_url")
                 urls)))))

(defn canonical-status [status]
  (let [quoted (aget status "quoted_status")
        rted (aget status "retweeted_status")]
    (cond
      quoted (canonical-status quoted)
      rted (canonical-status rted)
      :else status)))

(defn component-main [state user-feed]
  [:div
   (for [status (aget user-feed "statuses")]
     (let [is-reply (aget status "in_reply_to_status_id")
           top-level-status status
           status (canonical-status status)]
       (js/console.log "status" status)
       (when (not is-reply)
         ; [:pre (aget status "text")]
         [:div {:key (aget status "id_str")}
          [:h4 (aget status "created_at")]
          (for [u (-> status (aget "entities") (aget "urls"))]
            ; [:pre (js/JSON.stringify u nil 2)]
            [:pre {:key (aget u "expanded_url")} (aget u "expanded_url")])
          ;[:pre (aget status "full_text")]
          ;[:pre (aget status "text")]
          [:pre (aget top-level-status "favorite_count") " + " (aget status "favorite_count")]
          [:pre (aget top-level-status "retweet_count") " + " (aget status "retweet_count")]])))
   [:pre
       (js/JSON.stringify user-feed nil 2)]])

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (p/let [user-feed (get-user-feed "paulg")]
    (js/console.log "user-feed" user-feed)
    (rd/render [component-main state user-feed] (js/document.querySelector "main"))))

(defn main! []
  (js/console.log "main!")
  (reload!))
