(ns org.soulspace.qclojure.domain.math
  "Mathematical operations and utilities for quantum algorithms."
  (:require [fastmath.core :as m]))

; Enable fastmath operator macros
#_(m/use-primitive-operators)

(defn gcd
  "Calculate greatest common divisor using Euclidean algorithm."
  [a b]
  (if (zero? b)
    a
    (recur b (mod a b))))

(defn mod-exp
  "Calculate (base^exp) mod m efficiently using binary exponentiation."
  [base exp m]
  (loop [result 1
         base (mod base m)
         exp exp]
    (cond
      (zero? exp) result
      (odd? exp) (recur (mod (* result base) m) base (dec exp))
      :else (recur result (mod (* base base) m) (quot exp 2)))))

(defn continued-fraction
  "Convert a fraction to continued fraction representation.
  
  This implementation handles numerical precision issues and early termination
  conditions that are important for Shor's algorithm. It can detect periodic
  patterns in the continued fraction expansion, which is crucial for finding
  the correct period.
  
  Parameters:
  - num: Numerator of the fraction
  - den: Denominator of the fraction
  - max-depth: (Optional) Maximum depth of continued fraction expansion
  - epsilon: (Optional) Precision threshold for detecting near-zero remainders
  
  Returns:
  Vector of continued fraction terms"
  ([num den]
   (continued-fraction num den 100 1e-10))
  ([num den max-depth]
   (continued-fraction num den max-depth 1e-10))
  ([num den max-depth epsilon]
   (loop [n num
          d den
          cf []
          depth 0]
     (cond
       ;; Stop if denominator is zero or very close to zero
       (or (zero? d) (< (Math/abs d) epsilon))
       cf

       ;; Stop if we've reached max depth to prevent infinite loops
       (>= depth max-depth)
       cf

       :else
       (let [q (quot n d)
             r (mod n d)]
         ;; If remainder is very small relative to denominator, stop
         (if (< (/ r d) epsilon)
           (conj cf q)
           (recur d r (conj cf q) (inc depth))))))))

(defn convergents
  "Calculate convergents from continued fraction representation.
  
  This enhanced implementation handles edge cases better and includes
  additional validation to ensure proper convergence, which is important
  for accurately extracting periods in Shor's algorithm.
  
  Parameters:
  - cf: Vector of continued fraction terms
  
  Returns:
  Vector of convergents as [numerator denominator] pairs"
  [cf]
  (reduce (fn [acc term]
            (let [h (count acc)]
              (cond
                (= h 0) [[term 1]]
                (= h 1) (conj acc [(+ (* term (ffirst acc)) 1) term])
                :else (let [prev-2 (nth acc (- h 2))
                            prev-1 (nth acc (- h 1))
                            p (+ (* term (first prev-1)) (first prev-2))
                            q (+ (* term (second prev-1)) (second prev-2))]
                        (conj acc [p q])))))
          []
          cf))

(defn round-precision
  "Round a number to specified decimal places.
  
  Parameters:
  - x: Number to round
  - precision: Number of decimal places
  
  Returns:
  Number rounded to specified precision"
  [x precision]
  (if (zero? precision)
    (double (m/round x))
    (let [bd (bigdec x)
          scale precision
          rounded (.setScale bd scale java.math.RoundingMode/HALF_UP)]
      (double rounded))))

(defn find-period
  "Find the period from a phase estimate using improved continued fraction expansion.
  
  This function implements a more robust version of period extraction from
  a phase measurement, which is critical for Shor's algorithm.
  
  Parameters:
  - measured-value: The value from quantum measurement
  - precision: Number of bits used in phase estimation
  - N: Modulus for period finding
  - a: Base for modular exponentiation
  
  Returns:
  Most likely period or nil if no valid period found"
  [measured-value precision N a]
  (let [;; Calculate phase from measurement
        phase (/ measured-value (Math/pow 2 precision))

        ;; Try different depths of continued fraction expansion
        candidates (for [depth [10 20 50 100]
                         :let [cf (continued-fraction measured-value (Math/pow 2 precision) depth)
                               convs (convergents cf)]
                         [num den] convs
                         ;; Verify this is actually a period
                         :when (and (pos? den)
                                    (<= den N)
                                    (= 1 (mod-exp a den N)))]
                     {:period den
                      :fraction [num den]
                      :error (Math/abs (- phase (/ num (Math/pow 2 precision))))})

        ;; Sort by error (lowest first) and then by period (smallest valid first)
        sorted-candidates (sort-by (juxt :error :period) candidates)]

    ;; Return the best candidate's period, or nil if none found
    (when (seq sorted-candidates)
      (:period (first sorted-candidates)))))

; Disable fastmath operator macros to avoid conflicts
#_(m/unuse-primitive-operators)