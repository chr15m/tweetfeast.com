(ns rsstonews.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["rss-to-js" :as rss]
            ["csvtojson" :as csv]
            ["prosemirror-view" :refer [EditorView Decoration DecorationSet]]
            ["prosemirror-state" :refer [EditorState Plugin]]
            ["prosemirror-markdown" :refer [schema defaultMarkdownParser defaultMarkdownSerializer]]
            ["prosemirror-example-setup" :refer [exampleSetup]]
            ["turndown" :as turndown]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def initial-state {:messages []
                    :tab :compose
                    :last-update {:newsletters :feeds}
                    :feeds []
                    :editor {:subject nil :content nil :html nil}
                    :newsletters []})

(defonce state (r/atom initial-state))

(defonce save-debounce-timeout (atom nil))

(defonce turndown-service (turndown))

(def persist-keys #{:feeds :newsletters :last-update :items :lists :editor})

; *** functions *** ;

(defn <p!-get-data [state]
  (-> (js/fetch "/data")
      (.then (fn [res]
               (when (= res.status 403) (swap! state assoc :tab :login))
               (.json res)))))

(defn fetch-data! [state & [done-callback]]
  (go
    (let [data (<p! (<p!-get-data state))]
      (js/console.log "User data:" (clj->js data))
      (when data
        (swap! state merge (into {} (map (fn [k] [k (js->clj (aget data (name k)) :keywordize-keys true)]) persist-keys)))
        (when done-callback
          (done-callback))))))

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

(defn login [state password]
  (-> (js/fetch "/login"
                #js {:method "POST"
                     :headers #js {:content-type "application/json"}
                     :body (js/JSON.stringify (clj->js {:password password}))})
      (.then (fn [res]
               (go
                 (if (= res.status 200)
                   (do
                     (fetch-data! state #(swap! state assoc :tab :compose)))
                   (let [error (<p! (.json res))]
                     (add-message! state {:type :error :text (aget error "error")}))))))))

(defn logout [state]
  (-> (js/fetch "/logout"
                #js {:headers #js {:content-type "application/json"}})
      (.then (fn [res]
               (reset! state (merge initial-state {:tab :login}))))))

(defn merge-new-items [new-items old-items]
  (let [old-item-keys (set (map #(:link %) old-items))
        new-items-filtered (remove #(contains? old-item-keys (:link %)) new-items)]
    (vec (concat old-items new-items-filtered))))

(defn handle-fetch-errors [state url res]
  (if (aget res "ok")
                 (.text res)
                 (do
                   (add-message! state {:type :error :text (str "failed to load " url)})
                   nil)))

(defn feed-fetch-and-parse [state feed]
  (when (:value feed)
    (-> (js/fetch (str "/proxy?url=" (js/encodeURIComponent (:value feed))))
        (.then (partial handle-fetch-errors state (:value feed)))
        (.then (fn [text]
                 (when text
                   (-> (rss.)
                       (.parseString text)))))
        (.then (fn [rss-struct]
                 (when rss-struct
                   (doseq [i (aget rss-struct "items")]
                     (aset i "feed" #js {:title (aget rss-struct "title")
                                         :url (:value feed)}))
                   (aget rss-struct "items")))))))

(defn refresh-feeds! [state]
  (swap! state assoc-in [:refreshing :feeds] true)
  (let [rss-promises (map (partial feed-fetch-and-parse state) (:feeds @state))]
    (->
      (js/Promise.all (clj->js rss-promises))
      (.then (fn [results]
               (let [all-items (vec (apply concat (js->clj results :keywordize-keys true)))]
                 (let []
                   (swap! state
                          #(-> %
                               (update-in [:items] (partial merge-new-items all-items))
                               (update-in [:refreshing] dissoc :feeds)
                               (assoc-in [:last-update :feeds] (.getTime (js/Date.))))))))))))

(defn conform-csv [text]
  "Make sure CSV conforms by removing [] braces from mailchimp export."
  (-> text (.split "\n") (.map #(-> % (.replace #"\[+(.*?)\]+" "$1"))) (.join "\n")))

(defn fetch-newsletter-and-parse [state newsletter]
  (let [url (:value newsletter)]
    (when url
      (-> (js/fetch (str "/proxy?url=" (js/encodeURIComponent url)))
          (.then (partial handle-fetch-errors state url))
          (.then (fn [text]
                   (when text
                     (-> (csv)
                         (.fromString (conform-csv text))
                         (.then (fn [rows]
                                  [(:list-name newsletter)
                                   [newsletter (js->clj rows :keywordize-keys true)]]))))))))))

(defn refresh-lists! [state]
  (swap! state assoc-in [:refreshing :lists] true)
  (let [newsletter-promises (map (partial fetch-newsletter-and-parse state) (:newsletters @state))]
    (->
      (js/Promise.all (clj->js newsletter-promises))
      (.then (fn [results]
               (let [all-newsletters (into {} results)]
                 (let []
                   (swap! state
                          #(-> %
                               (assoc :lists all-newsletters)
                               (update-in [:refreshing] dissoc :lists)
                               (assoc-in [:last-update :newsletters] (.getTime (js/Date.))))))))))))

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

(defn archive-post! [state item]
  (swap! state update-in [:items]
         (fn [posts]
           (map (fn [{:keys [title link pubDate] :as post} post]
                  (if (= post item)
                    {:title title :link link :pubDate pubDate :archived true}
                    post))
                posts))))

(def re-image-markdown "!\\[(.*?)\\]\\((.*?)\\)")
(def re-image-src-html "")

(defn pre-process-html [html-content]
  (.turndown turndown-service html-content))

(defn use-content! [state html-content title link]
  (swap! state #(-> %
                    (assoc-in [:editor :subject] title)
                    (assoc-in [:editor :content] (str (pre-process-html html-content) "\n\nRead this article online: <" link ">\n\n"))
                    (assoc :tab :compose))))

; https://discuss.prosemirror.net/t/how-to-input-like-placeholder-behavior/705/13
(def placeholder-text-plugin
  (fn [text]
    (Plugin.
      #js {:props
           #js {:decorations
                (fn decorations [state]
                  (let [doc (aget state "doc")
                        decoration-list #js []
                        decorate (fn [node pos]
                                   (when (and
                                           (aget node "type" "isBlock")
                                           (= (aget doc "firstChild" "content" "size") 0)
                                           (= (aget doc "childCount") 1))
                                     (.push decoration-list (.node Decoration pos (+ pos (aget node "nodeSize")) #js {:class "placeholder"}))))]
                    (.descendants doc decorate)
                    (.create DecorationSet doc decoration-list)))}})))

(defn view-plugin [editor-state]
  (Plugin.
    #js {:view (defn view [editor-view]
                 #js {:update (fn [editor-view]
                                (let [content (.serialize defaultMarkdownSerializer (aget editor-view "state" "doc"))
                                      html (-> editor-view .-dom .-innerHTML)]
                                  (swap! editor-state assoc :content content :html html)
                                  (js/console.log "content" content html)))
                      :destroy (fn [])})}))

(defn make-editor! [editor-state el]
  (EditorView. el
               #js {:state
                    (.create EditorState
                             #js {:doc (.parse defaultMarkdownParser (or (:content @editor-state) ""))
                                  :plugins (.concat (exampleSetup #js {:schema schema})
                                                    #js [(placeholder-text-plugin "Newsletter text...")
                                                         (view-plugin editor-state)])})}))

(defn editor-mounted [editor-state editor el]
  (if el
    (reset! editor (make-editor! editor-state el))
    (swap! editor (fn [old-editor] (.destroy old-editor) nil))))

(defn md-to-email-image-references [md]
  (.replace md (js/RegExp. re-image-markdown "g")
            (fn [segment]
              (let [image-link (.exec (js/RegExp. re-image-markdown) segment)
                    image-link (when image-link (aget image-link 2))]
                (if image-link
                  (cond
                    (= (.indexOf image-link "https://") 0) image-link
                    (= (.indexOf image-link "http://") 0) image-link
                    (= (.indexOf image-link "data") 0) "(see attached image)"
                    (= (.indexOf image-link "/") 0) "[RELATIVE IMAGE REMOVED]"
                    (= (.indexOf image-link "file://")) "[LOCAL FILE IMAGE IGNORED]"
                    :else "NO")
                  "")))))

(def re-linked-image "\\[!\\[(.*?)\\]\\((.*?)\\)\\]\\((.*?)(\\ {0,1}\\\"(.*?)\\\"){0,1}\\)")

(defn md-to-email-image-link [md]
  (.replace md (js/RegExp. re-linked-image "g")
            (fn [segment]
              (let [parsed (.exec (js/RegExp. re-linked-image) segment)
                    link (when parsed (nth parsed 3))
                    link-text (when parsed (nth parsed 5))]
                (cond
                  link-text (str link-text ": " link)
                  link link
                  :else segment)))))

(defn md-to-email-text [md]
  (let [md (md-to-email-image-link md)
        md (md-to-email-image-references md)]
    md))

(defn send-emails! [state]
  (swap! state assoc-in [:refreshing :send] true)
  (let [editor (:editor @state)
        fields {:recipients []
                :text (md-to-email-text (:content editor))
                :html (:html editor)}]
    (-> (js/fetch "/send-emails"
                  #js {:method "POST"
                       :headers #js {:content-type "application/json"}
                       :body (js/JSON.stringify (clj->js fields))})
        (.then (fn [res]
                 (swap! state update-in [:refreshing] dissoc :send)
                 ; TODO: update with log, last-post time
                 (js/console.log "send-emails result" res))))))

; *** views *** ;

(defn component-subject [editor-state]
  [:div [:input {:name :subject
                 :placeholder "Email subject..."
                 :on-change #(swap! editor-state assoc :subject (-> % .-target .-value))
                 :value (or (:subject @editor-state) "")
                 :class :fit}]])

(defn component-editor-prosemirror [editor-state editor]
  [:div {:ref (partial editor-mounted editor-state editor)}])

(defn component-plaintext-view [content]
  [:div#plaintext
   [:h3 "Plaintext email version:"]
   [:pre (md-to-email-text content)]])

(defn component-editor [state]
  (let [editor (atom nil)
        editor-state (r/cursor state [:editor])]
    (fn []
      [:div#editor
       [component-subject editor-state]
       [component-editor-prosemirror editor-state editor]
       [component-plaintext-view (:content @editor-state)]
       [:button {:on-click (partial send-emails! state)}
        (if (-> @state :refreshing :send)
          [:div {:class "spin"} "( )"]
          [:div "send"])]])))

(defn component-last-update [state k]
  [:span.last
     (str "Last update: " (or (time-since (-> @state :last-update k)) "just now"))])

(defn component-page-compose [state]
  [:div#compose
   [:h1 "compose"]
   [:div#lists
    [:ul
     (for [[list-name [newsletter entries]] (:lists @state)]
       [:li {:key list-name} list-name " (" (count entries) ")"])]
    [:div
     [:button {:on-click (partial #'refresh-lists! state)}
      (if (-> @state :refreshing :lists)
        [:div {:class "spin"} "( )"]
        [:div "refresh"])]
     [component-last-update state :newsletters]]]
   [component-editor state]])

(defn component-post-list [state]
  (let [posts (sort-posts (:items @state))]
    [:div
     (for [idx (range (count posts))]
       (let [i (nth posts idx)]
         (when (not (:archived i))
           [:div.post {:key (:link i)}
            [:h3.title (-> (:title i) (.substr 0 96))]
            [:span.source (-> i :feed :url (.split "//") (.pop))]
            [:span.date (-> i :pubDate (js/Date.) str (.split " ") (.slice 0 4) (.join " "))]
            [:div.content (-> (i :contentSnippet) (or "") (.split " ") (.slice 0 33) (.join " "))]
            [:div
             [:button {:on-click (partial archive-post! state i)} "archive"]
             [:button {:on-click (partial use-content! state (or (:content i) (:contentSnippet i) "") (:title i) (:link i))} "compose"]]])))]))

(defn component-page-posts [state]
  [:section#posts
   [:h1 "posts"]
   [:h4 "Ideas and content you can re-purpose for your newsletter."]
   [:div
    [:button {:on-click (partial #'refresh-feeds! state)}
     (if (-> @state :refreshing :feeds)
       [:div {:class "spin"} "( )"]
       [:div "refresh"])]
    [component-last-update state :feeds]]
   [component-post-list state]])

(defn remove-nth [col idx]
  (vec (keep-indexed (fn [i item] (when (not= idx i) item)) col)))

(defn component-config-item [state base-key fields & [idx]]
  (let [item (get-in @state [base-key idx])]
    [:li {:key (:id item)}
     (for [[field-name field-placeholder] fields]
       (with-meta
         [:input {:key (name field-name)
                  :value (field-name item)
                  :class (str "fit " (name field-name))
                  :placeholder field-placeholder
                  :on-change #(swap! state assoc-in [base-key idx field-name] (-> % .-target .-value))}]
         {:key (name field-name)}))
     [:button {:on-click #(swap! state update-in [base-key] remove-nth idx)} "x"]]))

(defn component-config-items [state section-key fields]
  [:section#config
    [:h2 (name section-key)]
    [:ul
     (for [f (range (count (@state section-key)))]
       (with-meta [component-config-item state section-key fields f] {:key f}))]
    [:button {:on-click #(swap! state update-in [section-key] conj {:id (js/Math.random)})} "+"]])

(defn component-page-config [state]
  [:section
   [:h1 "config"]
   [component-config-items state :newsletters [[:value "https://..."]
                                               [:list-name "List name..."]
                                               [:email-field "Email field name..."]]]
   [component-config-items state :feeds [[:value "https://..."]]]])

(defn component-tab-item [state tabname]
  [:li {:on-click #(swap! state assoc :tab tabname)
        :class (if (= (@state :tab) tabname) :selected)} (name tabname)])

(defn component-login [state password]
  (fn []
    [:div
     [:h1 "login"]
     [:input {:ref #(when % (.focus %))
              :type "password"
              :value @password
              :on-key-down #(if (= (.-keyCode %) 13) (login state @password))
              :on-change #(reset! password (-> % .-target .-value))}]
     [:button {:on-click #(login state @password)} "login"]]))

(defn component-main [state]
  [:main
   (when (not-empty (@state :messages))
     [:div#messages
      (for [m (@state :messages)]
        [:p.message {:key (get m :id)
                     :on-click #(remove-message! state m)
                     :class (get m :class)}
         (get m :text)])])
   (if (= (@state :tab) :login)
     [component-login state (r/atom "")]
     [:div
      [:nav
       [:ul
        [component-tab-item state :compose]
        [component-tab-item state :posts]
        [component-tab-item state :config]
        [:li {:on-click (partial logout state)} "logout"]]]
      (case (@state :tab)
        :compose [component-page-compose state]
        :posts [component-page-posts state]
        :config [component-page-config state])
      #_ [:code (str @state)]])])

; *** startup *** ;

(defn debounced-save! [k state-atom old-state new-state]
  (js/console.log "new state" (clj->js new-state))
  (when (and (not= (:tab new-state) :login) (not= old-state new-state))
    (js/clearTimeout @save-debounce-timeout)
    (reset! save-debounce-timeout
            (js/setTimeout
              #(save-data!
                 (into {} (map (fn [k] [k (k new-state)]) persist-keys)))
              1000))))

(defonce watcher
  (add-watch state :saver #'debounced-save!))

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      (<! (fetch-data! state))
      (reload!))
  (js/console.log "main!"))

