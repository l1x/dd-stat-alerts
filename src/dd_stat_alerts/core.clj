; Licensed under the Apache License, Version 2.0 (the "License"); 
; you may not use this file except in compliance with the License. 
; You may obtain a copy of the License at
;
; http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software 
; distributed under the License is distributed on 
; an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
; either express or implied. See the License for the specific 
; language governing permissions and limitations under the License.

(ns dd-stat-alerts.core
  (:require 
    [clojure.tools.cli  :refer [parse-opts]       ]
    [clojure.string     :as     string            ]
    [clojure.edn        :as     edn               ]
    [clojure.walk       :refer [keywordize-keys]  ]
    [clj-http.client    :as     http-client       ]
    [clojure.data.json  :as     json              ]
    [clojure.string     :refer [split]            ]
    [clojure.java.io    :as     io                ])
   (:import
    [com.google.common.hash     Hashing HashFunction HashCode ]
    [java.nio.charset           Charset                       ])
  (:gen-class))


;; PARAMETERS

(def alarm_threshold_multiplicator 1.3) ; 1.3 * threshold ;; threshold is currently (std + mean)
(def alarm_count_maximum 8)             ; this controlls how many times the node can alarm maximum
(def alerting_level_minimum 3)          ; 
                                        ;dd-stat-alerts.core=> (filter 
                                        ; #(> (nth % 1) (- alerting_level_minimum 1)) 
                                        ;      {:nodeA 3})
                                        ;([:nodeA 3])
                                        ;
                                        ;dd-stat-alerts.core=> (filter 
                                        ; #(> (nth % 1) (- alerting_level_minimum 1)) 
                                        ;     {:nodeA 2})
                                        ;()
                                        ; you can be sure that the node require attention
                                        ; either by automation or engaging human
                                        ;
                                        ; i have not measured how effective these settings are
                                        ; the hit rate is: 90.9% and 99.3%

;; Helpers

(defn epoch 
  "Returns (System/currentTimeMillis)/1000"
  [] 
  (int (/ (System/currentTimeMillis) 1000)))

(defn epoch-min 
  "Returns the epoch-min, to any epoch input
  epoch min is the the last minute's epoch value
  1390086540 1390086600 1390086660"
  [^Integer epoch] 
  (- epoch (mod epoch 60)))

(defn last-datapoints 
  [^Integer epoch]
  "Returns a lazy sequence with the previous minutes
  (take 5 (last-datapoints 1398137284))
  (1398137280 1398137220 1398137160 1398137100 1398137040)"
  (for [x (range)] (- (epoch-min epoch) (* x 60))))

(defn get-pairs 
  "Gets a list and returns a list of 2 item lists
  (1 2 3 4 5) -> ((1 2) (2 3) (3 4) (4 5))"
  [l] 
  (partition 2 1 l))

(defn rround
  [num prec]
  (let [div (int (Math/pow 10 prec))]
    (float (/ (Math/round (* num div)) div))))

(defn std-dev [samples]
  (let [  n             (count samples)
          mean          (rround (/ (reduce + samples) n) 4)
          intermediate  (map #(Math/pow (- %1 mean) 2) samples)
          sd            (rround (Math/sqrt (/ (reduce + intermediate) n)) 4) ]

    ;this should be only calculating std-dev and mean and return those
    {:ok {:sd sd :mean mean}}))

;;user=> (println (std-dev  [9 8 5 7 9 8 5 20]))
;;{:sd 4.456385867493972, :mean 8.875} 

(def ^:private ^HashFunction murmur-fun (Hashing/murmur3_128))
(def ^:private ^HashFunction sha1-fun   (Hashing/sha1))
(def ^:private ^HashFunction md5-fun    (Hashing/md5))

(def ^:private ^sun.nio.cs.UTF_8 utf8-chr-set (Charset/forName "UTF-8"))

(defn gen-hash 
  "General generator for hashing functions"
  [^HashFunction fun] 
  (fn ^String [^String string] 
    (.toString (.hashString fun string utf8-chr-set))))

(def murmur (gen-hash murmur-fun))
(def sha1   (gen-hash sha1-fun))
(def md5    (gen-hash md5-fun))

(defn uuid
  "Returns a new java.util.UUID as string" 
  []
  (str (java.util.UUID/randomUUID)))

(defn average 
  [coll] 
  (/ (reduce + coll) (count coll)))
;; Operations

; Reading config
(defn read-config 
  [file]
  (try
    {:ok (clojure.edn/read-string (slurp file))}
  (catch Exception e 
    {:error "Exception" :fn "query-api" :exception (.getMessage e)})))

; Printing config
(defn print-config 
  [m] 
  (println m))

(defn read-data [id epoch-min]
  (let [ file (io/file "data" id (str epoch-min ".json")) ]
  (try
    {:ok {:content (slurp file)}}
  (catch Exception e
    {:error "Exception" :fn "read-data" :exception (.getMessage e)}))))

(defn save-data [id epoch-min data]
  (let [ file (io/file "data" id (str epoch-min ".json")) ]
    ;making sure the directory exists 
    (io/make-parents file)
    ;writing the content to the file
    (try
      ;io
      (spit file (json/write-str data))
      ;ret
      {:ok {:file (str file)}}
    (catch Exception e
      {:error "Exception" :fn "save-data" :exception (.getMessage e)}))))

(defn query-api 
  [api-key app-key from to query]
  (let [ url (apply str [  "https://app.datadoghq.com/api/v1/query?"
                            "&api_key="         api-key
                            "&application_key=" app-key 
                            "&from="            from 
                            "&to="              to 
                            "&query="           query ]) ]
    ;try to get data from the url 
    ;return {}
    (try
      {:ok (:body (http-client/get url {:as :json-strict-string-keys} ))}
    (catch Exception e
      {:error "Exception" :fn "query-api" :exception (.getMessage e)}))))

(defn get-data
  "This function downloads and saves the data into JSON files.
  Each file contains 1 minute worth of data."
  [config]
  (let [  api-key (get-in config [:ok :api-key])
          app-key (get-in config [:ok :app-key])
          start   (get-in config [:ok :start])
          end     (get-in config [:ok :end])
          query   (get-in config [:ok :query])
                  ;(reverse (take 5 (1398137280 1398137220 ...)
          minutes (reverse (take (+ 1 (/ (- end start) 60)) (last-datapoints end)))
          pairs   (get-pairs minutes) ]
    (doseq
      ;if the call returns data save the data to a file
      ;there can be only 300 requests per hour per IP
      ;we could try to have few giant requests but for now this is good enough
      ;every minute range is one request
      [pair pairs]
      (let [  id    (murmur query) 
              fst   (nth pair 0) 
              scnd  (nth pair 1)
              data  (query-api api-key app-key fst scnd query) ]
        (cond 
          (contains? data :ok)
            ;if the data is returned ok than try to write it
            (println (save-data id fst (:ok data)))
          (contains? data :error) 
            ;data contains the error
            (println data)
          :else 
            (println "The query-api call returned something unexpected..."))))))

(def alerts (atom {}))

(defn update-vals [mmap vals f]
  (reduce #(update-in % [%2] f) mmap vals))

(defn remove-empties [mmap]
  (into {} (remove #(-> % val (= 0)) mmap)))

(defn inc-max-n 
  [x] 
  (fn [n] (cond (< n x) (inc n) :else x)))

(def inc-max (inc-max-n alarm_count_maximum))

(defn alert?
  [alarm]
  (let [  ts              (:ts alarm)
          current-nodes   (map #(nth % 0) (:alarming alarm))
          prev-nodes      (keys @alerts) 
          not-anymore     (vec (clojure.set/difference (set prev-nodes) (set current-nodes))) ]

    (println ts (into {} (filter #(> (nth % 1) (- alerting_level_minimum 1)) @alerts)))
    ;decrement the counter on everything not alarming anymore
    (swap! alerts update-vals not-anymore (fnil dec 0))
    (swap! alerts remove-empties)
    ;increment the counter on everythin that is still alarming or just started to alarm
    (swap! alerts update-vals (vec current-nodes) (fnil inc-max 0))))


(defn alarm? 
  [vec-map]
  (doseq 
    [m vec-map] 
    (let [threshold (rround                                          ;round 4 digits
                      (+ 
                        (get-in m [:sstd-dev :sd]) 
                        (get-in m [:sstd-dev :mean])) 4)]            ;(round (+ std mean) 4)
      (alert? { :ts (:ts m) 
                :sstd-dev (:sstd-dev m) 
                :alarming 
                  (vec (filter                                       ;filtering into a vector
                    #(< 
                      (* alarm_threshold_multiplicator threshold) 
                      (nth % 1))                                     ;get only the alarming nodes
                        (:host-metric m))) } ))))                    ;alarming = seen more than X in a row

(defn analyze-data
  [config] 
  (let [  start   (get-in config [:ok :start])
          end     (get-in config [:ok :end])
          query   (get-in config [:ok :query])
          minutes (reverse (take                  ;take N last-datapoints in a reverse order
                            (+ 1 (/ (- end start) ;(1398026400 1398026460 1398026520 1398026580...)
                                    60)) 
                            (last-datapoints end)))
          pairs   (get-pairs minutes)
          vec-map  (transient []) ]
    ;main loop
    (doseq
      [pair pairs]
      (let [  id    (murmur query)
              fst   (nth pair 0)
              data  (read-data id fst) ]

        ;build a [ {:ts N :host-metric [] :sstd-dev {}} ... {} {} ... ]
        (cond
          (contains? data :ok)
            (let [ mmap         (keywordize-keys (json/read-str (get-in data [:ok :content])))
                   series       (:series mmap)
                   host-metric  ;[[:hostA metric] [:hostB metric]]
                                (vec (for [s series] ; 1 minute average
                                  [ (keyword 
                                      (nth (split (nth (split (:scope s) #",") 0) #":") 1))   ; hostname
                                    (rround (average (remove nil?                             ; no nils
                                      (map #(nth % 1) (:pointlist s)))) 3) ] ))               ; avg
                   sstd-dev     (std-dev (map #(nth % 1) host-metric))
                   return-map   {:ts fst :host-metric host-metric :sstd-dev (:ok sstd-dev)}
                 ]
              ;add the new hashmap to the vector
              (conj! vec-map return-map))
          (contains? data :error)
            ;data contains the error
            (println data)
          :else
            (println "The read-data function returned something unexpected..."))))
      (alarm? (persistent! vec-map))));end analyze-data

;; CLI

(def cli-options
  [
    ["-c" "--config FILE" "Configuration file" :default "conf/app.edn"]
    ["-h" "--help" "Print the help"]
  ])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn usage [options-summary]
  ;http://clojuredocs.org/clojure_core/clojure.core/-%3E%3E
  (->> [
        "Usage: program-name [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  print-config   Prints the config file"
        "  get-data       Downloads and saves data"
        "  analyze-data   Processes the data on disk"
        ""
        "Please refer to the readme for more information."]
       (string/join \newline)))

(defn -main [& args]
  ;same-named symbols to the map keys
  ;parse-opts returns -> {:options {:config "file/path"}, :arguments [print-config], :summary...}
  (let [  {:keys [options arguments errors summary]} (parse-opts args cli-options)
          ; options = {:config "file/path" :help true ...}
          config (read-config (:config options)) ]
    ; Handle help and error conditions
    (cond
      (:help options)
        (exit 0 (usage summary))
      errors 
        (exit 1 (println errors)))

    ; Execute program with options
    (case (first arguments)
      "print-config"
        (print-config config)
      "get-data"
        (get-data config) 
      "analyze-data"
        (analyze-data config)
      ;default
        (exit 1 (usage summary)))))

;; END

