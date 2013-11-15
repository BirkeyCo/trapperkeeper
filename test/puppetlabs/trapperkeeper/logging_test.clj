(ns puppetlabs.trapperkeeper.logging-test
  (:import (org.apache.log4j Level Logger))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.logging :refer :all]
            [puppetlabs.trapperkeeper.logging :refer :all]))

(deftest test-catch-all-logger
  (testing "catch-all-logger ensures that message from an exception is logged"
    (with-test-logging
      (catch-all-logger
        (Exception. "This exception is expected; testing error logging")
        "this is my error message")
      (is (logged? #"this is my error message" :error)))))

(deftest test-logging-configuration
  (testing "Calling `configure-logging!` with a log4j.properties file"
    (configure-logging! {:global {:logging-config "./test-resources/log4j.properties"}})
    (is (= (Level/DEBUG) (.getLevel (Logger/getRootLogger)))))

  (testing "Calling `configure-logging!` with another log4j.properties file"
    (configure-logging! {:global {:logging-config "./test-resources/another-log4j.properties"}})
    (is (= (Level/WARN) (.getLevel (Logger/getRootLogger))))))