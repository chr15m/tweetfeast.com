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

(defn get-users [results]
  (aget results "includes" "users"))

(defn get-user [users author_id]
  (first (filter #(= (aget % "id") author_id) users)))

(defn make-file-url [content filename content-type]
  (let [file (js/File. #js [content] (clj->js {:content-type content-type
                                           :name filename}))]
    (js/URL.createObjectURL file)))

(defn make-full-json-string [results]
  (let [results (js/JSON.parse (js/JSON.stringify results))
        results-meta (aget results "meta")
        _ (js-delete results-meta "next_token")]
    (js/JSON.stringify results nil 2)))

(defn make-flat-json [results]
  (let [users (get-users results)
        tweets (aget results "data")]
    (.map (aget results "data")
          (fn [tweet i]
            (let [user (get-user users (aget tweet "author_id"))
                  m (aget tweet "public_metrics")]
              (clj->js
                {:id (aget tweet "id")
                 :text (aget tweet "text")
                 :date-time-iso (aget tweet "created_at")

                 :metric-likes (aget m "like_count")
                 :metric-replies (aget m "reply_count")
                 :metric-quotes (aget m "quote_count")
                 :metric-retweets (aget m "retweet_count")

                 :ai-sentiment (aget tweet "sentiment")

                 :user-id (aget tweet "author_id")
                 :user-name (aget user "username")
                 :user-full-name (aget user "name")
                 :user-image-url (aget user "profile_image_url")}))))))

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
  (let [users (get-users (@state :results))]
    [:div
     (for [tweet (aget (@state :results) "data")]
       (with-meta [component-tweet tweet users] {:key (aget tweet "id")}))]))

(defn component-download-results [state]
  (let [tweets (@state :results)
        users (get-users (@state :results))]
    [:div.downloads
     [:a {:href "#"
          :download "tweets.csv"} "download csv"]
     [:a {:href (make-file-url
                  (js/JSON.stringify (make-flat-json (@state :results)) nil 2)
                  "tweets.json"
                  "application/json")
          :download "tweets.json"} "download json"]
     [:a {:href (make-file-url
                  (make-full-json-string (@state :results))
                  "tweets-full.json"
                  "application/json")
          :download "tweets.json"}
      "download API json"]]))

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

