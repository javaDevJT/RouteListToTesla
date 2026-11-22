"""Exercise the actual composite-action minimizer with synthetic Grype data."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/container-security/action.yml'
BASE = 'a' * 40
HEAD = 'b' * 40

def minimizer_script():
    source = ACTION.read_text()
    section = source.split('    - name: Minimize complete reports after the vulnerability gate\n', 1)[1]
    section = section.split('\n    - name:', 1)[0]
    return textwrap.dedent(section.split('      run: |\n', 1)[1])

class RevisionTests(unittest.TestCase):
    def run_minimizer(self, event, head=HEAD, *, high=False):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            reports = folder / 'reports'
            reports.mkdir()
            matches = []
            if high:
                matches.append({'vulnerability': {'id': 'CVE-2099-0001', 'severity': 'High'},
                                'artifact': {'name': 'synthetic-package', 'version': '1.0'}})
            (reports / 'linux-amd64.grype.json').write_text(json.dumps({'matches': matches}))
            environment = dict(os.environ, GITHUB_WORKSPACE=str(ROOT), RUNNER_TEMP=str(folder),
                               GITHUB_OUTPUT=str(folder / 'output'), GITHUB_REPOSITORY='javaDevJT/RouteListToTesla',
                               GITHUB_SHA=BASE, GITHUB_EVENT_NAME=event, SECURITY_PR_HEAD_SHA=head,
                               SECURITY_REPORT_DIRECTORY=str(reports), SECURITY_PLATFORMS='linux/amd64',
                               SECURITY_IMAGE='oci-archive:/synthetic/image.tar')
            result = subprocess.run(['/bin/bash', '-c', minimizer_script()], env=environment,
                                    capture_output=True, text=True, timeout=10)
            output = folder / 'security-high-critical.json'
            return result.returncode, json.loads(output.read_text()) if output.exists() else None

    def test_target_report_uses_built_head(self):
        code, report = self.run_minimizer('pull_request_target')
        self.assertEqual(code, 0)
        self.assertEqual(report['revision'], HEAD)
        self.assertIn('SECURITY_PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}', ACTION.read_text())

    def test_regular_pull_request_keeps_merge_revision(self):
        code, report = self.run_minimizer('pull_request')
        self.assertEqual(code, 0)
        self.assertEqual(report['revision'], BASE)

    def test_push_keeps_revision_and_high_finding(self):
        code, report = self.run_minimizer('push', high=True)
        self.assertEqual(code, 0)
        self.assertEqual(report['revision'], BASE)
        self.assertEqual(report['reports'][0]['highCriticalCount'], 1)
        self.assertEqual(report['findings'][0]['id'], 'CVE-2099-0001')

    def test_target_without_head_refuses_artifact(self):
        code, report = self.run_minimizer('pull_request_target', head='')
        self.assertNotEqual(code, 0)
        self.assertIsNone(report)

    def test_target_with_invalid_head_refuses_artifact(self):
        code, report = self.run_minimizer('pull_request_target', head='invalid; printf injected')
        self.assertNotEqual(code, 0)
        self.assertIsNone(report)

if __name__ == '__main__':
    unittest.main()
