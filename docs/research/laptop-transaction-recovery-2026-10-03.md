# Laptop transaction harness recovery — 2026-10-03

Physical zip-prefix-20261003 control-1 is INVALID, not a performance vote.
Controller failed to discover Java despite a live game: current integration
transaction/interactive helper matched backslash paths against Prism forward
slash command line. Previously hardened #320 helpers were not integrated and
were accidentally omitted from the new bundle. Recover incorrectly believed
there was no target Java and restored files while the game remained live.

PID2044 creation2026-10-02T22:32:10.735Z matches Prism launch; received command
line has no benchmark properties, so neither probe nor autoclose was active.
Original configured tuning was lost too. New GC logging value used a comma
(time,uptime) inside unquoted QSettings JvmArgs: Qt list serialization is the
likely cause, not yet a direct Qt reproduction. Always quote entire encoded
JvmArgs and additionally use uptime-only GC decoration for the retry. Validate
actual required argv exactly once before any benchmark vote.

Import known #320 fixes: normalize separators for BOTH process detection and
recovery, atomic state-file replacement, correct force switch binding, longer
scheduler lifetime covering appearance grace. Quote QSettings whole string
preserving backslash/quote escaping. Static parser and comma roundtrip PASS.
No Java/game runtime modifications or performance claim in this PR.

Retry must use fresh campaign ID, same frozen ebbe44f2 JAR, two controls/two
candidates from zero valid prior observations. Close only verified owned Java,
verify original config/JAR hashes, ensure no active controller/Prism before
staging. Record effective process argv and creation/session. Never call a run
valid solely because it reached a menu or controller reports restored.
