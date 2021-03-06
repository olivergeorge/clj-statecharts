(ns statecharts.impl-test
  (:require [statecharts.impl :as impl :refer [assign]]
            [clojure.test :refer [deftest is are use-fixtures testing]]
            #?(:clj [kaocha.stacktrace])))

#?(:clj
   (alter-var-root
    #'kaocha.stacktrace/*stacktrace-filters*
    (constantly ["java." "clojure." "kaocha." "orchestra."])))

(defonce actions-data (atom nil))

(def safe-inc (fnil inc 0))

(defn inc-actions-data [k]
  (swap! actions-data update k safe-inc))

(defn create-connection [state event]
  (is (= (:_state state) :connecting))
  (is (#{:connect :error} (:type event))))

(defn update-connect-attempts [state event]
  (update state :connect-attempts (fnil inc 0)))

(defn test-machine []
  (impl/machine
   {:id      :conn
    :initial :idle
    :context {:x 0
              :y 0}
    :entry   (fn [& _]
               (inc-actions-data :global-entry))
    :on {:logout {:target :wait-login
                  :actions [(assign (fn [context & _]
                                   (update context :logout-times safe-inc)))
                            (fn [& _]
                              (inc-actions-data :global-logout))]}}
    :states
    {:wait-login {:on {:login :idle}}
     :idle       {:on {:connect {:target  :connecting
                                 :actions [(assign update-connect-attempts)]}}}
     :connecting {:entry create-connection
                  :on    {:connected :connected}}
     :connected  {:on {:error :connecting}
                  :exit [(fn [& _]
                           (inc-actions-data :connected-exit))
                         (assign
                          (fn [context & _]
                            (update context :disconnections safe-inc)))]}}}))

(deftest test-e2e
  (reset! actions-data nil)
  (let [fsm (test-machine)
        state (impl/initialize fsm)
        _ (do
            (is (= (:_state state) :idle))
            (is (= (:global-entry @actions-data) 1)))

        {:keys [connect-attempts] :as state} (impl/transition fsm state :connect)

        _ (do
            (is (= (:_state state) :connecting))
            (is (= (:connect-attempts state) 1)))

        state (impl/transition fsm state :connected)

        _ (is (= (:_state state) :connected))

        state (impl/transition fsm state :error)

        _ (do
            (is (= (:disconnections state) 1))
            (is (= (:connected-exit @actions-data) 1))
            (is (= (:_state state) :connecting)))

        state (impl/transition fsm state :logout)

        _ (do
            (is (= (:_state state) :wait-login))
            (is (= (:global-logout @actions-data) 1))
            (is (= (:logout-times state) 1)))
        ]))

(deftest test-override-context
  (let [fsm (test-machine)
        n (rand-int 100)
        state (impl/initialize fsm {:context {:foo n}})]
    (is (= (:foo state) n))))

(defn fake-action-fn []
  (fn [& args]))

(deftest test-transition
  (let [[entry0
         entry1 exit1 a12 a13
         entry2 exit2 a21 a23
         entry3 exit3 a31 a32 a34 a35
         entry4 exit4 exit5]
        (repeatedly 20 fake-action-fn)

        event-ref (atom nil)
        entry5 (fn [_ event]
                 (reset! event-ref event))

        guard-event-ref (atom nil)

        test-machine
        (impl/machine
         {:id :test
          :initial :s1
          :entry entry0
          :states
          {:s1 {:entry entry1
                :exit exit1
                :on {:e12 :s2
                     :e13 {:target :s3
                           :actions a13}}}
           :s2 {:entry entry2
                :exit exit2
                :on {:e23 {:target :s3
                           :actions a23}}}
           :s3 {:entry entry3
                :exit exit3
                :always [{:guard (constantly false)
                          :actions a34
                          :target :s4}
                         {:guard (fn [state event]
                                   (reset! guard-event-ref event)
                                   true)
                          :actions a35
                          :target :s5}]
                :on {:e31 :s1}}
           :s4 {:entry entry4
                :exit exit3}
           :s5 {:entry entry5
                :exit exit5}}})

        init-state (impl/initialize test-machine {:debug true})

        tx (fn [v event]
             (impl/transition test-machine {:_state v} event {:debug true}))
        check-tx (fn [curr event _ expected]
                   (let [new-state (tx curr event)]
                     (is (= (:_state new-state) expected))))
        check-actions (fn [curr event _ expected]
                        (let [new-state (tx curr event)]
                          (is (= (:_actions new-state) expected))))]

    (is (= (:_state init-state) :s1))
    (is (= (:_actions init-state) [entry0 entry1]))

    (check-tx :s1 :e12 :=> :s2)
    ;; (check-actions :s1 :e12 :=> [exit1])
    ;; (check-tx :s1 :e13 :=> :s3)
    ;; (check-actions :s1 :e13 :=> [exit1 a13 entry3])

    (check-tx :s2 :e23 :=> :s5)
    (check-actions :s2 :e23 :=> [exit2 a23 entry3 exit3 a35 entry5])

    (testing "eventless transitions should pass the event along"
      (tx :s2 {:type :e23
               :k1 :v1
               :k2 :v2})
      (is (= (:k1 @event-ref) :v1))
      (is (= (:k2 @event-ref) :v2))
      (is (= (:k1 @guard-event-ref) :v1)))))

(defn guard-fn [state _]
  (> (:y state) 1))

(defn root-a01 [& _])

(defn nested-machine []
  (impl/machine
   {:id      :fsm
    :context {:x 1 :y 2}
    :entry   :entry0
    :exit    :exit0
    :after   {100 [:. :s6]}
    :on      {:e01           {:target [:. :s1]
                              :actions root-a01}
              :e02           [:. :s2]
              :e0_0_internal {:actions :a_0_0_internal}
              :e0_0_external {:target [] :actions :a_0_0_external}}
    :initial :s1
    :states  {:s1
              {:initial :s1.1
               :entry   :entry1
               :exit    :exit1
               :on      {:e12 :s2
                         :e14 :s4}
               :states  {:s1.1 {:entry   :entry1.1
                                :exit    :exit1.1
                                :initial :s1.1.1
                                :on      {:e1.1_1.2      {:target  :s1.2
                                                          :actions :a1.1_1.2}
                                          :e1.1_or_1.1.1 [:> :s4]}
                                :states  {:s1.1.1 {:entry :entry1.1.1
                                                   :exit  :exit1.1.1
                                                   :on    {:e1.1.1_1.2    [:> :s1 :s1.2]
                                                           :e1.1_or_1.1.1 [:> :s3]
                                                           :e1.1.1_3      [:> :s3]}}}}

                         :s1.2
                         {:entry :entry1.2
                          :exit  :exit1.2
                          :on    {:e1.2_1.2_internal {:actions :a1.2_1.2_internal}
                                  :e1.2_1.3 {:target :s1.3
                                             :actions :a1.2_1.3}
                                  :e1.2_1.2_external {:target  :s1.2
                                                      :actions :a1.2_1.2_external}}}
                         :s1.3
                         {:entry :entry1.3
                          :exit  :exit1.3
                          :always [{:target [:> :s2]
                                    :actions :a1.3_2
                                    :guard (constantly false)}
                                   {:target [:> :s9]
                                    :actions :a1.3_9}]}}}
              :s2 {:entry :entry2
                   :exit  :exit2
                   :on    {:e2_3          :s3
                           :e2_2_internal {:actions :a2_2_internal}
                           :e2_2_external {:target  :s2
                                           :actions :a2_2_external}}}
              :s3 {:entry :entry3
                   :exit  :exit3}
              :s4 {:initial :s4.1
                   :entry   :entry4
                   :exit    :exit4
                   :on      {:e4_4.2_internal {:target  [:. :s4.2]
                                               :actions :a4_4.2_internal}
                             :e4_4.2_external {:target  [:> :s4 :s4.2]
                                               :actions :a4_4.2_external}}
                   :states  {:s4.1 {:entry :entry4.1
                                    :exit  :exit4.1}
                             :s4.2 {:entry :entry4.2
                                    :exit  :exit4.2}}}
              :s5 {:on {:e5x [{:target :s3
                               :guard  (fn [context _]
                                        (> (:x context) 1))}
                              {:target :s4}]

                        :e5y [{:target :s3
                               :guard  (fn [context _]
                                        (> (:y context) 1))}
                              {:target :s4}]

                        :e5z [{:target :s3
                               :guard  (fn [context _]
                                        (= (:y context) 1))}]
                        }}
              :s6 {:after [{:delay  1000
                            :target :s7}]}
              :s7 {:after {2000 [{:target :s6
                                  :guard guard-fn}
                                 {:target :s8}]}}
              :s8 {}
              :s9 {:entry :entry9
                   :exit :exit9
                   :always {:target :s8
                            :actions :a98}}}}))

(deftest test-expand-path
  (let [fsm (nested-machine)]
    (is (= (impl/expand-path fsm :s2)
           [(-> fsm
                impl/extract-path-element)
            (-> fsm
                :states
                :s2
                (assoc :id :s2)
                impl/extract-path-element)]))

    (is (= (impl/expand-path fsm [:s1])
           (impl/expand-path fsm [:s1 :s1.1])
           [(-> fsm
                (dissoc :states)
                (impl/extract-path-element))
            (-> fsm
                :states
                :s1
                (assoc :id :s1)
                impl/extract-path-element)
            (-> fsm
                :states
                :s1
                :states
                :s1.1
                (assoc :id :s1.1)
                impl/extract-path-element)
            (-> fsm
                (get-in [:states :s1 :states :s1.1 :states :s1.1.1])
                (assoc :id :s1.1.1)
                impl/extract-path-element)]))

    (is (= (impl/expand-path fsm [:s1 :s1.2])
           [(-> fsm
                (dissoc :states)
                (impl/extract-path-element))
            (-> fsm
                :states
                :s1
                (assoc :id :s1)
                impl/extract-path-element)
            (-> fsm
                :states
                :s1
                :states
                :s1.2
                (assoc :id :s1.2)
                impl/extract-path-element)]))))

(defn prepare-nested-test []
  (let [fsm (nested-machine)
        init-state (impl/initialize fsm {:exec false})

        tx (fn [v event]
             (impl/transition fsm
                              (assoc (:context fsm)
                                     :_state v)
                             event {:exec false}))
        check-tx (fn [curr event _ expected]
                   (let [new-state (tx curr event)]
                     (is (= (:_state new-state) expected))))
        check-actions (fn [curr event _ expected]
                        (let [new-state (tx curr event)]
                          (is (= (:_actions new-state) expected))))]
    {:fsm fsm
     :init-state init-state
     :check-tx check-tx
     :check-actions check-actions}))

(deftest test-nested-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "initialize shall handle nested state"
      (is (= (:_state init-state) [:s1 :s1.1 :s1.1.1]))
      (is (= (:_actions init-state) [:entry0
                                    {:action      :fsm/schedule-event,
                                     :event       [:fsm/delay [] 100],
                                     :event-delay 100}
                                    :entry1 :entry1.1 :entry1.1.1])))

    (testing "from inner nested state to another top level state"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1_1.2 :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1_1.2
                     :=> [:exit1.1.1 :exit1.1 :a1.1_1.2 :entry1.2]))

    (testing "transition to inner nested state in another top level parent"
      (check-tx [:s1 :s1.1 :s1.1.1] :e14 :=> [:s4 :s4.1])
      (check-actions [:s1 :s1.1 :s1.1.1] :e14 :=>
                     [:exit1.1.1 :exit1.1 :exit1 :entry4 :entry4.1]))

    (testing "event unhandled by child state is handled by parent"
      (check-tx [:s1 :s1.1 :s1.1.1] :e12 :=> :s2))

    (testing "child has higher priority than parent when both handles an event"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1_or_1.1.1 :=> :s3))

    (testing "in inner state, event handled by top level event"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1.1_1.2
                :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1.1_1.2
                     :=> [:exit1.1.1 :exit1.1 :entry1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1.1_3
                     :=> [:exit1.1.1 :exit1.1 :exit1 :entry3]))

    (testing "event handled by root"
      (check-tx :s3 :e01 :=> [:s1 :s1.1 :s1.1.1]))
      (check-actions [:s3] :e01 :=> [:exit3 root-a01 :entry1 :entry1.1 :entry1.1.1])
      ))

(deftest test-self-transitions
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "internal self-transition"
      (check-tx :s2 :e2_2_internal :=> :s2)
      (check-actions :s2 :e2_2_internal
                     :=> [:a2_2_internal]))

    (testing "external self-transition"
      (check-tx :s2 :e2_2_external :=> :s2)
      (check-actions :s2 :e2_2_external
                     :=> [:exit2 :a2_2_external :entry2]))

    (testing "internal self-transition on root"
      (check-tx [:s1 :s1.1 :s1.1.1] :e0_0_internal :=> [:s1 :s1.1 :s1.1.1])
      (check-actions :s1 :e0_0_internal
                     :=> [:a_0_0_internal]))

    (testing "external self-transition on root"
      (check-tx [:s1 :s1.1 :s1.1.1] :e0_0_external :=> [:s1 :s1.1 :s1.1.1])
      (check-actions [:s1 :s1.1 :s1.1.1] :e0_0_external
                     :=> [:exit1.1.1 :exit1.1 :exit1
                          {:action :fsm/unschedule-event
                           :event [:fsm/delay [] 100]}
                          :exit0
                          :a_0_0_external
                          :entry0
                          {:action :fsm/schedule-event,
                           :event [:fsm/delay [] 100],
                           :event-delay 100}
                          :entry1 :entry1.1 :entry1.1.1
                          ]))

    (testing "internal self-transition in an inner state"
      (check-tx [:s1 :s1.2] :e1.2_1.2_internal :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.2] :e1.2_1.2_internal
                     :=> [:a1.2_1.2_internal]))

    (testing "external self-transition in an inner state"
      (check-tx [:s1 :s1.2] :e1.2_1.2_external :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.2] :e1.2_1.2_external
                     :=> [:exit1.2 :a1.2_1.2_external :entry1.2]))

    ))

(deftest test-internal-external-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "internal parent->child"
      (check-tx [:s4 :s4.1] :e4_4.2_internal :=> [:s4 :s4.2])
      (check-actions [:s4 :s4.1] :e4_4.2_internal
                     :=> [:exit4.1 :a4_4.2_internal :entry4.2]))

    (testing "external parent->child"
      (check-tx [:s4 :s4.1] :e4_4.2_external :=> [:s4 :s4.2])
      (check-actions [:s4 :s4.1] :e4_4.2_external
                     :=> [:exit4.1 :exit4 :a4_4.2_external :entry4 :entry4.2]))))

(deftest test-guarded-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (check-tx [:s5] :e5x :=> [:s4 :s4.1])
    (check-tx [:s5] :e5y :=> :s3)
    (check-tx [:s5] :e5z :=> :s5)))

(deftest test-resolve-target
  (are [current target _ resolved] (= (impl/resolve-target current target)
                                      resolved)
    ;; nil
    :s1         nil :=> [:s1]
    [:s1 :s1.1] nil :=> [:s1 :s1.1]

    ;; absolute
    :s1 [:> :s2] :=> [:s2]

    ;; relative
    :s1         [:s2]   :=> [:s2]
    [:s1 :s1.1] [:s1.2] :=> [:s1 :s1.2]

    ;; keyword
    :s1         :s2   :=> [:s2]
    [:s1 :s1.1] :s1.2 :=> [:s1 :s1.2]

    ;; child
    [:s1]       [:. :s1.1]   :=> [:s1 :s1.1]
    [:s1 :s1.1] [:. :s1.1.1] :=> [:s1 :s1.1 :s1.1.1]
    ))

(defn make-node [id]
  (let [kw #(-> %
                name
                (str id)
                keyword)]
    {:id (kw :s) :entry [(kw :entry)] :exit [(kw :exit)]}))

(deftest test-collection-actions
  (are [current target external-transition? _ actions]
      (= (impl/collect-actions current target external-transition?) actions)

    [(make-node "1")]
    [(make-node "2")]
    false
    :=>
    {:exit [:exit1]
     :entry [:entry2]}

    [(make-node "1")
     (make-node "1.1")]
    [(make-node "1")
     (make-node "1.2")]
    false
    :=>
    {:exit [:exit1.1]
     :entry [:entry1.2]}

    [(make-node "1")
     (make-node "1.1")]
    [(make-node "1")
     (make-node "1.2")]
    true
    :=>
    {:exit [:exit1.1 :exit1]
     :entry [:entry1 :entry1.2]}

    [(make-node "1")
     (make-node "1.1")]
    [(make-node "1")
     (make-node "1.2")
     (make-node "1.2.1")]
    false
    :=>
    {:exit [:exit1.1]
     :entry [:entry1.2 :entry1.2.1]}

    [(make-node "1")
     (make-node "1.1")]
    [(make-node "1")
     (make-node "1.2")
     (make-node "1.2.1")]
    true
    :=>
    {:exit [:exit1.1 :exit1]
     :entry [:entry1 :entry1.2 :entry1.2.1]}

    [(make-node "1")
     (make-node "1.1")]
    [(make-node "2")
     (make-node "2.1")]
    false
    :=>
    {:exit [:exit1.1 :exit1]
     :entry [:entry2 :entry2.1]}
    ))

(deftest test-verify-targets-when-creating-machine
  (are [fsm re] (thrown-with-msg? #?(:clj Exception
                                     :cljs js/Error) re (impl/machine fsm))
    {:id :fsm
     :initial :s1
     :states {:s2 {}}}
    #"target.*s1"

    {:id :fsm
     :initial :s1
     :states {:s1 {:on {:e1 :s2}}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:initial :s1.1
                   :states {:s1.1 {:on {:s1.1_s1.2 :s2}}}}
              :s2 {}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:after {1000 :s2}}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:always {:guard :g12
                            :target :s2}}}}
    #"target.*s2"

    ))

(deftest test-delayed-transition-unit
  (let [{:keys [fsm init-state check-tx check-actions]} (prepare-nested-test)]

    (let [{:keys [entry exit on] :as s6} (get-in fsm [:states :s6])]
      (is (= entry [{:action :fsm/schedule-event
                     :event-delay 1000
                     :event [:fsm/delay [:s6] 1000]}]))
      (is (= exit [{:action :fsm/unschedule-event
                    :event [:fsm/delay [:s6] 1000]}]))
      (is (= on {[:fsm/delay [:s6] 1000] [{:target :s7}]})))

    (let [{:keys [entry exit on] :as s7} (get-in fsm [:states :s7])]
      (is (= entry [{:action :fsm/schedule-event
                     :event-delay 2000
                     :event [:fsm/delay [:s7] 2000]}]))

      (is (= exit [{:action :fsm/unschedule-event
                    :event [:fsm/delay [:s7] 2000]}]))

      (is (= (get on [:fsm/delay [:s7] 2000])
             [{:guard guard-fn
               :target :s6}
              {:target :s8}])))))

(deftest test-nested-eventless-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]
    (check-tx [:s1 :s1.2] :e1.2_1.3 :=> :s8)
    (check-actions [:s1 :s1.2] :e1.2_1.3
                   :=> [:exit1.2 :a1.2_1.3 :entry1.3
                        :exit1.3 :exit1
                        :a1.3_9 :entry9 :exit9 :a98])))

(deftest test-eventless-transtions-iterations
  (let [test-machine
        (impl/machine
         {:id :test
          :initial :s1
          :context {:x 100}
          :states
          {:s1 {:on {:e12 {:target :s2
                           :actions (assign (fn [state _]
                                              (assoc state :x 200)))}}}
           :s2 {:always {:target :s3
                         :guard (fn [{:keys [x]} _]
                                  (= x 200))}}
           :s3 {}}})]
    (is (= (:_state (impl/transition test-machine {:_state :s1} :e12))
           :s3))))
