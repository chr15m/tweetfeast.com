(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["twitter-text" :as twitter-text]
            ["twemoji" :as twemoji]))

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

(defn initiate-search [state]
  (swap! state assoc-in [:progress :search] :loading)
  (go
    (let [response (<p! (js/fetch "/search" #js {:method "POST"
                                                 :body (js/JSON.stringify #js {:q (@state :q)})
                                                 :headers #js {:content-type "application/json"}}))
          json (<p! (.json response))]
      (swap! state #(-> %
                       (assoc-in [:results] json)
                       (update-in [:progress] dissoc :search))))))

(defn get-user [users author_id]
  (first (filter #(= (aget % "id") author_id) users)))

(defn make-json-file-url [tweets users]
  "#")

; *** components *** ;

(defn component-search [state user]
  [:fieldset.horizontal
    [:input.fit {:placeholder "Search for tweets..."
                 :value (@state :q)
                 :on-change #(swap! state assoc :q (-> % .-target .-value))
                 :on-key-down #(when (= (aget % "keyCode") 13) (initiate-search state))}]
    [:button
     {:on-click (partial initiate-search state)}
     "search"]])

(defn component-tweet [tweet users]
  (let [user (get-user users (aget tweet "author_id"))]
    [:div.twitter-tweet {:key (aget tweet "id")
                         :class (cond (>= (aget tweet "sentiment") 0.1) "sentiment-positive"
                                      (<= (aget tweet "sentiment") -0.1) "sentiment-negative")}
     [:div.profile
      [:img {:src (aget user "profile_image_url")}]
      [:div
       [:a.name
        {:href (str "https://twitter.com/" (aget user "username"))} (aget user "name")] [:br]
       [:a.username
        {:href (str "https://twitter.com/" (aget user "username"))} "@" (aget user "username")]]]
     [:div.tweet
      {:ref (fn [el]
              (when el
                (aset el "innerHTML"
                      (-> (aget tweet "text") (.replace "\n" "<br/>") (twitter-text/autoLink)))
                (twemoji/parse el)))}]
     [:div.date
      [:a {:href (str "https://twitter.com/i/web/status/" (aget tweet "id"))}
       (-> (aget tweet "created_at") (.split "T") (.join " ") (.split ".") first)]]
     (let [m (aget tweet "public_metrics")]
       [:div
        [:span "likes: " (aget m "like_count") " "]
        [:span "replies: " (aget m "reply_count") " "]
        [:span "quotes: " (aget m "quote_count") " "]
        [:span "retweets: " (aget m "retweet_count")]])
     [:div [:a {:on-click
                (fn [ev]
                  (let [el (-> ev .-target)]
                    (j/call-in js/twttr [:widgets :createTweet] (aget tweet "id") el)))}
            "see tweet"]]]))

(defn component-tweets [state]
  (let [users (aget (@state :results) "includes" "users")]
    [:div
     (for [tweet (aget (@state :results) "data")]
       [component-tweet tweet users])]))

(defn component-download-results [state]
  (let [tweets (@state :results)
        users (aget (@state :results) "includes" "users")]
    [:div.downloads
     [:a {:href "#"} "download csv"]
     [:a {:href (make-json-file-url tweets users)} "download json"]
     [:a {:href "#"} "download simplified json"]]))

(defn component-main-interface [state user]
  [:div
   ;[:pre (pr-str user)]
   [:nav
    [:a {:href "/logout"} "Sign out"]
    [:div.user-profile
     [:img {:src (:profile_image_url user)}]]]
   [component-search state]
   (when (-> @state :progress :search)
     [:div.spinner.spin])
   (when (@state :results)
     [component-download-results state])
   (when (@state :results)
     [component-tweets state])])

(defn component-main [state]
  (let [user (auth)]
    [:main
     (if user
       [component-main-interface state user]
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

