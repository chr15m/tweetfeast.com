(ns rsstonews.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            ["rss-parser" :as rss]
            ["csvtojson" :as csv]
            ["prosemirror-view" :refer [EditorView Decoration DecorationSet]]
            ["prosemirror-state" :refer [EditorState Plugin]]
            ["prosemirror-markdown" :refer [schema defaultMarkdownParser defaultMarkdownSerializer]]
            ["prosemirror-example-setup" :refer [exampleSetup]]
            ["turndown" :as turndown]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]))

(def initial-state {:messages []
                    :tab :compose
                    :last-update {:newsletters :feeds}
                    :emails {:list-last-post {} :log []}
                    :feeds []
                    :editor {:subject nil :content nil :html nil :selected-lists {}}
                    :newsletters []})

(defonce state (r/atom initial-state))

(defonce save-debounce-timeout (atom nil))

(defonce turndown-service (turndown))

(def persist-keys #{:feeds :newsletters :last-update :items :lists :editor :emails})

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
                   (-> (rss. (clj->js {:customFields {:item ["media:group"]}}))
                       (.parseString text)))))
        (.then (fn [rss-struct]
                 (when rss-struct
                   (let [items (aget rss-struct "items")]
                     (doseq [item items]
                       (aset item "feed" #js {:title (aget rss-struct "title")
                                              :url (:value feed)}))
                     items)))))))

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

(defn use-content! [state html-content title link link-preamble]
  (swap! state #(-> %
                    (assoc-in [:editor :subject] title)
                    (assoc-in [:editor :content] (str (pre-process-html html-content) "\n\n" link-preamble ": <" link ">\n\n"))
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
  (when md
    (let [md (md-to-email-image-link md)
          md (md-to-email-image-references md)]
      md)))

(defn get-selected-lists [state]
  (->> state :editor :selected-lists vec (filter last) keys))

(defn append-list-info [list-data recipient]
  (assoc recipient "list" list-data))

(defn get-recipients [lists list-name]
  (let [[list-info recipients] (get lists list-name)]
    (map #(assoc % "list" list-info) recipients)))

(defn send-emails! [state]
  (swap! state assoc-in [:refreshing :send] true)
  (let [editor (:editor @state)
        selected-lists (get-selected-lists @state)
        lists (:lists @state)
        recipients (apply concat (map #(get-recipients lists %) selected-lists))
        subject (:subject editor)
        text (md-to-email-text (:content editor))
        html (:html editor)
        fields {:recipients recipients
                :subject subject
                :text text
                :html html}]
    ; TODO: data images are not being detached correctly
    (-> (js/fetch "/send-emails"
                  #js {:method "POST"
                       :headers #js {:content-type "application/json"}
                       :body (js/JSON.stringify (clj->js fields))})
        (.then #(.json %))
        (.then (fn [emails]
                 (let [now (-> (js/Date.) .getTime)
                       total-sent (count emails)
                       total-good (count (remove #(aget % "error") emails))]
                   (swap! state
                          #(-> %
                               (assoc-in [:editor :sent-counts] [total-good total-sent])
                               (update-in [:refreshing] dissoc :send)
                               (update-in [:emails :list-last-post]
                                          (fn [list-last-post]
                                            (reduce (fn [acc v] (assoc acc v now)) list-last-post selected-lists)))
                               (update-in [:emails :log] conj {:lists selected-lists
                                                               :recipients recipients
                                                               :subject subject
                                                               :text text
                                                               :html html
                                                               :response (js->clj emails)})
                               (update-in [:editor] assoc :subject nil :content nil)
                               (assoc :tab :compose)))
                   (js/console.log "email results" emails)
                   (js/console.log total-good "/" total-sent))
                 (let [prosemirror (@state :prosemirror)
                       pm-dom (when prosemirror (aget prosemirror "dom"))]
                   ; hack to tell prosemirror to clear the document
                   (when pm-dom
                     (aset pm-dom "innerHTML" ""))))))))

; *** views *** ;

(defn component-subject [editor-state]
  [:div [:input {:name :subject
                 :placeholder "Email subject..."
                 :on-change #(swap! editor-state assoc :subject (-> % .-target .-value))
                 :value (or (:subject @editor-state) "")
                 :class :fit}]])

(defn component-editor-prosemirror [editor-state editor]
  (js/console.log "prosemirror remount")
  [:div {:ref (partial editor-mounted editor-state editor)}])

(defn component-plaintext-view [content]
  [:div#plaintext
   [:h3 "Plaintext email version:"]
   [:pre (md-to-email-text content)]])

(defn component-editor [state]
  (let [editor (r/cursor state [:prosemirror])
        editor-state (r/cursor state [:editor])]
    ; TODO: add base64 attachments
    ; https://nodemailer.com/message/attachments/
    (fn []
      [:div#editor
       [component-subject editor-state]
       [component-editor-prosemirror editor-state editor]
       [component-plaintext-view (:content @editor-state)]
       ; TODO: add the ability to generate a .eml to download into mail client "sent"
       [:button {:on-click (partial send-emails! state)
                 :disabled (or (empty? (get-selected-lists @state))
                               (empty? (-> @editor-state :subject))
                               (empty? (-> @editor-state :content)))}
        (if (-> @state :refreshing :send)
          [:div {:class "spin"} "( )"]
          [:div "send"])]
       (let [[sent-count total-count] (:sent-counts @editor-state)]
         (when total-count
           [:span.sent-count
            "sent " sent-count " of " total-count " successfully"
            [:button {:on-click #(swap! editor-state dissoc :sent-counts)} "ok"]]))])))

(defn component-last-update [state k]
  [:span.last
     (str "Last update: " (or (time-since (-> @state :last-update k)) "just now"))])

(defn component-page-compose [state]
  [:div#compose
   [:h1 "compose"]
   [:div#lists
    [:ul
     (for [[list-name [newsletter entries]] (:lists @state)]
       [:li {:key list-name
             :on-click #(swap! state update-in [:editor :selected-lists list-name] not)}
        [:input {:type :checkbox
                 :read-only true
                 :checked (or (-> @state :editor :selected-lists (get list-name)) false)
                 :name (str "cb-" list-name)}]
        [:label {:for (str "cb-" list-name)}
         (str (name list-name) " (" (count entries) ")")]
        (let [last-post-timestamp (-> @state :emails :list-last-post (get list-name))]
          (when last-post-timestamp
            [:span (time-since last-post-timestamp) " ago"]))])]
    [:div
     [:button {:on-click (partial #'refresh-lists! state)}
      (if (-> @state :refreshing :lists)
        [:div {:class "spin"} "( )"]
        [:div "refresh"])]
     [component-last-update state :newsletters]]]
   [component-editor state]])

(defn get-content [i]
  ; if it's a YouTube video handle differently
  (if (:media:group i)
    (let [link (-> i :link)
          thumbnail (-> i :media:group :media:thumbnail first :$ :url)
          description (-> i :media:group :media:description first)]
    [(str "<p>" description "</p>" "<a href='" link "'><img src='" thumbnail "'/></a>")
     (str description)
     "Watch this on YouTube"])
    [(or (:content i) (:contentSnippet i) "")
     (-> (i :contentSnippet) (or "") (.split " ") (.slice 0 33) (.join " "))
     "Read this article online"]))

(defn component-post-list [state]
  (let [posts (sort-posts (:items @state))]
    [:div
     (for [idx (range (count posts))]
       (let [i (nth posts idx)
             [content short-content link-preamble] (get-content i)]
         (when (not (:archived i))
           [:div.post {:key (:link i)}
            [:h3.title (-> (:title i) (.substr 0 96))]
            [:span.source (-> i :feed :url (.split "//") (.pop))]
            [:span.date (-> i :pubDate (js/Date.) str (.split " ") (.slice 0 4) (.join " "))]
            [:div.content short-content]
            [:div
             [:button {:on-click (partial archive-post! state i)} "archive"]
             [:button {:on-click (partial use-content! state content (:title i) (:link i) link-preamble)} "compose"]]])))]))

(defn component-page-posts [state]
  [:section#posts
   [:h1 "posts"]
   [:h4 "Newsletter ideas from your feeds."]
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
   [component-config-items state :newsletters [[:value "CSV URL https://..."]
                                               [:list-name "List name..."]
                                               [:email-field "Email field name..."]
                                               [:subscribe-url "Subscribe url https://..."]]]
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

