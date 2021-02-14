(ns rsstonews.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["rss-to-js" :as rss]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

;(def init-state
;  {:search nil})

(defonce state (r/atom {:messages []
                        :tab :compose
                        :feeds []
                        :newsletters []}))

(defonce save-debounce-timeout (atom nil))

(def persist #{:feeds :newsletters})

; *** functions *** ;

(defn <p!-get-data [state]
  (-> (js/fetch "/data")
      (.then (fn [res]
               (when (= res.status 403) (swap! state assoc :tab :login))
               (.json res)))))

(defn fetch-data! [state]
  (go
    (let [data (<p! (<p!-get-data state))]
      (js/console.log "User data:" (clj->js data))
      (when data
        (swap! state assoc
               ; TODO: use persist keys for this bit
               :feeds (js->clj (aget data "feeds") :keywordize-keys true)
               :newsletters (js->clj (aget data "newsletters") :keywordize-keys true))))))

(defn save-data! [state-structure]
  (-> (js/fetch "/save"
                #js {:method "POST"
                     :headers #js {:content-type "application/json"}
                     :body (js/JSON.stringify (clj->js state-structure))})
      (.then (fn [res]
               (when (= res.status 403) (swap! state assoc :tab :login))
               (.json res)))
      (.then (fn [res] (js/console.log "save" res)))))

(defn add-message! [state msg]
  (swap! state update-in [:messages] conj (assoc msg :id (js/Math.random))))

(defn remove-message! [state m]
  (swap! state update-in [:messages] (fn [messages] (remove #(= % m) messages))))

(defn login [state]
  (-> (js/fetch "/login"
                #js {:method "POST"
                     :headers #js {:content-type "application/json"}
                     :body (js/JSON.stringify (clj->js {:password (@state :password)}))})
      (.then (fn [res]
               (go
                 (if (= res.status 200)
                   (do
                     (swap! state assoc :tab :compose)
                     (fetch-data! state))
                   (let [error (<p! (.json res))]
                     (add-message! state {:type :error :text (aget error "error")}))))))))

(defn merge-new-items [new-items old-items]
  (let [old-item-keys (set (map #(:link %) old-items))
        new-items-filtered (remove #(contains? old-item-keys (:link %)) new-items)]
    (concat old-items new-items-filtered)))

(defn refresh-feeds! [state]
  (swap! state assoc :refreshing true)
  (let [rss-promises (map (fn [feed]
                             (-> (js/fetch (str "/proxy?url=" (:value feed)))
                                 (.then #(.text %))
                                 (.then (fn [text]
                                          (when text
                                            (-> (rss.)
                                                (.parseString text)))))
                                 (.then (fn [rss-struct]
                                          (doseq [i (aget rss-struct "items")]
                                            (aset i "feed" #js {:title (aget rss-struct "title")
                                                                :url (:value feed)}))
                                          (aget rss-struct "items")))))
                           (:feeds @state))]
    (->
      (js/Promise.all (clj->js rss-promises))
      (.then (fn [results]
               (let [all-items (vec (apply concat (js->clj results :keywordize-keys true)))]
                 (let []
                   (swap! state
                          #(-> %
                               (update-in [:items] (partial merge-new-items all-items))
                               (dissoc :refreshing)
                               (assoc :last-update (.getTime (js/Date.))))))))))))

(defn sort-posts [posts]
  (sort-by #(-> % :pubDate (js/Date.) (.getTime) (* -1)) posts))

(defn check-time-interval [seconds [divisor interval-name]]
  (let [interval (js/Math.floor (/ seconds divisor))]
    (when (> interval 1)
      (str interval " " interval-name))))

(defn time-since [date-string]
  (let [since-epoch (-> date-string (js/Date.))
        seconds (js/Math.floor (/ (- (js/Date.) since-epoch) 1000))]
    (first (remove nil? (map (partial check-time-interval seconds)
                             [[31536000 "years"]
                              [2592000 "months"]
                              [86400 "days"]
                              [3600 "hrs"]
                              [60 "mins"]
                              [1 "secs"]])))))

; *** views *** ;

(defn component-config-item [state base-key & [idx]]
  (let [item (get-in @state [base-key idx])]
    [:li {:key (:id item)}
     [:input {:value (:value item)
              :class "fit"
              :placeholder "https://..."
              :on-change #(swap! state assoc-in [base-key idx :value] (-> % .-target .-value))}]]))

(defn component-page-compose [state]
  [:h1 "compose"])

(defn component-page-posts [state]
  [:section#posts
   [:h1 "posts"]
   [:div
    [:button {:on-click (partial #'refresh-feeds! state)}
     (if (@state :refreshing)
       [:div {:class "spin"} "( )"]
       [:div "refresh"])]
    [:span.last
     (str "Last update: " (or (time-since (@state :last-update)) "just now"))]]
   [:div
    (for [i (sort-posts (:items @state))]
      [:div.post {:key (:link i)}
       [:h3.title (-> (:title i) (.substr 0 96))]
       [:span.source (-> i :feed :url (.split "//") (.pop))]
       [:span.date (-> i :pubDate (js/Date.) str (.split " ") (.slice 0 4) (.join " "))]
       [:div.content (-> (i :contentSnippet) (or "") (.split " ") (.slice 0 33) (.join " "))]
       [:div
        [:button "archive"]
        [:button "compose"]]
       ; [:div.content {:ref (fn [el] (when el (aset el "innerHTML" (i :content))))}]

       ])]])

(defn component-config-items [state section-key]
  [:section
    [:h2 (name section-key)]
    [:ul
     (for [f (range (count (@state section-key)))]
       [component-config-item state section-key f])]
    [:button {:on-click #(swap! state update-in [section-key] conj {:id (js/Math.random)})} "+"]])

(defn component-page-config [state]
  [:section
   [:h1 "config"]
   [component-config-items state :feeds]
   [component-config-items state :newsletters]])

(defn component-tab-item [state tabname]
  [:li {:on-click #(swap! state assoc :tab tabname)
        :class (if (= (@state :tab) tabname) :selected)} (name tabname)])

(defn component-main [state]
  [:main
   (for [m (@state :messages)]
     [:div {:key (get m :id)
            :on-click #(remove-message! state m)
            :class (get m :class)}
      (get m :text)])
   (if (= (@state :tab) :login)
     [:div
      [:h1 "login"]
      [:input {:type "password"
               :value (@state :password)
               :on-change #(swap! state assoc :password (-> % .-target .-value))}]
      [:button {:on-click #(login state)} "login"]]
     [:div
      [:nav
       [:ul
        [component-tab-item state :compose]
        [component-tab-item state :posts]
        [component-tab-item state :config]]]
      (case (@state :tab)
        :compose [component-page-compose state]
        :posts [component-page-posts state]
        :config [component-page-config state])
      [:code (str @state)]])])

; *** startup *** ;

(defn debounced-save! [k state-atom old-state new-state]
  (js/console.log "new state" (clj->js new-state))
  (js/clearTimeout @save-debounce-timeout)
  ; TODO: check if "persist" keys have changed specifically
  (reset! save-debounce-timeout
         (js/setTimeout
           #(save-data! {:feeds (:feeds new-state)
                         :newsletters (:newsletters new-state)})
           1000)))

(defonce watcher
  (add-watch state :saver #'debounced-save!))

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      (<! (fetch-data! state))
      (reload!))
  (js/console.log "hi"))

