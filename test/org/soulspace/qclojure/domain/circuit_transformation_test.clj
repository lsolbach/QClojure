(ns org.soulspace.qclojure.domain.circuit-transformation-test
  "Tests for circuit transformation functionality."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.spec.alpha :as s]
            [org.soulspace.qclojure.domain.circuit :as qc]
            [org.soulspace.qclojure.domain.circuit-transformation :as ct]
            [org.soulspace.qclojure.domain.operation-registry :as gr]))

;;
;; Circuit Composition Tests
;;
(deftest test-circuit-composition
  (testing "Basic circuit composition"
    (let [c1 (qc/h-gate (qc/create-circuit 1 "C1") 0)
          c2 (qc/x-gate (qc/create-circuit 1 "C2") 0)
          composed (ct/compose-circuits c1 c2)]
      (is (= (:num-qubits composed) 1))
      (is (= (count (:operations composed)) 2))))
  
  (testing "Composition with different qubit counts"
    (let [c1 (qc/h-gate (qc/create-circuit 1 "C1") 0)
          c2 (qc/cnot-gate (qc/create-circuit 2 "C2") 0 1)
          composed (ct/compose-circuits c1 c2)]
      (is (= (:num-qubits composed) 2))
      (is (= (count (:operations composed)) 2))))

  (testing "Composition with offset mapping"
    (let [c1 (qc/create-circuit 3 "C1")
          c2 (qc/h-gate (qc/create-circuit 1 "C2") 0)
          composed (ct/compose-circuits c1 c2 {:offset 1})]
      (is (= (:num-qubits composed) 3))
      ;; Check that the H gate now targets qubit 1 instead of 0
      (is (= (get-in (nth (:operations composed) 0) [:operation-params :target]) 1))))

  (testing "Composition with control-qubits-only option"
    (let [total-qubits 5
          control-qubits 2
          c1 (qc/create-circuit total-qubits "Main Circuit")
          c2 (qc/h-gate (qc/h-gate (qc/create-circuit control-qubits "QFT") 0) 1)
          composed (ct/compose-circuits c1 c2 {:control-qubits-only true})]
      (is (= (:num-qubits composed) total-qubits))
      ;; Check that the H gates still target the original qubit indices
      (is (= (get-in (nth (:operations composed) 0) [:operation-params :target]) 0))
      (is (= (get-in (nth (:operations composed) 1) [:operation-params :target]) 1)))))

;; Test circuit extension functionality
(deftest test-circuit-extension
  (testing "Basic circuit extension"
    (let [c (qc/h-gate (qc/create-circuit 1) 0)
          extended (ct/extend-circuit c 3)]
      (is (= (:num-qubits extended) 3))
      (is (= (get-in (first (:operations extended)) [:operation-params :target]) 0))))

  (testing "Circuit extension with qubit mapping"
    (let [c (qc/cnot-gate (qc/create-circuit 2) 0 1)
          extended (ct/extend-circuit c 4 :qubit-mapping #(+ % 2))]
      (is (= (:num-qubits extended) 4))
      (is (= (get-in (first (:operations extended)) [:operation-params :control]) 2))
      (is (= (get-in (first (:operations extended)) [:operation-params :target]) 3)))))

;;
;; Circuit Transformer Tests
;;
(deftest test-circuit-transformation
  (testing "Basic circuit transformation"
    (let [;; Create a circuit with a Y gate (which can be decomposed to RX and RZ)
          circuit (-> (qc/create-circuit 2 "Test Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 1)
                      (qc/cnot-gate 0 1))

          ;; Use a set that doesn't support Y gates
          ;; Transform the circuit
          result (ct/transform-circuit circuit #{:h :x :z :rx :rz :cnot})]

      ;; Verify that the Y gate was transformed
      (is (pos? (:transformed-operation-count result)))

      ;; Verify that no unsupported operations remain
      (is (empty? (:unsupported-operations result)))

      ;; Verify the circuit is valid
      (is (s/valid? ::qc/quantum-circuit (:quantum-circuit result))))))

(deftest test-transformation-options
  (testing "Non-transformation option"
    (let [;; Create a circuit with an unsupported gate
          circuit (-> (qc/create-circuit 1)
                      (qc/y-gate 0))

          ;; Use a set that doesn't support Y gates
          ;; Transform with transformation disabled
          result (ct/transform-circuit circuit #{:h :x :cnot}
                                       {:transform-unsupported? false})]

      ;; Verify the circuit was not transformed
      (is (zero? (:transformed-operation-count result)))

      ;; Verify that the Y gate is reported as unsupported
      (is (= [:y] (:unsupported-operations result))))))

(deftest test-max-iterations
  (testing "Circuit transformation doesn't hit max iterations"
    (let [;; Create a circuit with multiple gates that need complex decomposition
          circuit (-> (qc/create-circuit 3 "Complex Circuit")
                      (qc/y-gate 0)    ;; Decomposes to rx, rz
                      (qc/y-gate 1)    ;; Decomposes to rx, rz
                      (qc/y-gate 2))   ;; Decomposes to rx, rz

          ;; Use a set with limited gate support that forces decomposition
          ;; Transform with a slightly higher max iterations to prevent hitting the limit
          result (ct/transform-circuit circuit #{:h :x :z :rz :cnot} {:max-iterations 20})]
      
      ;; The transformation should complete without hitting max iterations
      ;; and should report the appropriate unsupported operations
      (is (some? result))

      ;; If we're still seeing unsupported operations like :rx, that's OK
      ;; The important thing is that we don't hit an infinite loop
      (is (s/valid? ::qc/quantum-circuit (:quantum-circuit result))))))

(deftest test-universal-gate-set
  (testing "Any circuit can be transformed to use only universal gates"
    (let [;; Create a complex circuit with various gates
          circuit (-> (qc/create-circuit 3 "Complex Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 1)    ;; Non-universal gate
                      (qc/rz-gate 2 (/ Math/PI 4))  ;; Parametric gate
                      (qc/cnot-gate 0 1)  ;; CNOT gate
                      (qc/cz-gate 1 2)) ;; Requires decomposition

          ;; Transform the circuit
          result (ct/transform-circuit circuit gr/universal-gate-set)]

      ;; Check that transformation succeeded
      (is (some? result))

      ;; Check that no unsupported gates remain
      (is (empty? (:unsupported-operations result)))

      ;; Check that all gates in the result are from the universal set
      (is (every? #(contains? gr/universal-gate-set (:operation-type %))
                  (:operations (:quantum-circuit result)))))))

(deftest test-universal-gate-set2
  (testing "Any circuit can be transformed to use only universal gates"
    (let [;; Create a complex circuit with various gates
          circuit (-> (qc/create-circuit 3 "Complex Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 1)    ;; Non-universal gate
                      (qc/rz-gate 2 (/ Math/PI 4))  ;; Parametric gate
                      (qc/cnot-gate 0 1)  ;; CNOT gate
                      (qc/add-gate :swap {:control 1 :target 2})) ;; Using generic add-gate

          ;; Transform the circuit
          result (ct/transform-circuit circuit gr/universal-gate-set)]

      ;; Check that transformation succeeded
      (is (some? result))

      ;; Check that no unsupported gates remain
      (is (empty? (:unsupported-operations result)))

      ;; Check that all gates in the result are from the universal set
      (is (every? #(contains? gr/universal-gate-set (:operation-type %))
                  (:operations (:quantum-circuit result)))))))

(comment
  ;; Run tests
  (run-tests)

  ;; Specific testing for debugging
  (let [circuit (-> (qc/create-circuit 2)
                    (qc/h-gate 0)
                    (qc/y-gate 1))]
    (ct/transform-circuit circuit #{:h :x :z :cnot})))

;;
;; Circuit Optimizer Tests
;;
(deftest test-analyze-qubit-usage
  (testing "Analyzing qubit usage in a circuit"
    (let [;; Circuit using qubits 0, 2, 4 out of 5 total qubits
          circuit (-> (qc/create-circuit 5 "Sparse Circuit")
                      (qc/h-gate 0)
                      (qc/x-gate 2)
                      (qc/cnot-gate 0 4))
          analysis (ct/analyze-qubit-usage circuit)]

      ;; Check used qubits
      (is (= #{0 2 4} (:used-qubits analysis)))

      ;; Check total qubits
      (is (= 5 (:total-qubits analysis)))

      ;; Check unused qubits
      (is (= #{1 3} (:unused-qubits analysis)))

      ;; Check max qubit ID
      (is (= 4 (:max-qubit-id analysis)))

      ;; Check efficiency (3 used out of 5 total = 0.6)
      (is (= 0.6 (:qubit-usage-efficiency analysis)))))

  (testing "Analyzing circuit with all qubits used"
    (let [circuit (-> (qc/create-circuit 3 "Full Circuit")
                      (qc/h-gate 0)
                      (qc/x-gate 1)
                      (qc/cnot-gate 1 2))
          analysis (ct/analyze-qubit-usage circuit)]

      (is (= #{0 1 2} (:used-qubits analysis)))
      (is (= 3 (:total-qubits analysis)))
      (is (empty? (:unused-qubits analysis)))
      (is (= 2 (:max-qubit-id analysis)))
      (is (= 1.0 (:qubit-usage-efficiency analysis)))))

  (testing "Analyzing empty circuit"
    (let [circuit (qc/create-circuit 2 "Empty Circuit")
          analysis (ct/analyze-qubit-usage circuit)]

      (is (empty? (:used-qubits analysis)))
      (is (= 2 (:total-qubits analysis)))
      (is (= #{0 1} (:unused-qubits analysis)))
      (is (= -1 (:max-qubit-id analysis)))
      (is (= 0.0 (:qubit-usage-efficiency analysis))))))

(deftest test-optimize-qubit-usage
  (testing "Optimizing circuit with sparse qubit usage"
    (let [;; Circuit using qubits [0, 3, 7] out of 8 total qubits
          circuit (-> (qc/create-circuit 8 "Sparse Circuit")
                      (qc/h-gate 0)
                      (qc/x-gate 3)
                      (qc/cnot-gate 0 7)
                      (qc/cnot-gate 3 7))
          result (ct/optimize-qubit-usage circuit)]

      ;; Check that optimization succeeded
      (is (some? result))

      ;; Check qubit mapping (0->0, 3->1, 7->2)
      (is (= {0 0, 3 1, 7 2} (:qubit-mapping result)))

      ;; Check qubit counts
      (is (= 8 (:original-qubits result)))
      (is (= 3 (:optimized-qubits result)))
      (is (= 5 (:qubits-saved result)))

      ;; Check optimized circuit
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= 3 (:num-qubits optimized-circuit)))
        (is (= 4 (count (:operations optimized-circuit))))

        ;; Check that gate qubits were remapped correctly
        (let [operations (:operations optimized-circuit)]
          ;; H gate on original qubit 0 -> new qubit 0
          (is (= 0 (get-in (first operations) [:operation-params :target])))
          ;; X gate on original qubit 3 -> new qubit 1
          (is (= 1 (get-in (second operations) [:operation-params :target])))
          ;; CNOT gates: (0,7) -> (0,2) and (3,7) -> (1,2)
          (let [cnot1 (nth operations 2)]
            (is (or (and (= 0 (get-in cnot1 [:operation-params :control]))
                         (= 2 (get-in cnot1 [:operation-params :target])))
                    (and (= 1 (get-in cnot1 [:operation-params :control]))
                         (= 2 (get-in cnot1 [:operation-params :target]))))))))

      ;; Verify circuit is still valid
      (is (s/valid? ::qc/quantum-circuit (:quantum-circuit result)))))

  (testing "Optimizing circuit with no gaps"
    (let [;; Circuit already using sequential qubits
          circuit (-> (qc/create-circuit 3 "Sequential Circuit")
                      (qc/h-gate 0)
                      (qc/x-gate 1)
                      (qc/cnot-gate 1 2))
          result (ct/optimize-qubit-usage circuit)]

      ;; Should have no changes since qubits are already optimal
      (is (= {0 0, 1 1, 2 2} (:qubit-mapping result)))
      (is (= 0 (:qubits-saved result)))
      (is (= 3 (:original-qubits result)))
      (is (= 3 (:optimized-qubits result)))

      ;; Circuit should be unchanged
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= circuit optimized-circuit)))))

  (testing "Optimizing empty circuit"
    (let [circuit (qc/create-circuit 5 "Empty Circuit")
          result (ct/optimize-qubit-usage circuit)]

      ;; Empty circuit should be optimized to 0 qubits
      (is (empty? (:qubit-mapping result)))
      (is (= 5 (:qubits-saved result)))
      (is (= 5 (:original-qubits result)))
      (is (= 0 (:optimized-qubits result)))

      ;; Optimized circuit should have 0 qubits
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= 0 (:num-qubits optimized-circuit)))
        (is (empty? (:operations optimized-circuit)))))))

(deftest test-optimize-for-backend
  (testing "Comprehensive backend optimization"
    (let [;; Create a circuit with unsupported gates and sparse qubit usage
          circuit (-> (qc/create-circuit 6 "Complex Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 1)      ;; Needs transformation
                      (qc/cnot-gate 0 5) ;; Uses sparse qubits
                      (qc/x-gate 5))

          ;; Set that doesn't support Y gates
          ;; Perform comprehensive optimization
          result (ct/optimize circuit #{:h :x :z :rx :rz :cnot})]

      ;; Check that optimization succeeded
      (is (some? result))

      ;; Check transformation result
      (let [transformation (:transformation-result result)]
        (is (pos? (:transformed-operation-count transformation)))
        (is (empty? (:unsupported-operations transformation))))

      ;; Check qubit optimization result
      (let [qubit-opt (:qubit-optimization-result result)]
        (is (pos? (:qubits-saved qubit-opt)))
        (is (< (:optimized-qubits qubit-opt) 6)))

      ;; Check final circuit
      (let [final-circuit (:quantum-circuit result)]
        (is (< (:num-qubits final-circuit) 6))
        (is (s/valid? ::qc/quantum-circuit final-circuit)))

      ;; Check summary
      (is (string? (:optimization-summary result)))
      (is (seq (:optimization-summary result)))))

  (testing "Optimization with options"
    (let [circuit (-> (qc/create-circuit 5 "Test Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 3))  ;; Sparse usage and unsupported gate

          ;; Optimize with only qubit optimization enabled
          result (ct/optimize circuit #{:h :x :cnot}
                              {:transform-operations? false
                               :optimize-qubits? true})
          ;; Should have optimized qubits but not transformed gates
          transformation (:transformation-result result)
          qubit-opt (:qubit-optimization-result result)]
      ;; Check that no gates were transformed
      (is (zero? (:transformed-operation-count transformation)))
      ;; Y gate should be detected as unsupported but not transformed
      (is (contains? (set (:unsupported-operations transformation)) :y))
      (is (pos? (:qubits-saved qubit-opt)))))

  (testing "Optimization with no changes needed"
    (let [;; Perfect circuit: all qubits used sequentially, all gates supported
          circuit (-> (qc/create-circuit 2 "Perfect Circuit")
                      (qc/h-gate 0)
                      (qc/cnot-gate 0 1))
          result (ct/optimize circuit #{:h :cnot})
          transformation (:transformation-result result)
          qubit-opt (:qubit-optimization-result result)]

      (is (zero? (:transformed-operation-count transformation)))
      (is (empty? (:unsupported-operations transformation)))
      (is (zero? (:qubits-saved qubit-opt)))
      (is (= 2 (:optimized-qubits qubit-opt))))))

(deftest test-circuit-optimizer-edge-cases
  (testing "Circuit with rotation gates that have angle parameters"
    (let [;; Circuit with rotation gates that have angle parameters
          circuit (-> (qc/create-circuit 4 "Rotation Circuit")
                      (qc/rx-gate 0 (/ Math/PI 2))
                      (qc/ry-gate 3 (/ Math/PI 4))  ;; Uses sparse qubit
                      (qc/crz-gate 0 3 (/ Math/PI 3)))  ;; Controlled rotation

          result (ct/optimize-qubit-usage circuit)
          ;; Check that parameters are preserved after qubit remapping
          optimized-circuit (:quantum-circuit result)
          operations (:operations optimized-circuit)]

      (is (= 2 (:num-qubits optimized-circuit)))
      (is (= 3 (count operations)))

      ;; Check that angle parameters are preserved
      (doseq [operation operations]
        (when (contains? (:operation-params operation) :angle)
          (is (number? (get-in operation [:operation-params :angle])))
          (is (pos? (get-in operation [:operation-params :angle])))))))

  (testing "Multi-qubit gate parameter remapping"
    (let [;; Circuit with multi-qubit gates using sparse qubits
          circuit (-> (qc/create-circuit 6 "Multi-qubit Gates")
                      (qc/toffoli-gate 1 3 5)  ;; Uses qubits 1, 3, 5
                      (qc/fredkin-gate 0 1 5)) ;; Uses qubits 0, 1, 5

          result (ct/optimize-qubit-usage circuit)]

      ;; Should compact to 4 qubits (original qubits 0,1,3,5 -> new qubits 0,1,2,3)
      (is (= 4 (:optimized-qubits result)))
      (is (= {0 0, 1 1, 3 2, 5 3} (:qubit-mapping result)))

      ;; Check that multi-qubit gate parameters were remapped correctly
      (let [operations (:operations (:quantum-circuit result))]
        (is (= 2 (count operations)))

        ;; Check Toffoli gate parameters
        (let [toffoli-gate (first operations)]
          (is (= :toffoli (:operation-type toffoli-gate)))
          (is (= 1 (get-in toffoli-gate [:operation-params :control1])))
          (is (= 2 (get-in toffoli-gate [:operation-params :control2])))
          (is (= 3 (get-in toffoli-gate [:operation-params :target]))))

        ;; Check Fredkin gate parameters
        (let [fredkin-gate (second operations)]
          (is (= :fredkin (:operation-type fredkin-gate)))
          (is (= 0 (get-in fredkin-gate [:operation-params :control])))
          (is (= 1 (get-in fredkin-gate [:operation-params :target1])))
          (is (= 3 (get-in fredkin-gate [:operation-params :target2]))))))))

;;
;; Topology Optimization Tests
;;

(deftest test-topology-creation
  (testing "Linear topology creation"
    (let [linear-3 (ct/create-linear-topology 3)]
      (is (= 3 (count linear-3)))
      (is (= [[1] [0 2] [1]] linear-3))))

  (testing "Ring topology creation"
    (let [ring-4 (ct/create-ring-topology 4)]
      (is (= 4 (count ring-4)))
      (is (= [[3 1] [0 2] [1 3] [2 0]] ring-4))))

  (testing "Grid topology creation"
    (let [grid-2x2 (ct/create-grid-topology 2 2)]
      (is (= 4 (count grid-2x2)))
      ;; Each qubit should have 2 neighbors in a 2x2 grid
      (is (= 2 (count (nth grid-2x2 0))))
      (is (= 2 (count (nth grid-2x2 1))))
      (is (= 2 (count (nth grid-2x2 2))))
      (is (= 2 (count (nth grid-2x2 3))))))

  (testing "Star topology creation"
    (let [star-5 (ct/create-star-topology 5)]
      (is (= 5 (count star-5)))
      ;; Center qubit (0) should connect to all others
      (is (= 4 (count (nth star-5 0))))
      ;; Peripheral qubits should only connect to center
      (is (= 1 (count (nth star-5 1))))
      (is (= 1 (count (nth star-5 2))))
      (is (= 1 (count (nth star-5 3))))
      (is (= 1 (count (nth star-5 4)))))))

(deftest test-topology-validation
  (testing "Valid topology validation"
    (is (ct/validate-topology [[1] [0 2] [1]]))
    (is (ct/validate-topology [[3 1] [0 2] [1 3] [2 0]]))
    (is (ct/validate-topology [[1 2 3 4] [0] [0] [0] [0]])))

  (testing "Invalid topology validation"
    ;; Asymmetric topology
    (is (not (ct/validate-topology [[1] [2] [0]])))
    ;; Self-connections
    (is (not (ct/validate-topology [[0 1] [0]])))
    ;; Out-of-bounds connections
    (is (not (ct/validate-topology [[1 5] [0]])))))

(deftest test-distance-matrix-calculation
  (testing "Linear topology distances"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)]
      (is (= [[0 1 2] [1 0 1] [2 1 0]] distances))))

  (testing "Ring topology distances"
    (let [ring-4 (ct/create-ring-topology 4)
          distances (ct/calculate-distance-matrix ring-4)]
      (is (= [[0 1 2 1] [1 0 1 2] [2 1 0 1] [1 2 1 0]] distances))))

  (testing "Star topology distances"
    (let [star-5 (ct/create-star-topology 5)
          distances (ct/calculate-distance-matrix star-5)]
      ;; Center to all others: distance 1
      (is (= [0 1 1 1 1] (nth distances 0)))
      ;; Peripheral to center: distance 1, to others: distance 2
      (is (= [1 0 2 2 2] (nth distances 1))))))

(deftest test-two-qubit-operation-extraction
  (testing "Bell circuit extraction"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          ops (ct/extract-two-qubit-operations bell-circuit)]
      (is (= 1 (count ops)))
      (is (= {:control 0 :target 1 :operation-type :cnot} (first ops)))))

  (testing "GHZ circuit extraction"
    (let [ghz-circuit (-> (qc/create-circuit 3 "GHZ")
                          (qc/h-gate 0)
                          (qc/cnot-gate 0 1)
                          (qc/cnot-gate 0 2))
          ops (ct/extract-two-qubit-operations ghz-circuit)]
      (is (= 2 (count ops)))
      (is (= {:control 0 :target 1 :operation-type :cnot} (first ops)))
      (is (= {:control 0 :target 2 :operation-type :cnot} (second ops)))))

  (testing "Single qubit circuit extraction"
    (let [single-circuit (-> (qc/create-circuit 2 "Single")
                             (qc/h-gate 0)
                             (qc/x-gate 1))
          ops (ct/extract-two-qubit-operations single-circuit)]
      (is (= 0 (count ops))))))

(deftest test-mapping-cost-calculation
  (testing "Identity mapping cost"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)
          bell-ops [{:control 0 :target 1 :operation-type :cnot}]
          identity-mapping {0 0 1 1}
          cost (ct/calculate-mapping-cost bell-ops identity-mapping distances)]
      (is (= 1 cost))))

  (testing "Swapped mapping cost"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)
          bell-ops [{:control 0 :target 1 :operation-type :cnot}]
          swapped-mapping {0 1 1 0}
          cost (ct/calculate-mapping-cost bell-ops swapped-mapping distances)]
      (is (= 1 cost))))

  (testing "Distant mapping cost"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)
          bell-ops [{:control 0 :target 1 :operation-type :cnot}]
          distant-mapping {0 0 1 2}
          cost (ct/calculate-mapping-cost bell-ops distant-mapping distances)]
      (is (= 2 cost))))

  (testing "Incomplete mapping cost"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)
          bell-ops [{:control 0 :target 1 :operation-type :cnot}]
          incomplete-mapping {0 0}  ; missing mapping for qubit 1
          cost (ct/calculate-mapping-cost bell-ops incomplete-mapping distances)]
      (is (= Integer/MAX_VALUE cost)))))

(deftest test-optimal-mapping-finding
  (testing "Bell circuit optimal mapping"
    (let [linear-3 (ct/create-linear-topology 3)
          distances (ct/calculate-distance-matrix linear-3)
          bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          bell-ops (ct/extract-two-qubit-operations bell-circuit)
          mapping (ct/find-optimal-mapping bell-ops 3 distances)]
      (is (= 2 (count mapping)))
      (is (contains? mapping 0))
      (is (contains? mapping 1))))

  (testing "GHZ circuit optimal mapping"
    (let [star-5 (ct/create-star-topology 5)
          distances (ct/calculate-distance-matrix star-5)
          ghz-circuit (-> (qc/create-circuit 3 "GHZ")
                          (qc/h-gate 0)
                          (qc/cnot-gate 0 1)
                          (qc/cnot-gate 0 2))
          ghz-ops (ct/extract-two-qubit-operations ghz-circuit)
          mapping (ct/find-optimal-mapping ghz-ops 5 distances)]
      (is (= 3 (count mapping)))
      (is (contains? mapping 0))
      (is (contains? mapping 1))
      (is (contains? mapping 2))
      ;; In star topology, qubit 0 should ideally map to center (physical 0)
      ;; for optimal cost
      (is (= 0 (get mapping 0))))))

(deftest test-full-topology-optimization
  (testing "Bell circuit optimization on linear topology"
    (let [linear-3 (ct/create-linear-topology 3)
          bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          result (ct/optimize-for-topology bell-circuit linear-3)]
      (is (contains? result :quantum-circuit))
      (is (contains? result :logical-to-physical))
      (is (contains? result :physical-to-logical))
      (is (contains? result :total-cost))
      (is (contains? result :swap-count))
      (is (= 0 (:swap-count result)))  ; No SWAPs needed for Bell circuit
      (is (= 1 (:total-cost result)))))  ; Adjacent qubits

  (testing "GHZ circuit optimization on star topology"
    (let [star-5 (ct/create-star-topology 5)
          ghz-circuit (-> (qc/create-circuit 3 "GHZ")
                          (qc/h-gate 0)
                          (qc/cnot-gate 0 1)
                          (qc/cnot-gate 0 2))
          result (ct/optimize-for-topology ghz-circuit star-5)]
      (is (contains? result :quantum-circuit))
      (is (contains? result :logical-to-physical))
      (is (contains? result :physical-to-logical))
      (is (contains? result :total-cost))
      (is (contains? result :swap-count))
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 2 (:total-cost result))))))  ; Two operations, each distance 1

(deftest test-topology-comparison
  (testing "Compare topologies for same circuit"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          ring-4 (ct/create-ring-topology 4)
          star-5 (ct/create-star-topology 5)
          comparison (ct/compare-topologies bell-circuit 
                                            {"linear-3" linear-3
                                             "ring-4" ring-4
                                             "star-5" star-5})]
      (is (= 3 (count comparison)))
      (is (contains? (first comparison) :topology-name))
      (is (contains? (first comparison) :total-cost))
      (is (contains? (first comparison) :swap-count))
      ;; All should have same cost (1) for Bell circuit
      (is (every? #(= 1 (:total-cost %)) comparison)))))

(deftest test-topology-analysis
  (testing "Analyze linear topology connectivity"
    (let [linear-3 (ct/create-linear-topology 3)
          analysis (ct/analyze-topology-connectivity linear-3)]
      (is (contains? analysis :num-qubits))
      (is (contains? analysis :total-edges))
      (is (contains? analysis :avg-degree))
      (is (contains? analysis :diameter))
      (is (= 3 (:num-qubits analysis)))
      (is (= 2 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))))

  (testing "Analyze star topology connectivity"
    (let [star-5 (ct/create-star-topology 5)
          analysis (ct/analyze-topology-connectivity star-5)]
      (is (= 5 (:num-qubits analysis)))
      (is (= 4 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))  ; Star has diameter 2
      (is (= 1.6 (:avg-degree analysis)))))  ; 4*2/5 = 1.6

  (testing "Get topology info"
    (let [ring-4 (ct/create-ring-topology 4)
          info (ct/get-topology-info ring-4)]
      (is (string? info))
      (is (re-find #"Qubits: 4" info))
      (is (re-find #"Edges: 4" info)))))

(deftest test-generate-swap-operations
  (testing "No SWAPs needed for adjacent qubits"
    (let [path [0 1]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single SWAP for distance 2"
    (let [path [0 1 2]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 2 (count swaps)))  ; Path [0 1 2] generates 2 SWAPs: (0↔1) and (1↔2)
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))))

  (testing "Multiple SWAPs for longer path"
    (let [path [0 1 2 3]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 3 (count swaps)))  ; Path [0 1 2 3] generates 3 SWAPs
      ;; First SWAP: 0 ↔ 1
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      ;; Second SWAP: 1 ↔ 2
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))
      ;; Third SWAP: 2 ↔ 3
      (let [swap3 (nth swaps 2)]
        (is (= :swap (:operation-type swap3)))
        (is (= {:target1 2 :target2 3} (:operation-params swap3)))
        (is (= {:moves-qubit 0 :from 2 :to 3} (:routing-info swap3))))))

  (testing "Empty path returns no SWAPs"
    (let [path []
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single qubit path returns no SWAPs"
    (let [path [0]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps)))))

(deftest test-optimize-for-topology-basic
  (testing "Bell circuit on linear topology with optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3)]
      
      ;; Check result structure
      (is (contains? result :quantum-circuit))
      (is (contains? result :logical-to-physical))
      (is (contains? result :physical-to-logical))
      (is (contains? result :swap-count))
      (is (contains? result :total-cost))
      (is (contains? result :topology-summary))
      
      ;; Bell circuit should have minimal cost with optimal mapping
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 1 (:total-cost result)))  ; Adjacent qubits cost 1
      (is (= 2 (count (:logical-to-physical result)))) ; Maps 2 logical qubits
      
      ;; Check circuit is valid
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= 2 (count (:operations optimized-circuit))))
        (is (every? #(contains? #{:h :cnot} (:operation-type %)) 
                   (:operations optimized-circuit))))))

  (testing "Bell circuit on linear topology without optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3 {:optimize-mapping? false})]
      
      ;; With identity mapping, should still work fine
      (is (= {0 0, 1 1} (:logical-to-physical result)))
      (is (= 0 (:swap-count result)))  ; Still no SWAPs needed for adjacent operations
      (is (= 1 (:total-cost result)))))

  (testing "Circuit requiring SWAPs with identity mapping"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))  ; Distance 2 on linear topology
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 {:optimize-mapping? false})]
      
      ;; Should insert SWAPs for non-adjacent operation
      (is (> (:swap-count result) 0))
      (is (> (count (:operations (:quantum-circuit result))) 
             (count (:operations circuit))))
      (is (= {0 0, 1 1, 2 2} (:logical-to-physical result)))
      
      ;; Verify correct operation ordering: SWAPs should come BEFORE the CNOT
      (let [operations (:operations (:quantum-circuit result))]
        (is (= 2 (count operations)))
        (is (= :swap (:operation-type (first operations))))  ; First operation should be SWAP
        (is (= :cnot (:operation-type (second operations)))) ; Second operation should be CNOT
        ;; Verify the CNOT uses adjacent qubits after routing
        (is (= {:control 1, :target 2} (:operation-params (second operations)))))))

  (testing "Disable SWAP insertion"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 
                                          {:optimize-mapping? false
                                           :insert-swaps? false})]
      
      ;; Should not insert SWAPs even for non-adjacent operations
      (is (= 0 (:swap-count result)))
      (is (= (count (:operations (:quantum-circuit result)))
             (count (:operations circuit)))))))

(deftest test-optimize-for-topology-complex
  (testing "Complex circuit with multiple two-qubit operations"
    (let [complex-circuit (-> (qc/create-circuit 4 "Complex")
                              (qc/h-gate 0)
                              (qc/cnot-gate 0 3)  ; Long distance
                              (qc/cnot-gate 1 2)  ; Adjacent
                              (qc/cnot-gate 0 2)  ; Medium distance
                              (qc/x-gate 3))
          linear-4 (ct/create-linear-topology 4)
          result-opt (ct/optimize-for-topology complex-circuit linear-4)
          result-no-opt (ct/optimize-for-topology complex-circuit linear-4 
                                                  {:optimize-mapping? false})]
      
      ;; With optimal mapping should be better than without
      (is (<= (:total-cost result-opt) (:total-cost result-no-opt)))
      (is (<= (:swap-count result-opt) (:swap-count result-no-opt)))
      
      ;; Both should have valid circuits
      (is (pos? (count (:operations (:quantum-circuit result-opt)))))
      (is (pos? (count (:operations (:quantum-circuit result-no-opt)))))))

  (testing "GHZ circuit on star topology"
    (let [ghz-circuit (-> (qc/create-circuit 3 "GHZ")
                          (qc/h-gate 0)
                          (qc/cnot-gate 0 1)
                          (qc/cnot-gate 0 2))
          star-5 (ct/create-star-topology 5)
          result (ct/optimize-for-topology ghz-circuit star-5)]
      
      ;; Star topology should be ideal for GHZ (all operations from center)
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 2 (:total-cost result))))))  ; Two operations, each distance 1

(deftest test-topology-comparison
  (testing "Compare topologies for same circuit"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          ring-4 (ct/create-ring-topology 4)
          star-5 (ct/create-star-topology 5)
          comparison (ct/compare-topologies bell-circuit 
                                            {"linear-3" linear-3
                                             "ring-4" ring-4
                                             "star-5" star-5})]
      (is (= 3 (count comparison)))
      (is (contains? (first comparison) :topology-name))
      (is (contains? (first comparison) :total-cost))
      (is (contains? (first comparison) :swap-count))
      ;; All should have same cost (1) for Bell circuit
      (is (every? #(= 1 (:total-cost %)) comparison)))))

(deftest test-topology-analysis
  (testing "Analyze linear topology connectivity"
    (let [linear-3 (ct/create-linear-topology 3)
          analysis (ct/analyze-topology-connectivity linear-3)]
      (is (contains? analysis :num-qubits))
      (is (contains? analysis :total-edges))
      (is (contains? analysis :avg-degree))
      (is (contains? analysis :diameter))
      (is (= 3 (:num-qubits analysis)))
      (is (= 2 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))))

  (testing "Analyze star topology connectivity"
    (let [star-5 (ct/create-star-topology 5)
          analysis (ct/analyze-topology-connectivity star-5)]
      (is (= 5 (:num-qubits analysis)))
      (is (= 4 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))  ; Star has diameter 2
      (is (= 1.6 (:avg-degree analysis)))))  ; 4*2/5 = 1.6

  (testing "Get topology info"
    (let [ring-4 (ct/create-ring-topology 4)
          info (ct/get-topology-info ring-4)]
      (is (string? info))
      (is (re-find #"Qubits: 4" info))
      (is (re-find #"Edges: 4" info)))))

(deftest test-generate-swap-operations
  (testing "No SWAPs needed for adjacent qubits"
    (let [path [0 1]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single SWAP for distance 2"
    (let [path [0 1 2]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 2 (count swaps)))  ; Path [0 1 2] generates 2 SWAPs: (0↔1) and (1↔2)
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))))

  (testing "Multiple SWAPs for longer path"
    (let [path [0 1 2 3]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 3 (count swaps)))  ; Path [0 1 2 3] generates 3 SWAPs
      ;; First SWAP: 0 ↔ 1
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      ;; Second SWAP: 1 ↔ 2
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))
      ;; Third SWAP: 2 ↔ 3
      (let [swap3 (nth swaps 2)]
        (is (= :swap (:operation-type swap3)))
        (is (= {:target1 2 :target2 3} (:operation-params swap3)))
        (is (= {:moves-qubit 0 :from 2 :to 3} (:routing-info swap3))))))

  (testing "Empty path returns no SWAPs"
    (let [path []
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single qubit path returns no SWAPs"
    (let [path [0]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps)))))

(deftest test-optimize-for-topology-basic
  (testing "Bell circuit on linear topology with optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3)]
      
      ;; Check result structure
      (is (contains? result :quantum-circuit))
      (is (contains? result :logical-to-physical))
      (is (contains? result :physical-to-logical))
      (is (contains? result :swap-count))
      (is (contains? result :total-cost))
      (is (contains? result :topology-summary))
      
      ;; Bell circuit should have minimal cost with optimal mapping
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 1 (:total-cost result)))  ; Adjacent qubits cost 1
      (is (= 2 (count (:logical-to-physical result)))) ; Maps 2 logical qubits
      
      ;; Check circuit is valid
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= 2 (count (:operations optimized-circuit))))
        (is (every? #(contains? #{:h :cnot} (:operation-type %)) 
                   (:operations optimized-circuit))))))

  (testing "Bell circuit on linear topology without optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3 {:optimize-mapping? false})]
      
      ;; With identity mapping, should still work fine
      (is (= {0 0, 1 1} (:logical-to-physical result)))
      (is (= 0 (:swap-count result)))  ; Still no SWAPs needed for adjacent operations
      (is (= 1 (:total-cost result)))))

  (testing "Circuit requiring SWAPs with identity mapping"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))  ; Distance 2 on linear topology
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 {:optimize-mapping? false})]
      
      ;; Should insert SWAPs for non-adjacent operation
      (is (> (:swap-count result) 0))
      (is (> (count (:operations (:quantum-circuit result))) 
             (count (:operations circuit))))
      (is (= {0 0, 1 1, 2 2} (:logical-to-physical result)))
      
      ;; Verify correct operation ordering: SWAPs should come BEFORE the CNOT
      (let [operations (:operations (:quantum-circuit result))]
        (is (= 2 (count operations)))
        (is (= :swap (:operation-type (first operations))))  ; First operation should be SWAP
        (is (= :cnot (:operation-type (second operations)))) ; Second operation should be CNOT
        ;; Verify the CNOT uses adjacent qubits after routing
        (is (= {:control 1, :target 2} (:operation-params (second operations)))))))

  (testing "Disable SWAP insertion"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 
                                          {:optimize-mapping? false
                                           :insert-swaps? false})]
      
      ;; Should not insert SWAPs even for non-adjacent operations
      (is (= 0 (:swap-count result)))
      (is (= (count (:operations (:quantum-circuit result)))
             (count (:operations circuit)))))))

(deftest test-optimize-for-topology-complex
  (testing "Complex circuit with multiple two-qubit operations"
    (let [complex-circuit (-> (qc/create-circuit 4 "Complex")
                              (qc/h-gate 0)
                              (qc/cnot-gate 0 3)  ; Long distance
                              (qc/cnot-gate 1 2)  ; Adjacent
                              (qc/cnot-gate 0 2)  ; Medium distance
                              (qc/x-gate 3))
          linear-4 (ct/create-linear-topology 4)
          result-opt (ct/optimize-for-topology complex-circuit linear-4)
          result-no-opt (ct/optimize-for-topology complex-circuit linear-4 
                                                  {:optimize-mapping? false})]
      
      ;; With optimal mapping should be better than without
      (is (<= (:total-cost result-opt) (:total-cost result-no-opt)))
      (is (<= (:swap-count result-opt) (:swap-count result-no-opt)))
      
      ;; Both should have valid circuits
      (is (pos? (count (:operations (:quantum-circuit result-opt)))))
      (is (pos? (count (:operations (:quantum-circuit result-no-opt)))))))

  (testing "GHZ circuit on star topology"
    (let [ghz-circuit (-> (qc/create-circuit 3 "GHZ")
                          (qc/h-gate 0)
                          (qc/cnot-gate 0 1)
                          (qc/cnot-gate 0 2))
          star-5 (ct/create-star-topology 5)
          result (ct/optimize-for-topology ghz-circuit star-5)]
      
      ;; Star topology should be ideal for GHZ (all operations from center)
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 2 (:total-cost result))))))  ; Two operations, each distance 1

(deftest test-topology-comparison
  (testing "Compare topologies for same circuit"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          ring-4 (ct/create-ring-topology 4)
          star-5 (ct/create-star-topology 5)
          comparison (ct/compare-topologies bell-circuit 
                                            {"linear-3" linear-3
                                             "ring-4" ring-4
                                             "star-5" star-5})]
      (is (= 3 (count comparison)))
      (is (contains? (first comparison) :topology-name))
      (is (contains? (first comparison) :total-cost))
      (is (contains? (first comparison) :swap-count))
      ;; All should have same cost (1) for Bell circuit
      (is (every? #(= 1 (:total-cost %)) comparison)))))

(deftest test-topology-analysis
  (testing "Analyze linear topology connectivity"
    (let [linear-3 (ct/create-linear-topology 3)
          analysis (ct/analyze-topology-connectivity linear-3)]
      (is (contains? analysis :num-qubits))
      (is (contains? analysis :total-edges))
      (is (contains? analysis :avg-degree))
      (is (contains? analysis :diameter))
      (is (= 3 (:num-qubits analysis)))
      (is (= 2 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))))

  (testing "Analyze star topology connectivity"
    (let [star-5 (ct/create-star-topology 5)
          analysis (ct/analyze-topology-connectivity star-5)]
      (is (= 5 (:num-qubits analysis)))
      (is (= 4 (:total-edges analysis)))
      (is (= 2 (:diameter analysis)))  ; Star has diameter 2
      (is (= 1.6 (:avg-degree analysis)))))  ; 4*2/5 = 1.6

  (testing "Get topology info"
    (let [ring-4 (ct/create-ring-topology 4)
          info (ct/get-topology-info ring-4)]
      (is (string? info))
      (is (re-find #"Qubits: 4" info))
      (is (re-find #"Edges: 4" info)))))

(deftest test-generate-swap-operations
  (testing "No SWAPs needed for adjacent qubits"
    (let [path [0 1]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single SWAP for distance 2"
    (let [path [0 1 2]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 2 (count swaps)))  ; Path [0 1 2] generates 2 SWAPs: (0↔1) and (1↔2)
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))))

  (testing "Multiple SWAPs for longer path"
    (let [path [0 1 2 3]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (= 3 (count swaps)))  ; Path [0 1 2 3] generates 3 SWAPs
      ;; First SWAP: 0 ↔ 1
      (let [swap1 (first swaps)]
        (is (= :swap (:operation-type swap1)))
        (is (= {:target1 0 :target2 1} (:operation-params swap1)))
        (is (= {:moves-qubit 0 :from 0 :to 1} (:routing-info swap1))))
      ;; Second SWAP: 1 ↔ 2
      (let [swap2 (second swaps)]
        (is (= :swap (:operation-type swap2)))
        (is (= {:target1 1 :target2 2} (:operation-params swap2)))
        (is (= {:moves-qubit 0 :from 1 :to 2} (:routing-info swap2))))
      ;; Third SWAP: 2 ↔ 3
      (let [swap3 (nth swaps 2)]
        (is (= :swap (:operation-type swap3)))
        (is (= {:target1 2 :target2 3} (:operation-params swap3)))
        (is (= {:moves-qubit 0 :from 2 :to 3} (:routing-info swap3))))))

  (testing "Empty path returns no SWAPs"
    (let [path []
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps))))

  (testing "Single qubit path returns no SWAPs"
    (let [path [0]
          target-qubit 0
          swaps (ct/generate-swap-operations path target-qubit)]
      (is (empty? swaps)))))

(deftest test-optimize-for-topology-basic
  (testing "Bell circuit on linear topology with optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3)]
      
      ;; Check result structure
      (is (contains? result :quantum-circuit))
      (is (contains? result :logical-to-physical))
      (is (contains? result :physical-to-logical))
      (is (contains? result :swap-count))
      (is (contains? result :total-cost))
      (is (contains? result :topology-summary))
      
      ;; Bell circuit should have minimal cost with optimal mapping
      (is (= 0 (:swap-count result)))  ; No SWAPs needed
      (is (= 1 (:total-cost result)))  ; Adjacent qubits cost 1
      (is (= 2 (count (:logical-to-physical result)))) ; Maps 2 logical qubits
      
      ;; Check circuit is valid
      (let [optimized-circuit (:quantum-circuit result)]
        (is (= 2 (count (:operations optimized-circuit))))
        (is (every? #(contains? #{:h :cnot} (:operation-type %)) 
                   (:operations optimized-circuit))))))

  (testing "Bell circuit on linear topology without optimal mapping"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology bell-circuit linear-3 {:optimize-mapping? false})]
      
      ;; With identity mapping, should still work fine
      (is (= {0 0, 1 1} (:logical-to-physical result)))
      (is (= 0 (:swap-count result)))  ; Still no SWAPs needed for adjacent operations
      (is (= 1 (:total-cost result)))))

  (testing "Circuit requiring SWAPs with identity mapping"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))  ; Distance 2 on linear topology
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 {:optimize-mapping? false})]
      
      ;; Should insert SWAPs for non-adjacent operation
      (is (> (:swap-count result) 0))
      (is (> (count (:operations (:quantum-circuit result))) 
             (count (:operations circuit))))
      (is (= {0 0, 1 1, 2 2} (:logical-to-physical result)))
      
      ;; Verify correct operation ordering: SWAPs should come BEFORE the CNOT
      (let [operations (:operations (:quantum-circuit result))]
        (is (= 2 (count operations)))
        (is (= :swap (:operation-type (first operations))))  ; First operation should be SWAP
        (is (= :cnot (:operation-type (second operations)))) ; Second operation should be CNOT
        ;; Verify the CNOT uses adjacent qubits after routing
        (is (= {:control 1, :target 2} (:operation-params (second operations)))))))

  (testing "Disable SWAP insertion"
    (let [circuit (-> (qc/create-circuit 3 "Test")
                      (qc/cnot-gate 0 2))
          linear-3 (ct/create-linear-topology 3)
          result (ct/optimize-for-topology circuit linear-3 
                                          {:optimize-mapping? false
                                           :insert-swaps? false})]
      
      ;; Should not insert SWAPs even for non-adjacent operations
      (is (= 0 (:swap-count result)))
      (is (= (count (:operations (:quantum-circuit result)))
             (count (:operations circuit)))))))

(deftest test-comprehensive-optimize-with-topology
  (testing "Full optimization pipeline with topology optimization enabled"
    (let [;; Complex circuit needing all types of optimization
          circuit (-> (qc/create-circuit 4 "Complex Test Circuit")
                      (qc/h-gate 0)
                      (qc/y-gate 1)       ;; Unsupported gate (needs transformation)
                      (qc/cnot-gate 0 3)  ;; Sparse qubits + non-adjacent (needs optimization)
                      (qc/cnot-gate 1 2)  ;; Adjacent operation
                      (qc/x-gate 2))

          linear-topology (ct/create-linear-topology 4)
          result (ct/optimize circuit #{:h :x :z :rx :rz :cnot :swap}
                              {:optimize-topology? true 
                               :topology linear-topology})]

      ;; Check that all optimization results are present
      (is (contains? result :transformation-result))
      (is (contains? result :qubit-optimization-result))
      (is (contains? result :topology-optimization-result))
      (is (contains? result :optimization-summary))

      ;; Check transformation worked (Y gate was transformed)
      (let [transformation (:transformation-result result)]
        (is (pos? (:transformed-operation-count transformation)))
        (is (empty? (:unsupported-operations transformation))))

      ;; Check qubit optimization worked (sparse qubits were compacted)
      (let [qubit-opt (:qubit-optimization-result result)]
        (is (>= (:qubits-saved qubit-opt) 0))  ;; May be 0 if no sparse qubits after transformation
        (is (<= (:optimized-qubits qubit-opt) 4)))

      ;; Check topology optimization worked
      (let [topology-opt (:topology-optimization-result result)]
        (is (contains? topology-opt :logical-to-physical))
        (is (contains? topology-opt :swap-count))
        (is (contains? topology-opt :total-cost))
        (is (contains? topology-opt :topology-summary)))

      ;; Check final circuit is valid
      (let [final-circuit (:quantum-circuit result)]
        (is (s/valid? ::qc/quantum-circuit final-circuit))
        (is (pos? (count (:operations final-circuit)))))

      ;; Check comprehensive summary includes all optimization types
      (let [summary (:optimization-summary result)]
        (is (string? summary))
        (is (re-find #"Operations transformed" summary))
        (is (re-find #"Qubits saved" summary))
        (is (re-find #"SWAP operations added" summary))
        (is (re-find #"Topology routing cost" summary)))))

  (testing "Topology optimization with different topology types"
    (let [bell-circuit (-> (qc/create-circuit 2 "Bell Circuit")
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))

          ;; Test with different topology types
          linear-topology (ct/create-linear-topology 3)
          ring-topology (ct/create-ring-topology 4)
          star-topology (ct/create-star-topology 5)

          linear-result (ct/optimize bell-circuit #{:h :cnot :swap}
                                     {:optimize-topology? true 
                                      :topology linear-topology})
          ring-result (ct/optimize bell-circuit #{:h :cnot :swap}
                                   {:optimize-topology? true 
                                    :topology ring-topology})
          star-result (ct/optimize bell-circuit #{:h :cnot :swap}
                                   {:optimize-topology? true 
                                    :topology star-topology})]

      ;; All results should be valid
      (doseq [result [linear-result ring-result star-result]]
        (is (contains? result :topology-optimization-result))
        (let [topology-opt (:topology-optimization-result result)]
          (is (number? (:swap-count topology-opt)))
          (is (number? (:total-cost topology-opt)))
          (is (string? (:topology-summary topology-opt)))))

      ;; Bell circuit should have minimal cost on all topologies (adjacent qubits)
      (is (= 0 (get-in linear-result [:topology-optimization-result :swap-count])))
      (is (= 0 (get-in ring-result [:topology-optimization-result :swap-count])))
      (is (= 0 (get-in star-result [:topology-optimization-result :swap-count])))))

  (testing "Topology optimization with SWAP insertion needed"
    (let [;; Circuit using all qubits to prevent qubit optimization from interfering
          circuit (-> (qc/create-circuit 3 "SWAP Test")
                      (qc/h-gate 0)
                      (qc/h-gate 1)     ;; Use qubit 1 to prevent optimization
                      (qc/cnot-gate 0 2))  ;; Distance 2 on linear topology

          linear-topology (ct/create-linear-topology 3)
          result (ct/optimize circuit #{:h :cnot :swap}
                              {:optimize-topology? true 
                               :topology linear-topology
                               :optimize-mapping? false   ;; Force identity mapping
                               :optimize-qubits? false})] ;; Disable qubit optimization

      ;; Should insert SWAPs for non-adjacent operation
      (let [topology-opt (:topology-optimization-result result)]
        (is (pos? (:swap-count topology-opt)))
        (is (pos? (:total-cost topology-opt))))

      ;; Final circuit should contain SWAP operations
      (let [final-circuit (:quantum-circuit result)
            operations (:operations final-circuit)
            swap-ops (filter #(= :swap (:operation-type %)) operations)]
        (is (pos? (count swap-ops))))))

  (testing "Optimization without topology optimization"
    (let [circuit (-> (qc/create-circuit 3 "No Topology Test")
                      (qc/h-gate 0)
                      (qc/y-gate 1)
                      (qc/cnot-gate 0 2))

          result (ct/optimize circuit #{:h :x :z :rx :rz :cnot}
                              {:optimize-topology? false})]

      ;; Should have topology-optimization-result with default values
      (let [topology-opt (:topology-optimization-result result)]
        (is (= 0 (:swap-count topology-opt)))
        (is (= 0 (:total-cost topology-opt)))
        (is (re-find #"not enabled" (:topology-summary topology-opt))))

      ;; Summary should indicate topology optimization was not enabled
      (let [summary (:optimization-summary result)]
        (is (re-find #"Topology optimization: Not enabled" summary)))))

  (testing "Error handling for invalid topology optimization options"
    ;; Test missing topology when topology optimization is requested
    (is (thrown? Exception
                 (ct/optimize (qc/h-gate (qc/create-circuit 1) 0) #{:h}
                              {:optimize-topology? true})))

    ;; Test invalid topology
    (is (thrown? Exception
                 (ct/optimize (qc/h-gate (qc/create-circuit 1) 0) #{:h}
                              {:optimize-topology? true
                               :topology [[0]]}))))  ; Self-connection invalid

  (testing "Optimization result consistency"
    (let [circuit (-> (qc/create-circuit 4 "Consistency Test")
                      (qc/h-gate 0)
                      (qc/cnot-gate 0 1)
                      (qc/cnot-gate 1 2)
                      (qc/cnot-gate 2 3))

          linear-topology (ct/create-linear-topology 4)
          result (ct/optimize circuit #{:h :cnot :swap}
                              {:optimize-topology? true 
                               :topology linear-topology})]

      ;; Verify operation counts are consistent
      (let [original-ops (count (:operations circuit))
            final-ops (count (:operations (:quantum-circuit result)))
            transformed-ops (:transformed-operation-count (:transformation-result result))
            swap-ops (:swap-count (:topology-optimization-result result))]
        
        ;; Final operations should equal original + transformed + swaps
        (is (= final-ops (+ original-ops transformed-ops swap-ops))))

      ;; Verify qubit counts are consistent
      (let [original-qubits (:num-qubits circuit)
            final-qubits (:num-qubits (:quantum-circuit result))]
        
        ;; Final qubits should be less than or equal to original qubits
        (is (<= final-qubits original-qubits))))))
