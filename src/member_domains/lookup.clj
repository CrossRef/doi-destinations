(ns member-domains.lookup
  (:require [member-domains.db :as db])
  (:require [crossref.util.doi :as crdoi])
  (:require [clojure.string :as string])
  (:require [clojure.tools.logging :refer [info]])
  (:require [net.cgrand.enlive-html :as html]
            [cemerick.url :as cemerick-url]
            [robert.bruce :refer [try-try-again]]
            [org.httpkit.client :as http]
            [clojure.data.json :as json])
  (:import [java.net URL URI URLEncoder URLDecoder]))

(def whole-doi-re #"^10\.\d{4,9}/[^\s]+$")
(def doi-re #"(10\.\d{4,9}/[^\s]+)")
(def doi-encoded-re #"10\.\d{4,9}%2[fF][^\s]+")

; TODO not entirely sure what the grammar of ShortDOI is but this seems to fit.
; Unfortunately this also matches the first half of a DOI.
; Match the shortcut URL (e.g. "doi.org/aabbe") or the handle (e.g. "10/aabbe").

; Locate a shortDOI in its natural habitat.
(def shortdoi-find-re #"(?:doi.org|10)/([a-zA-Z0-9]+)")

; The shortDOI itself is just an alphanumeric string, which isn't particularly disinctive.
(def shortdoi-re #"[a-z0-9]+")

; https://en.wikipedia.org/wiki/Publisher_Item_Identifier
; Used by Elsevier and others.
(def pii-re #"[SB][0-9XB]{16}")

; Set of all full member domains.
(def member-full-domains (atom #{}))

(defn fetch-member-full-domains []
  (reset! member-full-domains (set (db/unique-member-domains))))

(fetch-member-full-domains)

; Helpers

(defn try-url
  "Try to construct a URL."
  [text]
  (try (new URL text) (catch Exception _ nil)))

(defn try-hostname
  "Try to get a hostname from a URL string."
  [text]
  (try (.getHost (new URL text)) (catch Exception e nil)))

(defn doi-from-url
  "If a URL is a DOI, return the non-URL version of the DOI."
  [text]
  (when-let [url (try-url text)]
    (when (#{"doi.org" "dx.doi.org"} (.getHost url))
      (.substring (or (.getPath url) "") 1))))

(defn matches-doi?
  "Does this look like a DOI?"
  [input]
  (and (not (string/blank? input)) (re-matches whole-doi-re input)))

(defn remove-doi-colon-prefix
  "Turn 'doi:10.5555/12346789' into '10.5555/12345678'"
  [input]
  (when-let [match (re-matches #"^[a-zA-Z ]+: ?(10\.\d+/.*)$" input)]
    (.toLowerCase (second match))))

(defn resolve-doi
  "Resolve a DOI or ShortDOI, expressed as not-URL form. Return the DOI."
  [doi]
  (let [response @(try-try-again {:sleep 500 :tries 2}
                    #(http/get (str "http://doi.org/" (URLEncoder/encode doi "UTF-8"))
                      {:follow-redirects false}))
        status (:status response)
        redirect-header (-> response :headers :location)]
      (cond
        ; If it's a shortDOI it will redirect to the real one. Use this.
        (= (try-hostname redirect-header) "doi.org") (crdoi/non-url-doi redirect-header)
        ; If it's a real DOI it will return a 30x. 
        (= (quot status 100) 3) (crdoi/non-url-doi doi)

        ; If it's not anything then don't return anything.
        :default nil)))

(def max-drops 5)
(defn validate-doi
  "For a given suspected DOI or shortDOI, validate that it exists against the API, possibly modifying it to get there."
  [doi]
  (loop [i 0
         doi doi]
    ; Terminate if we're at the end of clipping things off or the DOI no longer looks like an DOI. 
    ; The API will return 200 for e.g. "10.", so don't try and feed it things like that.
    (if (or (= i max-drops)
            (nil? doi)
            (< (.length doi) i)
            ; The shortDOI regular expression is rather liberal, but it is what it is.
            (not (or (re-matches doi-re doi) (re-matches shortdoi-re doi))))
      ; Stop recursion.
      nil
      ; Or try this substring.
      (if-let [clean-doi (resolve-doi doi)] 
        ; resolve-doi may alter the DOI it returns, e.g. resolving a shortDOI to a real DOI or lower-casing.
        clean-doi
        (recur (inc i) (.substring doi 0 (- (.length doi) 1)))))))

(defn validate-pii
  "Validate a PII and return the DOI if it's been used as an alternative ID."
  [pii]
  (let [result (try-try-again {:sleep 500 :tries 2} #(http/get "http://api.crossref.org/v1/works" {:query-params {:filter (str "alternative-id:" pii)}}))
        body (-> @result :body json/read-str)
        items (get-in body ["message" "items"])]
    ; Only return when there's exactly one match.
    (when (= 1 (count items))
      (get (first items) "DOI"))))
  

(defn url-matches-doi?
  "Does the given DOI resolve to the given URL? Return DOI if so."
  [url doi]
  (info "Check " url " for " doi)
  (when-let [result (try-try-again {:sleep 500 :tries 2} #(http/get (str "http://doi.org/" doi)
                                                         {:follow-redirects true
                                                          :throw-exceptions true
                                                          :socket-timeout 5000
                                                          :conn-timeout 5000
                                                          :headers {"Referer" "chronograph.crossref.org"
                                                                    "User-Agent" "CrossRefDOICheckerBot (labs@crossref.org)"}}))]
    (let [doi-urls (set (conj (-> @result :trace-redirects) (-> @result :opts :url)))
          url-match (doi-urls url)]
      (when url-match
        doi))))

(defn extract-text-fragments-from-html
  "Extract all text from an HTML document."
  [input]
  (string/join " "
    (-> input
    (html/html-snippet)
    (html/select [:body html/text-node])
    (html/transform [:script] nil)
    (html/texts))))

; DOI Extraction
; Extract things that look like DOIs. Don't validate them yet.

(defn extract-doi-from-get-params
  "If there's a DOI in a get parameter of a URL, find it"
  [url]
  (let [params (-> url cemerick-url/query->map clojure.walk/keywordize-keys)
        doi-like (keep (fn [[k v]] (when (re-matches whole-doi-re v) v)) params)]
    (first doi-like)))

(defn extract-doi-in-a-hrefs-from-html
  "Extract all <a href> links from an HTML document."
  [input]
    (let [links (html/select (html/html-snippet input) [:a])
          hrefs (keep #(-> % :attrs :href) links)
          dois (keep doi-from-url hrefs)]
      (distinct dois)))

(defn extract-potential-dois-from-text
  "Extract potential DOIs from arbitrary text, including URL-encoded ones which will be unencoded."
  [text]
  ; doi-re and short-doi-find-re have a capture group for the actual value we want to find, hence `second`.
  (let [matches (map second (concat (re-seq doi-re text) (re-seq shortdoi-find-re text)))
        encoded-matches (map #(URLDecoder/decode %) (re-seq doi-encoded-re text))]
        (distinct (concat encoded-matches matches))))

(defn extract-potential-piis-from-text
  [text]
  (let [matches (re-seq pii-re text)]
        (distinct matches)))

(defn resolve-doi-from-url
  "Take a URL and try to resolve it to find what DOI it corresponds to."
  [url limit-to-member-domains]
  (info "Try to resolve:" url)

  ; Check if we want to bother with this URL.
  (when (or (->> url try-hostname (get @member-full-domains))
            (not limit-to-member-domains))
    (when-let [result (try-try-again {:sleep 500 :tries 2} #(http/get url
                                                           {:follow-redirects true
                                                            :throw-exceptions true
                                                            :socket-timeout 5000
                                                            :conn-timeout 5000
                                                            :headers {"Referer" "chronograph.crossref.org"
                                                                      "User-Agent" "CrossRefDOICheckerBot (labs@crossref.org)"}}))]
      (let [body (:body @result)
            text (extract-text-fragments-from-html body)
            
            dois-from-text (extract-potential-dois-from-text text)
            links-from-text (extract-doi-in-a-hrefs-from-html body)

            ; Look at the first DOI in the text before any of the others - high chance that it's the first one.
            first-text-doi (url-matches-doi? url (first dois-from-text))]

      (info "Found" (count dois-from-text) "DOIs in text," (count links-from-text) "DOIs from links")
      (info "Match for first doi" (first dois-from-text) ":" first-text-doi)

        ; If the first DOI in the text doesn't work then we need to start looking at the rest.
        (if first-text-doi
            first-text-doi
            (let [; Now we have a set of DOIs we think it could be. Try to resolve each one to see if we end up in the same place.
                  potential-dois (concat (rest dois-from-text) links-from-text)

                  ; Validate ones that exist. The regular expression might be a bit greedy, so this may chop bits off the end to make it work.
                  extant-dois (keep validate-doi potential-dois)

                  ; Matched DOIs. Some DOIs may map to the same page, e.g. components in PLOS.
                  ; e.g. "10.1371/journal.pone.0144297.g002" goes to the sampe place as "10.1371/journal.pone.0144297"
                  ; In the case of multiple results, choose the shortest.
                  ; This does lots of network requests, so pmap is useful.
                  matched-url-doi (->> extant-dois
                                       (pmap #(url-matches-doi? url %))
                                       (filter identity)
                                       ; sort by length of DOI.
                                       (sort-by count)
                                       first)]
              (info "Found matching DOI:" matched-url-doi)
            matched-url-doi))))))

; Combined methods.
; Combine extraction methods and validate.

(defn get-embedded-doi-from-string
  "Get DOI that's embedded in a URL (or an arbitrary string) by a number of methods."
  [url]
  ; First see if cleanly represented it's in the GET params.
  (if-let [doi (-> url extract-doi-from-get-params validate-doi)]
    doi
    ; Next try extracting DOIs and/or PII with regular expressions.
    (let [potential-dois (extract-potential-dois-from-text url)
          validated-doi (->> potential-dois (keep validate-doi) first)
          potential-alternative-ids (extract-potential-piis-from-text url)
          validated-pii-doi (->> potential-alternative-ids (keep validate-pii) first)]

      (if (or validated-doi validated-pii-doi)
        (or validated-doi validated-pii-doi)

        ; We may need to do extra things.
        ; Try splitting in various places.
        (let [; e.g. nomos-elibrary.de
              last-slash (map #(clojure.string/replace % #"^(10\.\d+/(.*))/.*$" "$1") potential-dois)

              ; e.g. ijorcs.org
              first-slash (map #(clojure.string/replace % #"^(10\.\d+/(.*?))/.*$" "$1") potential-dois)

              ; e.g. SICIs
              semicolon (map #(clojure.string/replace % #"^(10\.\d+/(.*));.*$" "$1") potential-dois)
              
              ; eg. JSOR
              hashchar (map #(clojure.string/replace % #"^(10\.\d+/(.*?))#.*$" "$1") potential-dois)

              ; e.g. biomedcentral
              question-mark (map #(clojure.string/replace % #"^(10\.\d+/(.*?))\?.*$" "$1") potential-dois)

              ; e.g. citeseerx
              amp-mark (map #(clojure.string/replace % #"^(10\.\d+/(.*?))&.*$" "$1") potential-dois)

              candidates (distinct (concat first-slash last-slash semicolon hashchar question-mark amp-mark))
              
              ; Lots of these produce duplicates.
              distinct-candidates (distinct candidates)

              ; Now take the first one that we could validate.
              doi (->> distinct-candidates (keep validate-doi) first)]
          (if doi
            doi
            nil))))))

(defn cleanup-doi
  "Take a URL or DOI or something that could be a DOI, return the DOI if it is one."
  [potential-doi]
  (let [normalized-doi (crdoi/non-url-doi potential-doi)
        doi-colon-prefixed-doi (remove-doi-colon-prefix potential-doi)]

  ; Find the first operation that produces an output that looks like a DOI.
  (cond
    (matches-doi? normalized-doi) normalized-doi
    (matches-doi? doi-colon-prefixed-doi) doi-colon-prefixed-doi
    :default nil)))

; External functions.

; This is exposed for testing.
(defn lookup-uncached
  "As lookup, but without the cache."
  [input limit-to-member-domains]
  ; Try to treat it as a DOI in a particular encoding.
  (if-let [cleaned-doi (cleanup-doi input)]
    cleaned-doi

    ; Try to treat it as a Publisher URL that has a DOI in the URL, or a string with a DOI in it somehow.
    (if-let [embedded-doi (get-embedded-doi-from-string input)]
      embedded-doi

    ; Try to treat it as a Publisher URL that must be fetched to extract its DOI.
    (if-let [resolved-doi (resolve-doi-from-url input limit-to-member-domains)]
      resolved-doi
      nil))))

(defn lookup
  "Look up some input and turn it into a DOI. Could be a URL landing page or a DOI in any form. Cached.
  If limit-to-member-domains is true, don't try and resolve URLs for for domains that aren't member domains."
  [url limit-to-member-domains]
  (info "Lookup" url)
  (if-let [doi (db/get-cache-doi-for-url url)]
    doi
    (if-let [doi (lookup-uncached url limit-to-member-domains)]
      (do
        (db/set-cache-doi-for-url url doi true)
        doi)
      ; Store for later analysis if it failed.
      (do
        (db/set-cache-doi-for-url url "" false)
        nil))))


(defn bifurcate
  "Using the given method, bifurcate a file into successes and failures."
  [method input-path success-path failure-path]
  (info "Bifurcate")
  (info "Input path" input-path)
  (info "Success path" success-path)
  (info "Failure path" failure-path)
  (with-open [success-file (clojure.java.io/writer success-path)]
    (with-open [failure-file (clojure.java.io/writer failure-path)]
      (let [method (condp = method :get-embedded-doi-from-string get-embedded-doi-from-string)
            urls (line-seq (clojure.java.io/reader input-path))
            results (pmap #(vector % (method %)) urls)]
        (doseq [[url result] results]
          (info "URL" url)
          (if result
           (.write success-file (str url "\n"))
           (.write failure-file (str url "\n")))
          ; Low volume, this is fine.
          (.flush success-file)
          (.flush failure-file))))))