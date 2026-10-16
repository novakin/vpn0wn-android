import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOLS_AVAILABLE = shutil.which('bsdtar') and shutil.which('xmllint')


@unittest.skipUnless(TOOLS_AVAILABLE, 'requires bsdtar and xmllint')
class TranslationImportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='vpn0wn-translation-')
        self.addCleanup(self.temporary.cleanup)
        self.work = pathlib.Path(self.temporary.name)
        self.resources = self.work / 'ui/src/main/res'
        self.resources.mkdir(parents=True)

    def import_resources(self, entries):
        archive = self.work / 'translations.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            for qualifier, content in entries.items():
                output.writestr(
                    'wireguard-android/project/export/android/res/' + qualifier + '/strings.xml',
                    content,
                )
        return subprocess.run(
            ['bash', str(ROOT / 'sync-crowdin.sh'), str(archive)],
            cwd=self.work, capture_output=True, text=True, timeout=20,
        )

    def test_archive_filenames_remain_literal(self):
        qualifiers = [
            'values space', 'values-quote\'"', 'values-$(touch INJECTED)',
            'values-`touch INJECTED`', 'values-;touch INJECTED;#', 'values-[x]',
            'values-line\nbreak',
        ]
        content = '<resources><string name="sample">value</string></resources>'
        result = self.import_resources(dict.fromkeys(qualifiers, content))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.work / 'INJECTED').exists())
        for qualifier in qualifiers:
            with self.subTest(qualifier=qualifier):
                self.assertEqual(content, (self.resources / qualifier / 'strings.xml').read_text())

    def test_only_empty_resource_directory_is_removed(self):
        sibling = self.resources / 'sibling.txt'
        sibling.write_text('preserve')
        result = self.import_resources({
            'values-empty $(touch INJECTED)': '<resources/>',
            'values-full': '<resources><string name="sample">value</string></resources>',
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.resources / 'values-empty $(touch INJECTED)').exists())
        self.assertTrue((self.resources / 'values-full/strings.xml').exists())
        self.assertEqual('preserve', sibling.read_text())
        self.assertFalse((self.work / 'INJECTED').exists())

    def test_malformed_xml_fails_without_deleting_its_directory(self):
        result = self.import_resources({'values-invalid': '<resources>'})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('<resources>', (self.resources / 'values-invalid/strings.xml').read_text())

    def test_empty_trailing_newline_directory_preserves_populated_sibling(self):
        populated = '<resources><string name="sample">value</string></resources>'
        sentinel = self.resources / 'sentinel.txt'
        sentinel.write_text('preserve')
        result = self.import_resources({
            'values-sibling': populated,
            'values-sibling\n': '<resources/>',
            '\n': '<resources/>',
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.resources / 'values-sibling\n').exists())
        self.assertFalse((self.resources / '\n').exists())
        self.assertEqual(populated, (self.resources / 'values-sibling/strings.xml').read_text())
        self.assertEqual('preserve', sentinel.read_text())

    def test_invalid_archive_stops_before_cleanup(self):
        empty = self.resources / 'values-empty/strings.xml'
        empty.parent.mkdir()
        empty.write_text('<resources/>')
        archive = self.work / 'invalid.zip'
        archive.write_text('not an archive')
        result = subprocess.run(
            ['bash', str(ROOT / 'sync-crowdin.sh'), str(archive)],
            cwd=self.work, capture_output=True, text=True, timeout=20,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(empty.exists())


if __name__ == '__main__':
    unittest.main()
