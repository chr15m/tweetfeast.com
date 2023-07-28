(ns twentiments.common
  (:require
    ["twemoji" :as twemoji]))

(defn auth []
  (let [user-data (->
                    (js/document.querySelector "main")
                    (.getAttribute "data-user"))]
    (when user-data
      (-> user-data
          js/atob
          js/JSON.parse
          (js->clj :keywordize-keys true)))))

(defn make-tweet-link [id]
  (str "https://twitter.com/i/web/status/" id))

(defn component-progress [message]
  [:div.progress
   [:div.spinner.spin]
   [:p message]])

(defn component-icon [icon]
  (let [icon (-> icon (.split "\n") rest)]
    [:span.icon {:dangerouslySetInnerHTML {:__html icon}}]))

(defn component-twicon [character]
  [:span.icon {:dangerouslySetInnerHTML {:__html (twemoji/parse character)}}])
