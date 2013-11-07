(ns puppetlabs.trapperkeeper.core
  (:require [clojure.java.io :refer [IOFactory]]
            [plumbing.graph :as graph]
            [plumbing.core :refer [fnk]]
            [puppetlabs.utils :refer [cli!]]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]))

;  A type representing a trapperkeeper application.  This is intended to provide
;  an abstraction so that users don't need to worry about the implementation
;  details and can pass the app object to our functions in a type-safe way.
;  The internal properties are not intended to be used outside of this
;  namespace.
(defrecord TrapperKeeperApp [graph-instance])

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.utils/cli!`.
  Hard-codes the command-line arguments expected by trapperkeeper to be:
      --debug
      --bootstrap-config <bootstrap file>
      --config <.ini file or directory>"
  [cli-args]
  (let [specs       [["-d" "--debug" "Turns on debug mode" :flag true]
                     ["-b" "--bootstrap-config" "Path to bootstrap config file (optional)"]
                     ["-c" "--config" "Path to .ini file or directory of .ini files to be read and consumed by services (optional)"]]
        required    []]
    (first (cli! cli-args specs required))))

(defn- cli-service
  "The 'service' that provides command-line argument access to other services.
  It is really just a `fnk` that always ends up in the service graph so that
  services can access the command-line arguments"
  [cli-data]
  {:cli-service
   (fnk []
     {:cli-data (fn
                  ([] cli-data)
                  ([k] (cli-data k)))})})

(defn bootstrap*
  "Helper function for bootstrapping a trapperkeeper app."
  ([bootstrap-config] (bootstrap* bootstrap-config {}))
  ([bootstrap-config cli-data]
  {:pre [(satisfies? IOFactory bootstrap-config)
         (map? cli-data)]
   :post [(instance? TrapperKeeperApp %)]}
  (let [graph-map       (merge (cli-service cli-data) (bootstrap/parse-bootstrap-config! bootstrap-config))
        graph-fn        (graph/eager-compile graph-map)
        graph-instance  (graph-fn {})
        app             (TrapperKeeperApp. graph-instance)]
    app)))

(defn bootstrap
  "Bootstrap a trapperkeeper application.  This is accomplished by reading a
  bootstrap configuration file containing a list of (namespace-qualified)
  service functions.  These functions will be called to generate a service
  graph for the application; dependency resolution between the services will
  be handled automatically to ensure that they are started in the correct order.
  Functions that a service expresses dependencies on will be injected prior to
  instantiation of a service.

  The bootstrap config file will be searched for in this order:

  * At a path specified by the optional command-line argument `--bootstrap-config`
  * In the current working directory, in a file named `bootstrap.cfg`.
  * On the classpath, in a file named `bootstrap.cfg`."
  [cli-args]
  (let [cli-data (parse-cli-args! cli-args)]
    (if-let [bootstrap-config (or (bootstrap/config-from-cli! cli-data)
                                (bootstrap/config-from-cwd)
                                (bootstrap/config-from-classpath))]
      (bootstrap* bootstrap-config cli-data)
      (throw (IllegalStateException.
               "Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath")))))

(defn get-service-fn
  "Given a trapperkeeper application, a service name, and a sequence of keys,
  returns the function provided by the service at that path."
  [^TrapperKeeperApp app service k & ks]
  {:pre [(keyword? service)
         (keyword? k)
         (every? keyword? ks)]
   :post [(ifn? %)]}
  (get-in (:graph-instance app) (cons service (cons k ks))))

(defn- io->fnk-binding-form
  "Converts a service's input-output map into a binding-form suitable for
  passing to a fnk. The binding-form defines the fnk's expected input and
  output values, and is required to satisfy graph compilation.

  This function is necessary in order to allow for the defservice macro to
  support arbitrary code in the body. A fnk will attempt to determine what
  its output-schema is, but will only work if a map is immediately returned
  from the body. When a map is not immediately returned (i.e. a `let` block
  around the map), the output-schema must be explicitly provided in the fnk
  metadata in order to satisfy graph compilation."
  [io-map]
  (let [to-output-schema  (fn [provides]
                            (reduce (fn [m p] (assoc m (keyword p) true))
                                    {}
                                    provides))
        output-schema     (to-output-schema (:provides io-map))]
    ;; Add an output-schema entry to the depends vector's metadata map
    (vary-meta (:depends io-map) assoc :output-schema output-schema)))

(defmacro defservice
  "Define a service that may depend on other services, and provides functions
  for other services to depend on. Defining a service requires a:
    * service name
    * optional documentation string
    * input-output map in the form: {:depends [...] :provides [...]}
    * a body of code that returns a map of functions the service provides.
      The keys of the map must match the values of the :provides vector.

  Examples:

    (defservice logging-service
      {:depends  []
       :provides [debug info warn]}
      {:debug (partial println \"DEBUG:\")
       :info  (partial println \"INFO:\")
       :warn  (partial println \"WARN:\")})

    (defservice datastore-service
      \"Store key-value pairs.\"
      {:depends  [[:logging-service debug]]
       :provides [get put]}
      (let [log       (partial debug \"[datastore]\")
            datastore (atom {})]
        {:get (fn [key]       (log \"Getting...\") (get @datastore key))
         :put (fn [key value] (log \"Putting...\") (swap! datastore assoc key value))}))"
  [svc-name & forms]
  (let [[svc-doc io-map body] (if (string? (first forms))
                                  [(first forms) (second forms) (nthrest forms 2)]
                                  ["" (first forms) (rest forms)])]
    (let [binding-form (io->fnk-binding-form io-map)]
      `(defn ~svc-name
         ~svc-doc
         []
         {~(keyword svc-name)
            (fnk
              ~binding-form
              ~@body)}))))
