(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io Reader FileNotFoundException)
           (java.net URI))
  (:require [clojure.java.io :refer [IOFactory]]
            [clojure.string :as string]
            [clojure.java.io :refer [reader resource file input-stream]]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.config :refer [configure!]]
            [puppetlabs.trapperkeeper.shutdown :refer [register-shutdown-hooks!]]
            [puppetlabs.trapperkeeper.app :refer [validate-service-graph! service-graph?
                                                  compile-graph instantiate]])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def bootstrap-config-file-name "bootstrap.cfg")

(defn- parse-bootstrap-line!
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-fn-name>'.  Returns
  a 2-item vector containing the namespace and the service function name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line]
  {:pre  [(string? line)]
   :post [(vector? %)
          (= 2 (count %))
          (every? string? %)]}
  (if-let [[match namespace fn-name] (re-matches
                                       #"^([a-zA-Z0-9\.\-]+)/([a-zA-Z0-9\.\-]+)$"
                                       line)]
    [namespace fn-name]
    (throw (IllegalArgumentException.
             (str "Invalid line in bootstrap config file:\n\n\t"
                  line
                  "\n\nAll lines must be of the form: '<namespace>/<service-fn-name>'.")))))

(defn- call-service-fn!
  "Given the namespace and name of a service function, loads the namespace,
  calls the function, validates that the result is a valid service graph, and
  returns the graph.  Throws an `IllegalArgumentException` if the service graph
  cannot be loaded."
  [fn-ns fn-name]
  {:pre  [(string? fn-ns)
          (string? fn-name)]
   :post [(service-graph? %)]}
  (try (require (symbol fn-ns))
       (catch FileNotFoundException e
         (throw (IllegalArgumentException.
                  (str "Unable to load service: " fn-ns "/" fn-name)
                  e))))
  (if-let [service-fn (ns-resolve (symbol fn-ns) (symbol fn-name))]
    (validate-service-graph! (service-fn))
    (throw (IllegalArgumentException.
             (str "Unable to load service: " fn-ns "/" fn-name)))))

(defn- remove-comments
  "Given a line of text from the bootstrap config file, remove
  anything that is commented out with either a '#' or ';'. If
  the entire line is commented out, an empty string is returned."
  [line]
  {:pre  [(string? line)]
   :post [(string? %)]}
  (-> line
      (string/replace #"(?:#|;).*$" "")
      (string/trim)))

(defn- config-path-readable?
  "Answers whether or not the given config path contains a readable file.
   Supports specifying the config file via URI, so it can be inside a .jar."
  [path]
  (or
    (.exists (file path))
    (try
      (input-stream (URI. path))
      true
      (catch Exception e
        false))))

(defn- config-from-cli
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of the bootstrap
  config file.  If so, return it.  Throws an IllegalArgumentException if a
  location was specified but the file doesn't actually exist."
  [cli-data]
  {:pre  [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (if (config-path-readable? config-path)
        (do
          (log/debug (str "Loading bootstrap config from specified path: '" config-path "'"))
          config-path)
        (throw (IllegalArgumentException.
                 (str "Specified bootstrap config file does not exist: '" config-path "'")))))))

(defn- config-from-cwd
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (let [config-file (-> bootstrap-config-file-name
                        (file)
                        (.getAbsoluteFile))]
    (when (.exists config-file)
      (log/debug (str "Loading bootstrap config from current working directory: '"
                      (.getAbsolutePath config-file) "'"))
      config-file)))

(defn- config-from-classpath
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when-let [classpath-config (resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    classpath-config))

(defn- find-bootstrap-config
  "Get the bootstrap config file from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data]
  {:pre  [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (if-let [bootstrap-config (or (config-from-cli cli-data)
                                (config-from-cwd)
                                (config-from-classpath))]
    bootstrap-config
    (throw (IllegalStateException.
             (str "Unable to find bootstrap.cfg file via --bootstrap-config "
                  "command line argument, current working directory, or on classpath")))))

(defn parse-bootstrap-config!
  "Parse the trapperkeeper bootstrap configuration and return the service graph
  that is the result of merging the graphs of all of the services specified in
  the configuration."
  [config]
  {:pre  [(satisfies? IOFactory config)]
   :post [(sequential? %)
          (every? service-graph? %)]}
  (let [lines (line-seq (reader config))]
    (when (empty? lines) (throw (Exception. "Empty bootstrap config file")))
    (for [line (map remove-comments lines)
          :when (not (empty? line))]
      (let [[service-fn-namespace service-fn-name] (parse-bootstrap-line! line)]
        (call-service-fn! service-fn-namespace service-fn-name)))))

(defn bootstrap-services
  "Given the services to run and command-line arguments,
  bootstrap and return the trapperkeeper application."
  [services cli-data]
  {:pre  [(sequential? services)
          (every? service-graph? services)
          (map? cli-data)]
   :post [(instance? TrapperKeeperApp %)]}
  (-> cli-data
      (configure! services)
      (register-shutdown-hooks!)
      (compile-graph)
      (instantiate)
      (TrapperKeeperApp.)))

(defn bootstrap
  "Get the services out of the bootstrap config file
  provided in `cli-data` and bootstrap the application.
  See `puppetlabs.trapperkeeper.core/bootstrap` for information."
  [cli-data]
  {:pre  [(map? cli-data)
          (contains? cli-data :config)]
   :post [(instance? TrapperKeeperApp %)]}
  (-> cli-data
      (find-bootstrap-config)
      (parse-bootstrap-config!)
      (bootstrap-services cli-data)))
