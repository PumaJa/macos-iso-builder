;;; kinetic.lisp - Kinetic OS on-VESC ride-mode manager + local derating
;;; NOT VALIDATED ON HARDWARE. Test on a stand with the wheel off the ground.
;;; Safety logic runs entirely on the VESC; the phone/BLE link is optional.
;;; Protocol: see protocol.md (v2, lock commands 4-6).  Tunables: see README.md.

;; ---------------------------------------------------------------- tunables
(define proto-magic 0x4B)          ; 'K'
(define proto-ver   2)             ; current version; v1 requests still accepted
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
;; anti-theft lock (see README "Anti-theft lock")
(define ea-lock 100)               ; EEPROM addr (0..127): 1 = locked
(define ea-pin  101)               ; EEPROM addr: PIN xor pin-key (obfuscation only, NOT crypto)
(define pin-key 0x5A17C3E9)
(define ea-fails  102)             ; EEPROM addr: persisted consecutive-failure count (== max-fails: lockout pending)
(define ea-series 103)             ; EEPROM addr: persisted lockout level (0 = none; duration 60 s * 2^(level-1), cap 1 h)
(define max-fails 5)               ; consecutive wrong PINs before lockout
(define lockout-base 60.0)         ; s, doubles per further failure series
(define lockout-cap 3600.0)        ; s
(define tamper-secs 1.0)           ; moving this long while locked sets TAMPER

;; ---------------------------------------------------------------- helpers
(defun kmin (a b) (if (< a b) a b))
(defun kmax (a b) (if (> a b) a b))
(defun clamp (x lo hi) (kmax lo (kmin x hi)))
;; round to nearest int (to-i truncates; f32 products like 0.57*1000 can land on 569.99997)
(defun rnd (x) (if (< x 0.0) (to-i (- x 0.5)) (to-i (+ x 0.5))))
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
;; Lock state is read FIRST: if the EEPROM lock flag is set, output is disabled and the
;; current scale forced to 0 before anything else runs. Called again by the tests to
;; simulate a reboot (conf is not persisted: a real reboot restores the stored config).
(define locked 0)
(define pin-x nil)       ; stored (xored) PIN, nil = no PIN set
(define fail-cnt 0)
(define fail-series 0)
(define lock-until 0.0)  ; uptime (s) until which unlock attempts are refused; RAM only
(define uptime 0.0)      ; seconds, advanced by derate-tick
(define tamper 0)
(define tamper-t 0.0)
(define lock-reassert 0)
(define ep-cnt 0)        ; mirrors of the EEPROM values (skip identical writes)
(define ep-series 0)
(defun lockout-dur (series) (kmin lockout-cap (* lockout-base (pow 2.0 (to-float (- series 1))))))
(defun lock-boot ()
  {
    (define locked (if (eq (eeprom-read-i ea-lock) 1) 1 0))
    (if (= locked 1) (app-disable-output -1) nil)
    (define pin-x (eeprom-read-i ea-pin))
    ;; persisted PIN-failure state: a pending/active lockout restarts from boot with the
    ;; persisted duration, so power cycling can never shorten it
    (define ep-cnt (let ((v (eeprom-read-i ea-fails))) (if (eq v nil) 0 v)))
    (define ep-series (let ((v (eeprom-read-i ea-series))) (if (eq v nil) 0 v)))
    (define fail-series ep-series)
    (define fail-cnt (if (>= ep-cnt max-fails) 0 ep-cnt))
    (define lock-until (if (and (>= ep-cnt max-fails) (> ep-series 0)) (lockout-dur ep-series) 0.0))
    (define ceil-scale (conf-get 'l-current-max-scale))
    (if (= locked 1) (conf-set 'l-current-max-scale 0.0) nil)
  })
(lock-boot)
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

;; ---------------------------------------------------------------- lock helpers
(defun lock-left () (if (> lock-until uptime) (- lock-until uptime) 0.0))
(defun lock-left-u8 () (kmin 255 (to-i (+ (lock-left) 0.999))))
(defun lock-flags ()
  (+ (if (= locked 1) 1 0)
     (+ (if (eq pin-x nil) 0 2)
        (+ (if (= tamper 1) 4 0) (if (> (lock-left) 0.0) 8 0)))))

(defun pin-valid (p) (and (>= p 1000) (<= p 99999999)))
(defun pin-enc (p) (bitwise-xor (to-i p) pin-key))
(defun pin-ok (p) (and (not (eq pin-x nil)) (pin-valid p) (= (pin-enc p) pin-x)))

;; write the persisted failure state only when it changed (flash wear; motor stops briefly)
(defun persist-fails (c sr)
  {
    (if (not (= c ep-cnt)) { (eeprom-store-i ea-fails c) (define ep-cnt c) } nil)
    (if (not (= sr ep-series)) { (eeprom-store-i ea-series sr) (define ep-series sr) } nil)
  })

;; 0 = PIN correct, 7 = wrong, 9 = locked out. Counts failures; lockout 60 s, doubling per
;; series, cap 1 h. Failure count/level persist in EEPROM (written only on a failed attempt
;; or when a success clears them). Callers refuse while moving (code 3) BEFORE calling this.
(defun check-pin (p)
  (if (> lock-until uptime)
      9
      (if (pin-ok p)
          {
            (define fail-cnt 0)
            (define fail-series 0)
            (persist-fails 0 0)
            0
          }
          {
            (define fail-cnt (+ fail-cnt 1))
            (if (>= fail-cnt max-fails)
                {
                  (define fail-series (kmin 30 (+ fail-series 1)))
                  (define fail-cnt 0)
                  (define lock-until (+ uptime (lockout-dur fail-series)))
                  (persist-fails max-fails fail-series)
                }
                (persist-fails fail-cnt fail-series))
            7
          })))

(defun moving () (>= (abs erpm) stop-erpm))

;; Output is disabled via app-disable-output (stops ADC/PPM/UART apps) and the current
;; scale is forced to 0. No set-current / set-brake is ever called: the motor freewheels.
(defun do-lock ()
  {
    (if (not (= locked 1)) (eeprom-store-i ea-lock 1) nil)
    (define locked 1)
    (define tamper 0)
    (define tamper-t 0.0)
    (define lock-reassert 0)
    (app-disable-output -1)
    (conf-set 'l-current-max-scale 0.0)
    (define applied-scale 0.0)
    (define eff-scale 0.0)
  })

(defun do-unlock ()
  {
    (eeprom-store-i ea-lock 0)
    (define locked 0)
    (define tamper 0)
    (define tamper-t 0.0)
    (app-disable-output 0)
    (apply-static)
    (define applied-scale -1.0)   ; force scale write; eff-scale (0) ramps up at step-up per tick
  })

;; ---------------------------------------------------------------- protocol
(define applied-scale -1.0)
(define req-ver 2)       ; version byte of the request being answered (replies echo it)

(defun send-status (cmd result)
  (let ((b (bufcreate 19)))
    {
      (bufset-u8 b 0 proto-magic)
      (bufset-u8 b 1 req-ver)
      (bufset-u8 b 2 cmd)
      (bufset-u8 b 3 result)
      (bufset-u8 b 4 cur-mode)
      (bufset-u16 b 5 (rnd (* eff-scale 1000.0)))
      (bufset-u8 b 7 flags)
      (bufset-u8 b 8 last-reject)
      (bufset-i8 b 9 (rnd t-fet))
      (bufset-i8 b 10 (rnd t-mot))
      (bufset-u16 b 11 (rnd (* vin 10.0)))
      (bufset-i32 b 13 (rnd erpm))
      (bufset-u8 b 17 (lock-flags))
      (bufset-u8 b 18 (lock-left-u8))
      (send-data b)
    }))

(defun reject (cmd code)
  {
    (define last-reject code)
    (send-status cmd code)
  })

(defun handle-set-mode (idx)
  (if (= locked 1)
      (reject 1 11)
  (if (or (< idx 0) (> idx 4))
      (reject 1 2)
      (if (or (< (abs erpm) stop-erpm) (not-more-powerful idx cur-mode))
          {
            (define cur-mode idx)
            (apply-static)
            (define last-reject 0)
            (send-status 1 0)
          }
          (reject 1 3)))))

;; cmd 2 payload v1 (len 14): [3]=idx(3|4) [4..5]=scale permille u16 [6..7]=watt u16 [8..9]=batt A*10 u16 [10..13]=erpm u32
;; cmd 2 payload v2 (len 16): [3]=idx [4..5]=scale permille u16 [6..9]=watt u32 [10..11]=batt A*10 u16 [12..15]=erpm u32
(defun handle-set-custom (d)
  (if (= locked 1)
      (reject 2 11)
  (if (< (buflen d) (if (= req-ver 1) 14 16))
      (reject 2 4)
      (let ((idx (bufget-u8 d 3)))
        (if (or (< idx 3) (> idx 4))
            (reject 2 6)
            {
                  ;; params may be edited anytime for non-active slot; if slot is
                  ;; active, new values must not be more powerful unless stationary
                  (let ((ns (clamp (/ (to-float (bufget-u16 d 4)) 1000.0) 0.0 1.0))
                        (nw (kmin ceil-watt (to-float (if (= req-ver 1) (bufget-u16 d 6) (bufget-u32 d 6)))))
                        (ni (kmin ceil-icur (/ (to-float (bufget-u16 d (if (= req-ver 1) 8 10))) 10.0)))
                        (ne (kmin ceil-erpm (to-float (bufget-u32 d (if (= req-ver 1) 10 12))))))
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
            })))))

;; cmd 4 [3..6] pin u32 | cmd 5 [3..6] pin u32 | cmd 6 [3..6] old pin, [7..10] new pin
(defun handle-lock (d)
  (if (< (buflen d) 7)
      (reject 4 4)
      (if (eq pin-x nil)
          (reject 4 8)
          (if (moving)
              (reject 4 3)
              (let ((r (check-pin (bufget-u32 d 3))))
                (if (not (= r 0))
                    (reject 4 r)
                    {
                      (if (= locked 1) nil (do-lock))
                      (define last-reject 0)
                      (send-status 4 0)
                    }))))))

(defun handle-unlock (d)
  (if (< (buflen d) 7)
      (reject 5 4)
      (if (eq pin-x nil)
          (reject 5 8)
          (if (moving)
              (reject 5 3)
              (let ((r (check-pin (bufget-u32 d 3))))
                (if (not (= r 0))
                    (reject 5 r)
                    {
                      (if (= locked 1) (do-unlock) nil)
                      (define last-reject 0)
                      (send-status 5 0)
                    }))))))

(defun handle-set-pin (d)
  (if (< (buflen d) 11)
      (reject 6 4)
      (if (= locked 1)
          (reject 6 11)
          (if (and (not (eq pin-x nil)) (moving))
              (reject 6 3)
              (let ((r (if (eq pin-x nil) 0 (check-pin (bufget-u32 d 3))))
                    (np (bufget-u32 d 7)))
                (if (not (= r 0))
                    (reject 6 r)
                    (if (not (pin-valid np))
                        (reject 6 10)
                        {
                          (define pin-x (pin-enc np))
                          (eeprom-store-i ea-pin pin-x)
                          (define last-reject 0)
                          (send-status 6 0)
                        })))))))

(defun proc-data (d)
  (if (and (>= (buflen d) 3)
           (= (bufget-u8 d 0) proto-magic)
           (or (= (bufget-u8 d 1) 1) (= (bufget-u8 d 1) 2)))
      (let ((cmd (bufget-u8 d 2)))
        {
          (define req-ver (bufget-u8 d 1))
          (cond
            ((= cmd 1) (if (>= (buflen d) 4) (handle-set-mode (bufget-u8 d 3)) (reject 1 4)))
            ((= cmd 2) (handle-set-custom d))
            ((= cmd 3) (send-status 3 0))
          ((= cmd 4) (handle-lock d))
          ((= cmd 5) (handle-unlock d))
          ((= cmd 6) (handle-set-pin d))
            (t (reject cmd 5)))
        })
      nil))   ; wrong magic / version not 1 or 2: silently ignored (could be other app data)

(defun event-handler ()
  (loopwhile t
    (recv
      ((event-data-rx . (? data)) (proc-data data))
      (_ nil))))

;; ---------------------------------------------------------------- derate loop
;; while locked: re-assert output-off every cycle, scale 0 (on change and every ~50 ticks),
;; never touch mode limits; TAMPER if |erpm| >= stop-erpm continuously for > tamper-secs.
(defun lock-tick ()
  {
    (app-disable-output -1)
    (define lock-reassert (+ lock-reassert 1))
    (if (or (> applied-scale 0.002) (>= lock-reassert 50))
        {
          (conf-set 'l-current-max-scale 0.0)
          (define applied-scale 0.0)
          (define lock-reassert 0)
        }
        nil)
    (define eff-scale 0.0)
    (define flags 0)
    (if (>= (abs erpm) stop-erpm)
        {
          (define tamper-t (+ tamper-t loop-dt))
          (if (> tamper-t tamper-secs) (define tamper 1) nil)
        }
        (define tamper-t 0.0))
  })

(defun derate-tick ()
  {
    (define t-fet (get-temp-fet))
    (define t-mot (get-temp-mot))
    (define vin (get-vin))
    (define erpm (get-rpm))
    (define uptime (+ uptime loop-dt))
    (if (= locked 1) (lock-tick) (derate-apply))
  })

(defun derate-apply ()
  {
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
(define eff-scale (if (= locked 1) 0.0 (mode-scale 0)))      ; start in eco, at its scale (0 if locked)
(apply-static)
;;;@@ AUTOSTART-BEGIN (test/run_tests.sh strips everything from this line to EOF; keep it last)
(event-register-handler (spawn event-handler))
(event-enable 'event-data-rx)

(loopwhile t
  {
    (derate-tick)
    (sleep loop-dt)
  })
