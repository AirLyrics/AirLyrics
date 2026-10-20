import unittest
from checks import placeholders, stray_percent_positions, kotlin_cjk_literals

class LocalizationChecksTest(unittest.TestCase):
    def test_indexed_reordering(self):
        self.assertEqual(placeholders('%1$s: %2$d'), placeholders('%2$d — %1$s'))
    def test_missing_and_wrong_type(self):
        self.assertNotEqual(placeholders('%1$s %2$d'), placeholders('%1$s'))
        self.assertNotEqual(placeholders('%1$s'), placeholders('%1$d'))
    def test_ordinary_order_is_semantic(self):
        self.assertNotEqual(placeholders('%s %d'), placeholders('%d %s'))
    def test_escaped_percent_is_not_an_argument(self):
        self.assertEqual(placeholders('%%s %s'), placeholders('%s'))
        self.assertEqual([], stray_percent_positions('%% %1$s'))
        self.assertEqual([0], stray_percent_positions('%'))
    def test_relative_argument(self):
        self.assertEqual(placeholders('%s %<s'), placeholders('%1$s %1$s'))
    def test_comments_are_not_literals(self):
        self.assertEqual([], list(kotlin_cjk_literals('// 中文\n/* 外 /* 内 */ 文 */ val a = "English"')))
    def test_regular_raw_and_escaped_strings(self):
        self.assertEqual(2, len(list(kotlin_cjk_literals('val a = "中文\\\"文本"\nval b = """中文"""'))))

    def test_template_comments_are_not_ui_text(self):
        self.assertEqual([], list(kotlin_cjk_literals('val x = "${ /* 中文 */ value }"')))
        self.assertEqual(1, len(list(kotlin_cjk_literals('val x = "${value("中文")}"'))))
    def test_unicode_escapes_are_checked(self):
        self.assertEqual(1, len(list(kotlin_cjk_literals(r'val x = "\u4e2d"'))))

if __name__ == '__main__': unittest.main()
