(ns compojure.core
  "A concise syntax for generating Ring handlers."
  (:require [clojure.string :as str])
  (:use clout.core
        compojure.response
        [clojure.core.incubator :only (-?>)]
        [clojure.tools.macro :only (name-with-attributes)]))

(defn- method-matches?
  "True if this request matches the supplied request method."
  [method request]
  (let [request-method (request :request-method)
        form-method    (get-in request [:form-params "_method"])]
    (if (and form-method (= request-method :post))
      (= (str/upper-case (name method)) form-method)
      (= method request-method))))

(defn- if-method
  "Evaluate the handler if the request method matches."
  [method handler]
  (fn [request]
    (cond
      (or (nil? method) (method-matches? method request))
        (handler request)
      (and (= :get method) (= :head (:request-method request)))
        (-?> (handler request)
             (assoc :body nil)))))

(defn- assoc-route-params
  "Associate route parameters with the request map."
  [request params]
  (merge-with merge request {:route-params params, :params params}))

(defn- if-route
  "Evaluate the handler if the route matches the request."
  [route handler]
  (fn [request]
    (if-let [params (route-matches route request)]
      (handler (assoc-route-params request params)))))

(defn- prepare-route
  "Pre-compile the route."
  [route]
  (cond
    (string? route)
      `(route-compile ~route)
    (vector? route)
      `(route-compile
        ~(first route)
        ~(apply hash-map (rest route)))
    :else
      `(if (string? ~route)
         (route-compile ~route)
         ~route)))

(defn- assoc-&-binding [binds req sym]
  (assoc binds sym `(dissoc (:params ~req)
                            ~@(map keyword (keys binds))
                            ~@(map str (keys binds)))))

(defn- assoc-symbol-binding [binds req sym]
  (assoc binds sym `(get-in ~req [:params ~(keyword sym)]
                      (get-in ~req [:params ~(str sym)]))))

(defn- vector-bindings
  "Create the bindings for a vector of parameters."
  [args req]
  (loop [args args, binds {}]
    (if-let [sym (first args)]
      (cond
        (= '& sym)
          (recur (nnext args) (assoc-&-binding binds req (second args)))
        (= :as sym)
          (recur (nnext args) (assoc binds (second args) req))
        (symbol? sym)
          (recur (next args) (assoc-symbol-binding binds req sym))
        :else
          (throw (Exception. (str "Unexpected binding: " sym))))
      (mapcat identity binds))))

(defmacro let-request [[bindings request] & body]
  (if (vector? bindings)
    `(let [~@(vector-bindings bindings request)] ~@body)
    `(let [~bindings ~request] ~@body)))

(defn- compile-route
  "Compile a route in the form (method path & body) into a function."
  [method route bindings body]
  `(#'if-method ~method
     (#'if-route ~(prepare-route route)
       (fn [request#]
         (let-request [~bindings request#]
           (render (do ~@body) request#))))))

(defn routing
  "Apply a list of routes to a Ring request map."
  [request & handlers]
  (some #(% request) handlers))

(defn routes
  "Create a Ring handler by combining several handlers into one."
  [& handlers]
  #(apply routing % handlers))

(defn routes-metadata
  "Extract metadata from list of routes."
  [routes]
  (map (fn [item]
         (let [[method path args & body] item]
           [method path args (meta item)]))
       routes))

(defmacro defroutes
  "Define a Ring handler function from a sequence of routes. The name may be
  optionally be followed by a doc-string and metadata map.

  A metadata entry :routes will be set on the handler function with a
  list of (method path arguments metadata) tuples extracted from each
  route."
  [name & routes]
  (let [[name routes] (name-with-attributes name routes)
        metadata (routes-metadata routes)]
    `(def ~name (with-meta (routes ~@routes)
                  {:routes '~metadata}))))

(defmacro GET "Generate a GET route."
  [path args & body]
  (compile-route :get path args body))

(defmacro POST "Generate a POST route."
  [path args & body]
  (compile-route :post path args body))

(defmacro PUT "Generate a PUT route."
  [path args & body]
  (compile-route :put path args body))

(defmacro DELETE "Generate a DELETE route."
  [path args & body]
  (compile-route :delete path args body))

(defmacro HEAD "Generate a HEAD route."
  [path args & body]
  (compile-route :head path args body))

(defmacro ANY "Generate a route that matches any method."
  [path args & body]
  (compile-route nil path args body))

(defn- remove-suffix [path suffix]
  (subs path 0 (- (count path) (count suffix))))

(defn- wrap-context [handler]
  (fn [request]
    (let [uri     (:uri request)
          path    (:path-info request uri)
          context (or (:context request) "")
          subpath (-> request :route-params :__path-info)]
      (handler
       (-> request
           (assoc :path-info (if (= subpath "") "/" subpath))
           (assoc :context (remove-suffix uri subpath))
           (update-in [:params] dissoc :__path-info)
           (update-in [:route-params] dissoc :__path-info))))))

(defn- context-route [route]
  (let [re-context {:__path-info #"|/.*"}]
    (cond
      (string? route)
       `(route-compile ~(str route ":__path-info") ~re-context)
      (vector? route)
       `(route-compile
         ~(str (first route) ":__path-info")
         ~(merge (apply hash-map (rest route)) re-context)))))

(defmacro context
  [path args & routes]
  `(#'if-route ~(context-route path)
     (#'wrap-context
       (fn [request#]
         (let-request [~args request#]
           (routing request# ~@routes))))))

(defn- middleware-sym [x]
  (symbol (namespace x) (str "wrap-" (name x))))

(defn- ->middleware
  "Turn a keyword into a wrapper function symbol.
  e.g. :test => wrap-test
       (:test x) => (wrap-test x)"
  [kw]
  (cond
    (keyword? kw)
      (middleware-sym kw)
    (and (seq? kw) (keyword? (first kw)))
      (cons (middleware-sym (first kw)) (rest kw))
    :else
      kw))

(defmacro wrap!
  "DEPRECATED: Use '->' instead.
  Wrap a handler in middleware functions. Uses the same syntax as the ->
  macro. Additionally, keywords may be used to denote a leading 'wrap-'.
  e.g.
    (wrap! foo (:session cookie-store))
    => (wrap! foo (wrap-session cookie-store))
    => (def foo (wrap-session foo cookie-store))"
  {:deprecated "0.6.0"}
  [handler & funcs]
  (let [funcs (map ->middleware funcs)]
    `(alter-var-root
       (var ~handler)
       (constantly (-> ~handler ~@funcs)))))
