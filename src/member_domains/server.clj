(ns member-domains.server
  (:require [member-domains.db :as db]
            [member-domains.etld :as etld]
            [member-domains.lookup :as lookup])
  (:require [compojure.core :refer [context defroutes GET ANY POST]]
            [compojure.handler :as handler]
            [compojure.route :as route])
  (:require [ring.util.response :refer [redirect]])
  (:require [liberator.core :refer [defresource resource]]
            [liberator.representation :refer [ring-response]])
  (:require [selmer.parser :refer [render-file cache-off!]]
            [selmer.filters :refer [add-filter!]])
  (:require [clojure.data.json :as json])
  (:require [crossref.util.doi :as crdoi]
            [crossref.util.config :refer [config]])
  (:require [org.httpkit.server :refer [with-channel on-close on-receive send! run-server]])
  (:require [heartbeat.core :refer [def-service-check]]
            [heartbeat.ring :refer [wrap-heartbeat]]))

(def-service-check :mysql (fn [] (db/heartbeat)))

(selmer.parser/cache-off!)
   
; Just serve up a blank page with JavaScript to pick up from event-types-socket.
(defresource home
  []
  :available-media-types ["text/html"] 
  :handle-ok (fn [ctx]
               (let [info {:num-dois (db/num-resolved-dois)
                           :num-domains (db/num-domains)
                           :average-dois-per-member (db/average-dois-per-member)
                           :num-members (db/num-members)}]
                 (render-file "templates/home.html" info))))

(defresource data-full-domain-names-json
  []
  :available-media-types ["application/json"] 
  :handle-ok (fn [ctx]
               (let [domains (db/unique-member-domains)]
                 domains)))

(defresource data-domain-names-json
  []
  :available-media-types ["text/plain"] 
  :handle-ok (fn [ctx]
               (let [full-domains (db/unique-member-domains)
                     domain-names (set (map #(->> % etld/get-main-domain (drop 1)) full-domains))]
                 (map #(clojure.string/join "." %) domain-names))))

(defresource data-full-domain-names-text
  []
  :available-media-types ["text/plain"] 
  :handle-ok (fn [ctx]
               (let [domains (db/unique-member-domains)]
                 (clojure.string/join "\n" domains))))

(defresource data-domain-names-json
  []
  :available-media-types ["application/json"] 
  :handle-ok (fn [ctx]
               (let [full-domains (db/unique-member-domains)
                     domain-names (set (map #(->> % etld/get-main-domain (drop 1)) full-domains))]
                 (map #(clojure.string/join "." %) domain-names))))

(defresource data-domain-names-text
  []
  :available-media-types ["text/plain"] 
  :handle-ok (fn [ctx]
               (let [full-domains (db/unique-member-domains)
                     domain-names (set (map #(->> % etld/get-main-domain (drop 1)) full-domains))]
                 (clojure.string/join "\n" (map #(clojure.string/join "." %) domain-names)))))

; GNIP text format rules for member domains. 
(defresource data-domain-names-gnip-txt
  []
  :available-media-types ["text/plain"] 
  :handle-ok (fn [ctx]
               (let [full-domains (db/unique-member-domains)
                     filtered (remove #(re-matches #"^[0-9.]+$" %) full-domains)
                     gnip-rules (map #(format "url_contains:\"//%s/\"" %) filtered)
                     result (clojure.string/join "\n" gnip-rules)]
                result)))

(defresource data-domain-names-gnip-json
  []
  :available-media-types ["application/json"] 
  :handle-ok (fn [ctx]
               (let [full-domains (db/unique-member-domains)
                     filtered (remove #(re-matches #"^[0-9.]+$" %) full-domains)
                     gnip-rules (map (fn [domain] {"value" (format "url_contains:\"//%s/\"" domain)}) filtered)
                     result {"rules" gnip-rules}]
                result)))


(defresource member-prefixes
  []
  :available-media-types ["application/json"] 
  :handle-ok (fn [ctx]
               (let [prefixes (db/all-prefixes)]
                 prefixes)))

(defresource member-prefixes-gnip
  []
  :available-media-types ["application/json"] 
  :handle-ok (fn [ctx]
               (let [prefixes (db/all-prefixes)
                     gnip-prefixes (map (fn [domain] {"value" (format "contains:\"%s/\"" domain)}) prefixes)]
                 {"rules" gnip-prefixes})))

(defresource lookup-url
  []
  :available-media-types ["text/plain"]
  :exists? (fn [ctx]
            (let [doi (lookup/lookup (get-in ctx [:request :params "url"]))]
              [doi {::doi doi}]))
  :handle-ok (fn [ctx]
              (::doi ctx)))

(defresource guess-doi
  [limit-to-member-domains]
  :available-media-types ["text/plain"]
  :malformed? (fn [ctx]
                (let [input (get-in ctx [:request :params :q])]
                  [(not input) {::input input}]))
  :exists? (fn [ctx]
            (let [doi (lookup/lookup (::input ctx) limit-to-member-domains)]
              [doi {::doi doi}]))
  :handle-ok (fn [ctx] (::doi ctx)))

(defroutes app-routes
  (GET "/" [] (home))
  (GET "/data/full-domain-names.json" [] (data-full-domain-names-json))
  (GET "/data/domain-names.json" [] (data-domain-names-json))
  (GET "/data/full-domain-names.txt" [] (data-full-domain-names-text))
  (GET "/data/domain-names.txt" [] (data-domain-names-text))
  (GET "/data/full-domain-names.gnip.txt" [] (data-domain-names-gnip-txt))
  (GET "/data/full-domain-names.gnip.json" [] (data-domain-names-gnip-json))
  (GET "/data/member-prefixes.json" [] (member-prefixes))
  (GET "/data/member-prefixes.gnip.json" [] (member-prefixes-gnip))
  (GET "/guess-doi" [] (guess-doi true))
  (GET "/guess-doi-all-domains" [] (guess-doi false))
  (route/resources "/"))

(defonce server (atom nil))

(def app
  (-> app-routes
      handler/site
      (wrap-heartbeat)))

(defn start []
  (reset! server (run-server #'app {:port (:port config)})))