#!/usr/bin/env python3
"""Regenerate docs/reference/annotations-index.md.

The page is generated so that it cannot drift from the code:

  * every PACKAGE is resolved against the project's real classpath, and each candidate is checked
    with `javap` to confirm it is an annotation type rather than a same-named class;
  * the "ambiguous names" table is whatever the classpath actually makes ambiguous;
  * the "Used" column comes from scanning src/**/*.java with comments stripped.

Usage, from the repository root:

    mvn -q -pl order-service dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=test
    python docs/reference/_generator/generate.py order-service/target/cp.txt

Inputs you edit by hand:
    annotations.psv   Name|package|one-line description
    ambiguous.psv     Name|package-to-prefer|why it matters
    tail.md           the "By task" / "See also" sections, appended verbatim
"""
import collections
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
OUT = os.path.join(ROOT, 'docs', 'reference', 'annotations-index.md')
SRC_ROOTS = ('platform-starter/src', 'order-service/src')


def read_psv(name, fields):
    rows = []
    for line in open(os.path.join(HERE, name), encoding='utf-8'):
        if not line.strip():
            continue
        parts = line.rstrip('\n').split('|')
        if len(parts) != fields:
            sys.exit('%s: expected %d fields, got %d: %r' % (name, fields, len(parts), line))
        rows.append(parts)
    return rows


def load_classpath(cp_file):
    raw = open(cp_file, encoding='utf-8').read()
    jars = [j for j in re.split(r'[;:\n]', raw.replace('\r', '')) if j.endswith('.jar')]
    # A Windows drive letter ("C:/...") must not be split on its colon.
    fixed, i = [], 0
    while i < len(jars):
        if len(jars[i]) == 1 and i + 1 < len(jars):
            fixed.append(jars[i] + ':' + jars[i + 1]); i += 2
        else:
            fixed.append(jars[i]); i += 1
    return sorted({j for j in fixed if os.path.isfile(j)})


def index_classes(jars):
    """simple name -> {fully qualified name} for every non-inner class on the classpath."""
    index = collections.defaultdict(set)
    import zipfile
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                for entry in z.namelist():
                    if entry.endswith('.class') and '$' not in entry:
                        fqn = entry[:-6].replace('/', '.')
                        index[fqn.rsplit('.', 1)[-1]].add(fqn)
        except zipfile.BadZipFile:
            continue
    return index


def annotation_types(index, names, jars):
    """Keep only the candidates that really are annotations, per javap."""
    sep = ';' if os.name == 'nt' else ':'
    cp = sep.join(jars)
    out = collections.defaultdict(set)
    for name in names:
        for fqn in sorted(index.get(name, ())):
            try:
                head = subprocess.run(['javap', '-cp', cp, fqn], capture_output=True, text=True,
                                      timeout=60).stdout[:400]
            except (OSError, subprocess.TimeoutExpired):
                continue
            if 'extends java.lang.annotation.Annotation' in head:
                out[name].add(fqn)
    return out


def strip_comments(text):
    """Javadoc mentions annotations constantly; counting those as "used" would make the column lie."""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'(?m)//.*$', '', text)


def scan_usage():
    main, test = collections.defaultdict(set), collections.defaultdict(set)
    for base in SRC_ROOTS:
        for dirpath, _, names in os.walk(os.path.join(ROOT, base)):
            for n in names:
                if not n.endswith('.java'):
                    continue
                path = os.path.join(dirpath, n)
                bucket = test if os.sep + 'test' + os.sep in path else main
                body = strip_comments(open(path, encoding='utf-8').read())
                for m in re.finditer(r'@([A-Z][A-Za-z0-9]*)', body):
                    bucket[m.group(1)].add(n[:-5])
    return main, test


def cell(name, main, test):
    for bucket, prefix in ((main, ''), (test, '_test:_ ')):
        if name in bucket:
            files = sorted(bucket[name])
            extra = ' +%d' % (len(files) - 1) if len(files) > 1 else ''
            return prefix + '`' + files[0] + '`' + extra
    return '-'


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    jars = load_classpath(sys.argv[1])
    rows = read_psv('annotations.psv', 3)
    prefer = {n: (pkg, note) for n, pkg, note in read_psv('ambiguous.psv', 3)}
    print('classpath jars: %d, documented annotations: %d' % (len(jars), len(rows)))

    index = index_classes(jars)
    resolved = annotation_types(index, sorted({n for n, _, _ in rows}), jars)

    # Fail loudly on a package that does not exist: a wrong package is the one error this page
    # must never contain. Project-local annotations are exempt (they are not on the dependency cp).
    problems = []
    for name, pkg, _ in rows:
        if pkg.startswith('com.example'):
            continue
        packages = {f.rsplit('.', 1)[0] for f in resolved.get(name, ())}
        if not packages:
            problems.append('%s: no annotation type named @%s on the classpath' % (name, name))
        elif pkg not in packages:
            problems.append('%s: documented as %s, classpath says %s' % (name, pkg, sorted(packages)))
    if problems:
        sys.exit('REFUSING TO GENERATE:\n  ' + '\n  '.join(problems))

    used_main, used_test = scan_usage()
    out, letter = [], None
    w = out.append
    w('# Reference: annotation index (A-Z)')
    w('')
    w('A flat lookup table. For the *why*, the traps and the mental model, read')
    w('[annotations.md](annotations.md) - this page is for answering "which package is that in again?"')
    w('and "do we already use it?".')
    w('')
    w('**Every package below was resolved from this project\'s actual classpath** (%d jars, Spring'
      % len(jars))
    w('Boot 3.5.16 / Spring Framework 6.2.19 / Jakarta EE 10 / Hibernate 6.6 / Micrometer /')
    w('Resilience4j), and every type was confirmed to really be an annotation rather than a')
    w('same-named class. The *Used* column is generated by scanning `src/**/*.java` with comments')
    w('stripped, so it reflects real usage rather than javadoc mentions, and it cannot drift from')
    w('the code.')
    w('')
    w('`-` in *Used* means the annotation is documented here but not used in this project. Two rows')
    w('are marked THIS PROJECT: they are the custom annotations defined in this repo, listed so the')
    w('mechanism they demonstrate is easy to find.')
    w('')
    for name, pkg, desc in sorted(rows, key=lambda r: r[0].lower()):
        if name[0].upper() != letter:
            letter = name[0].upper()
            w('')
            w('### ' + letter)
            w('')
            w('| Annotation | Package | What it does | Used |')
            w('| --- | --- | --- | --- |')
        w('| `@%s` | `%s` | %s | %s |' % (name, pkg, desc, cell(name, used_main, used_test)))

    ambiguous = sorted(n for n, v in resolved.items() if len(v) > 1)
    w('')
    w('---')
    w('')
    w('## Ambiguous simple names on this classpath')
    w('')
    w('These %d annotation names each resolve to more than one real annotation type among this'
      % len(ambiguous))
    w("project's dependencies - so your IDE's auto-import offers you a choice, and the wrong choice")
    w('compiles cleanly and does nothing. This table is generated by scanning all %d jars and'
      % len(jars))
    w('checking which same-named types are genuinely annotations rather than same-named classes.')
    w('')
    w('| Name | Take this one | Also on the classpath | Why it matters |')
    w('| --- | --- | --- | --- |')
    for name in ambiguous:
        pkg, note = prefer.get(name, ('', '_no preference recorded in ambiguous.psv_'))
        others = sorted(f for f in resolved[name] if f != pkg + '.' + name)
        w('| `@%s` | `%s` | %s | %s |'
          % (name, pkg + '.' + name, '<br>'.join('`' + o + '`' for o in others) or '-', note))
    w('')
    body = '\n'.join(out) + '\n' + open(os.path.join(HERE, 'tail.md'), encoding='utf-8').read()
    open(OUT, 'w', encoding='utf-8').write(body)
    print('wrote %s (%d lines)' % (os.path.relpath(OUT, ROOT), body.count('\n')))
    print('used in main: %d, tests only: %d, documented but unused: %d'
          % (sum(1 for n, _, _ in rows if n in used_main),
             sum(1 for n, _, _ in rows if n not in used_main and n in used_test),
             sum(1 for n, _, _ in rows if n not in used_main and n not in used_test)))
    print('ambiguous names: %d%s' % (len(ambiguous),
          '' if all(n in prefer for n in ambiguous)
          else ' (MISSING from ambiguous.psv: %s)' % [n for n in ambiguous if n not in prefer]))


if __name__ == '__main__':
    main()
