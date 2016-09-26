(ns muuntaja.core
  (:require [muuntaja.parse :as parse]
            [muuntaja.formats :as formats]
            [clojure.set :as set]))

(defn- throw! [formats format message]
  (throw
    (ex-info
      (str message ": " format)
      {:formats (-> formats :formats keys)
       :format format})))

(defn- some-value [pred c]
  (let [f (fn [x] (if (pred x) x))]
    (some f c)))

(defn- assoc-assoc [m k1 k2 v]
  (assoc m k1 (assoc (k1 m) k2 v)))

(defn- on-request-decode-exception [^Exception e request-format request-charset request]
  (throw
    (ex-info
      (str "Malformed " request-format " request.")
      {:type ::decode
       :format request-format
       :charset request-charset
       :request request}
      e)))

(defn- set-content-type [response content-type]
  (assoc-assoc response :headers "Content-Type" content-type))

(defn- content-type [formats format charset]
  (str ((:produces formats) format) "; charset=" charset))

;;
;; Protocols
;;

(defprotocol RequestFormatter
  (negotiate-request [_ request])
  (negotiate-response [_ request])
  (decode-request? [_ request])
  (encode-response? [_ request response]))

(defprotocol Formatter
  (encoder [_ format])
  (decoder [_ format]))

;;
;; Content negotiation
;;

(defn- -negotiate-content-type [{:keys [consumes matchers default-charset]} s]
  (if s
    (let [[content-type-raw charset-raw] (parse/parse-content-type s)]
      [(if content-type-raw
         (or (consumes content-type-raw)
             (some
               (fn [[name r]]
                 (if (re-find r content-type-raw) name))
               matchers)))
       (or charset-raw default-charset)])))

(defn- -negotiate-accept [{:keys [produces default-format]} s]
  (or
    (some-value
      produces
      (parse/parse-accept s))
    default-format))

(defn- -negotiate-accept-charset [{:keys [default-charset charsets]} s]
  (or
    (some-value
      (or charsets identity)
      (parse/parse-accept-charset s))
    default-charset))

;;
;; Records
;;

(defrecord Adapter [encode decode])

(defrecord Formats [negotiate-content-type
                    negotiate-accept
                    negotiate-accept-charset

                    extract-content-type-fn
                    extract-accept-fn
                    extract-accept-charset-fn

                    encode?
                    decode?

                    consumes
                    matchers

                    adapters
                    default-charset
                    default-format]
  RequestFormatter

  (negotiate-request [_ request]
    (negotiate-content-type (extract-content-type-fn request)))

  (negotiate-response [_ request]
    [(negotiate-accept (extract-accept-fn request))
     (negotiate-accept-charset (extract-accept-charset-fn request))])

  (decode-request? [_ request]
    (and decode?
         (not (contains? request ::format))
         (decode? request)))

  (encode-response? [_ request response]
    (and encode?
         (map? response)
         (not (contains? response ::format))
         (encode? request response)))

  Formatter
  (encoder [_ format]
    (-> format adapters :encode))

  (decoder [_ format]
    (-> format adapters :decode)))

(defn encode [formats format data]
  (if-let [encode (encoder formats format)]
    (encode data)
    (throw! formats format "invalid encode format")))

(defn decode [formats format data]
  (if-let [decode (decoder formats format)]
    (decode data)
    (throw! formats format "invalid decode format")))

;;
;; Creation
;;

(defn- key-set [m accept?]
  (set
    (for [[k v] m
          :when (accept? v)]
      k)))

(defn- matchers [formats]
  (->>
    (for [[name {:keys [matches]}] formats]
      [name matches])
    (into {})))

(defn- on-exception [^Exception e format type]
  (println format type)
  (throw
    (ex-info
      (str "Malformed " format " (" type ")")
      {:type type
       :format format}
      e)))

(defn- create-adapters [formats]
  (let [make (fn [format type spec spec-opts [p pf]]
               (let [g (if (vector? spec)
                         (let [[f opts] spec]
                           (f (merge opts spec-opts)))
                         spec)
                     g' (fn [x]
                          (try
                            (g x)
                            (catch Exception e
                              (on-exception e format type))))]
                 (if (and p pf)
                   (fn [x]
                     (try
                       (if (and (record? x) (satisfies? p x))
                         (pf x)
                         (g' x))
                       (catch Exception e
                         (on-exception e format type))))
                   g')))]
    (->> (for [[name {:keys [decoder decoder-opts encoder encoder-opts encode-protocol]}] formats]
           [name (map->Adapter
                   (merge
                     (if decoder {:decode (make name ::decode decoder decoder-opts nil)})
                     (if encoder {:encode (make name ::encode encoder encoder-opts encode-protocol)})))])
         (into {}))))

(declare default-options)

(defn create
  ([]
   (create default-options))
  ([{:keys [formats default-format] :as options}]
   (let [adapters (create-adapters formats)
         valid-format? (key-set formats identity)
         m (map->Formats
             (merge
               (dissoc options :formats)
               {:adapters adapters
                :consumes (key-set formats :decoder)
                :produces (key-set formats :encoder)
                :matchers (matchers formats)}))]
     (when-not (valid-format? default-format)
       (throw
         (ex-info
           (str "Invalid default format " default-format)
           {:formats valid-format?
            :default-format default-format})))
     (-> m
         (assoc
           :negotiate-accept-charset
           (parse/fast-memoize
             (parse/cache 1000)
             (partial -negotiate-accept-charset m)))
         (assoc
           :negotiate-accept
           (parse/fast-memoize
             (parse/cache 1000)
             (partial -negotiate-accept m)))
         (assoc
           :negotiate-content-type
           (parse/fast-memoize
             (parse/cache 1000)
             (partial -negotiate-content-type m)))))))

;;
;; Request
;;

(defn- handle-request [request decoder request-format request-charset response-format response-charset]
  (let [body (:body request)]
    (as-> request $
          (assoc $ ::accept response-format)
          (assoc $ ::accept-charset response-charset)
          (if (and body decoder)
            (try
              (-> $
                  (assoc ::format request-format)
                  (assoc :body nil)
                  (assoc :body-params (decoder body)))
              (catch Exception e
                (on-request-decode-exception e request-format request-charset $)))
            $))))

;; TODO: use the negotiated request charset
(defn format-request [formats request]
  (let [[ctf ctc] (negotiate-request formats request)
        [af ac] (negotiate-response formats request)
        decoder (if (decode-request? formats request)
                  (decoder formats ctf))]
    (handle-request request decoder ctf ctc af ac)))

;;
;; Response
;;

(defn- handle-response [response formats format encoder charset]
  (as-> response $
        (assoc $ ::format format)
        (dissoc $ ::content-type)
        (update $ :body encoder)
        (if-not (get (:headers $) "Content-Type")
          (set-content-type $ (content-type formats format charset))
          $)))

(defn- resolve-response-format [response formats request]
  (or (if-let [ct (::content-type response)]
        ((:produces formats) ct))
      (::accept request)
      (:default-format formats)))

(defn- resolve-response-charset [response formats request]
  (or (::accept-charset request)
      (:default-format formats)))

;; TODO: use the negotiated response charset
(defn format-response [formats request response]
  (or
    (if (encode-response? formats request response)
      (if-let [format (resolve-response-format response formats request)]
        (if-let [charset (resolve-response-charset response formats request)]
          (if-let [encoder (encoder formats format)]
            (handle-response response formats format encoder charset)))))
    response))

;;
;; Options
;;

(defn extract-content-type-ring
  "Extracts content-type from ring-request."
  [request]
  (get (:headers request) "content-type"))

(defn extract-accept-ring
  "Extracts accept from ring-request."
  [request]
  (get (:headers request) "accept"))

(defn extract-accept-charset-ring
  "Extracts accept-charset from ring-request."
  [request]
  (get (:headers request) "accept-charset"))

(defn encode-collections-with-override [_ response]
  (or
    (-> response ::encode?)
    (-> response :body coll?)))

(def default-options
  {:extract-content-type-fn extract-content-type-ring
   :extract-accept-charset-fn extract-accept-charset-ring
   :extract-accept-fn extract-accept-ring
   :decode? (constantly true)
   :encode? encode-collections-with-override

   :default-charset "utf-8"
   ;charsets #{"utf-8", "utf-16", "iso-8859-1"}

   :default-format "application/json"
   :formats {"application/json" {:matches #"application/(.+\+)?json"
                                 :decoder [formats/make-json-decoder {:keywords? true}]
                                 :encoder [formats/make-json-encoder]
                                 :encode-protocol [formats/EncodeJson formats/encode-json]}
             "application/edn" {:matches #"^application/(vnd.+)?(x-)?(clojure|edn)"
                                :decoder [formats/make-edn-decoder]
                                :encoder [formats/make-edn-encoder]
                                :encode-protocol [formats/EncodeEdn formats/encode-edn]}
             "application/msgpack" {:matches #"^application/(vnd.+)?(x-)?msgpack"
                                    :decoder [formats/make-msgpack-decoder {:keywords? true}]
                                    :encoder [formats/make-msgpack-encoder]
                                    :encode-protocol [formats/EncodeMsgpack formats/encode-msgpack]}
             "application/x-yaml" {:matches #"^(application|text)/(vnd.+)?(x-)?yaml"
                                   :decoder [formats/make-yaml-decoder {:keywords true}]
                                   :encoder [formats/make-yaml-encoder]
                                   :encode-protocol [formats/EncodeYaml formats/encode-yaml]}
             "application/transit+json" {:matches #"^application/(vnd.+)?(x-)?transit\+json"
                                         :decoder [(partial formats/make-transit-decoder :json)]
                                         :encoder [(partial formats/make-transit-encoder :json)]
                                         :encode-protocol [formats/EncodeTransitJson formats/encode-transit-json]}
             "application/transit+msgpack" {:matches #"^application/(vnd.+)?(x-)?transit\+msgpack"
                                            :decoder [(partial formats/make-transit-decoder :msgpack)]
                                            :encoder [(partial formats/make-transit-encoder :msgpack)]
                                            :encode-protocol [formats/EncodeTransitMessagePack formats/encode-transit-msgpack]}}})

;;
;; Working with options
;;

(defn transform-format-options [f options]
  (update options :formats #(into (empty %) (map (fn [[k v]] [k (f v)]) %))))

(def no-decoding (partial transform-format-options #(dissoc % :decoder)))
(def no-encoding (partial transform-format-options #(dissoc % :encoder)))

(def no-protocol-encoding
  (partial transform-format-options #(dissoc % :encode-protocol)))

(defn with-decoder-opts [options format opts]
  (when-not (get-in options [:formats format])
    (throw
      (ex-info
        (str "invalid format: " format)
        {:format format
         :formats (keys (:formats options))})))
  (assoc-in options [:formats format :decoder-opts] opts))

(defn with-encoder-opts [options format opts]
  (when-not (get-in options [:formats format])
    (throw
      (ex-info
        (str "invalid format: " format)
        {:format format
         :formats (keys (:formats options))})))
  (assoc-in options [:formats format :encoder-opts] opts))

(defn with-formats [options formats]
  (let [existing-formats (-> options :formats keys set)
        future-formats (set formats)]
    (when-let [diff (seq (set/difference future-formats existing-formats))]
      (throw
        (ex-info
          (str "invalid formats: " diff)
          {:invalid (seq diff)
           :formats (seq formats)
           :existing (seq existing-formats)})))
    (-> options
        (update :formats select-keys formats)
        (assoc :default-format (first formats)))))

;;
;; request helpers
;;

(defn disable-request-decoding [request]
  (assoc request ::format nil))

;;
;; response helpers
;;

(defn disable-response-encoding [response]
  (assoc response ::format nil))

(defn set-response-content-type [response content-type]
  (assoc response ::content-type content-type))
