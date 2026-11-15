"""Offline checks for portable JDK selection and Gradle classpath loading."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from run import java_environment, load_classpath


class JavaEnvironmentTest(unittest.TestCase):
    def settings(self, home, version='17'):
        return subprocess.CompletedProcess([], 0, stdout='', stderr=(
            f'    java.home = {home}\n'
            f'    java.specification.version = {version}\n'
        ))

    def make_jdk(self, directory):
        home = Path(directory) / 'JDK 17'
        (home / 'bin').mkdir(parents=True)
        (home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')).touch()
        return home.resolve()

    def test_java_home_takes_priority_and_preserves_custom_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            home = self.make_jdk(directory)
            original = {'JAVA_HOME': str(home), 'PATH': 'existing-tools',
                        'GRADLE_USER_HOME': str(Path(directory) / 'gradle cache')}
            with patch('run.subprocess.run', return_value=self.settings(home)) as run, \
                    patch('run.shutil.which') as which:
                env, java, javac = java_environment(original)
            which.assert_not_called()
            self.assertEqual(run.call_args.args[0][0], java)
            self.assertEqual(Path(java).parent, home / 'bin')
            self.assertEqual(Path(javac).parent, home / 'bin')
            self.assertEqual(env['GRADLE_USER_HOME'], original['GRADLE_USER_HOME'])
            self.assertEqual(env['PATH'], str(home / 'bin') + os.pathsep + original['PATH'])
            self.assertEqual(original['PATH'], 'existing-tools')

    def test_path_launcher_uses_the_jdk_reported_by_java(self):
        with tempfile.TemporaryDirectory() as directory:
            home = self.make_jdk(directory)
            launcher = str(Path(directory) / 'java-shim')
            with patch('run.shutil.which', return_value=launcher) as which, \
                    patch('run.subprocess.run', return_value=self.settings(home)) as run:
                env, java, javac = java_environment({'PATH': 'selected-tools'})
            which.assert_called_once_with('java.exe' if os.name == 'nt' else 'java',
                                          path='selected-tools')
            self.assertEqual(run.call_args.args[0][0], launcher)
            self.assertEqual(env['JAVA_HOME'], str(home))
            self.assertEqual(Path(java).parent, home / 'bin')
            self.assertEqual(Path(javac).parent, home / 'bin')

    def test_other_java_versions_and_missing_compiler_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for version in ('17', '21'):
                with self.subTest(version=version), \
                        patch('run.subprocess.run', return_value=self.settings(home, version)):
                    with self.assertRaisesRegex(ValueError, 'JDK 17'):
                        java_environment({'JAVA_HOME': str(home)})

    def test_missing_java_gives_configuration_guidance(self):
        with patch('run.shutil.which', return_value=None), patch('run.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'JAVA_HOME'):
                java_environment({'PATH': ''})
        run.assert_not_called()


class ClasspathTest(unittest.TestCase):
    def test_gradle_paths_support_spaces_and_are_deduplicated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'Gradle cache'
            root.mkdir()
            jar = root / 'runtime.jar'
            jar.touch()
            manifest = root / 'classpath.json'
            manifest.write_text(json.dumps([str(jar), str(jar)]), encoding='utf-8')
            self.assertEqual(load_classpath(manifest), [jar])

    def test_invalid_or_missing_dependencies_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'classpath.json'
            for entries in ([], {}, 'runtime.jar', [None], [''], [str(root / 'missing.jar')], [str(root)]):
                with self.subTest(entries=entries):
                    manifest.write_text(json.dumps(entries), encoding='utf-8')
                    with self.assertRaises(ValueError):
                        load_classpath(manifest)


if __name__ == '__main__':
    unittest.main()
