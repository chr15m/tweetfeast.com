(ns event-log
  (:require
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
      (js/console.log (js/JSON.stringify row)))))
