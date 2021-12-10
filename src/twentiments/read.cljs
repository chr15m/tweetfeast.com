(ns twentiments.read
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [promesa.core :as p]
            [sitefox.ui :refer [log]]
            [twentiments.common :refer [auth make-tweet-link]]))

(def initial-state {:results-view-table true})

(defonce state (r/atom initial-state))

(defn make-search-url [prefix entity latest]
  (str "/api/v1/search/tweets.json?q=" prefix entity " filter:links -filter:images -filter:videos include:nativeretweets"
       "&include_entities=1&tweet_mode=extended&count=100"
       (when latest
         "&src=typed_query&result_type=recent&f=live")))

(defn recursive-search-api-call [url remaining-call-count & [max-id]]
  (js/console.log "recursive-search-api-call" url remaining-call-count)
  (p/let [res (js/fetch (str url (when max-id (str "&max_id=" max-id))))
          json (.json res)
          statuses (aget json "statuses")
          max-id (aget json "search_metadata" "max_id_str")
          next-results (aget "search_metadata" "next_results")
          next-url (when next-results (-> json next-results (js/URL.)))
          next-url-search (when next-results (aget next-url "searchParams"))
          max-id (when next-results (aget next-url-search "max_id"))
          next-statuses (when
                          (and (> remaining-call-count 0) max-id)
                          (recursive-search-api-call url (dec remaining-call-count) max-id))]
    (log "json" json)
    (log "max-id" max-id)
    (if next-statuses (.concat statuses (clj->js [{"page" true}]) next-statuses) statuses)) )

(defn get-search-feed [prefix entity call-count]
  (p/catch
    (p/let [statuses (recursive-search-api-call
                       (make-search-url prefix entity false)
                       call-count)]
      statuses)
    (fn [err]
      (js/console.error err)
      (clj->js {"error" {"error" err}}))))

(defn get-urls-from-status [status]
  (when status
    (let [entities (or (aget status "entities") #js {})
          urls (aget entities "urls")
          extracted-urls (->> urls
                              (map #(aget % "expanded_url"))
                              (remove #(.startsWith (or % "") "https://twitter.com")))]
      (clj->js extracted-urls))))

(defn canonical-status [status path counts]
  (let [;has-url (get-url-from-status status)
        quoted (aget status "quoted_status")
        rted (aget status "retweeted_status")
        counts (conj counts [(aget status "favorite_count") (aget status "retweet_count")])]
    (cond
      ;has-url [status path counts]
      quoted (canonical-status quoted (conj path "quoted") counts)
      rted (canonical-status rted (conj path "rted") counts)
      :else [status path counts])))

(defn get-all-urls [status]
  ;[url status like-count retweet-count]
  (if status
    (let [quoted-status (aget status "quoted_status")
          retweet-status (aget status "retweeted_status")
          like-count (aget status "favorite_count")
          retweet-count (aget status "retweet_count")
          urls (get-urls-from-status status)
          results (map #(identity [% status like-count retweet-count]) urls)]
      (concat results (get-all-urls quoted-status) (get-all-urls retweet-status)))
    []))

(defn component-home [state user-data]
  [:div "Hello"])

(defn component-main [state entity-type statuses]
  [:div
   (for [status statuses]
     (if (aget status "page")
       [:div "--- PAGE ---"
        [:hr]]
       (let [is-reply (aget status "in_reply_to_status_id")
             top-level-status status
             all-urls (get-all-urls status)
             [status path counts] (canonical-status status [] [])]
         (js/console.log "status" status)
         (when true ;(not is-reply)
           ; [:pre (aget status "text")]
           [:div {:key (aget status "id_str")}
            [:h4 (aget status "created_at")]
            [:p
             [:span {:on-click #(log top-level-status status)} "print data"]
             " "
             [:a {:href (make-tweet-link (aget top-level-status "id_str"))
                  :target "_BLANK"}
              "tweet"]]
            (for [[url status like-count retweet-count] all-urls]
              [:p [:a {:href url} url] " " like-count " / " retweet-count])
            #_ (let [url (get-url-from-status top-level-status)]
                 [:p [:a {:href url} url]])
            #_ [:pre (js/JSON.stringify status nil 2)]
            [:pre (aget status "full_text")]
            ;[:pre (aget status "text")]
            ;[:pre (aget top-level-status "favorite_count") " + " (aget status "favorite_count")]
            ;[:pre (aget top-level-status "retweet_count") " + " (aget status "retweet_count")]
            [:pre (pr-str counts)]
            [:pre (pr-str path)]]))))
   #_ [:pre
       (js/JSON.stringify feed nil 2)]])

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (p/let [user-data (auth)
          url (js/URL. (aget js/document "location" "href"))
          entity (-> (aget url "pathname") (.replace "/reader" "") (.substr 1))
          entity-type (cond
                        (.startsWith entity "list") :list
                        (= entity "") nil
                        :else :user)
          dataset (case entity-type
                    :list (get-search-feed "list:" (.replace entity "list/" "") 2)
                    :user (get-search-feed "from:" entity 2)
                    nil)]
    (rd/render
      (if (nil? entity-type)
        [component-home state user-data]
        [component-main state entity-type dataset])
      (js/document.querySelector "main"))))

(defn main! []
  (js/console.log "main!")
  (reload!))



;*** unusued ***;

(defn get-user-lists [username]
  (p/catch
    (p/let [res (js/fetch (str "lists/list.json?screen_name=" username))
            json (.json res)]
      json)
    (fn [err]
      (clj->js {"error" {"error" err}}))))

(defn get-user-timeline []
  (p/catch
    (p/let [res (js/fetch
                  (str "/api/v1/search/tweets.json?q=filter:follows filter:nativeretweets filter:links&src=typed_query&result_type=recent&include_entities=1&tweet_mode=extended"))
            ; (js/fetch "/api/v1/statuses/home_timeline.json")
            json (.json res)]
      json)
    (fn [err]
      (clj->js {"error" {"error" err}}))))

(defn get-user-following [username]
  (p/catch
    (p/let [res (js/fetch (str "/api/users/by/username/" username))
            user (.json res)
            res (js/fetch (str "/api/users/" (aget user "data" "id") "/following"))
            following (.json res)
            ; following.meta.next_token -> pagination_token
            ]
      #js {:user user :following following})
    (fn [err]
      (clj->js {"error" {"error" err}}))))


