"""Offline checks for configurable Apple lyric-fetch credentials."""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import apple_lyrics_fetch as fetch


class AppleLyricsFetchTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        header = self.encode({'alg': 'HS256', 'typ': 'JWT'})
        payload = self.encode({'iss': 'AMPWebPlay-test', 'padding': 'synthetic' * 20})
        self.token = f'{header}.{payload}.synthetic-signature'
        self.index = self.root / 'apple-index.js'
        self.index.write_text(f'const token = "{self.token}";', encoding='utf-8')
        self.media = self.root / 'media-token.txt'
        self.media.write_text('synthetic-media-token\n', encoding='utf-8')
        environment = patch.dict(os.environ, {
            'AIRLYRICS_APPLE_INDEX_JS': '',
            'AIRLYRICS_APPLE_MEDIA_TOKEN_FILE': '',
        })
        environment.start()
        self.addCleanup(environment.stop)

    @staticmethod
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode('utf-8')).decode('ascii').rstrip('=')

    def arguments(self):
        return ['Example song', 'Example artist', '--out', str(self.root / 'output'),
                '--storefronts', 'us']

    def check_fetch(self, extra_arguments):
        stdout = io.StringIO()
        with patch.object(fetch, 'http', return_value=(200, b'{"results": {}}')) as http, \
                contextlib.redirect_stdout(stdout):
            self.assertEqual(fetch.main(self.arguments() + extra_arguments), 1)
        http.assert_called_once()
        self.assertEqual(http.call_args.args[1:], (self.token, 'synthetic-media-token'))
        self.assertNotIn(self.token, stdout.getvalue())
        self.assertNotIn('synthetic-media-token', stdout.getvalue())

    def test_file_paths_can_be_supplied_by_environment(self):
        with patch.dict(os.environ, {
            'AIRLYRICS_APPLE_INDEX_JS': str(self.index),
            'AIRLYRICS_APPLE_MEDIA_TOKEN_FILE': str(self.media),
        }):
            self.check_fetch([])

    def test_explicit_paths_override_environment(self):
        with patch.dict(os.environ, {
            'AIRLYRICS_APPLE_INDEX_JS': str(self.root / 'unused-script'),
            'AIRLYRICS_APPLE_MEDIA_TOKEN_FILE': str(self.root / 'unused-token'),
        }):
            self.check_fetch(['--index-js', str(self.index), '--media-token-file', str(self.media)])

    def test_missing_configuration_fails_before_network_or_output_creation(self):
        for options, expected in (
                (['--media-token-file', str(self.media)], 'Set --index-js'),
                (['--index-js', str(self.index)], 'Set --media-token-file')):
            with self.subTest(options=options), patch.object(fetch, 'http') as http, \
                    contextlib.redirect_stderr(io.StringIO()) as stderr:
                with self.assertRaises(SystemExit) as error:
                    fetch.main(self.arguments() + options)
                self.assertEqual(error.exception.code, 2)
                self.assertIn(expected, stderr.getvalue())
                self.assertFalse((self.root / 'output').exists())
                http.assert_not_called()

    def test_invalid_credentials_fail_without_printing_contents_or_local_paths(self):
        empty = self.root / 'empty-token.txt'
        empty.write_text('', encoding='utf-8')
        invalid = self.root / 'invalid-script.js'
        invalid.write_text('const token = "synthetic-private-content";', encoding='utf-8')
        for index, media in ((self.root / 'missing.js', self.media),
                             (self.index, self.root / 'missing-token'),
                             (self.index, empty), (invalid, self.media)):
            with self.subTest(index=index, media=media), patch.object(fetch, 'http') as http, \
                    contextlib.redirect_stderr(io.StringIO()) as stderr:
                with self.assertRaises(SystemExit) as error:
                    fetch.main(self.arguments() + ['--index-js', str(index),
                                                   '--media-token-file', str(media)])
                self.assertEqual(error.exception.code, 2)
                self.assertNotIn(str(self.root), stderr.getvalue())
                self.assertNotIn('synthetic-private-content', stderr.getvalue())
                self.assertNotIn(self.token, stderr.getvalue())
                self.assertFalse((self.root / 'output').exists())
                http.assert_not_called()


if __name__ == '__main__':
    unittest.main()
