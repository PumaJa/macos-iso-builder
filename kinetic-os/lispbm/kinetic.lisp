;;; kinetic.lisp - Kinetic OS on-VESC ride-mode manager + local derating
;;; NOT VALIDATED ON HARDWARE. Test on a stand with the wheel off the ground.
;;; Safety logic runs entirely on the VESC; the phone/BLE link is optional.
;;; Protocol: see protocol.md.  Tunables: see README.md.

;; ---------------------------------------------------------------- tunables
(define proto-magic 0x4B)          ; 'K'
(define proto-ver   1)
(define stop-erpm   300.0)         ; |erpm| below this = "stationary" (mode up-shift allowed)
(define loop-dt     0.1)           ; derate loop period, seconds (50..100 ms recommended)
(define derate-floor 0.15)         ; derate never goes below this (VESC's own cutoffs still apply)
(define step-down   0.10)          ; max scale decrease per loop tick (fast)
(define step-up     0.02)          ; max scale increase per loop tick (slow)
(define fet-start   70.0)          ; degC, derate starts
(define fet-end     90.0)          ; degC, derate reaches floor
(define mot-start   90.0)
(define mot-end     120.0)
(define use-mot-temp 1)            ; 0 if motor has no temp sensor
(define v-start     0.0)           ; V, derate starts when vin falls below this. 0 = auto
(define v-end       0.0)           ; V, derate floor at/below this. 0 = auto
;; auto: v-start = boot l-battery-cut-start + 3.0, v-end = boot l-battery-cut-start
(define v-auto-margin 3.0)

;; ---------------------------------------------------------------- helpers
(defun kmin (a b) (if (< a b) a b))
(defun kmax (a b) (if (> a b) a b))
(defun clamp (x lo hi) (kmax lo (kmin x hi)))
(defun repl (l i v)
  (if (= i 0) (cons v (rest l))
      (cons (first l) (repl (rest l) (- i 1) v))))

;; linear derate: 1.0 when x<=start, floor when x>=end
(defun derate-hi (x start end)
  (if (<= x start) 1.0
      (if (>= x end) derate-floor
          (- 1.0 (* (- 1.0 derate-floor) (/ (- x start) (- end start)))))))
;; low-side (voltage): 1.0 when x>=start, floor when x<=end
(defun derate-lo (x start end)
  (if (>= x start) 1.0
      (if (<= x end) derate-floor
          (- 1.0 (* (- 1.0 derate-floor) (/ (- start x) (- start end)))))))

;; ---------------------------------------------------------------- boot ceiling
;; Read once at boot. NEVER raised afterwards.
(define ceil-scale (conf-get 'l-current-max-scale))
(define ceil-watt  (conf-get 'l-watt-max))
(define ceil-icur  (conf-get 'l-in-current-max))
(define ceil-erpm  (conf-get 'l-max-erpm))
(define bat-cut    (conf-get 'l-battery-cut-start))
(if (= v-start 0.0) (define v-start (+ bat-cut v-auto-margin)))
(if (= v-end 0.0)   (define v-end bat-cut))

;; ---------------------------------------------------------------- mode table
;; index: 0 eco, 1 sport, 2 race, 3 custom1, 4 custom2
;; scale = fraction (0..1) of boot l-current-max-scale; others absolute, clamped to ceiling.
(define m-scale (list 0.40 0.70 1.00 0.50 0.50))
(define m-watt  (list (* ceil-watt 0.40) (* ceil-watt 0.70) ceil-watt (* ceil-watt 0.50) (* ceil-watt 0.50)))
(define m-icur  (list (* ceil-icur 0.40) (* ceil-icur 0.70) ceil-icur (* ceil-icur 0.50) (* ceil-icur 0.50)))
(define m-erpm  (list (* ceil-erpm 0.50) (* ceil-erpm 0.75) ceil-erpm (* ceil-erpm 0.60) (* ceil-erpm 0.60)))

(define cur-mode 0)
(define last-reject 0)   ; see reject codes in protocol.md
(define flags 0)         ; bit0 FET temp, bit1 motor temp, bit2 voltage
(define eff-scale 0.0)   ; rate-limited derate-and-mode scale (0..1, fraction of boot scale)
(define t-fet 0.0)
(define t-mot 0.0)
(define vin 0.0)
(define erpm 0.0)

(defun mode-scale (i) (kmin 1.0 (ix m-scale i)))
(defun mode-watt  (i) (kmin ceil-watt (ix m-watt i)))
(defun mode-icur  (i) (kmin ceil-icur (ix m-icur i)))
(defun mode-erpm  (i) (kmin ceil-erpm (ix m-erpm i)))

;; apply mode limits (absolute ones) -- never above ceiling
(defun apply-static ()
  {
    (conf-set 'l-watt-max (mode-watt cur-mode))
    (conf-set 'l-in-current-max (mode-icur cur-mode))
    (conf-set 'l-max-erpm (mode-erpm cur-mode))
  })

;; mode b is "not more powerful" than a if every limit is <=
(defun not-more-powerful (b a)
  (and (<= (mode-scale b) (mode-scale a))
       (<= (mode-watt b)  (mode-watt a))
       (<= (mode-icur b)  (mode-icur a))
       (<= (mode-erpm b)  (mode-erpm a))))

;; ---------------------------------------------------------------- protocol
(define applied-scale -1.0)

(defun send-status (cmd result)
  (let ((b (bufcreate 17)))
    {
      (bufset-u8 b 0 proto-magic)
      (bufset-u8 b 1 proto-ver)
      (bufset-u8 b 2 cmd)
      (bufset-u8 b 3 result)
      (bufset-u8 b 4 cur-mode)
      (bufset-u16 b 5 (to-i (* eff-scale 1000.0)))
      (bufset-u8 b 7 flags)
      (bufset-u8 b 8 last-reject)
      (bufset-i8 b 9 (to-i t-fet))
      (bufset-i8 b 10 (to-i t-mot))
      (bufset-u16 b 11 (to-i (* vin 10.0)))
      (bufset-i32 b 13 (to-i erpm))
      (send-data b)
    }))

(defun reject (cmd code)
  {
    (define last-reject code)
    (send-status cmd code)
  })

(defun handle-set-mode (idx)
  (if (or (< idx 0) (> idx 4))
      (reject 1 2)
      (if (or (< (abs erpm) stop-erpm) (not-more-powerful idx cur-mode))
          {
            (define cur-mode idx)
            (apply-static)
            (define last-reject 0)
            (send-status 1 0)
          }
          (reject 1 3))))

;; cmd 2 payload: [3]=idx(3|4) [4..5]=scale permille u16 [6..7]=watt u16 [8..9]=batt A*10 u16 [10..13]=erpm u32
(defun handle-set-custom (d)
  (if (< (buflen d) 14)
      (reject 2 4)
      (let ((idx (bufget-u8 d 3)))
        (if (or (< idx 3) (> idx 4))
            (reject 2 6)
            {
                  ;; params may be edited anytime for non-active slot; if slot is
                  ;; active, new values must not be more powerful unless stationary
                  (let ((ns (clamp (/ (to-float (bufget-u16 d 4)) 1000.0) 0.0 1.0))
                        (nw (kmin ceil-watt (to-float (bufget-u16 d 6))))
                        (ni (kmin ceil-icur (/ (to-float (bufget-u16 d 8)) 10.0)))
                        (ne (kmin ceil-erpm (to-float (bufget-u32 d 10)))))
                    (if (and (= idx cur-mode) (>= (abs erpm) stop-erpm)
                             (or (> ns (mode-scale idx)) (> nw (mode-watt idx))
                                 (> ni (mode-icur idx)) (> ne (mode-erpm idx))))
                        (reject 2 3)
                        {
                          (define m-scale (repl m-scale idx ns))
                          (define m-watt  (repl m-watt idx nw))
                          (define m-icur  (repl m-icur idx ni))
                          (define m-erpm  (repl m-erpm idx ne))
                          (if (= idx cur-mode) (apply-static))
                          (define last-reject 0)
                          (send-status 2 0)
                        }))
            }))))

(defun proc-data (d)
  (if (and (>= (buflen d) 3)
           (= (bufget-u8 d 0) proto-magic)
           (= (bufget-u8 d 1) proto-ver))
      (let ((cmd (bufget-u8 d 2)))
        (cond
          ((= cmd 1) (if (>= (buflen d) 4) (handle-set-mode (bufget-u8 d 3)) (reject 1 4)))
          ((= cmd 2) (handle-set-custom d))
          ((= cmd 3) (send-status 3 0))
          (t (reject cmd 5))))
      nil))   ; wrong magic/version: silently ignored (could be other app data)

(defun event-handler ()
  (loopwhile t
    (recv
      ((event-data-rx . (? data)) (proc-data data))
      (_ nil))))

;; ---------------------------------------------------------------- derate loop
(defun derate-tick ()
  {
    (define t-fet (get-temp-fet))
    (define t-mot (get-temp-mot))
    (define vin (get-vin))
    (define erpm (get-rpm))
    (let ((df (derate-hi t-fet fet-start fet-end))
          (dm (if (= use-mot-temp 1) (derate-hi t-mot mot-start mot-end) 1.0))
          (dv (derate-lo vin v-start v-end)))
      (let ((target (kmin (mode-scale cur-mode) (kmin df (kmin dm dv)))))
        {
          (define flags (+ (if (< df 1.0) 1 0)
                           (+ (if (< dm 1.0) 2 0) (if (< dv 1.0) 4 0))))
          ;; rate limiter
          (if (> target eff-scale)
              (define eff-scale (kmin target (+ eff-scale step-up)))
              (define eff-scale (kmax target (- eff-scale step-down))))
          ;; never above boot ceiling: eff-scale is a fraction (<=1) of boot scale
          (let ((s (* ceil-scale (clamp eff-scale 0.0 1.0))))
            (if (> (abs (- s applied-scale)) 0.002)
                {
                  (conf-set 'l-current-max-scale s)
                  (define applied-scale s)
                }))
        }))
  })

;; ---------------------------------------------------------------- start
(define eff-scale (mode-scale 0))      ; start in eco, at its scale
(apply-static)
(event-register-handler (spawn event-handler))
(event-enable 'event-data-rx)

(loopwhile t
  {
    (derate-tick)
    (sleep loop-dt)
  })
