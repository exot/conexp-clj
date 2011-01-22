;; Copyright (c) Daniel Borchmann. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns conexp.tests.layouts.util
  (:use conexp.base
        conexp.fca.contexts
        conexp.fca.lattices
        conexp.layouts.util
        conexp.layouts.base)
  (:use clojure.test))

(defvar test-lattices
  [(make-lattice [1 2 3 4 5] <=),
   (concept-lattice (rand-context 10 10 0.7))])

(defvar test-layouts
  [(make-layout {1 [0 0],
                 2 [1 2],
                 3 [10 20]}
                #{[1 2] [2 3]}),
   (make-layout {1 [0 0],
                 2 [-1 1],
                 3 [1 1],
                 4 [0 2]}
                #{[1 2] [1 3] [2 4] [3 4]})])

;;;

(deftest test-enclosing-rectangle
  (is (thrown? IllegalArgumentException
               (enclosing-rectangle [])))
  (is (= (enclosing-rectangle [[1 2] [2 3] [3 4]])
         [1 2 3 4])))

(deftest test-scale-layout
  (with-testing-data [layout test-layouts]
    (is (= (enclosing-rectangle (vals (positions (scale-layout [0 0]
                                                               [100 100]
                                                               layout))))
           [0 0 100 100]))))

;;;

(deftest test-layers
  (with-testing-data [lattice test-lattices]
    (let [<=     (order lattice),
          layers (layers lattice)]
      (every? (fn [[lower upper]]
                (forall [x lower]
                  (exists [y upper]
                    (<= x y))))
              (partition 2 1 layers)))))

(deftest test-edges
  (with-testing-data [lattice test-lattices]
    (let [edges (edges lattice)]
      (forall [a (base-set lattice),
               b (base-set lattice)]
        (<=> (contains? edges [a b])
             (directly-neighboured? lattice a b))))))

(deftest test-top-down-elements-in-layout
  (with-testing-data [layout test-layouts]
    (let [index (zipmap (top-down-elements-in-layout layout)
                        (range))]
      (forall [[a b] (connections layout)]
        (< (index b) (index a))))))

;;;

(deftest test-fit-layout-to-grid
  (with-testing-data [layout test-layouts]
    (let [positions (vals (positions (fit-layout-to-grid layout
                                                         [1/2 -13/10]
                                                         2/5
                                                         7/10)))]
      (forall [[a b] positions,
               [c d] positions]
       (and (integer? (/ (- a c) 2/5))
            (integer? (/ (- b d) 7/10)))))))

(deftest test-discretize-layout
  (with-testing-data [layout test-layouts]
    (let [[xmin ymin xmax ymax] (enclosing-rectangle (vals (positions layout))),
          origin [xmin ymin],
          layout (discretize-layout layout 3 7),
          x_pad  (/ (- xmax xmin) 3),
          y_pad  (/ (- ymax ymin) 7)]
      (forall [[a b] (vals (positions layout))]
        (and (integer? (/ (- a xmin) x_pad))
             (integer? (/ (- b ymin) y_pad)))))))

;;;

nil
