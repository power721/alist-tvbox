"""Offline fixtures: parse untrusted source without ever importing it."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "maintenance_inspector", ROOT / "server/src/main/resources/worker/inspect_source.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StaticInspectorTest(unittest.TestCase):
    def test_explicit_literal_nonplayback_is_a_declaration_not_runtime_success(self):
        result = MODULE.inspect_source("class Spider:\n def playerContent(self, flag, id, vipFlags):\n"
            "  return {'url': '', 'parse': 0, 'msg': '\u6b64\u6e90\u4ec5\u63d0\u4f9b\u8d44\u6599\uff0c\u4e0d\u63d0\u4f9b\u89c6\u9891\u64ad\u653e'}\n")
        self.assertEqual({"kind": "metadata-only", "authority": "static_literal_player_declaration"},
                         result["playbackDeclaration"])
        self.assertEqual("static", result["evidenceClass"])
        self.assertNotIn("\u6b64\u6e90", str(result))

    def test_empty_broken_players_and_dynamic_players_are_not_metadata_declarations(self):
        for body in (
            "return {'url': ''}",
            "return {'url': '', 'msg': 'request failed'}",
            "return {'url': id, 'msg': 'metadata only'}",
            "return {'url': '', 'playUrl': 'https://parser.invalid/', 'msg': 'metadata only'}",
            "return {'url': '', 'parse': 1, 'msg': 'metadata only'}",
            "return {'url': '', 'msg': message}",
            "return {'url': '', 'msg': 'metadata only', **options}",
            "\n  if id: return {'url': id}\n  return {'url': '', 'msg': 'metadata only'}",
        ):
            result = MODULE.inspect_source("class Spider:\n def playerContent(self, flag, id, vipFlags):\n  " + body + "\n")
            self.assertTrue(result["parseOk"], body)
            self.assertIsNone(result["playbackDeclaration"], body)

    def test_duplicate_or_decorated_player_cannot_declare_metadata_capability(self):
        for body in (
            "class Spider:\n @replace_player\n def playerContent(self, *args): return {'url': '', 'msg': 'metadata only'}\n",
            "class Spider:\n def playerContent(self): return {'url': 'media'}\n"
                " def playerContent(self): return {'url': '', 'msg': 'metadata only'}\n",
        ):
            self.assertIsNone(MODULE.inspect_source(body)["playbackDeclaration"])

    def test_no_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "executed"
            body = f"from pathlib import Path\nPath({str(marker)!r}).touch()\nclass Spider: pass\n"
            result = MODULE.inspect_source(body)
            self.assertTrue(result["parseOk"])
            self.assertFalse(marker.exists())
            self.assertEqual(["pathlib"], result["imports"])

    def test_duplicate_method_and_duplicate_class_are_not_green(self):
        result = MODULE.inspect_source("class Spider:\n def init(self): pass\n def init(self): pass\n")
        self.assertEqual("duplicate_method", result["issues"][0]["code"])
        self.assertEqual([2, 3], result["issues"][0]["lines"])
        result = MODULE.inspect_source("class Spider: pass\nclass Spider: pass")
        self.assertEqual("spider_class_count", result["issues"][0]["code"])

    def test_utf8_bom_and_crlf_are_identified_by_exact_content(self):
        import hashlib
        body = "\ufeff# \u4e2d\u6587\r\nclass Spider: pass\r\n"
        result = MODULE.inspect_source(body)
        self.assertTrue(result["parseOk"])
        self.assertEqual(hashlib.sha256(body.encode("utf-8")).hexdigest(), result["sha256"])

    def test_syntax_error_is_redacted(self):
        result = MODULE.inspect_source("private_secret = 'sensitive'\nclass Spider(:")
        self.assertFalse(result["parseOk"])
        self.assertEqual({"code": "invalid_syntax", "line": 2}, result["issues"][0])

    def test_effective_signatures_are_pinned_without_exposing_default_values(self):
        first = MODULE.inspect_source("class Spider:\n def init(self, extend='SECRET_DEFAULT'): pass\n")
        whitespace = MODULE.inspect_source("class Spider:\n def init( self, extend = 'SECRET_DEFAULT' ):\n  return None\n")
        changed = MODULE.inspect_source("class Spider:\n def init(self): pass\n")
        self.assertEqual(first["methodContracts"], whitespace["methodContracts"])
        self.assertNotEqual(first["methodContracts"], changed["methodContracts"])
        self.assertNotIn("SECRET_DEFAULT", str(first))
        duplicate = MODULE.inspect_source("class Spider:\n def init(self): pass\n def init(self, extend='SECRET_DEFAULT'): pass\n")
        self.assertEqual(first["methodContracts"], duplicate["methodContracts"])

    def test_truncated_contract_observations_are_explicit(self):
        body = "class Spider:\n" + "".join(
            f" def m{i}(self): pass\n" for i in range(MODULE.MAX_METHOD_CONTRACTS + 1))
        result = MODULE.inspect_source(body)
        self.assertTrue(result["methodsTruncated"])
        self.assertEqual(MODULE.MAX_METHOD_CONTRACTS, len(result["methodContracts"]))
        result = MODULE.inspect_source("".join(f"import package{i}\n" for i in range(101)) + "class Spider: pass\n")
        self.assertTrue(result["importsTruncated"])

    def test_large_spider_retains_every_signature_including_player_methods(self):
        body = "class Spider:\n" + "".join(f" def m{i}(self): pass\n" for i in range(180))
        body += " def playerContent(self, flag, id, vipFlags): return {}\n"
        result = MODULE.inspect_source(body)
        self.assertFalse(result["methodsTruncated"])
        self.assertEqual(181, len(result["methodContracts"]))
        self.assertIn("playerContent", result["methodContracts"])
        changed = MODULE.inspect_source(body.replace("flag, id, vipFlags", "flag, id"))
        self.assertNotEqual(result["methodContracts"]["playerContent"],
                            changed["methodContracts"]["playerContent"])

    def test_class_headers_method_decorators_and_import_aliases_are_observed(self):
        plain = MODULE.inspect_source("import json\nclass Spider:\n def init(self): pass\n")
        derived = MODULE.inspect_source("import json\nclass Spider(Base):\n def init(self): pass\n")
        decorated = MODULE.inspect_source("import json\nclass Spider:\n @staticmethod\n def init(self): pass\n")
        aliased = MODULE.inspect_source("import json as renamed\nclass Spider:\n def init(self): pass\n")
        self.assertNotEqual(plain["classContractSha256"], derived["classContractSha256"])
        self.assertNotEqual(plain["methodContracts"], decorated["methodContracts"])
        self.assertNotEqual(plain["importContractSha256"], aliased["importContractSha256"])

    def test_additive_import_entries_pin_scope_binding_and_namespace_without_execution(self):
        plain = MODULE.inspect_source("import urllib.request\nclass Spider:\n def init(self): pass\n")
        added = MODULE.inspect_source(
            "import urllib.request, urllib.parse\nclass Spider:\n def init(self):\n"
            "  from Crypto.Cipher import AES\n")
        self.assertIn(plain["importEntries"][0], added["importEntries"])
        first, second, local = added["importEntries"]
        self.assertEqual(first["bindingSha256"], second["bindingSha256"])
        self.assertEqual(first["targetSha256"], second["targetSha256"])
        self.assertNotEqual(first["scopeSha256"], local["scopeSha256"])
        changed = MODULE.inspect_source("import urllib.request as other\nclass Spider: pass\n")
        self.assertNotEqual(first["bindingSha256"], changed["importEntries"][0]["bindingSha256"])
        truncated = MODULE.inspect_source("import json\n" * 257 + "class Spider: pass\n")
        self.assertTrue(truncated["importsTruncated"])
        self.assertEqual(256, len(truncated["importEntries"]))

    def test_search_page_upgrade_preserves_exact_prior_signature_without_execution(self):
        old = MODULE.inspect_source("class Spider:\n def searchContent(self, key, quick): pass\n")
        upgraded = MODULE.inspect_source("class Spider:\n def searchContent(self, key, quick, pg='1'): pass\n")
        self.assertIsNone(old["searchPageCompatibility"])
        self.assertEqual(old["methodContracts"]["searchContent"],
                         upgraded["searchPageCompatibility"]["priorMethodContractSha256"])
        self.assertTrue(upgraded["searchPageCompatibility"]["preservesTwoArgumentCall"])
        for declaration in (
            "def searchContent(self, key, quick, pg):",
            "def searchContent(self, key, quick, pg='SECRET_DEFAULT'):",
            "def searchContent(self, key, quick, pg=1):",
            "def searchContent(self, key, quick, pg: str='1'):",
            "def searchContent(self, key, quick, pg='1', **kwargs):",
            "def searchContent(self, key, quick, *, pg='1'):",
            "async def searchContent(self, key, quick, pg='1'):",
            "def searchContent(self, /, key, quick, pg='1'):",
            "def searchContent(self, key, quick=False, pg='1'):",
        ):
            with self.subTest(declaration=declaration):
                result = MODULE.inspect_source("class Spider:\n " + declaration + " pass\n")
                self.assertIsNone(result["searchPageCompatibility"])
        decorated = MODULE.inspect_source(
            "class Spider:\n @staticmethod\n def searchContent(self, key, quick, pg='1'): pass\n")
        self.assertIsNone(decorated["searchPageCompatibility"])

    def test_selector_binding_only_records_effective_method_calls_without_exposing_literals(self):
        import hashlib
        body = """CONFIG = 'span.old'
# soup.select('span.old')
class Spider:
 def categoryContent(self, tid, pg, filter, extend):
  return ignored.select('span.old')
 def categoryContent(self, tid, pg, filter, extend):
  def later(): return hidden.select('span.old')
  return row.select('span.title')
"""
        calls = MODULE.inspect_source(body)["selectorCalls"]
        self.assertEqual(1, len(calls))
        self.assertEqual("categoryContent", calls[0]["method"])
        self.assertEqual(hashlib.sha256(b"span.title").hexdigest(), calls[0]["selectorSha256"])
        self.assertNotIn("span.title", str(calls))
        self.assertNotIn("span.old", str(calls))

    def test_selector_call_contract_survives_only_the_literal_change(self):
        before = MODULE.inspect_source("\ufeffclass Spider:\r\n def parse(self):\r\n  return row.select('span.old')\r\n")
        after = MODULE.inspect_source("class Spider:\n def parse(self):\n  return row.select('span.title')\n")
        changed = MODULE.inspect_source("class Spider:\n def parse(self):\n  return other.select('span.title')\n")
        self.assertEqual(before["selectorCalls"][0]["callContractSha256"],
                         after["selectorCalls"][0]["callContractSha256"])
        self.assertNotEqual(after["selectorCalls"][0]["callContractSha256"],
                            changed["selectorCalls"][0]["callContractSha256"])

    def test_xpath_calls_are_bound_without_exposing_the_expression(self):
        import hashlib
        result = MODULE.inspect_source(
            "class Spider:\n def parse(self):\n  return row.xpath(\"//span[@class='title']/text()\")\n")
        self.assertEqual("xpath", result["selectorCalls"][0]["selectorType"])
        self.assertEqual(hashlib.sha256(b"//span[@class='title']/text()").hexdigest(),
                         result["selectorCalls"][0]["selectorSha256"])
        self.assertNotIn("//span", str(result["selectorCalls"]))

    def test_xpath_binding_ignores_dynamic_nested_overridden_and_oversize_calls(self):
        body = """class Spider:
 def parse(self):
  return row.xpath('.//ignored')
 def parse(self):
  def later(): return row.xpath('.//hidden')
  row.xpath(dynamic)
  row.xpath('.//' + tag)
  row.xpath('.//span', namespaces={})
  return row.xpath('.//title/text()')
"""
        calls = MODULE.inspect_source(body)["selectorCalls"]
        self.assertEqual(1, len(calls))
        self.assertEqual("xpath", calls[0]["selectorType"])
        before = MODULE.inspect_source("class Spider:\n def parse(self): return row.xpath('.//span/text()')\n")
        after = MODULE.inspect_source(
            "class Spider:\n def parse(self): return row.xpath('.//span[@class=\\'title\\']/text()')\n")
        self.assertEqual(before["selectorCalls"][0]["callContractSha256"],
                         after["selectorCalls"][0]["callContractSha256"])
        oversize = "class Spider:\n def parse(self): return row.xpath(" + repr("x" * 161) + ")\n"
        self.assertEqual([], MODULE.inspect_source(oversize)["selectorCalls"])
        result = MODULE.inspect_source("class Spider:\n def parse(self):\n" + "  row.xpath('.//span')\n" * 129)
        self.assertTrue(result["selectorCallsTruncated"])
        self.assertEqual(128, len(result["selectorCalls"]))

    def test_selector_binding_rejects_dynamic_and_partial_observations(self):
        body = """class Spider:
 def parse(self, selector):
  row.select(selector)
  row.select('span.old', namespaces={})
  row.select('span.' + selector)
"""
        self.assertEqual([], MODULE.inspect_source(body)["selectorCalls"])
        body = "class Spider:\n def parse(self):\n" + "  row.select('span.old')\n" * 129
        result = MODULE.inspect_source(body)
        self.assertTrue(result["selectorCallsTruncated"])
        self.assertEqual(128, len(result["selectorCalls"]))


if __name__ == "__main__":
    unittest.main()
