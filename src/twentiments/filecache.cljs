(ns twentiments.filecache
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["fs" :as fs]
    ["path" :as path]
    ["mkdirp" :refer [mkdirp]]
    ["uslug" :as uslug]
    ["node-fetch$default" :as fetch]))

(defn is-cache-older-than [days cache-file]
  (let [stat (try (fs/statSync cache-file)
                  (catch :default _e nil))]
    (if stat
      (< (js/Date. (j/get stat :mtime))
         (- (js/Date.) (int (* 1000 60 60 24 days))))
      true)))

(defn fetch-and-cache-url
  [url]
  (mkdirp ".feed-cache")
  (p/let [file-slug (uslug url)
          cache-file (path/join ".feed-cache" file-slug)
          cache-file-contents (try (-> (fs/readFileSync cache-file) .toString) (catch :default _e nil))]
    (if
      (is-cache-older-than 1 cache-file)
      (p/catch
        (p/let [res (fetch url)
                text (if (j/get res :ok) (.text res) cache-file-contents)]
          (fs/writeFileSync cache-file text)
          text)
        (fn [_err]
          cache-file-contents))
      cache-file-contents)))
