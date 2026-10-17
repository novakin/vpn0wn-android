import hashlib
import io
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MAKEFILE = ROOT / 'tunnel/tools/libwg-go/Makefile'
PATCH_NAME = 'goruntime-boottime-over-monotonic.diff'


class GoRuntimePreparationTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix='vpn0wn-go-preparation-')
        self.addCleanup(self.workspace.cleanup)
        self.directory = pathlib.Path(self.workspace.name)
        shutil.copy2(MAKEFILE, self.directory / 'Makefile')
        self.build = self.directory / 'build'
        self.runtime = self.build / 'go-fixture'
        self.marker = self.runtime / '.prepared'
        self.archive = self.directory / 'cache/caches/golang/gofixture.linux-amd64.tar.gz'
        self.archive.parent.mkdir(parents=True)
        with tarfile.open(self.archive, 'w:gz') as archive:
            content = b'original runtime\n'
            member = tarfile.TarInfo('go/runtime.txt')
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        self.archive_hash = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.write_patch('first patch')
        self.command = [
            'make', '--no-print-directory', str(self.marker),
            'BUILDDIR=' + str(self.build),
            'GRADLE_USER_HOME=' + str(self.directory / 'cache'),
            'GO_VERSION=fixture', 'GO_PLATFORM=linux-amd64',
            'GO_HASH_linux-amd64=' + self.archive_hash,
        ]

    def write_patch(self, replacement, original='original runtime'):
        (self.directory / PATCH_NAME).write_text(
            '--- a/runtime.txt\n+++ b/runtime.txt\n@@ -1 +1 @@\n'
            '-' + original + '\n+' + replacement + '\n'
        )

    def prepare(self, success=True, env=None):
        result = subprocess.run(self.command, cwd=self.directory, env=env,
                                capture_output=True, text=True, timeout=15)
        message = result.stdout + result.stderr
        if success:
            self.assertEqual(result.returncode, 0, message)
            self.assertTrue(self.marker.is_file(), message)
        else:
            self.assertNotEqual(result.returncode, 0, message)
            self.assertFalse(self.marker.exists(), message)
        return message

    def invalidate(self, changed_input):
        for input_path in (self.archive, self.directory / PATCH_NAME, self.directory / 'Makefile'):
            os.utime(input_path, (1, 1))
        os.utime(self.marker, (2, 2))
        os.utime(changed_input, (3, 3))

    def test_same_version_patch_edit_replaces_only_owned_runtime(self):
        self.prepare()
        unrelated = self.build / 'unrelated-evidence'
        unrelated.write_text('preserve')
        (self.runtime / 'stale-generated-file').write_text('old')
        self.write_patch('second patch')
        self.invalidate(self.directory / PATCH_NAME)
        self.prepare()
        self.assertEqual((self.runtime / 'runtime.txt').read_text(), 'second patch\n')
        self.assertFalse((self.runtime / 'stale-generated-file').exists())
        self.assertEqual(unrelated.read_text(), 'preserve')

    def test_unchanged_inputs_reuse_preparation(self):
        self.prepare()
        original_time = self.marker.stat().st_mtime_ns
        sentinel = self.runtime / 'compiled-cache-sentinel'
        sentinel.write_text('preserve')
        self.prepare()
        self.assertEqual(self.marker.stat().st_mtime_ns, original_time)
        self.assertEqual(sentinel.read_text(), 'preserve')

    def test_makefile_edit_invalidates_existing_preparation(self):
        self.prepare()
        (self.runtime / 'stale-generated-file').write_text('old')
        with (self.directory / 'Makefile').open('a') as makefile:
            makefile.write('\n# changed preparation recipe\n')
        self.invalidate(self.directory / 'Makefile')
        self.prepare()
        self.assertFalse((self.runtime / 'stale-generated-file').exists())

    def test_failed_patch_has_no_marker_and_retry_uses_pristine_archive(self):
        self.prepare()
        self.write_patch('bad patch', original='missing original')
        self.invalidate(self.directory / PATCH_NAME)
        self.prepare(success=False)
        (self.runtime / 'failed-only').write_text('partial preparation')
        self.write_patch('recovered patch')
        self.prepare()
        self.assertEqual((self.runtime / 'runtime.txt').read_text(), 'recovered patch\n')
        self.assertFalse((self.runtime / 'runtime.txt.rej').exists())
        self.assertFalse((self.runtime / 'failed-only').exists())

    def test_changed_verified_archive_replaces_preparation(self):
        self.prepare()
        with tarfile.open(self.archive, 'w:gz') as archive:
            for name, content in (('runtime.txt', b'original runtime\n'), ('compiler.txt', b'new archive\n')):
                member = tarfile.TarInfo('go/' + name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        self.command[-1] = 'GO_HASH_linux-amd64=' + hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.invalidate(self.archive)
        self.prepare()
        self.assertTrue((self.runtime / 'compiler.txt').is_file())
        self.assertEqual((self.runtime / 'compiler.txt').read_text(), 'new archive\n')
        self.assertEqual((self.runtime / 'runtime.txt').read_text(), 'first patch\n')

    def test_changed_archive_is_verified_before_acceptance(self):
        self.prepare()
        self.archive.write_bytes(b'corrupted archive')
        self.invalidate(self.archive)
        output = self.prepare(success=False)
        self.assertIn('FAILED', output)

    def test_verified_but_invalid_archive_has_no_marker(self):
        original_archive = self.archive.read_bytes()
        self.archive.write_bytes(b'not a gzip archive')
        self.command[-1] = 'GO_HASH_linux-amd64=' + hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.prepare(success=False)
        (self.runtime / 'failed-only').write_text('partial extraction')
        self.archive.write_bytes(original_archive)
        self.command[-1] = 'GO_HASH_linux-amd64=' + self.archive_hash
        self.prepare()
        self.assertEqual((self.runtime / 'runtime.txt').read_text(), 'first patch\n')
        self.assertFalse((self.runtime / 'failed-only').exists())

    def test_parallel_preparation_rechecks_freshness_inside_shared_lock(self):
        wrappers = self.directory / 'bin'
        wrappers.mkdir()
        tar = wrappers / 'tar'
        tar.write_text('#!/bin/sh\nprintf "extract\\n" >> "$EXTRACTION_LOG"\n'
                       'sleep 1\nexec "$REAL_TAR" "$@"\n')
        tar.chmod(0o755)
        extraction_log = self.directory / 'extractions'
        env = dict(os.environ, PATH=str(wrappers) + os.pathsep + os.environ['PATH'],
                   EXTRACTION_LOG=str(extraction_log), REAL_TAR=shutil.which('tar'))
        first = subprocess.Popen(self.command, cwd=self.directory, env=env,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        second = subprocess.Popen(self.command, cwd=self.directory, env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            first_output = first.communicate(timeout=15)[0]
            second_output = second.communicate(timeout=15)[0]
        finally:
            for process in (first, second):
                if process.poll() is None:
                    process.kill()
                    process.communicate()
        self.assertEqual(first.returncode, 0, first_output)
        self.assertEqual(second.returncode, 0, second_output)
        self.assertEqual(extraction_log.read_text().splitlines(), ['extract'])
        self.assertEqual((self.runtime / 'runtime.txt').read_text(), 'first patch\n')


if __name__ == '__main__':
    unittest.main()
