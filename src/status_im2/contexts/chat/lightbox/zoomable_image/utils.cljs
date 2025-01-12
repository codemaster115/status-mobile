(ns status-im2.contexts.chat.lightbox.zoomable-image.utils
  (:require
    [clojure.string :as string]
    [react-native.orientation :as orientation]
    [react-native.platform :as platform]
    [status-im2.contexts.chat.lightbox.zoomable-image.constants :as c]
    [status-im2.contexts.chat.lightbox.animations :as anim]
    [utils.re-frame :as rf]))

;;; Helpers
(defn center-x
  [{:keys [pinch-x pinch-x-start pan-x pan-x-start]} exit?]
  (let [duration (if exit? 100 c/default-duration)]
    (anim/animate pinch-x c/init-offset duration)
    (anim/set-val pinch-x-start c/init-offset)
    (anim/animate pan-x c/init-offset duration)
    (anim/set-val pan-x-start c/init-offset)))

(defn center-y
  [{:keys [pinch-y pinch-y-start pan-y pan-y-start]} exit?]
  (let [duration (if exit? 100 c/default-duration)]
    (anim/animate pinch-y c/init-offset duration)
    (anim/set-val pinch-y-start c/init-offset)
    (anim/animate pan-y c/init-offset duration)
    (anim/set-val pan-y-start c/init-offset)))

(defn reset-values
  [exit? animations {:keys [focal-x focal-y]}]
  (center-x animations exit?)
  (center-y animations exit?)
  (reset! focal-x nil)
  (reset! focal-y nil))

(defn rescale-image
  [value
   exit?
   {:keys [x-threshold-scale y-threshold-scale]}
   {:keys [scale saved-scale] :as animations}
   {:keys [pan-x-enabled? pan-y-enabled?] :as props}]
  (when (= value c/min-scale)
    (reset-values exit? animations props))
  (anim/animate scale value (if exit? 100 c/default-duration))
  (anim/set-val saved-scale value)
  (reset! pan-x-enabled? (> value x-threshold-scale))
  (reset! pan-y-enabled? (> value y-threshold-scale)))

;;; Handlers
(defn handle-orientation-change
  [curr-orientation
   focused?
   {:keys [landscape-scale-val x-threshold-scale y-threshold-scale]}
   {:keys [rotate rotate-scale scale] :as animations}
   {:keys [pan-x-enabled? pan-y-enabled?]}]
  (if focused?
    (cond
      (= curr-orientation orientation/landscape-left)
      (do
        (anim/animate rotate "90deg")
        (anim/animate rotate-scale landscape-scale-val))
      (= curr-orientation orientation/landscape-right)
      (do
        (anim/animate rotate "-90deg")
        (anim/animate rotate-scale landscape-scale-val))
      (= curr-orientation orientation/portrait)
      (do
        (anim/animate rotate c/init-rotation)
        (anim/animate rotate-scale c/min-scale)))
    (cond
      (= curr-orientation orientation/landscape-left)
      (do
        (anim/set-val rotate "90deg")
        (anim/set-val rotate-scale landscape-scale-val))
      (= curr-orientation orientation/landscape-right)
      (do
        (anim/set-val rotate "-90deg")
        (anim/set-val rotate-scale landscape-scale-val))
      (= curr-orientation orientation/portrait)
      (do
        (anim/set-val rotate c/init-rotation)
        (anim/set-val rotate-scale c/min-scale))))
  (center-x animations false)
  (center-y animations false)
  (reset! pan-x-enabled? (> (anim/get-val scale) x-threshold-scale))
  (reset! pan-y-enabled? (> (anim/get-val scale) y-threshold-scale)))

;; On ios, when attempting to navigate back while zoomed in, the shared-element transition animation
;; doesn't execute properly, so we need to zoom out first
(defn handle-exit-lightbox-signal
  [exit-lightbox-signal index scale rescale set-full-height?]
  (when (= exit-lightbox-signal index)
    (reset! set-full-height? false)
    (if (> scale c/min-scale)
      (do
        (rescale c/min-scale true)
        (js/setTimeout #(rf/dispatch [:navigate-back]) 70))
      (rf/dispatch [:navigate-back]))
    (js/setTimeout #(rf/dispatch [:chat.ui/exit-lightbox-signal nil]) 500)))

(defn handle-zoom-out-signal
  "Zooms out when pressing on another photo from the small bottom list"
  [zoom-out-signal index scale rescale]
  (when (and (= zoom-out-signal index) (> scale c/min-scale))
    (rescale c/min-scale true)))

;;; Dimensions
(defn get-dimensions
  "Calculates all required dimensions. Dimensions calculations are different on iOS and Android because landscape
   mode is implemented differently.On Android, we just need to resize the content, and the OS takes care of the
   animations. On iOS, we need to animate the content ourselves in code"
  [pixels-width pixels-height curr-orientation
   {:keys [window-width screen-width screen-height]}]
  (let [landscape?            (string/includes? curr-orientation orientation/landscape)
        portrait-image-width  window-width
        portrait-image-height (* pixels-height (/ window-width pixels-width))
        landscape-image-width (* pixels-width (/ window-width pixels-height))
        width                 (if landscape? landscape-image-width portrait-image-width)
        height                (if landscape? screen-height portrait-image-height)
        container-width       (if platform/ios? window-width width)
        container-height      (if (and platform/ios? landscape?) landscape-image-width height)]
    ;; width and height used in style prop
    {:image-width         (if platform/ios? portrait-image-width width)
     :image-height        (if platform/ios? portrait-image-height height)
     ;; container width and height, also used in animations calculations
     :width               container-width
     :height              container-height
     ;; screen width and height used in calculations, and depends on platform
     :screen-width        screen-width
     :screen-height       screen-height
     :x-threshold-scale   (/ screen-width (min screen-width container-width))
     :y-threshold-scale   (/ screen-height (min screen-height container-height))
     :landscape-scale-val (/ portrait-image-width portrait-image-height)}))


;;; MATH
(defn get-max-offset
  [size screen-size scale]
  (/ (- (* size (min scale c/max-scale))
        screen-size)
     2))

(defn get-scale-diff
  [new-scale saved-scale]
  (- (dec new-scale)
     (dec saved-scale)))

(defn get-double-tap-offset
  [size screen-size focal]
  (let [center        (/ size 2)
        target-point  (* (- center focal) c/double-tap-scale)
        max-offset    (get-max-offset size screen-size c/double-tap-scale)
        translate-val (min (Math/abs target-point) max-offset)]
    (if (neg? target-point) (- translate-val) translate-val)))

(defn get-pinch-position
  [scale-diff size focal]
  (* (- (/ size 2) focal) scale-diff))

(defn get-focal
  [focal size screen-size]
  (let [min (/ (- screen-size size) 2)
        max (+ min size)]
    (if (or (> focal max) (< focal min))
      (/ screen-size 2)
      focal)))
