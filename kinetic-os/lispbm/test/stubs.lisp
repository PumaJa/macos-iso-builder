;;; stubs.lisp - fake VESC-only extensions for running kinetic.lisp in the
;;; plain LispBM REPL. Test-only; never upload to a VESC.
;;; Controllable state: stub-conf (alist), stub-fet/-mot/-vin/-rpm, and the
;;; recorders stub-sent (newest first), stub-conf-log (newest first),
;;; stub-events.

(define stub-conf
  (list (cons 'l-current-max-scale 1.0)
        (cons 'l-watt-max          1500.0)
        (cons 'l-in-current-max    30.0)
        (cons 'l-max-erpm          100000.0)
        (cons 'l-battery-cut-start 42.0)))

(define stub-fet 25.0)
(define stub-mot 25.0)
(define stub-vin 50.0)
(define stub-rpm 0.0)
(define stub-sent nil)        ; buffers passed to send-data, newest first
(define stub-conf-log nil)    ; (key . value) for every conf-set, newest first
(define stub-events nil)
(define stub-trace nil)       ; t = print every conf-set (used by smoke test)

;; Like the VESC: unknown parameter name is an error (here: eval error).
(defun conf-get (k)
  (let ((r (assoc stub-conf k)))
    (if (eq r nil) (exit-error (list 'stub-unknown-conf k)) r)))

(defun conf-set (k v)
  {
    (if (eq (assoc stub-conf k) nil) (exit-error (list 'stub-unknown-conf k)) nil)
    (if (not (or (eq (type-of v) 'type-float) (eq (type-of v) 'type-i)))
        (exit-error (list 'stub-bad-conf-value k v)) nil)
    (if stub-trace (print "STUB conf-set " k " " v) nil)
    (define stub-conf (setassoc stub-conf k v))
    (define stub-conf-log (cons (cons k v) stub-conf-log))
    t
  })

(defun get-temp-fet () stub-fet)
(defun get-temp-mot () stub-mot)
(defun get-vin () stub-vin)
(defun get-rpm () stub-rpm)

(defun send-data (b)
  {
    (define stub-sent (cons b stub-sent))
    t
  })

;; event-register-handler is a built-in special form in the REPL; not stubbed.
(defun event-enable (e) (define stub-events (cons e stub-events)))

;; app-disable-output: ms, 0 = enable now, -1 = disable forever (verified in VESC README).
(define stub-out-disabled nil)   ; t while app output is (stubbed as) disabled
(define stub-out-log nil)        ; every ms argument, newest first
(defun app-disable-output (ms)
  {
    (define stub-out-log (cons ms stub-out-log))
    (define stub-out-disabled (if (= ms 0) nil t))
    t
  })

;; eeprom-store-i / eeprom-read-i: addr 0..127, i32; read of unwritten addr = nil.
(define stub-eeprom nil)         ; alist addr -> value (persists across simulated reboots)
(define stub-eeprom-writes 0)
(defun eeprom-store-i (addr v)
  {
    (if (or (< addr 0) (> addr 127)) (exit-error (list 'stub-bad-eeprom-addr addr)) nil)
    (if (not (eq (type-of v) 'type-i)) (exit-error (list 'stub-eeprom-not-i32 addr v)) nil)
    (define stub-eeprom (setassoc stub-eeprom addr v))
    (define stub-eeprom-writes (+ stub-eeprom-writes 1))
    t
  })
(defun eeprom-read-i (addr)
  {
    (if (or (< addr 0) (> addr 127)) (exit-error (list 'stub-bad-eeprom-addr addr)) nil)
    (assoc stub-eeprom addr)
  })
