# Generator for `annotations-index.md`

The A-Z annotation index is generated. A reference page that has drifted from the code is worse
than no reference page, so the facts that can be checked mechanically are checked mechanically:

* **packages** are resolved against the project's real classpath, and each candidate is run through
  `javap` to confirm it is an annotation type rather than a same-named class;
* **ambiguous names** are whatever the classpath actually makes ambiguous - not a list someone
  remembered;
* **the "Used" column** comes from scanning `src/**/*.java` with comments stripped, so a javadoc
  mention does not count as usage.

If a documented package is wrong, the generator exits non-zero and writes nothing.

## Run it

```bash
mvn -q -pl order-service dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt -DincludeScope=test
python docs/reference/_generator/generate.py order-service/target/cp.txt
```

Requires `javap` on the PATH (it ships with the JDK) and Python 3.8+. Takes a couple of minutes,
most of it spent in `javap`.

## Files

| File | Edit it? | Holds |
| --- | --- | --- |
| `annotations.psv` | yes | `Name\|package\|one-line description`, one per annotation |
| `ambiguous.psv` | yes | `Name\|package-to-prefer\|why it matters`, for names with several candidates |
| `tail.md` | yes | the "By task" / "See also" / "Regenerating" sections, appended verbatim. Its relative links are written for `docs/reference/`, where it ends up - so they look broken from inside this folder and are correct in the output |
| `generate.py` | rarely | the generator |
| `../annotations-index.md` | **no** | generated output |

Adding a dependency, or starting to use a new annotation, means re-running the generator rather
than editing the page.
