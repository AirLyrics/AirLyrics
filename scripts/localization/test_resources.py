"""Exercise the command-line checker against Android resource fixtures."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


CHECKER = Path(__file__).with_name('check.py').resolve()
LOCALES = ('values-es', 'values-zh-rCN', 'values-zh-rTW')


class ResourceChecksTest(unittest.TestCase):
    def check_resources(self, default, localized, kotlin=''):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for qualifier, strings in [('values', default)] + [
                (locale, localized) for locale in LOCALES
            ]:
                path = root / 'app/src/main/res' / qualifier / 'strings.xml'
                path.parent.mkdir(parents=True)
                path.write_text(f'<resources>{strings}</resources>', encoding='utf-8')
            source = root / 'app/src/main/java/Fixture.kt'
            source.parent.mkdir(parents=True)
            source.write_text(kotlin, encoding='utf-8')
            return subprocess.run(
                [sys.executable, '-B', str(CHECKER)], cwd=root,
                capture_output=True, text=True, check=False,
            )

    def test_non_translatable_resource_can_fall_back_to_default(self):
        result = self.check_resources(
            '<string name="badge" translatable="false">ID</string>',
            '', 'val badge = R.string.badge',
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_missing_translations_are_still_rejected(self):
        for attribute in ('', ' translatable="true"'):
            with self.subTest(attribute=attribute):
                result = self.check_resources(
                    f'<string name="label"{attribute}>Search</string>', '',
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("missing keys from default resources: ['label']", result.stdout)

    def test_localized_attribute_cannot_exempt_another_missing_key(self):
        result = self.check_resources(
            '<string name="label">Search</string><string name="other">Other</string>',
            '<string name="other" translatable="false">Other</string>',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing keys from default resources: ['label']", result.stdout)

    def test_extra_keys_are_still_rejected(self):
        result = self.check_resources(
            '<string name="badge" translatable="false">ID</string>',
            '<string name="unknown">Unknown</string>',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("extra keys not in default resources: ['unknown']", result.stdout)

    def test_explicit_override_still_checks_placeholders(self):
        result = self.check_resources(
            '<string name="badge" translatable="false">%1$s</string>',
            '<string name="badge">%1$d</string>',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn('placeholder mismatch for badge', result.stdout)

    def test_missing_resource_references_are_still_rejected(self):
        result = self.check_resources(
            '<string name="badge" translatable="false">ID</string>',
            '', 'val label = R.string.unknown',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Kotlin references missing string resources: ['unknown']", result.stdout)
