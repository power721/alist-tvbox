"""Static URL expression checks; no source imports, execution or networking."""
import unittest

from source_origin_discovery import source_origins


class StaticOriginsTest(unittest.TestCase):
    def test_concat_uses_the_complete_host_instead_of_a_valid_partial_host(self):
        self.assertEqual(["https://api.example.com"], source_origins(
            "ORIGIN = 'https://api.example' + '.com'"))

    def test_constant_aliases_and_fstrings_preserve_scheme_and_port(self):
        self.assertEqual(["http://api.example.com:8080"], source_origins(
            "SCHEME='http'\nDOMAIN='api.example.com'\nPORT=8080\n"
            "BASE=f'{SCHEME}://{DOMAIN}:{PORT}'\n"
            "URL=BASE + '/catalog'\nraise RuntimeError('MUST_NOT_EXECUTE')"))

    def test_class_and_instance_constants_keep_separate_source_scopes(self):
        self.assertEqual(["https://first.example.com", "https://second.example.com"], source_origins(
            "class First:\n    DOMAIN='first.example.com'\n"
            "    def init(self):\n        self.host='https://' + self.DOMAIN\n"
            "class Second:\n    DOMAIN='second.example.com'\n"
            "    def init(self):\n        self.host=f'https://{self.DOMAIN}'\n"))

    def test_unknown_path_is_allowed_but_an_entirely_dynamic_host_is_not_guessed(self):
        self.assertEqual(["https://fixed.example.com"], source_origins(
            "def request(item):\n    return f'https://fixed.example.com/items/{item}'"))
        for expression in ("'https://' + domain", "f'https://{domain}'"):
            with self.subTest(expression=expression), self.assertRaisesRegex(
                    ValueError, '^SOURCE_SETUP_ORIGINS_REQUIRED$'):
                source_origins("URL=" + expression)

    def test_literal_cdn_origins_keep_dynamic_path_consumers_and_original_order(self):
        self.assertEqual(['https://first.example.com', 'https://second.example.com',
                          'https://third.example.com'], source_origins(
            "def picture(path):\n    return 'https://first.example.com' + path.replace('old', 'new')\n"
            "def playback(value):\n    return 'https://second.example.com' + value.split('/')[1]\n"
            "def cover(pic):\n    return 'https://third.example.com' + urllib.parse.urlparse(pic).path\n"))

    def test_shadowed_reassigned_cyclic_and_called_values_are_not_evaluated(self):
        for declaration in (
                "DOMAIN='outer.example.com'\ndef request(DOMAIN):\n    return 'https://' + DOMAIN",
                "DOMAIN='first.example.com'\nDOMAIN=read_config()\nURL='https://' + DOMAIN",
                "FIRST=SECOND\nSECOND=FIRST\nURL='https://' + FIRST",
                "def domain():\n    raise RuntimeError('MUST_NOT_EXECUTE')\nURL='https://' + domain()"):
            with self.subTest(declaration=declaration), self.assertRaisesRegex(
                    ValueError, '^SOURCE_SETUP_ORIGINS_REQUIRED$'):
                source_origins(declaration)

    def test_composed_metadata_private_hosts_and_userinfo_are_not_granted(self):
        for declaration in (
                "META={'site':'https://' + 'metadata.example.com'}",
                "__METADATA__: dict={'site':f'https://metadata.example.com'}",
                "URL='https://' + '127.0.0.1'",
                "URL=f'https://{\"user:pass\"}@site.example.com'"):
            with self.subTest(declaration=declaration), self.assertRaisesRegex(
                    ValueError, '^SOURCE_SETUP_ORIGINS_REQUIRED$'):
                source_origins(declaration)

    def test_malformed_url_does_not_hide_another_valid_origin(self):
        self.assertEqual(['https://working.example.com'], source_origins(
            "HOSTS=['https://[broken', 'https://' + 'working.example.com']"))


if __name__ == '__main__':
    unittest.main()
