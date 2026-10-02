(ns tech.thomascothran.pavlov.ai.schema.result-test
  (:require [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli]))

(deftest decodes-and-validates-native-values
  (is (= #{:urgent}
         (schema/decode-result [:set :keyword] ["urgent"])))
  (let [result (schema/decode-result [:int] "not an integer")]
    (is (= :schema-violation (:kind result)))
    (is (some? (:explanation result)))))

(deftest callers-accept-native-provider-data-without-json-conversion
  (doseq [[response-schema data expected]
          [[[:maybe :string] nil nil]
           [[:boolean] false false]
           [[:map [:labels [:set :keyword]]]
            {:labels ["urgent"]} {:labels #{:urgent}}]]]
    (with-redefs [provider/call! (fn [_ _] {:data data})
                  schema/->json-schema
                  (fn [_] (throw (ex-info "No JSON Schema needed" {})))]
      (is (= expected
             (ai/call! {:provider ::native :schema response-schema}))))))
