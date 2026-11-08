#!/usr/bin/env python3
"""Check public-list filter queries without writing account marks."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--runner', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not args.device.startswith('emulator-'):
    parser.error('Use an emulator with an existing account session.')
result = subprocess.run(['adb', '-s', args.device, 'shell', 'am', 'instrument', '-w', '-e', 'mode',
                         'public-filters-live', args.runner], capture_output=True, text=True, timeout=600, check=True)
report = next((json.loads(line.removeprefix('INSTRUMENTATION_RESULT: result_json='))
               for line in result.stdout.splitlines() if line.startswith('INSTRUMENTATION_RESULT: result_json=')), None)
if not isinstance(report, dict):
    raise SystemExit('Missing structured probe result')
args.output.mkdir(parents=True, exist_ok=True)
(args.output/'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
summary = {key: report[key] for key in ('probe_success', 'marks_unchanged', 'phase', 'failure_type') if key in report}
summary['contexts'] = {
    name: {
        'sidebar': {mode: {'cases': len(rows), 'rows': rows[0]['rows'],
                           'all_unchanged': all(row['same_body_as_default'] for row in rows)}
                    for mode, rows in context['sidebar'].items()},
        'pages': context['pages'],
    } for name, context in report.get('contexts', {}).items()
}
print(json.dumps(summary, ensure_ascii=False))
if report.get('probe_success') is not True:
    raise SystemExit(1)
