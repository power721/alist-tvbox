"""Static AST inspection only. Never import or execute the submitted source."""
import ast
import copy
import hashlib
import json
import sys

MAX_METHOD_CONTRACTS = 512


def playback_declaration(member):
    if not isinstance(member, ast.FunctionDef) or member.decorator_list:
        return None
    body = member.body
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
        body = body[1:]
    if len(body) != 1 or not isinstance(body[0], ast.Return) or not isinstance(body[0].value, ast.Dict):
        return None
    returned = body[0].value
    if any(not isinstance(key, ast.Constant) or not isinstance(key.value, str) for key in returned.keys):
        return None
    fields = {key.value: value for key, value in zip(returned.keys, returned.values)}
    if len(fields) != len(returned.keys):
        return None
    if not isinstance(fields.get("url"), ast.Constant) or fields["url"].value != "":
        return None
    for name, expected in (("playUrl", ""), ("parse", 0), ("jx", 0)):
        if name in fields and (not isinstance(fields[name], ast.Constant) or fields[name].value != expected):
            return None
    message = fields.get("msg")
    if not isinstance(message, ast.Constant) or not isinstance(message.value, str) or len(message.value) > 512:
        return None
    declarations = ("\u4e0d\u63d0\u4f9b\u89c6\u9891\u64ad\u653e", "does not provide video playback", "metadata only", "metadata-only")
    if not any(value in message.value.lower() for value in declarations):
        return None
    return {"kind": "metadata-only", "authority": "static_literal_player_declaration"}


def search_page_compatibility(member):
    args = member.args
    if (not isinstance(member, ast.FunctionDef) or member.name != "searchContent"
            or member.decorator_list or args.posonlyargs or args.kwonlyargs
            or args.vararg or args.kwarg or len(args.args) != 4 or len(args.defaults) != 1
            or args.args[-1].arg != "pg" or args.args[-1].annotation is not None
            or not isinstance(args.defaults[0], ast.Constant) or args.defaults[0].value != "1"):
        return None
    original = copy.deepcopy(args)
    original.args.pop()
    original.defaults = []
    signature = "FunctionDef:" + ast.dump(original, include_attributes=False) + ":"
    return {"priorMethodContractSha256": hashlib.sha256(signature.encode("utf-8")).hexdigest(),
            "preservesTwoArgumentCall": True}


def selector_calls(member, content):
    class Visitor(ast.NodeVisitor):
        def __init__(self):
            self.calls, self.ordinal = [], 0
            self.truncated = False

        def visit_Call(self, node):
            ordinal = self.ordinal
            self.ordinal += 1
            if (isinstance(node.func, ast.Attribute) and node.func.attr in {"select", "select_one", "cssselect", "xpath"}
                    and len(node.args) == 1 and not node.keywords
                    and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str)
                    and 0 < len(node.args[0].value) <= 160):
                if len(self.calls) >= 128:
                    self.truncated = True
                else:
                    literal = ast.get_source_segment(content, node.args[0])
                    normalized = copy.deepcopy(node)
                    normalized.args[0] = ast.Constant(value="BOUND_SELECTOR")
                    self.calls.append({
                        "method": member.name, "call": node.func.attr, "ordinal": ordinal,
                        "selectorType": "xpath" if node.func.attr == "xpath" else "css",
                        "line": node.lineno,
                        "selectorSha256": hashlib.sha256(node.args[0].value.encode("utf-8")).hexdigest(),
                        "literalSha256": hashlib.sha256(literal.encode("utf-8")).hexdigest(),
                        "callContractSha256": hashlib.sha256(
                            ast.dump(normalized, include_attributes=False).encode("utf-8")).hexdigest()})
            self.generic_visit(node)

        # Nested/deferred bodies are not calls in this effective Spider method.
        def visit_FunctionDef(self, node):
            pass

        visit_AsyncFunctionDef = visit_FunctionDef
        visit_ClassDef = visit_FunctionDef
        visit_Lambda = visit_FunctionDef

    visitor = Visitor()
    for statement in member.body:
        visitor.visit(statement)
    return visitor.calls, visitor.truncated


def inspect_source(content):
    payload = content.encode("utf-8")
    result = {"sha256": hashlib.sha256(payload).hexdigest(), "parseOk": False,
              "issues": [], "methods": [], "imports": [], "evidenceClass": "static",
              "pythonVersion": sys.version.split()[0], "methodContracts": {},
              "methodsTruncated": False, "importsTruncated": False,
              "classContractSha256": None, "importContractSha256": None, "importEntries": [],
              "searchPageCompatibility": None, "selectorCalls": [],
              "selectorCallsTruncated": False, "playbackDeclaration": None}
    try:
        tree = ast.parse(payload, filename="source.py")
    except (SyntaxError, ValueError) as error:
        # Do not return the source line; it can contain credentials.
        result["issues"].append({"code": "invalid_syntax", "line": getattr(error, "lineno", None)})
        return result
    result["parseOk"] = True
    spiders = [n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Spider"]
    if len(spiders) != 1:
        result["issues"].append({"code": "spider_class_count", "count": len(spiders)})
    else:
        header = [ast.dump(n, include_attributes=False) for n in (
            spiders[0].bases + spiders[0].keywords + spiders[0].decorator_list)]
        result["classContractSha256"] = hashlib.sha256(json.dumps(header).encode("utf-8")).hexdigest()
    for spider in spiders:
        seen = {}
        contracts = {}
        effective = {}
        for member in spider.body:
            if isinstance(member, (ast.FunctionDef, ast.AsyncFunctionDef)):
                if member.name in seen:
                    result["issues"].append({"code": "duplicate_method", "method": member.name,
                                             "lines": [seen[member.name], member.lineno]})
                seen[member.name] = member.lineno
                effective[member.name] = member
                # Fingerprint the effective input signature, without exposing defaults/annotations.
                signature = type(member).__name__ + ":" + ast.dump(member.args, include_attributes=False)
                signature += ":" + ":".join(ast.dump(n, include_attributes=False) for n in member.decorator_list)
                contracts[member.name] = hashlib.sha256(signature.encode("utf-8")).hexdigest()
                if member.name == "searchContent":
                    result["searchPageCompatibility"] = search_page_compatibility(member)
        result["methods"] = sorted(seen)[:MAX_METHOD_CONTRACTS]
        result["methodsTruncated"] = len(seen) > MAX_METHOD_CONTRACTS
        result["methodContracts"] = {name: contracts[name] for name in result["methods"]}
        for name in sorted(effective):
            calls, truncated = selector_calls(effective[name], content.lstrip("\ufeff"))
            remaining = 128 - len(result["selectorCalls"])
            result["selectorCalls"].extend(calls[:remaining])
            result["selectorCallsTruncated"] |= truncated or len(calls) > remaining
    imports = set()
    import_contracts = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imports.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imports.add(node.module.split(".")[0])
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            import_contracts.append(ast.dump(node, include_attributes=False))
    result["imports"] = sorted(imports)[:100]
    result["importsTruncated"] = len(imports) > 100
    result["importContractSha256"] = hashlib.sha256(json.dumps(sorted(import_contracts)).encode("utf-8")).hexdigest()
    result["importEntries"], truncated = import_entries(tree)
    result["importsTruncated"] |= truncated
    result["issues"] = result["issues"][:100]
    if not result["issues"] and len(spiders) == 1 and "playerContent" in effective:
        result["playbackDeclaration"] = playback_declaration(effective["playerContent"])
    return result


def import_entries(tree):
    entries, scope = [], []

    def sha(value):
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    class Visitor(ast.NodeVisitor):
        def visit_FunctionDef(self, node):
            scope.append(type(node).__name__ + ":" + node.name)
            self.generic_visit(node)
            scope.pop()

        visit_AsyncFunctionDef = visit_FunctionDef
        visit_ClassDef = visit_FunctionDef

        def visit_Import(self, node):
            for alias in node.names:
                self.record(ast.Import(names=[alias]), alias.asname or alias.name.split(".")[0],
                            "module:" + (alias.name if alias.asname else alias.name.split(".")[0]))

        def visit_ImportFrom(self, node):
            for alias in node.names:
                self.record(ast.ImportFrom(module=node.module, level=node.level, names=[alias]),
                            alias.asname or alias.name,
                            "from:%s:%s:%s" % (node.level, node.module, alias.name))

        def record(self, node, binding, target):
            if len(entries) < 257:
                entries.append({"scopeSha256": sha("/".join(scope)), "bindingSha256": sha(binding),
                                "targetSha256": sha(target),
                                "importSha256": sha(ast.dump(node, include_attributes=False))})

    Visitor().visit(tree)
    return entries[:256], len(entries) > 256


if __name__ == "__main__":
    # Java sends UTF-8 JSON bytes. Windows' locale text decoder must not alter
    # submitted source, and -I intentionally ignores PYTHONIOENCODING.
    request = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    json.dump(inspect_source(request["content"]), sys.stdout, ensure_ascii=True, separators=(",", ":"))
