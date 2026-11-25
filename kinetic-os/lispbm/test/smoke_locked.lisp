;;; smoke_locked.lisp - loaded after stubs.lisp, before the UNMODIFIED kinetic.lisp:
;;; EEPROM already holds lock flag + PIN (as after a power cycle while locked).
(define stub-trace t)
(define stub-eeprom (list (cons 100 1) (cons 101 (bitwise-xor 4321 0x5A17C3E9))))
(define smoke-fail 0)
(defun smoke-check (name ok)
  (if ok (print "SMOKE PASS " name) { (define smoke-fail (+ smoke-fail 1)) (print "SMOKE FAIL " name) }))
(spawn 1024 (fn ()
  {
    (sleep 1.0)
    (smoke-check "locked boot: output disabled" stub-out-disabled)
    (smoke-check "locked boot: scale forced 0" (< (assoc stub-conf 'l-current-max-scale) 0.001))
    (define stub-fet 20.0)
    (sleep 1.0)
    (smoke-check "locked boot: scale stays 0 in main loop" (< (assoc stub-conf 'l-current-max-scale) 0.001))
    (smoke-check "locked boot: output never enabled" (not (member 0 stub-out-log)))
    (if (= smoke-fail 0) (print "SMOKE-ALL-OK") (print "SMOKE-FAILED"))
  }))
