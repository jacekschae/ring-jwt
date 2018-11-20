(ns ring.middleware.jwt-test
  (:require [cheshire.core :as json]
            [clojure.string :refer [split join]]
            [clojure.test :refer :all]
            [ring.middleware.jwt-test-utils :as util]
            [ring.middleware.jwt :refer [wrap-jwt]])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)
           (java.util Date)))

(def ^:private dummy-handler (constantly identity))
(def ^:private issuer "issuer")

(defn- build-request
  [claims alg-opts]
  (util/add-jwt-token {} claims alg-opts))

(defn- epoch-seconds-instant []
  (-> (Instant/now)
      (.getEpochSecond)
      (Instant/ofEpochSecond)))

(defn- instant->date [instant]
  (Date/from instant))

(deftest claims-from-valid-jwt-token-in-authorization-header-are-added-to-request
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims  {:a 1 :b 2 :iss issuer}
        handler (wrap-jwt (dummy-handler) {:alg        :RS256
                                           :issuer     issuer
                                           :public-key public-key})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        res     (handler req)]
    (is (= claims (:claims res)))))

(deftest claims-from-valid-jwt-token-in-authorization-header-are-added-to-request-without-validating-issuer
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims  {:a 1 :b 2 :iss issuer}
        handler (wrap-jwt (dummy-handler) {:alg        :RS256
                                           :public-key public-key})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        res     (handler req)]
    (is (= claims (:claims res)))))

(deftest jwt-token-signed-with-wrong-algorithm-causes-401
  (let [{:keys [private-key]} (util/generate-key-pair :RS256)
        claims  {:a 1 :b 2}
        handler (wrap-jwt (dummy-handler) {:alg    :HS256
                                           :secret (util/generate-hmac-secret)})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "Signature could not be verified." body))))

(deftest jwt-token-signed-with-wrong-issuer-causes-401
  (let [{:keys [private-key]} (util/generate-key-pair :RS256)
        claims  {:a 1 :b 2 :iss issuer}
        handler (wrap-jwt (dummy-handler) {:alg    :HS256
                                           :secret (util/generate-hmac-secret)})
        req     (build-request claims {:alg         :RS256
                                       :issuer       (str "not" issuer)
                                       :private-key private-key})
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "Signature could not be verified." body))))

(deftest jwt-token-with-tampered-header-causes-401
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims          {:a 1 :b 2}
        token           (util/encode-token claims {:alg         :RS256
                                                   :private-key private-key})
        [_ payload signature] (split token #"\.")
        tampered-header (util/str->base64 (json/generate-string {:alg :RS256 :a 1}))
        tampered-token  (join "." [tampered-header payload signature])

        handler         (wrap-jwt (dummy-handler) {:alg        :RS256
                                                   :public-key public-key})
        req             {:headers {"Authorization" (str "Bearer " tampered-token)}}
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "Signature could not be verified." body))))

(deftest jwt-token-with-tampered-payload-causes-401
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims           {:a 1 :b 2}
        token            (util/encode-token claims {:alg       :RS256
                                                    :private-key private-key})

        [header _ signature] (split token #"\.")
        tampered-payload (util/str->base64 (json/generate-string {:a 1}))
        tampered-token   (join "." [header tampered-payload signature])

        handler          (wrap-jwt (dummy-handler) {:alg        :RS256
                                                    :public-key public-key})
        req              {:headers {"Authorization" (str "Bearer " tampered-token)}}
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "Signature could not be verified." body))))

(deftest no-jwt-token-causes-empty-claims-map-added-to-request
  (let [handler (wrap-jwt (dummy-handler) {:alg    :HS256
                                           :secret "whatever"})
        req     {:some "data"}
        res     (handler req)]
    (is (= req (dissoc res :claims)))
    (is (= {} (:claims res)))))

(deftest expired-jwt-token-causes-401
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims  {:exp (-> (epoch-seconds-instant)
                          (.minusSeconds 1)
                          (instant->date))}
        handler (wrap-jwt (dummy-handler) {:alg        :RS256
                                           :public-key public-key})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "Token has expired." body))))

(deftest future-active-jwt-token-causes-401
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims {:nbf (-> (epoch-seconds-instant)
                         (.plusSeconds 1)
                         (instant->date))}
        handler (wrap-jwt (dummy-handler) {:alg        :RS256
                                           :public-key public-key})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        {:keys [body status]} (handler req)]
    (is (= 401 status))
    (is (= "One or more claims were invalid." body))))

(defn- date->seconds [date]
  (/ (.getTime date) 1000))

(deftest expired-jwt-token-within-specified-leeway-is-valid
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims  {:exp (-> (epoch-seconds-instant)
                          (.minusSeconds 100)
                          (instant->date))}
        handler (wrap-jwt (dummy-handler) {:alg            :RS256
                                           :public-key     public-key
                                           :leeway-seconds 1000})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        res     (handler req)]
    (is (= (update claims :exp date->seconds) (:claims res)))))

(deftest future-jwt-token-within-specified-leeway-is-valid
  (let [{:keys [private-key public-key]} (util/generate-key-pair :RS256)
        claims  {:nbf (-> (epoch-seconds-instant)
                           (.plusSeconds 100)
                           (instant->date))}
        handler (wrap-jwt (dummy-handler) {:alg            :RS256
                                           :public-key     public-key
                                           :leeway-seconds 1000})
        req     (build-request claims {:alg         :RS256
                                       :private-key private-key})
        res     (handler req)]
    (is (= (update claims :nbf date->seconds) (:claims res)))))

(testing "invalid options"
  (deftest missing-option-causes-error
    (is (thrown-with-msg? ExceptionInfo #"Invalid options."
                          (wrap-jwt (dummy-handler) {:alg    :HS256
                                                     :bollox "whatever"}))))

  (deftest incorrect-option-type-causes-error
    (is (thrown-with-msg? ExceptionInfo #"Invalid options."
                          (wrap-jwt (dummy-handler) {:alg    :HS256
                                                     :secret 1}))))

  (deftest option-from-wrong-algorithm-causes-error
    (is (thrown-with-msg? ExceptionInfo #"Invalid options."
                          (wrap-jwt (dummy-handler) {:alg    :RS256
                                                     :secret "whatever"}))))

  (deftest extra-unsupported-option-does-not-cause-error
    (wrap-jwt (dummy-handler) {:alg    :HS256
                               :secret "somesecret"
                               :bollox "whatever"})))
