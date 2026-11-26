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
(define ea-par-pm 104)             ; EEPROM addr: parental max current scale, permille (absent/1000 = none)
(define ea-par-e  105)             ; EEPROM addr: parental max ERPM, i32 (absent/0 = none)
(define par-min-scale 0.05)        ; parental scale is never set below this
(define max-fails 5)               ; consecutive wrong PINs before lockout
(define lockout-base 60.0)         ; s, doubles per further failure series
(define lockout-cap 3600.0)        ; s
(define tamper-secs 1.0)           ; moving this long while locked sets TAMPER
;; EXPERIMENTAL traction control (off by default, cmd 9; only ever REDUCES current).
;; tc-max-erpm-rate is the ERPM/s rise rate considered "wheel spin" at 50% intervention.
;; !! MUST BE TUNED PER VEHICLE (motor, gearing, wheel inertia) on a stand with the wheel
;; !! off the ground. The default is a placeholder, NOT hardware-validated.
(define tc-max-erpm-rate 20000.0)  ; ERPM/s; slip threshold = this * (2 - pct/100)
(define tc-step    0.15)           ; scale decrease per tick on slip, times pct/100
(define tc-depth   0.70)           ; floor = 1 - tc-depth * pct/100
(define tc-recover 0.5)            ; scale recovery per second without slip
(define air-acc-g  0.35)           ; |acc| below this (g) = free fall candidate
(define air-secs   0.1)            ; ...for longer than this = airborne

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
(define neutral 0)       ; NOT persisted: power-up = drive enabled (unless locked)
(define par-scale 1.0)   ; parental ceiling, fraction of boot scale (1.0 = none)
(define par-erpm 0.0)    ; parental ERPM ceiling (0.0 = none)
(define par-dirty 0)     ; 1 = lowered while moving, EEPROM write deferred to standstill
(define ep-par-pm 1000)  ; mirrors of the EEPROM values
(define ep-par-e 0)
(define lock-reassert 0)
(define tc-pct 0)        ; traction control intervention 0..100, RAM only (0 after boot)
(define tc-scale 1.0)    ; TC current scale (0..1); only ever combined with min()
(define tc-prev-erpm 0.0)
(define tc-air-t 0.0)
(define tc-air 0)
(define ep-cnt 0)        ; mirrors of the EEPROM values (skip identical writes)
(define ep-series 0)
(defun lockout-dur (series) (kmin lockout-cap (* lockout-base (pow 2.0 (to-float (- series 1))))))
(defun lock-boot ()
  {
    (define locked (if (eq (eeprom-read-i ea-lock) 1) 1 0))
    (if (= locked 1) (app-disable-output -1) nil)
    (define pin-x (eeprom-read-i ea-pin))
    ;; parental limit: loaded before any mode limit is applied
    (define ep-par-pm (let ((v (eeprom-read-i ea-par-pm))) (if (eq v nil) 1000 v)))
    (define ep-par-e  (let ((v (eeprom-read-i ea-par-e)))  (if (eq v nil) 0 v)))
    (define par-scale (if (>= ep-par-pm 1000) 1.0 (kmax par-min-scale (/ (to-float ep-par-pm) 1000.0))))
    (define par-erpm  (if (> ep-par-e 0) (to-float ep-par-e) 0.0))
    (define par-dirty 0)
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

(defun erpm-cap () (if (> par-erpm 0.0) (kmin ceil-erpm par-erpm) ceil-erpm))
(defun mode-scale (i) (kmin par-scale (kmin 1.0 (ix m-scale i))))
(defun mode-watt  (i) (kmin ceil-watt (ix m-watt i)))
(defun mode-icur  (i) (kmin ceil-icur (ix m-icur i)))
(defun mode-erpm  (i) (kmin (erpm-cap) (ix m-erpm i)))

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

(defun par-active () (or (< par-scale 1.0) (> par-erpm 0.0)))
(defun flags2 () (+ (if (= neutral 1) 1 0) (if (par-active) 2 0)))

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
    (define tc-scale 1.0)
  })

(defun do-unlock ()
  {
    (eeprom-store-i ea-lock 0)
    (define locked 0)
    (define tamper 0)
    (define tamper-t 0.0)
    (if (= neutral 1) nil (app-disable-output 0))
    (apply-static)
    (define applied-scale -1.0)   ; force scale write; eff-scale (0) ramps up at step-up per tick
  })

;; ---------------------------------------------------------------- protocol
(define applied-scale -1.0)
(define req-ver 2)       ; version byte of the request being answered (replies echo it)

(defun tc-flags3 ()
  (+ (if (and (> tc-pct 0) (< tc-scale 0.999)) 1 0) (if (= tc-air 1) 2 0)))

(defun send-status (cmd result)
  (let ((b (bufcreate 24)))
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
      (bufset-u8 b 19 (flags2))
      (bufset-u16 b 20 (rnd (* par-scale 1000.0)))
      (bufset-u8 b 22 tc-pct)
      (bufset-u8 b 23 (tc-flags3))
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
                  (let ((ns (clamp (/ (to-float (bufget-u16 d 4)) 1000.0) 0.0 par-scale))
                        (nw (kmin ceil-watt (to-float (if (= req-ver 1) (bufget-u16 d 6) (bufget-u32 d 6)))))
                        (ni (kmin ceil-icur (/ (to-float (bufget-u16 d (if (= req-ver 1) 8 10))) 10.0)))
                        (ne (kmin (erpm-cap) (to-float (bufget-u32 d (if (= req-ver 1) 10 12))))))
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

(defun handle-neutral (d)
  (if (< (buflen d) 4)
      (reject 7 4)
      (let ((on (bufget-u8 d 3)))
        (cond
          ((= on 1) { (define neutral 1) (app-disable-output -1) (define last-reject 0) (send-status 7 0) })
          ((= on 0)
           (if (= locked 1)
               (reject 7 11)
               (if (moving)
                   (reject 7 3)
                   { (define neutral 0) (app-disable-output 0) (define last-reject 0) (send-status 7 0) })))
          (t (reject 7 2))))))

;; write the parental limit to EEPROM (only changed cells); only called at standstill
(defun persist-par ()
  {
    (let ((pm (if (< par-scale 1.0) (rnd (* par-scale 1000.0)) 1000))
          (e  (if (> par-erpm 0.0) (rnd par-erpm) 0)))
      {
        (if (not (= pm ep-par-pm)) { (eeprom-store-i ea-par-pm pm) (define ep-par-pm pm) } nil)
        (if (not (= e ep-par-e)) { (eeprom-store-i ea-par-e e) (define ep-par-e e) } nil)
      })
    (define par-dirty 0)
  })

(defun par-e-val (e) (if (> e 0.0) e 1000000000.0))

;; cmd 8: [3..6] pin u32, [7..8] max scale permille u16, [9..12] max erpm u32
;; Lowering while moving: PIN checked WITHOUT counting/EEPROM, applied at once, EEPROM write deferred to standstill.
;; Raising/clearing while moving: code 3. Stationary: normal counted PIN check.
(defun handle-parental (d)
  (if (< (buflen d) 13)
      (reject 8 4)
      (if (eq pin-x nil)
          (reject 8 8)
          (if (= locked 1)
              (reject 8 11)
              (let ((pm (bufget-u16 d 7))
                    (ne (let ((e (to-float (bufget-u32 d 9)))) (kmin 2147483000.0 e))))
                (let ((ns (if (>= pm 1000) 1.0 (kmax par-min-scale (/ (to-float pm) 1000.0)))))
                  (let ((lowering (and (<= ns par-scale) (<= (par-e-val ne) (par-e-val par-erpm))))
                        (r (if (moving) (if (> lock-until uptime) 9 (if (pin-ok (bufget-u32 d 3)) 0 7)) (check-pin (bufget-u32 d 3)))))
                    (if (and (moving) (not lowering))
                        (reject 8 3)
                        (if (not (= r 0))
                            (reject 8 r)
                            {
                              (define par-scale ns)
                              (define par-erpm ne)
                              (apply-static)
                              (if (moving) (define par-dirty 1) (persist-par))
                              (define last-reject 0)
                              (send-status 8 0)
                            })))))))))

;; cmd 9: [3] intervention percent 0..100 (0 = off). Raising always ok; lowering while
;; moving: code 3. Accepted while locked (can only reduce power). RAM only.
(defun handle-tc (d)
  (if (< (buflen d) 4)
      (reject 9 4)
      (let ((p (bufget-u8 d 3)))
        (if (> p 100)
            (reject 9 2)
            (if (and (< p tc-pct) (moving))
                (reject 9 3)
                {
                  (define tc-pct p)
                  (if (= p 0) { (define tc-scale 1.0) (define tc-air 0) (define tc-air-t 0.0) } nil)
                  (define last-reject 0)
                  (send-status 9 0)
                })))))

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
          ((= cmd 7) (handle-neutral d))
          ((= cmd 8) (handle-parental d))
          ((= cmd 9) (handle-tc d))
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
    (if (and (= par-dirty 1) (not (moving))) (persist-par) nil)
    (if (= neutral 1) (app-disable-output -1) nil)
    (if (= locked 1) (lock-tick) (derate-apply))
  })

;; |acc| in g, or nil if there is no IMU (trap: a missing/failing IMU must never crash)
(defun tc-acc ()
  (let ((r (trap (get-imu-acc))))   ; trap returns (exit-ok value) or (exit-error ...)
    (let ((a (if (and (eq (type-of r) 'type-list) (eq (first r) 'exit-ok)) (ix r 1) nil)))
    (if (and (eq (type-of a) 'type-list) (>= (length a) 3) (eq (type-of (first a)) 'type-float))
        (let ((x (ix a 0)) (y (ix a 1)) (z (ix a 2)))
          (sqrt (+ (* x x) (+ (* y y) (* z z)))))
        nil))))

;; traction control: only ever lowers tc-scale's contribution; see derate-apply (min)
(defun tc-update ()
  {
    (let ((rate (/ (- erpm tc-prev-erpm) loop-dt))
          (rec (kmin 1.0 (+ tc-scale (* tc-recover loop-dt)))))
      {
        (define tc-prev-erpm erpm)
        (if (= tc-pct 0)
            {
              (define tc-scale 1.0)
              (define tc-air 0)
              (define tc-air-t 0.0)
            }
            {
              (let ((a (tc-acc)))
                (if (and (not (eq a nil)) (< a air-acc-g))
                    {
                      (define tc-air-t (+ tc-air-t loop-dt))
                      (define tc-air (if (> tc-air-t air-secs) 1 0))
                    }
                    {
                      (define tc-air-t 0.0)
                      (define tc-air 0)
                    }))
              (let ((pf (/ (to-float tc-pct) 100.0)))
                (if (and (= tc-air 0) (> erpm 0.0) (> (get-current) 0.0)
                         (> rate (* tc-max-erpm-rate (- 2.0 pf))))
                    (define tc-scale (kmin tc-scale (kmax (- 1.0 (* tc-depth pf)) (- tc-scale (* tc-step pf)))))
                    (define tc-scale rec)))
            })
      })
  })

(defun derate-apply ()
  {
    (tc-update)
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
          ;; TC can only lower the applied scale: min(eff-scale, tc-scale)
          (let ((s (* ceil-scale (clamp (kmin eff-scale tc-scale) 0.0 1.0))))
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
