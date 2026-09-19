"""Gate full-reactor JaCoCo coverage, including cross-module integration execution.

Run after mvn -Ppostgres-it clean verify. No production classes are excluded.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
aggregate = ET.parse(root / 'jworkflow-example/target/site/jacoco-aggregate/jacoco.xml').getroot()
example = ET.parse(root / 'jworkflow-example/target/site/jacoco/jacoco.xml').getroot()
groups = {group.attrib['name']: group for group in aggregate.findall('group')}
if set(groups) != {'jworkflow-core', 'jworkflow-jdbc'}:
    raise SystemExit(f'Missing or unexpected aggregate coverage modules: {set(groups)}')
groups['jworkflow-example'] = example

total_covered = total_possible = 0
for name, report in groups.items():
    counters = {counter.attrib['type']: counter.attrib for counter in report.findall('counter')}
    covered = sum(int(counters[k]['covered']) for k in ('LINE', 'BRANCH'))
    possible = covered + sum(int(counters[k]['missed']) for k in ('LINE', 'BRANCH'))
    if possible == 0:
        raise SystemExit(f'{name}: no executable code in coverage report')
    print(f'{name}: {100 * covered / possible:.2f}% combined line/branch coverage')
    if covered / possible < 0.80:
        raise SystemExit(f'{name}: coverage is below 80%')
    total_covered += covered
    total_possible += possible
print(f'Overall: {100 * total_covered / total_possible:.2f}% ({total_covered}/{total_possible})')
