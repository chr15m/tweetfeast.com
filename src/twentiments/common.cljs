(ns twentiments.common)

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
