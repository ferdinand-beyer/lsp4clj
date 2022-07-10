(ns lsp4clj.server
  (:require
   [clojure.core.async :as async]
   [lsp4clj.json-rpc :as json-rpc]
   [lsp4clj.json-rpc.messages :as json-rpc.messages]
   [lsp4clj.protocols.endpoint :as protocols.endpoint]
   [lsp4clj.protocols.logger :as logger]))

(defprotocol IBlockingDerefOrCancel
  (deref-or-cancel [this timeout-ms timeout-val]))

(defn request
  "Returns an object representing a JSON-RPC request to a remote endpoint.

  Most of the time, you should call `lsp4clj.server/deref-or-cancel` on the
  object. This has the same signature as `clojure.core/deref` with a timeout. If
  the client produces a response, will return it, but if the timeout is reached
  will cancel the request by sending a `$/cancelRequest` notification to the
  client.

  Otherwise, the object presents the same interface as `future`. Responds to
  `future-cancel` (which sends `$/cancelRequest`), `realized?`, `future-done?`
  and `future-cancelled?`.

  If the request is cancelled, future invokations of `deref` will return
  `:lsp4clj.server/cancelled`.

  Sends `$/cancelRequest` only once, though it is permitted to call
  `lsp4clj.server/deref-or-cancel` and `future-cancel` multiple times."
  [p id server]
  (let [cancelled? (atom false)]
    (reify
      clojure.lang.IDeref
      (deref [_] (deref p))
      clojure.lang.IBlockingDeref
      (deref [_ timeout-ms timeout-val]
        (deref p timeout-ms timeout-val))
      IBlockingDerefOrCancel
      (deref-or-cancel [this timeout-ms timeout-val]
        (let [result (deref this timeout-ms ::timeout)]
          (if (= ::timeout result)
            (do (future-cancel this)
                timeout-val)
            result)))
      clojure.lang.IPending
      (isRealized [_] (realized? p))
      java.util.concurrent.Future
      (get [this]
        (let [result (deref this)]
          (if (= ::cancelled result)
            (throw (java.util.concurrent.CancellationException.))
            result)))
      (get [this timeout unit]
        (let [result (deref this (.toMillis unit timeout) ::timeout)]
          (case result
            ::cancelled (throw (java.util.concurrent.CancellationException.))
            ::timeout (throw (java.util.concurrent.TimeoutException.))
            result)))
      (isCancelled [_] @cancelled?)
      (isDone [this] (or (.isRealized this) (.isCancelled this)))
      (cancel [this _interrupt?]
        (if (.isDone this)
          false
          (do
            (reset! cancelled? true)
            (protocols.endpoint/send-notification server "$/cancelRequest" {:id id})
            (deliver p ::cancelled)
            true))))))

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
        ;; TODO: if this was a request, send a generic -32603 Internal error
        (logger/debug e "exception receiving")))
    ;; TODO: what does a nil message signify? What should we do with it?
    (logger/debug "client closed? or json parse error?")))

;; TODO: Does LSP have a standard format for traces?
;; TODO: Send traces elsewhere?
(defn ^:private trace-received-notification [method notif] (logger/debug "trace - received notification" method notif))
(defn ^:private trace-received-request [method req] (logger/debug "trace - received request" method req))
(defn ^:private trace-received-response [resp] (logger/debug "trace - received response" resp))
;; TODO: Are you supposed to trace before or after sending?
(defn ^:private trace-sending-notification [method notif] (logger/debug "trace - sending notification" method notif))
(defn ^:private trace-sending-request [method req] (logger/debug "trace - sending request" method req))
(defn ^:private trace-sending-response [method resp] (logger/debug "trace - sending response" method resp))

(defmulti handle-request (fn [method _context _params] method))
(defmulti handle-notification (fn [method _context _params] method))

(defmethod handle-request :default [method _context _params]
  (logger/debug "received unexpected request" method)
  (json-rpc.messages/standard-error-response :method-not-found {:method method}))

(defmethod handle-notification :default [method _context _params]
  (logger/debug "received unexpected notification" method))

(defrecord Server [parallelism
                   trace?
                   receiver
                   sender
                   request-id*
                   pending-requests*
                   join]
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
    join)
  (shutdown [_this]
    (async/close! receiver)
    (deliver join :done))
  (exit [_this] ;; wait for shutdown of client to propagate to receiver
    (async/<!! sender))
  (send-request [this method body]
    (let [id (swap! request-id* inc)
          req (json-rpc.messages/request id method body)
          p (promise)]
      (when trace? (trace-sending-request method req))
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! pending-requests* assoc id p)
      (async/>!! sender req)
      (request p id this)))
  (send-notification [_this method body]
    (let [notif (json-rpc.messages/request method body)]
      (when trace? (trace-sending-notification method notif))
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
    (when trace? (trace-received-request method req))
    (let [result (handle-request method context params)
          resp (json-rpc.messages/response id result)]
      (when trace? (trace-sending-response method resp))
      resp))
  (receive-notification [_this context {:keys [method params] :as notif}]
    (when trace? (trace-received-notification method notif))
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
     :join (promise)}))

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
    (let [join (protocols.endpoint/start server nil)]
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
      @join))

  ;; client replies
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        join (protocols.endpoint/start server nil)
        req (protocols.endpoint/send-request server "req" {:body "foo"})
        client-rcvd-msg (async/<!! sender)]
    (prn :client-received-request client-rcvd-msg)
    (async/put! receiver {:id (:id client-rcvd-msg)
                          :result {:processed true}})
    (prn :server-received-response @req)
    (protocols.endpoint/shutdown server)
    (protocols.endpoint/exit server)
    @join)

  ;; client doesn't reply; server cancels
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        join (protocols.endpoint/start server nil)
        req (protocols.endpoint/send-request server "req" {:body "foo"})]
    (prn :client-received (async/<!! sender))
    (prn :server-received (deref-or-cancel req 1000 :test-timeout))
    (prn :client-received (async/<!! sender))
    (protocols.endpoint/shutdown server)
    (protocols.endpoint/exit server)
    @join)

  ; client replies; server cancels (but no $/cancelRequest)
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        join (protocols.endpoint/start server nil)
        req (protocols.endpoint/send-request server "req" {:body "foo"})
        client-rcvd-msg (async/<!! sender)]
    (prn :client-received client-rcvd-msg)
    (async/put! receiver {:id (:id client-rcvd-msg)
                          :result {:processed true}})
    (prn :server-received (deref-or-cancel req 1000 :test-timeout))
    (prn :client-received (let [timeout (async/timeout 1000)
                                [result ch] (async/alts!! [sender timeout])]
                            (if (= ch timeout)
                              :nothing
                              result)))
    (prn :server-cancel (future-cancel req))
    (protocols.endpoint/shutdown server)
    (protocols.endpoint/exit server)
    @join)

  ;; server cancels
  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        join (protocols.endpoint/start server nil)
        req (protocols.endpoint/send-request server "req" {:body "foo"})]
    (prn :server-cancel (future-cancel req))
    (prn :server-cancel (future-cancel req))
    (prn :client-received (async/<!! sender))
    (prn :client-received (async/<!! sender))
    (protocols.endpoint/shutdown server)
    (protocols.endpoint/exit server)
    @join)

  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        p (promise)
        req (request p 1 server)]
    #_(deliver p :done)
    #_(future-cancel req)
    (prn (realized? req))
    (prn (future-done? req))
    (prn (future-cancelled? req)))

  (let [receiver (async/chan 1)
        sender (async/chan 1)
        server (chan-server {:sender sender
                             :receiver receiver
                             :parallelism 1
                             :trace? true})
        p (promise)
        req (request p 1 server)]
    #_(deliver p :done)
    #_(future-cancel req)
    (prn (.get req 1 java.util.concurrent.TimeUnit/SECONDS)))

  #_())
