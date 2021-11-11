(ns event-log
  (:require
    ["path" :as path]
    [promesa.core :as p]
    [sitefox.db :as db]))

(let [db (db/client)]
  (p/let
    [res (.query db "select * from keyv where key like 'event/%'")
     json (.map res (fn [row]
                      (let [k (aget row "key")
                            v (aget (js/JSON.parse (aget row "value")) "value")]
                        (aset v "kind" k)
                        v)))]
    (doseq [row json]
      (if (.startsWith (aget row "kind") "event/search")
        (js/console.log (aget row "username") "search")
        (let [url (aget row "url")
              is-name (when url (> (.indexOf url "users/by/username") -1))
              paginated (when url (or (> (.indexOf url "pagination_token") -1)
                                      (> (.indexOf url "next") -1)))]
          (when (and url (not is-name) (not paginated))
            (js/console.log (aget row "username") (-> url (.split "?") (aget 0) path/basename))))))))
