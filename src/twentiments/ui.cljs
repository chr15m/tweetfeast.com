(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["twitter-text" :as twitter-text]
            ["twemoji" :as twemoji]
            ["json2csv" :as json2csv]))

(def initial-state {:results-view-table true})

(defonce state (r/atom initial-state))

(def tweet-data-keys
  [:id
   :text
   :date-time-iso

   :metric-likes
   :metric-replies
   :metric-quotes
   :metric-retweets

   :ai-sentiment

   :user-id
   :user-name
   :user-full-name
   :user-image-url])

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

(defn split-date-time [dt]
  (-> dt (.split "T") (.join " ") (.split ".") first))

(defn get-users [results]
  (aget results "includes" "users"))

(defn get-user [users author_id]
  (first (filter #(= (aget % "id") author_id) users)))

(defn make-file-url [content filename content-type]
  (let [file (js/File.
               #js [content]
               (clj->js {:content-type content-type
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
              #js {:id (aget tweet "id")
                   :text (aget tweet "text")
                   :date-time-iso (split-date-time (aget tweet "created_at"))

                   :metric-likes (aget m "like_count")
                   :metric-replies (aget m "reply_count")
                   :metric-quotes (aget m "quote_count")
                   :metric-retweets (aget m "retweet_count")

                   :ai-sentiment (aget tweet "sentiment")

                   :user-id (aget tweet "author_id")
                   :user-name (aget user "username")
                   :user-full-name (aget user "name")
                   :user-image-url (aget user "profile_image_url")})))))

(defn decode-html [html]
  (let [txt (.createElement js/document "textarea")]
    (aset txt "innerHTML" html)
    (aget txt "value")))

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
                ; TODO: sanitize this? security issue if somebody tweets a script tag.
                (aset el "innerHTML"
                      (-> (aget tweet "text") (.replace "\n" "<br/>") (twitter-text/autoLink)))
                (twemoji/parse el)))}]
     [:div.date
      [:a {:href (str "https://twitter.com/i/web/status/" (aget tweet "id"))}
       (split-date-time (aget tweet "created_at"))]]
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

(defn component-tweets-table [state]
  (let [tweet-table-keys
        [[:user-image-url "Pic"]
         [:user-name "Username"]
         [:date-time-iso "Timestamp"]
         [:metric-likes "Likes"]
         [:metric-retweets "RTs"]
         [:metric-quotes "Quotes"]
         [:metric-replies "Replies"]
         [:ai-sentiment "Sentiment"]
         [:text "Tweet"]]]
    [:table
     [:thead
      [:tr
       (for [[k n] tweet-table-keys]
         [:th {:class (str "column-" (name k))} n])]]
     [:tbody
      (let [data (make-flat-json (@state :results))]
        (for [row data]
          [:tr
           (for [[k n] tweet-table-keys]
             [:td {:class (str "column-" (name k))}
              (case k
                :id nil
                :user-id nil
                :ai-sentiment (.toFixed (aget row "ai-sentiment") 2)
                :user-image-url [:img.user-image {:src (aget row "user-image-url")}]
                :text [:a {:href (str "https://twitter.com/i/web/status/" (aget row "id"))
                           :target "_blank"}
                       (decode-html (aget row "text"))]
                (aget row (name k)))])]))]]))

(defn component-download-results [state]
  (let [tweets (@state :results)
        users (get-users (@state :results))]
    [:div.downloads
     [:a {:href (make-file-url
                  (-> (json2csv/Parser.) (.parse (make-flat-json (@state :results))))
                  "tweets.csv"
                  "application/json")
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
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    [:main
     [:nav
      [:a {:href "/logout"} "Sign out"]
      [:div.user-profile
       [:img {:src (:profile_image_url user)}]]]
     [component-search state]
     [:section.options
      [:input {:name "search-state-check"
               :id "search-state-check"
               :type "checkbox"
               :checked (@state :results-view-table)
               :on-change #(swap! state assoc :results-view-table (-> % .-target .-checked))}]
      [:label {:for "search-state-check"} "table view"]]
     (when searching [:div.spinner.spin])
     (when (and (not searching) results)
       [component-download-results state])
     (when (and (not searching) results)
       (if (@state :results-view-table)
         [component-tweets-table state]
         [component-tweets state]))]))

(defn component-front-page []
  [:a {:href "/login"} "Sign in with Twitter"])

(defn component-main [state]
  (let [user (auth)]
    (if user
      [component-main-interface state user]
      [component-front-page])))

; *** startup *** ;

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      ;(<! (fetch-data! state))
      (reload!))
  (js/console.log "main!"))

