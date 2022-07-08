(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [lsp4clj.json-rpc :as json-rpc]
   [lsp4clj.json-rpc.messages :as json-rpc.messages]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.protocols.logger :as logger]))

(defn ^:private receive-message
  [server context message]
  (if-let [{:keys [id method] :as json} message]
    (try
      (cond
        (and id method) (protocols.endpoint/receive-request server context json)
        id              (do (protocols.endpoint/receive-response server json)
                            ;; Ensure server doesn't respond to responses
                            nil)
        :else           (do (protocols.endpoint/receive-notification server context json)
                            ;; Ensure server doesn't respond to notifications
                            nil))
      (catch Throwable e
        (logger/debug e "listener closed: exception receiving")))
    (logger/debug "listener closed: client closed")))

;; TODO: Does LSP have a standard format for traces?
;; TODO: Send traces elsewhere?
(defn ^:private trace-received-notification [notif] (logger/debug "trace - received notification" notif))
(defn ^:private trace-received-request [req] (logger/debug "trace - received request" req))
(defn ^:private trace-received-response [resp] (logger/debug "trace - received response" resp))
;; TODO: Are you supposed to trace before or after sending?
(defn ^:private trace-sending-notification [notif] (logger/debug "trace - sending notification" notif))
(defn ^:private trace-sending-request [req] (logger/debug "trace - sending request" req))
(defn ^:private trace-sending-response [resp] (logger/debug "trace - sending response" resp))

(defmulti handle-request (fn [method _context _params] method))
(defmulti handle-notification (fn [method _context _params] method))

(defmethod handle-request :default [method _context _params]
  (logger/debug "received unexpected request" method)
  (json-rpc.messages/standard-error-response :json-rpc/method-not-found {:method method}))

(defmethod handle-notification :default [method _context _params]
  (logger/debug "received unexpected notification" method))

(defrecord Server [parallelism
                   trace?
                   receiver
                   sender
                   request-id*
                   pending-requests*
                   on-shutdown]
  protocols.endpoint/IEndpoint
  (start [this context]
    (async/pipeline-blocking parallelism
                             sender
                             ;; TODO: return error until initialize request is received? https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initialize
                             ;; TODO: coerce here? Or leave that to servers?
                             ;; `keep` means we do not reply to responses and notifications
                             (keep #(receive-message this context %))
                             receiver)
    ;; invokers should deref the return of `start`, so the server stays alive
    ;; until it is shut down
    on-shutdown)
  (shutdown [_this]
    (async/close! receiver)
    (deliver on-shutdown :done))
  (exit [_this] ;; wait for shutdown of client to propagate to receiver
    (async/<!! sender))
  (send-request [_this method body]
    (let [id (swap! request-id* inc)
          req (json-rpc.messages/request id method body)
          p (promise)]
      (when trace? (trace-sending-request req))
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-requests* assoc id p)
      (async/>!! sender req)
      p))
  (send-notification [_this method body]
    (let [notif (json-rpc.messages/request method body)]
      (when trace? (trace-sending-notification notif))
      (async/put! sender notif)))
  (receive-response [_this {:keys [id] :as resp}]
    (if-let [request (get @pending-requests* id)]
      (do (when trace? (trace-received-response resp))
          (swap! pending-requests* dissoc id)
          (deliver request (if (:error resp)
                             resp
                             (:result resp))))
      (logger/debug "received response for unmatched request:" resp)))
  (receive-request [_this context {:keys [id method params] :as req}]
    (when trace? (trace-received-request req))
    (let [result (handle-request method context params)
          resp (json-rpc.messages/response id result)]
      (when trace? (trace-sending-response resp))
      resp))
  (receive-notification [_this context {:keys [method params] :as notif}]
    (when trace? (trace-received-notification notif))
    (handle-notification method context params)))

(defn chan-server [{:keys [sender receiver parallelism trace?]
                     :or {parallelism 4, trace? false}}]
  (map->Server
    {:parallelism parallelism
     :trace? trace?
     :sender sender
     :receiver receiver
     :request-id* (atom 0)
     :pending-requests* (atom {})
     :on-shutdown (promise)}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stdio-server [{:keys [in out] :as opts}]
  (chan-server (assoc opts
                      :receiver (json-rpc/input-stream->receiver-chan in)
                      :sender (json-rpc/output-stream->sender-chan out))))
(comment
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})]
    (prn "sending message to receiver")
    (async/put! receiver {:id 1
                          :method "foo"
                          :params {}})
    (prn "sent message to receiver")
    (let [started-server (protocols.endpoint/start server nil)]
      (prn "gettting message from sender")
      (async/<!! sender)
      (prn "got message from sender")
      (prn "sending message to receiver")
      (async/put! receiver {:id 2
                            :method "bar"
                            :params {}})
      (prn "sent message to receiver")
      (protocols.endpoint/shutdown server)
      (protocols.endpoint/exit server)
      @started-server))

  #_())
