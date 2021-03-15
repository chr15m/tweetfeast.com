(ns rsstonews.server
  (:require
    [rsstonews.web :as web]
    [rsstonews.mail :as mail]
    ["keyv" :as Keyv]
    ["path" :as path]
    ["node-fetch" :as fetch]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(set! *warn-on-infer* false)

(defn bail [msg]
  (js/console.error msg)
  (js/process.exit 1))

(defonce app (web/create))
(defonce keyv (Keyv. (web/env "DATABASE" "sqlite://./rsstonews.sqlite")))
(def site-password (or (web/env "PASSWORD") (bail "Set PASSWORD environment variable for site password.")))
(def site-from-address (or (web/env "FROM_EMAIL") (bail "Set FROM_EMAIL environment variable to the site's 'from' address.")))

(defn authenticate [req res pass]
  (if (not (aget req "session" "authenticated"))
    (-> res (.status 403) (.json #js {:error "Not authenticated."}))
    (pass)))

(defn get-data [req res]
  (go (.json res (<p! (.get keyv "user-data")))))

(defn set-data [req res]
  (go (.json res (<p! (.set keyv "user-data" (aget req "body"))))))

(defn set-password [req res]
  (go
    (let [existing-password (<p! (.get keyv "password"))]
      (if (nil? existing-password)
        (.json res (<p! (.set keyv "password" (aget req "body"))))
        (-> res (.status 403) (.json #js {:error "Password already set"}))))))

(defn login [req res]
  (if (= (aget req.body "password") site-password)
    (do
      (aset req.session "authenticated" true)
      (.json res true))
    (-> res (.status 403) (.json #js {:error "Incorrect password"}))))

(defn logout [req res]
  (aset req.session "authenticated" false)
  (.json res true))

(defn cors-proxy [req res]
  (let [url (aget req.query "url")]
    (-> (fetch url)
        (.catch (fn [err]
                  (-> res (.status 404) (.json #js {:error "Proxy fetch failed."}))
                  nil))
        (.then (fn [r] (when r
                         (-> (.text r)
                             (.then (fn [text] #js [text r]))))))
        (.then (fn [[text f]]
                 (when f
                   (for [[k v] (.entries f.headers)]
                     (.header res k v))
                   (.status res f.status)
                   (.send res text)))))))

(defn append-subscription-text [text subscribe-url unsubscribe-url]
  (str text "\n\n"
       "--" "\n"
       "Subscribe to this newsletter: <" subscribe-url ">\n"
       "Unsubscribe: " unsubscribe-url))

(defn append-subscription-html [html subscribe-url unsubscribe-url]
  (str
    ; start of the html wrapper table
    "<body style='background-color:#f6f6f6;'>"
    "<table border='0' cellspacing='0' width='100%'><tr><td></td>"
    "<td width='600' style='background-color:white;padding:30px;'>"
    ; the actual html content
    html "\n"
    ; end of the html wrapper table
    "</td><td></td></tr></table>"
    "<div style='text-align: center;' align='center'>"
    "<p>—</p>"
    "<p>Subscribe to these emails: <a href='" subscribe-url "'>" subscribe-url "</a></p>"
    "<p>Don't want these emails? <a href='" unsubscribe-url "'>Unsubscribe</a></p>\n"
    "</div>\n"
    "</body>"))

(defn send-emails [req res]
  (let [text (aget req.body "text")
        html (aget req.body "html")
        subject (aget req.body "subject")
        recipients (aget req.body "recipients")
        from site-from-address]
    (js/console.log "send-emails")
    (go
      (let [mailer (<p! (mail/create))
            send-promises (for [r recipients]
                            ; to from subject html text unsubscribe-url
                            (let [to (aget r "email")
                                  subscribe-url (aget r "list" "subscribe-url")
                                  unsubscribe-url (aget r "unsubscribe")
                                  text (append-subscription-text text subscribe-url unsubscribe-url)
                                  html (append-subscription-html html subscribe-url unsubscribe-url)]
                              (js/console.log "Sending to:" to subject)
                              (-> (mail/send-mail mailer to from subject html text unsubscribe-url)
                                  (.catch (fn [err] err))
                                  (.then (fn [sent]
                                           (js/console.log "sent:" (aget sent "accepted"))
                                           (if (aget sent "error")
                                             (let [err (-> sent js/JSON.stringify js/JSON.parse)]
                                               (aset err "message" (aget sent "error" "message"))
                                               (aset err "email" to)
                                               (js/console.log "error:" err)
                                               err)
                                             sent))))))
            send-results (<p! (.all js/Promise (clj->js send-promises)))]
        (.json res send-results)))))

(defn setup-routes [app]
  (.post app "/login" login)
  (.get app "/logout" logout)
  (.post app "/set-password" set-password)
  (.use app authenticate)
  (.get app "/proxy" cors-proxy)
  (.get app "/data" get-data)
  (.post app "/save" set-data)
  (.post app "/send-emails" send-emails))

(defn reload! []
  (web/reset-routes app)
  (setup-routes app)
  (println "Fresh routes loaded: " (aget app "_router" "stack" "length")))

(defn main! []
  (go
    (let [[host port] (<p! (web/serve app))]
      (reload!)
      (println "Servers started."))))
