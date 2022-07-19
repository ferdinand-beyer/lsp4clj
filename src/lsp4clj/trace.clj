(ns lsp4clj.trace
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn ^:private format-tag [^java.time.Instant at]
  (format "[Trace - %s]"
          (str (.truncatedTo at java.time.temporal.ChronoUnit/MILLIS))))

(defn ^:private format-request-header [{:keys [method id]}]
  (format "request '%s - (%s)'" method id))

(defn ^:private format-response-header [{:keys [method id]}]
  (format "response '%s - (%s)'" method id))

(defn ^:private format-notification-header [{:keys [method]}]
  (format "notification '%s'" method))

(defn ^:private format-body [label body]
  (str label ": " (json/generate-string body {:pretty true})))

(defn ^:private format-params [{:keys [params]}]
  (format-body "Params" params))

(defn ^:private format-response-body [{:keys [error result]}]
  (if error
    (format-body "Error data" (:data error))
    (format-body "Result" result)))

(defn ^:private format-str [at action header body]
  (str (format-tag at) " " action " " header "\n"
       body "\n\n\n"))

(defn ^:private latency [^java.time.Instant started ^java.time.Instant finished]
  (format "%sms" (- (.toEpochMilli finished) (.toEpochMilli started))))

(defn ^:private format-notification [action notif at]
  (format-str at action (format-notification-header notif)
              (format-params notif)))

(defn ^:private format-request [action req at]
  (format-str at action (format-request-header req)
              (format-params req)))

(defn ^:private format-response [action req {:keys [error] :as resp} started finished]
  (format-str finished action
              (format
                (str "%s. Request took %s." (when error " Request failed: %s (%s)."))
                (format-response-header req)
                (latency started finished)
                (:message error) (:code error))
              (format-response-body resp)))

(defn received-notification [notif at] (format-notification "Received" notif at))
(defn received-request [req at] (format-request "Received" req at))
(defn received-response [req resp started finished] (format-response "Received" req resp started finished))

(defn received-unmatched-response [resp at]
  (format-str at "Received" "response for unmatched request:"
              (format-body "Body" resp)))

(defn sending-notification [notif at] (format-notification "Sending" notif at))
(defn sending-request [req at] (format-request "Sending" req at))
(defn sending-response [req resp started finished] (format-response "Sending" req resp started finished))
