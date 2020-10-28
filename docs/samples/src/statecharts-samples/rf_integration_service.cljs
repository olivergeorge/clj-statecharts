;; BEGIN SAMPLE

(ns statecharts-samples.rf-integration-service
  (:require [statecharts.core :as fsm]
            [re-frame.core :as rf]
            [statecharts.integrations.re-frame :as fsm.rf]))

(def friends-path [(rf/path :friends)])

(declare friends-service)

(rf/reg-event-fx
 :friends/init
 (fn []
   (fsm/start friends-service)          ;; (1)
   nil))

(defn load-friends [s e]
  (send-http-request
   {:uri "/api/get-friends.json"
    :method :get
    :on-success #(fsm/send friends-service {:type :success-load :data %})
    :on-failure #(fsm/send friends-service {:type :fail-load :data %})})
  s)

(defn on-friends-loaded [state {:keys [data]}]
  (assoc state :friends (:friends data)))

(defn on-friends-load-failed [state {:keys [data]}]
  (assoc state :error (:status data)))

(def friends-machine
  (fsm/machine
    {:id      :friends
     :initial :loading
     :states
              {:loading     {:entry load-friends
                             :on    {:success-load {:actions on-friends-loaded
                                                    :target  :loaded}
                                     :fail-load    {:actions on-friends-load-failed
                                                    :target  :load-failed}}}
               :loaded      {}
               :load-failed {}}}))

(defonce friends-service (fsm/service friends-machine))

(fsm.rf/connect-rf-db friends-service [:friends]) ;; (2)

(defn ^:dev/after-load after-reload []
  (fsm/reload friends-service friends-machine)) ;; (3)

;; END SAMPLE
