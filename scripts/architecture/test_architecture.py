import copy
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from bytecode import check_bytecode, jdk_tool
from check import documentation, verify
from policy import Policy, cycles
from sources import analyze_file, check_sources, tokens

ROOT = Path(__file__).resolve().parents[2]
NS = "com.andsi.airlyrics"


class SourcePolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.policy = Policy.load(ROOT / "scripts/architecture/policy.json")
        self.sources = self.root / self.policy.data["source_roots"][0]

    def analyze(self, body, package="ui", filename="Example.kt"):
        path = self.sources / NS.replace(".", "/") / package.replace(".", "/") / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"package {NS}.{package}\n{body}")
        errors, refs, _ = analyze_file(path, self.sources, self.policy)
        return errors + verify(refs, self.policy)

    def test_imports_aliases_and_typealiases_cannot_hide_dependencies(self):
        cases = [
            f"import {NS}.lyrics.storage.LyricsStorage",
            f"import {NS}.media.model.CurrentMediaInfo as Media",
            f"typealias Media = {NS}.media.model.CurrentMediaInfo",
            f"val session by lazy {{ {NS}.app.interaction.LyricsEditorSession() }}",
            f"val target: {NS}.media.model.CurrentMediaInfo? = null",
        ]
        for code in cases:
            with self.subTest(code=code):
                errors = self.analyze(code)
                self.assertTrue(any("must not depend" in e for e in errors), errors)
                self.assertTrue(any("Example.kt:2:" in e for e in errors), errors)

    def test_comments_and_literal_text_are_not_code(self):
        bad = f"{NS}.app.Hidden"
        code = '\n'.join([
            f"// import {bad}",
            f"/* outer /* nested {bad} */ {bad} */",
            f'val text = "{bad}"',
            f'val raw = """{bad}"""',
            "val character = '\\''",
        ])
        self.assertEqual([], self.analyze(code))

    def test_kotlin_template_expressions_are_code_in_regular_and_raw_strings(self):
        for code in [
            'val text = "${' + NS + '.app.Hidden()}"',
            'val text = """literal ${' + NS + '.app.Hidden()}"""',
            'val text = "${run { "${' + NS + '.app.Hidden()}" }}"',
        ]:
            with self.subTest(code=code):
                self.assertTrue(any("must not depend" in e for e in self.analyze(code)))

    def test_backtick_keyword_identifier_is_not_a_package_declaration(self):
        self.assertEqual([], self.analyze('val `package` = "value"'))

    def test_multiline_and_backtick_qualified_names_are_checked(self):
        code = 'val x = com . andsi . airlyrics\n . `app` /* note */ . Hidden()'
        self.assertTrue(any("must not depend" in e for e in self.analyze(code)))

    def test_legal_shared_values_aliases_and_generated_resources(self):
        code = f"""import {NS}.core.model.SongIdentity as Song
import {NS}.core.color.*
import {NS}.R
val icon = {NS}.R.drawable.icon
val flags = {NS}.BuildConfig.DEBUG
val feedback: {NS}.feedback.AirFeedback? = null
"""
        self.assertEqual([], self.analyze(code))

    def test_contract_allowance_does_not_allow_concrete_implementation(self):
        self.assertTrue(self.analyze(f"import {NS}.feedback.ToastAirFeedback"))
        self.assertTrue(self.analyze(f"import {NS}.core.prefs.prefs"))
        self.assertTrue(self.analyze(f"import {NS}.core.*"))

    def test_precise_i18n_scope_and_no_reverse_dependency(self):
        self.assertEqual([], self.analyze(f"import {NS}.lyrics.storage.LyricsStorage", "i18n.lyrics"))
        self.assertTrue(self.analyze(f"import {NS}.lyrics.storage.LyricsStorage", "i18n"))
        self.assertTrue(self.analyze(f"import {NS}.lyrics.LyricsRepository", "i18n.lyrics"))
        self.assertTrue(self.analyze(f"import {NS}.i18n.lyrics.localizedLyricsLookupMessage", "lyrics"))
        self.assertTrue(self.analyze(f"import {NS}.i18n.lyrics.localizedLyricsLookupMessage", "ui"))
        self.assertEqual([], self.analyze(f"import {NS}.i18n.LocalizedServiceContext", "media"))

    def test_unregistered_source_and_target_fail_closed(self):
        self.assertTrue(any("unregistered source" in e for e in self.analyze("class NewFeature", "newfeature")))
        self.assertTrue(any("unregistered target" in e for e in self.analyze(f"import {NS}.newfeature.Hidden")))
        self.assertTrue(any("unregistered target" in e for e in self.analyze(f"import {NS}.Rogue.Hidden")))

    def test_displayscope_has_explicit_consumers(self):
        self.assertEqual([], self.analyze(f"import {NS}.displayscope.DisplayScopePolicy", "floating"))
        self.assertTrue(self.analyze(f"import {NS}.displayscope.DisplayScopePolicy", "ui"))
        self.assertTrue(self.analyze(f"import {NS}.app.MainActivity", "displayscope"))

    def test_annotation_references_are_checked_in_source(self):
        # jdeps omits some annotation attributes, so source checks must not be skipped.
        self.assertTrue(self.analyze(f"@{NS}.lyrics.Mark class Example"))
        self.assertTrue(self.analyze(f"import {NS}.lyrics.Mark as Flag\n@Flag class Example"))

    def test_java_imports_and_inline_qualified_types(self):
        self.assertTrue(self.analyze(f"import {NS}.media.model.CurrentMediaInfo;", filename="Example.java"))
        self.assertTrue(self.analyze(f"class Example {{ {NS}.app.Hidden value; }}", filename="Example.java"))

    def test_directory_package_mismatch_and_empty_scan_fail(self):
        errors, _, _ = check_sources(self.root, self.policy)
        self.assertTrue(errors)
        path = self.sources / "wrong/Example.kt"
        path.parent.mkdir(parents=True)
        path.write_text(f"package {NS}.ui\nclass Example")
        errors, _, _ = analyze_file(path, self.sources, self.policy)
        self.assertTrue(any("directory does not match" in e for e in errors))

    def test_new_production_source_set_cannot_silently_escape_checks(self):
        self.analyze("class Example")
        path = self.root / "app/src/release/kotlin/com/andsi/airlyrics/ui/ReleaseOnly.kt"
        path.parent.mkdir(parents=True)
        path.write_text(f"package {NS}.ui\nclass ReleaseOnly")
        errors, _, _ = check_sources(self.root, self.policy)
        self.assertTrue(any("unregistered production source root" in e for e in errors))
        path.unlink()
        path = self.root / "app/src/test/java/AnyTest.kt"
        path.parent.mkdir(parents=True)
        path.write_text("package tests\nclass AnyTest")
        self.assertEqual([], check_sources(self.root, self.policy)[0])

    def test_unterminated_lexical_construct_fails(self):
        for code in ('/* broken', 'val x = "broken', 'val x = `broken'):
            with self.subTest(code=code), self.assertRaises(ValueError):
                tokens(code)

    def test_policy_requires_scoped_reasoned_allowances(self):
        for allowance in [
            {"group": "unknown", "reason": "bad"},
            {"group": "core", "reason": ""},
            {"group": "core", "symbol": ["core.model"], "reason": "misspelled restriction"},
            {"group": "core", "symbols": ["*"], "reason": "too broad"},
            {"group": "core", "symbols": ["media.model"], "reason": "wrong owner"},
        ]:
            data = copy.deepcopy(self.policy.data)
            data["groups"]["ui"]["allows"] = [allowance]
            with self.subTest(allowance=allowance), self.assertRaises(ValueError):
                Policy(data)

    def test_nested_types_are_allowed_only_with_their_declared_contract(self):
        self.assertIsNone(self.policy.violation("ui", NS + ".feedback.AirFeedback$Result"))
        self.assertIsNotNone(self.policy.violation("ui", NS + ".feedback.AirFeedbackImpl"))
        self.assertIsNone(self.policy.violation("i18n.lyrics", NS + ".lyrics.storage.LyricsStorage$LocalLyricsItem"))

    def test_allowed_and_actual_cycles_report_paths(self):
        self.assertEqual(["app -> ui -> app"], cycles({"app": {"ui"}, "ui": {"app"}}))
        errors = verify([("ui", NS + ".app.Host", "Example.kt:2"),
                         ("app", NS + ".ui.View", "Host.kt:2")], self.policy)
        self.assertTrue(any("Dependency cycle: app -> ui -> app" in e for e in errors))
        data = copy.deepcopy(self.policy.data)
        data["groups"]["lyrics"]["allows"].append({"group": "i18n.lyrics", "reason": "cycle"})
        with self.assertRaisesRegex(ValueError, "Allowed dependency cycle"):
            Policy(data)

    def test_documentation_is_derived_from_policy_and_read_only_by_default(self):
        for name in ("ARCHITECTURE.md", "ARCHITECTURE.zh-CN.md"):
            path = self.root / "docs" / name
            path.parent.mkdir(exist_ok=True)
            path.write_text("<!-- architecture-policy:start -->stale<!-- architecture-policy:end -->")
        before = (self.root / "docs/ARCHITECTURE.md").read_text()
        self.assertEqual(2, len(documentation(self.root, self.policy)))
        self.assertEqual(before, (self.root / "docs/ARCHITECTURE.md").read_text())
        self.assertEqual([], documentation(self.root, self.policy, update=True))
        self.assertEqual([], documentation(self.root, self.policy))


class BytecodeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        cls.classes = cls.root / "classes"
        fixtures = {
            "lyrics/Hidden.java": 'public class Hidden { public String text() { return "x"; } }',
            "lyrics/Mark.java": '@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Mark {}',
            "core/model/Identity.java": 'public class Identity {}',
            "core/Factory.java": f'public class Factory {{ public static {NS}.lyrics.Hidden get() {{ return null; }} }}',
            "ui/Inferred.java": f'public class Inferred {{ public Object text() {{ var inferred = {NS}.core.Factory.get(); return inferred.text(); }} }}',
            "ui/Generic.java": f'public class Generic {{ public java.util.List<{NS}.lyrics.Hidden> values; }}',
            "ui/Annotated.java": f'@{NS}.lyrics.Mark public class Annotated {{}}',
            "ui/Inherited.java": f'public class Inherited extends {NS}.lyrics.Hidden {{}}',
            "ui/Legal.java": f'public class Legal {{ public {NS}.core.model.Identity value; public String literal = "{NS}.lyrics.Hidden"; }}',
        }
        sources = []
        for name, code in fixtures.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            package = name.rsplit("/", 1)[0].replace("/", ".")
            path.write_text(f"package {NS}.{package};\n{code}")
            sources.append(str(path))
        subprocess.run([jdk_tool("javac"), "--release", "17", "-d", str(cls.classes), *sources], check=True, capture_output=True)
        cls.policy = Policy.load(ROOT / "scripts/architecture/policy.json")

    def test_resolved_inference_generic_and_inheritance_dependencies(self):
        errors, refs, _ = check_bytecode([self.classes], self.policy)
        self.assertEqual([], errors)
        violations = verify(refs, self.policy)
        for name in ("Inferred", "Generic", "Inherited"):
            with self.subTest(name=name):
                self.assertTrue(any(f"ui.{name}: ui must not depend on {NS}.lyrics" in e for e in violations), violations)
        self.assertFalse(any("ui.Legal:" in e for e in violations), violations)

    def test_missing_internal_class_is_not_ignored_with_external_libraries(self):
        jar = self.root / "missing.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for path in self.classes.rglob("*.class"):
                if path.name != "Hidden.class":
                    archive.write(path, path.relative_to(self.classes))
        errors, _, _ = check_bytecode([jar], self.policy)
        self.assertTrue(any("missing project dependency" in e for e in errors))

    def test_empty_artifact_cannot_pass(self):
        empty = self.root / "empty"
        empty.mkdir(exist_ok=True)
        with self.assertRaisesRegex(ValueError, "No project classes"):
            check_bytecode([empty], self.policy)

    def test_unknown_compiled_source_is_rejected(self):
        source = self.root / "Unknown.java"
        source.write_text(f"package {NS}.newfeature; public class Unknown {{}}")
        output = self.root / "unknown-classes"
        subprocess.run([jdk_tool("javac"), "--release", "17", "-d", str(output), str(source)], check=True, capture_output=True)
        errors, _, _ = check_bytecode([output], self.policy)
        self.assertTrue(any("unregistered source class" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
