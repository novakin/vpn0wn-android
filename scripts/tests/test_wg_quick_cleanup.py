import pathlib
import re
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tunnel/tools/wireguard-tools/src/wg-quick/android.c'
PATCH = ROOT / 'tunnel/tools/patches/wg-quick-literal-cleanup.patch'


def function(source, name):
    declarations = list(re.finditer(r'^static [^\n]+\b' + name + r'\([^\n]*\)\n\{', source, re.MULTILINE))
    if len(declarations) != 1:
        raise ValueError('Expected one production function: ' + name)
    start = declarations[0].start()
    end = source.index('\n}', declarations[0].end()) + len('\n}')
    return source[start:end]


HARNESS = r'''
#define _GNU_SOURCE
#include <errno.h>
#include <regex.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define _cleanup_free_ __attribute__((cleanup(freep)))
#define _cleanup_regfree_ __attribute__((cleanup(regfree)))
struct command_buffer { FILE *stream; char *line; size_t len; };
static FILE *ipv4, *ipv6, *routes;
static void free_command_buffer(struct command_buffer *buffer) { free(buffer->line); }
#define DEFINE_CMD(name) __attribute__((cleanup(free_command_buffer))) struct command_buffer name = {0};
static char *cmd_ret(struct command_buffer *buffer, const char *command) {
    if (command) {
        if (!strcmp(command, "iptables-save")) buffer->stream = ipv4;
        else if (!strcmp(command, "ip6tables-save")) buffer->stream = ipv6;
        else if (!strcmp(command, "ip rule show")) buffer->stream = routes;
        else abort();
    }
    if (!buffer->stream) abort();
    return getline(&buffer->line, &buffer->len, buffer->stream) < 0 ? NULL : buffer->line;
}
static void cmd(const char *format, ...) {
    va_list arguments;
    va_start(arguments, format); vprintf(format, arguments); va_end(arguments);
    putchar('\n');
}
static void cndc(const char *format, ...) {
    va_list arguments;
    printf("netd: ");
    va_start(arguments, format); vprintf(format, arguments); va_end(arguments);
    putchar('\n');
}
%s
int main(int argc, char **argv) {
    if (argc != 5) return 2;
    ipv4 = fopen(argv[2], "r"); ipv6 = fopen(argv[3], "r"); routes = fopen(argv[4], "r");
    if (!ipv4 || !ipv6 || !routes) return 3;
    del_if(argv[1]);
    fclose(ipv4); fclose(ipv6); fclose(routes);
    return 0;
}
'''


class WgQuickCleanupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='vpn0wn-cleanup-')
        cls.directory = pathlib.Path(cls.workspace.name)
        patched = cls.directory / 'android.c'
        subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '--output', str(patched), str(SOURCE), str(PATCH)],
                       check=True, capture_output=True, timeout=5)
        source = patched.read_text()
        owners = '\n'.join(function(source, name) for name in ('xmalloc', 'xregcomp', 'concat', 'freep', 'del_if'))
        unit = cls.directory / 'cleanup.c'
        unit.write_text(HARNESS % owners)
        cls.executable = cls.directory / 'cleanup'
        subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', str(unit), '-o', str(cls.executable)], check=True, timeout=30)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def cleanup(self, name, ipv4=(), ipv6=(), routes=()):
        paths = []
        for filename, lines in zip(('ipv4', 'ipv6', 'routes'), (ipv4, ipv6, routes)):
            path = self.directory / filename
            path.write_text('\n'.join(lines))
            paths.append(str(path))
        result = subprocess.run([str(self.executable), name, *paths], check=True, capture_output=True, text=True, timeout=5)
        return result.stdout.splitlines()

    @staticmethod
    def rule(name):
        return '-A OUTPUT -m comment --comment "wireguard rule ' + name + '" -j ACCEPT'

    def test_literal_names_preserve_other_firewall_rules(self):
        for name, other in (('a.b', 'aXb'), ('a+b', 'aaab'), ('a.+b', 'aXXb'), ('z_=.-+x', 'z_=X--x')):
            with self.subTest(name=name):
                owned, unrelated = self.rule(name), self.rule(other)
                self.assertEqual(self.cleanup(name, (unrelated, owned), (unrelated, owned)),
                                 ['ip link del ' + name, 'iptables -D ' + owned[3:], 'ip6tables -D ' + owned[3:]])

    def test_already_patched_input_is_rejected(self):
        patched = self.directory / 'android.c'
        output = self.directory / 'reapplied.c'
        result = subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '--output', str(output),
                                 str(patched), str(PATCH)], capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)

    def test_network_destroy_uses_literal_requested_table(self):
        for name, other in (('a.b', 'aXb'), ('a+b', 'aaab'), ('wg0', 'wg01')):
            with self.subTest(name=name):
                rules = ('100: from all fwmark 0xc10002/0xcffff lookup ' + other,
                         '101: from all fwmark 0xc10001/0xcffff lookup ' + name)
                self.assertEqual(self.cleanup(name, routes=rules),
                                 ['ip link del ' + name, 'netd: network destroy 65537'])

    def test_no_owned_table_causes_no_network_destroy(self):
        for name, other in (('a.b', 'aXb'), ('a+b', 'aaab'), ('wg0', 'wg01'), ('wg0', 'wg0-extra')):
            with self.subTest(name=name):
                self.assertEqual(self.cleanup(name, routes=('100: from all fwmark 0xc10002/0xcffff lookup ' + other,)),
                                 ['ip link del ' + name])

    def test_exact_table_accepts_end_space_tab_and_newline(self):
        for suffix in ('', ' suppress_prefixlength 0', '\tprotocol static', '\n'):
            with self.subTest(suffix=suffix):
                self.assertEqual(self.cleanup('wg0', routes=('100: from all fwmark 0xc10001/0xcffff lookup wg0' + suffix,)),
                                 ['ip link del wg0', 'netd: network destroy 65537'])

    def test_plain_and_maximum_length_names_still_clean_both_families(self):
        for name in ('wg0', 'abc_DEF-123=xyz', 'a.+a.+a.+a.+a.+'):
            with self.subTest(name=name):
                self.assertLessEqual(len(name), 15)
                owned = self.rule(name)
                rules = ('100: from all fwmark 0xc10001/0xcffff lookup ' + name,)
                self.assertEqual(self.cleanup(name, (owned,), (owned,), rules),
                                 ['ip link del ' + name, 'iptables -D ' + owned[3:],
                                  'ip6tables -D ' + owned[3:], 'netd: network destroy 65537'])


if __name__ == '__main__':
    unittest.main()
