#!/usr/bin/env python3
"""Turn `am instrument -r` output into JUnit XML. Exit 1 unless every test passed.

    scripts/instrument_to_junit.py raw.txt junit.xml [--summary summary.md]

The exit code of `adb shell am instrument` says nothing: it is 0 when tests fail and when the
app crashes. What happened is only in the status lines, so they are parsed here. Status codes
(android.app.Instrumentation / AndroidJUnitRunner): 1 start, 0 ok, -1 error, -2 failure,
-3 ignored, -4 assumption failure. A run without an INSTRUMENTATION_CODE line, or with a
shortMsg (a crash), counts as an error; so does a run of zero tests.

With --summary, a Markdown table of the outcome is appended to that file, for
$GITHUB_STEP_SUMMARY in CI and the terminal locally.
"""
import sys
import xml.etree.ElementTree as ET


def parse(lines):
    tests, cur, key, result_lines = [], {}, None, []
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            k, _, v = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            cur[k] = v
            key = k
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code != 1:  # 1 = test started, the rest close it
                tests.append({"class": cur.get("class"), "name": cur.get("test"), "code": code, "stack": cur.get("stack", "")})
            cur, key = {}, None
        elif line.startswith("INSTRUMENTATION_RESULT: ") or line.startswith("INSTRUMENTATION_CODE: "):
            result_lines.append(line)
            key = None
        elif key is not None:
            cur[key] = cur.get(key, "") + "\n" + line  # multi-line values (stack, stream)
    return tests, result_lines


def run_time(lines):
    """The runner's own 'Time: 12.3', the last one in the output, or None."""
    found = None
    for line in lines:
        if line.startswith("Time: "):
            found = line[len("Time: "):].strip()
    return found


def main(src, dst, summary=None):
    with open(src, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().split("\n")
    tests, result = parse(lines)
    crashed = any("shortMsg=" in l for l in result) or not any(l.startswith("INSTRUMENTATION_CODE: ") for l in result)
    suite = ET.Element("testsuite", name="instrumented", tests=str(len(tests)))
    failures = errors = skipped = 0
    rows = []
    for t in tests:
        case = ET.SubElement(suite, "testcase", classname=t["class"] or "", name=t["name"] or "")
        outcome = "passed"
        if t["code"] == -2:
            failures += 1
            outcome = "FAILED"
            ET.SubElement(case, "failure", message=t["stack"].strip().split("\n")[0]).text = t["stack"]
        elif t["code"] == -1:
            errors += 1
            outcome = "ERROR"
            ET.SubElement(case, "error", message=t["stack"].strip().split("\n")[0]).text = t["stack"]
        elif t["code"] in (-3, -4):
            skipped += 1
            outcome = "skipped"
            ET.SubElement(case, "skipped")
        rows.append((t["class"] or "", t["name"] or "", outcome, t["stack"].strip().split("\n")[0] if t["stack"] else ""))
    if crashed:
        errors += 1
        ET.SubElement(ET.SubElement(suite, "testcase", classname="instrumentation", name="run"), "error",
                      message="instrumentation did not finish").text = "\n".join(result)
    suite.set("failures", str(failures))
    suite.set("errors", str(errors))
    suite.set("skipped", str(skipped))
    ET.ElementTree(suite).write(dst, encoding="utf-8", xml_declaration=True)

    time = run_time(lines) or "?"
    headline = f"{len(tests)} tests, {failures} failures, {errors} errors, {skipped} skipped, {time} s in the runner"
    print(headline)
    ok = not (failures or errors or not tests)
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"## Instrumented tests: {'passed' if ok else 'FAILED'}\n\n{headline}\n\n")
            if crashed:
                out.write("The instrumentation did not finish (a crash or a kill); see logcat.txt.\n\n")
            out.write("| Class | Test | Outcome | First line |\n|---|---|---|---|\n")
            for cls, name, outcome, first in rows:
                short = cls.rsplit(".", 1)[-1]
                out.write(f"| {short} | {name} | {outcome} | {first.replace('|', '/')[:160]} |\n")
            out.write("\n")
    return 0 if ok else 1


if __name__ == "__main__":
    args = sys.argv[1:]
    summary_path = None
    if "--summary" in args:
        i = args.index("--summary")
        summary_path = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    sys.exit(main(args[0], args[1], summary_path))
