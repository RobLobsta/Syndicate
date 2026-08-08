# DISC-002: Blender writes to stdout at the C level, breaking the tool's JSON-only contract

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S4.1

**Status:** active

## Summary
D09-R2 requires the tool's stdout to carry exactly one JSON document and nothing else. Blender does not cooperate: its glTF exporter writes a Draco availability notice to the process's stdout at the C level on every run, landing in the middle of the document an agent is about to parse. The tool now duplicates the real stdout to a private descriptor and points fd 1 at stderr.

## Details

**Symptom:** a successful run's stdout began with `ERROR Draco mesh compression is not available...` followed by two blank lines and then the JSON. `json.loads` fails at character 0. Redirecting to a file made it obvious; on a terminal it read as ordinary log noise.

**Why the obvious fixes do not work:** the message does not come from Python, so no `logging` configuration silences it, and `contextlib.redirect_stdout` only rebinds `sys.stdout` — the C-level `stdout` FILE* is untouched.

**Fix:** `errors.claim_stdout()` runs before anything else in `main`. It `os.dup`s fd 1 to a private descriptor and `os.dup2(2, 1)`, so everything the host prints "to stdout" from that point becomes diagnostics — which is where D09-R2 puts it anyway — and `emit_json` writes to the saved descriptor with `os.write`.

## Rationale / Context
The fix diff is six lines in an unrelated-looking module and does not explain itself. Without this entry the next person to see the Draco line assumes it is harmless log noise, and the one after that removes `claim_stdout` as an unexplained complication and silently breaks every caller that parses the tool's output.

## Impact
`blender-tool/syndicate_fracture/errors.py`, `__main__.py`. Any future tool that embeds a library which writes to stdout has the same problem and the same fix.
