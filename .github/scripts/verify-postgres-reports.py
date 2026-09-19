"""Require executed, non-skipped PostgreSQL contracts in designated full-matrix jobs."""
from pathlib import Path
import xml.etree.ElementTree as ET

required = {
    "CommandsPostgresIT": 19,
    "ConfigurationPostgresIT": 4,
    "DuplicatesPostgresIT": 75,
    "DurableContractsPostgresIT": 51,
    "FoundationPostgresIT": 4,
    "LeasesPostgresIT": 21,
    "MigrationsPostgresIT": 9,
    "OperationsPostgresIT": 3,
    "TransactionsPostgresIT": 17,
    "ValuesPostgresIT": 9,
    "DurableOrderPostgresIT": 1,
}
seen = {}
for report in Path('.').glob('*/target/failsafe-reports/TEST-*.xml'):
    suite = ET.parse(report).getroot()
    name = suite.attrib['name'].rsplit('.', 1)[-1]
    assert not any(int(suite.attrib.get(key, 0)) for key in ('errors', 'failures', 'skipped')), report
    count = int(suite.attrib['tests'])
    assert count > 0, report
    seen[name] = seen.get(name, 0) + count
for name, minimum in required.items():
    assert seen.get(name, 0) >= minimum, f'{name}: expected at least {minimum} executed cases, got {seen.get(name, 0)}'
print(f'Validated {sum(seen.values())} executed PostgreSQL cases; no skipped, failed, or missing suites.')
