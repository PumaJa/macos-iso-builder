;;; smoke.lisp - loaded between stubs.lisp and the UNMODIFIED kinetic.lisp.
;;; kinetic.lisp never returns (loopwhile t), so a spawned driver thread pokes
;;; it from outside: sensors, then a status request offered to all pids.
(define stub-trace t)
(define smoke-fail 0)
(defun smoke-check (name ok)
  (if ok (print "SMOKE PASS " name) { (define smoke-fail (+ smoke-fail 1)) (print "SMOKE FAIL " name) }))

(spawn 1024 (fn ()
  {
    (sleep 0.5)
    (smoke-check "event-data-rx enabled" (eq (ix stub-events 0) 'event-data-rx))
    (smoke-check "boot applied eco watt (600)" (< (abs (- (assoc stub-conf 'l-watt-max) 600.0)) 0.01))
    (define stub-fet 95.0)
    (sleep 2.0)
    (smoke-check "main loop derates scale on hot FET (<0.2)" (< (assoc stub-conf 'l-current-max-scale) 0.2))
    (let ((b (bufcreate 3)))
      {
        (bufset-u8 b 0 0x4B) (bufset-u8 b 1 1) (bufset-u8 b 2 3)
        ;; handler pid is not exposed: offer the event to the next 1500 pids (only the
        ;; script's handler recv-matches it; unknown pids are ignored)
        (looprange p (+ (self) 1) (+ (self) 1500) (send p (cons 'event-data-rx b)))
      })
    (sleep 0.5)
    (smoke-check "status reply sent through handler (19 bytes)" (and (= (length stub-sent) 1) (= (buflen (ix stub-sent 0)) 19)))
    (smoke-check "status flags show FET derate" (= (bufget-u8 (ix stub-sent 0) 7) 1))
    (if (= smoke-fail 0) (print "SMOKE-ALL-OK") (print "SMOKE-FAILED"))
  }))
