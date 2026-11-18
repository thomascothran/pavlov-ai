(ns tech.thomascothran.pavlov.ai.provider.process-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.ai.provider.process :as process]))

(deftest run-process-pipes-stdin-and-captures-output
  (is (= {:exit 0 :out "héllo\n" :err ""}
         (process/run-process {:command ["cat"] :stdin "héllo\n"})))
  (is (= 3 (:exit (process/run-process {:command ["sh" "-c" "echo oops >&2; exit 3"]
                                        :stdin ""})))))

(deftest run-process-uses-dir-only-when-given
  (let [tmp (System/getProperty "java.io.tmpdir")]
    (is (= (str (.getCanonicalFile (java.io.File. tmp)))
           (str/trim (:out (process/run-process {:command ["pwd" "-P"] :stdin "" :dir tmp}))))))
  (is (= (str (.getCanonicalFile (java.io.File. (System/getProperty "user.dir"))))
         (str/trim (:out (process/run-process {:command ["pwd" "-P"] :stdin ""}))))))

(deftest run-process-times-out-and-reports-missing-binaries
  (is (thrown? java.util.concurrent.TimeoutException
               (process/run-process {:command ["sleep" "5"] :stdin "" :timeout-ms 100})))
  (is (thrown? java.io.IOException
               (process/run-process {:command ["pavlov-no-such-binary"] :stdin ""}))))
