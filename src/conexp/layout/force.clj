(ns conexp.layout.force
  (:use conexp.base
        conexp.fca.lattices
	conexp.layout.util
	[conexp.math.util :only (with-doubles, partial-derivatives)]
	conexp.math.optimize
	clojure.contrib.pprint))

;;; Force layout as described by C. Zschalig

;; Helpers

(defn- square
  "Squares."
  [x]
  (with-doubles [x]
    (* x x)))

(defn- line-length-squared
  "Returns the square of the length of the line between [x_1, y_1] and [x_2, y_2]."
  [[x_1, y_1] [x_2, y_2]]
  (with-doubles [x_1, y_1, x_2, y_2]
    (+ (square (- x_1 x_2))
       (square (- y_1 y_2)))))

(defn- distance
  "Returns distance of two points."
  [[x_1 y_1] [x_2 y_2]]
  (with-doubles [x_1 y_1 x_2 y_2]
    (Math/sqrt (+ (square (- x_1 x_2)) (square (- y_1 y_2))))))

(defmacro sum
  "Sums up all values of bindings obtained with expr. See for."
  [bindings expr]
  `(reduce +
	   (double 0)
	   (for ~bindings
	     (double ~expr))))

(defn- rotate-pi2
  "Rotates given vector by pi/2 in positive direction."
  [[a b]]
  [(- b) a])

(defn- unit-vector
  "Returns unit vector between first and second point. Returns
  [0.0 0.0] when given zero vector."
  [[x_1 y_1] [x_2 y_2]]
  (with-doubles [x_1 y_1 x_2 y_2]
    (let [length (distance [x_1 y_1] [x_2 y_2])]
      (if (zero? length)
	[0.0 0.0]
	[(/ (- x_2 x_1) length), (/ (- y_2 y_1) length)]))))


;; Repulsive Energy and Force

(defn- node-line-distance
  "Returns the distance from node to the line between [x_1, y_1] and [x_2, y_2]."
  [[x, y] [[x_1, y_1] [x_2, y_2]]]
  (with-doubles [x, y, x_1, y_1, x_2, y_2]
    (if (and (= x_1 x_2) (= y_1 y_2))
      (distance [x, y] [x_1, y_1])
      (let [;; position of projection of [x y] onto the line, single coordinate
	    r (/ (+ (* (- x x_1)
		       (- x_2 x_1))
		    (* (- y y_1)
		       (- y_2 y_1)))
		 (+ (square (- x_2 x_1))
		    (square (- y_2 y_1)))),
	    r (min (max r 0) 1)]
	(distance [x, y] [(+ x_1 (* r (- x_2 x_1))),
			  (+ y_1 (* r (- y_2 y_1)))])))))

(defn- repulsive-energy
  "Computes the repulsive energy of the given layout."
  [[node-positions node-connections]]
  (try
   (let [edges (map (fn [[x y]]
		      [x y (get node-positions x) (get node-positions y)])
		    node-connections)]
     (sum [[v pos-v] node-positions,
	   [x y pos-x pos-y] edges
	   :when (not (contains? #{x, y} v))]
       (/ 1.0
	  (double (node-line-distance pos-v [pos-x, pos-y])))))
   (catch Exception e
     Double/POSITIVE_INFINITY)))

(defn- node-line-distance-derivative
  "Computes partial derivative of node-line-distance between [x y] and
  the edge through [x_1 y_1] and [x_2 y_2], after n_i, given part only."
  [[x y] [[x_1 y_1] [x_2 y_2]] n_i part lattice]
  (with-doubles [x y x_1 y_1 x_2 y_2]
    (let [r (/ (+ (* (- x x_1) (- x_2 x_1))
		  (* (- y y_1) (- y_2 y_1)))
	       (+ (square (- x_2 x_1)) (square (- y_2 y_1)))),
	  case-1 (<= r 0),
	  case-2 (<= 1 r),
	  case-3 (< 0 r 1),

	  order (order lattice),
	  t_1 (order n_i [x y]),
	  t_2 (order n_i [x_1 y_1]),
	  t_3 (order n_i [x_2 y_2]),
	  F_3 (and (not t_1) t_2 (not t_3)),
	  F_4 (and (not t_1) t_2 t_3),
	  F_5 (and t_1 (not t_2) (not t_3)),
	  F_7 (and t_1 t_2 (not t_3))]

      (or (cond
	   case-1 (cond
		   F_3 nil,
		   F_4 nil,
		   F_5 nil)
	   case-2 (cond
		   F_4 nil,
		   F_5 nil,
		   F_7 nil)
	   case-3 (cond
		   F_3 nil,
		   F_4 nil,
		   F_5 nil,
		   F_7 nil))
	  0.0))))			; default value

(defn- repulsive-force
  "Computes given part of repulsive force in layout on the
  inf-irreducible element n_i."
  [[node-positions node-connections] n_i part information]
  (sum [v (keys node-positions),
	[x y] node-connections,
	:when (not (contains? #{x,y} v))]
    (* (/ (square (node-line-distance (node-positions v)
				      [(node-positions x), (node-positions y)])))
       (node-line-distance-derivative v [x y] n_i part (:lattice information)))))

;; Attractive Energy and Force

(defn- attractive-energy
  "Computes the attractive energy of the given layout."
  [[node-positions node-connections]]
  (sum [[x y] node-connections]
       (line-length-squared (node-positions x) (node-positions y))))

(defn- attractive-force
  "Computes given part of attractive force in layout on the
  inf-irreducible element n_i."
  [layout n_i part information]
  (let [lattice (:lattice information),
	order (order lattice),
	pos (first layout),
	edges (second layout)]
    (* 2 (sum [[v_1 v_2] edges,
	       :when (and (order [n_i v_1])
			  (not (order [n_i v_2])))]
	   (- (nth (pos v_1) part) (nth (pos v_2) part))))))

;; Gravitative Energy and Force

(defn- phi
  "Returns the angle between an inf-irreducible node [x_1,y_1]
  and its upper neighbor [x_2, y_2]."
  [[x_1,y_1] [x_2,y_2]]
  (with-doubles [x_1, y_1, x_2, y_2]
    (let [result (Math/atan2 (- y_2 y_1) (- x_2 x_1))]
      (min (max 0 result) Math/PI))))

(defn- gravitative-energy
  "Returns the gravitative energy of the given layout."
  [[node-positions node-connections] information]
  (let [inf-irrs (:inf-irrs information),
	upper-neighbours (:upper-neighbours-of-inf-irrs information),

	phi_0 (double (/ Math/PI (+ 1 (count inf-irrs)))),
	E_0   (double (+ (- phi_0) (- (* (Math/sin phi_0) (Math/cos phi_0))))),
	E_1   (double (+ E_0 Math/PI))]
    (try
     (sum [n inf-irrs]
       (let [phi_n (double (phi (node-positions n)
				(node-positions (upper-neighbours n))))]
	 (cond
	  (<= 0 phi_n phi_0)
	  (+ phi_n
	     (/ (square (Math/sin phi_0))
		(Math/tan phi_n))
	     E_0),

	  (<= (- Math/PI phi_0) phi_n Math/PI)
	  (+ (- phi_n)
	     (/ (- (square (Math/sin phi_0)))
		(Math/tan phi_n))
	     E_1),

	  :else 0)))
     (catch Exception e
       Double/POSITIVE_INFINITY))))

(defn- gravitative-force
  "Computes given part of gravitative force in layout on the
  inf-irreducible element n_i."
  ;; unsure, paper gives contradicting formulas
  [layout n_i part information]
  (let [upper-neighbours (:upper-neighbours-of-inf-irrs information),
	inf-irrs (:inf-irrs information),
	positions (first layout),

	phi_n_i (double (phi (positions n_i) (positions (upper-neighbours n_i)))),
	phi_0 (double (/ Math/PI (+ 1 (count inf-irrs))))]
    (cond
     (<= 0 phi_n_i phi_0)
     (* (nth (rotate-pi2 n_i) part)
        (/ (- (square (Math/sin phi_n_i))
	      (square (Math/sin phi_0)))
	   (square (Math/sin phi_n_i)))),

     (<= phi_0 phi_n_i (- Math/PI phi_0))
     0.0,

     (<= (- Math/PI phi_0) phi_n_i Math/PI)
     (* (nth (rotate-pi2 n_i) part)
	(/ (- (square (Math/sin phi_0))
	      (square (Math/sin phi_n_i)))
	   (square (Math/sin phi_n_i)))))))

;; Overall Energy and Force

(def *repulsive-amount* 500.0)
(def *attractive-amount* 0.005)
(def *gravitative-amount* 100.0)

(defn layout-energy
  "Returns the overall energy of the given layout."
  [layout information]
  (double
   (+ (* *repulsive-amount* (repulsive-energy layout))
      (* *attractive-amount* (attractive-energy layout))
      (* *gravitative-amount* (gravitative-energy layout information)))))

(defn- layout-force
  "Computes overall force component of index n in the inf-irreducible elements."
  [layout inf-irrs n information]
  (let [part (mod n 2),
	n_i (nth inf-irrs (div n 2))]
    (double
     (+ (repulsive-force layout n_i part information)
	(attractive-force layout n_i part information)
	(gravitative-force layout n_i part information)))))

(defn- energy-by-inf-irr-positions
  "Returns a function calculating the energy of an attribute additive
  layout of lattice given by the positions of the infimum irreducible
  elements. seq-of-inf-irrs gives the order of the infimum irreducible
  elements."
  [lattice seq-of-inf-irrs]
  (let [upper-neighbours (hashmap-by-function (fn [n]
						(first (lattice-upper-neighbours lattice n)))
					      seq-of-inf-irrs)]
    (fn [& point-coordinates]
      (let [points (partition 2 point-coordinates),
	    inf-irr-placement (apply hash-map
				     (interleave seq-of-inf-irrs
						 points))]
	(layout-energy (layout-by-placement lattice inf-irr-placement)
		       {:inf-irrs seq-of-inf-irrs,
			:upper-neighbours-of-inf-irrs upper-neighbours})))))

(defn- force-by-inf-irr-positions
  "Returns a function representing the forces on the infimum
  irreducible elements of lattice. The function returned takes as
  input an index representing the coordinate to derive at and returns
  itself a function from a placement of the inf-irreducible elements
  to the force component given by the index. seq-of-inf-irrs gives the
  order of the inf-irreducible elements."
  [lattice seq-of-inf-irrs]
  (let [information {:upper-neighbours-of-inf-irrs
		     (hashmap-by-function (fn [n]
					    (first (lattice-upper-neighbours lattice n)))
					  seq-of-inf-irrs),
		     :inf-irrs seq-of-inf-irrs,
		     :lattice lattice}]
    (fn [index]
      (fn [& point-coordinates]
	(let [points (partition 2 point-coordinates),
	      inf-irr-placement (apply hash-map
				       (interleave seq-of-inf-irrs
						   points))]
	  (layout-force (layout-by-placement lattice inf-irr-placement)
			seq-of-inf-irrs
			index
			information))))))

;;; Force Layout

(defn force-layout
  "Improves given layout with force layout."
  [layout]
  (let [;; compute lattice from layout; this will be changed in the future
	lattice (lattice-from-layout layout),

	;; get positions of inf-irreducibles from layout as starting point
	inf-irrs (seq (lattice-inf-irreducibles lattice)),
	node-positions (first layout),
	inf-irr-points (map node-positions inf-irrs),

	;; move top element to [0,0], needed by layout-by-placement
	[top-x, top-y] (node-positions (lattice-one lattice)),
	inf-irr-points (map (fn [[x y]]
			      [(- x top-x), (- y top-y)])
			    inf-irr-points),

	;; minimize layout energy with above placement as initial value
	energy         (energy-by-inf-irr-positions lattice inf-irrs),
	[new-points, value] (minimize energy
				      (apply concat inf-irr-points)),

	;; make hash
	point-hash     (apply hash-map (interleave inf-irrs
						   (partition 2 new-points))),

	;; compute layout given by the result
	[placement connections] (layout-by-placement lattice point-hash),

	;; move points such that top element is at [top-x, top-y] again
	placement      (apply hash-map
			      (interleave (keys placement)
					  (map (fn [[x y]]
						 [(+ x top-x), (+ y top-y)])
					       (vals placement))))]

    ;; (pprint placement)
    [placement connections]))


;;;

nil
