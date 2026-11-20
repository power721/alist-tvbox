"""Static runtime origins shared by source setup and browser diagnostics. Never executes source."""
import ast
import ipaddress
import re
from urllib.parse import urlsplit

from source_egress import MAX_ROUTES, public_address, source_trees


def http_origin(text):
    if (not isinstance(text, str) or not text.startswith(("http://", "https://")) or not text.isascii()
            or re.search(r"[\x00-\x20\x7f\\]", text)):
        return None
    try:
        parsed = urlsplit(text)
        port, host = parsed.port, parsed.hostname
    except ValueError:
        return None
    if not host or parsed.username or parsed.password or "%" in host:
        return None
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None:
        if not public_address(host):
            return None
        host = "[" + str(address) + "]" if address.version == 6 else str(address)
    elif not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host) or "." not in host:
        return None
    if port is not None and not 1 <= port <= 65535:
        return None
    return parsed.scheme + "://" + host + (":" + str(port) if port is not None else "")


def static_strings(tree, parents):
    scopes = (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)
    bindings = {}

    def scope(node):
        while not isinstance(node, scopes):
            node = parents[node]
        return node

    def enclosing_class(node):
        while node in parents:
            node = parents[node]
            if isinstance(node, ast.ClassDef):
                return node
        return None

    def bind(owner, name, value):
        bindings.setdefault((owner, name), []).append(value)

    for node in ast.walk(tree):
        parent = parents.get(node)
        if isinstance(node, (ast.Name, ast.Attribute)) and isinstance(node.ctx, ast.Store):
            value = parent.value if isinstance(parent, (ast.Assign, ast.AnnAssign)) else None
            if isinstance(node, ast.Name):
                bind(scope(node), node.id, value)
            elif isinstance(node.value, ast.Name) and node.value.id == "self":
                owner = enclosing_class(node)
                if owner is not None:
                    bind(owner, node.attr, value)
        elif isinstance(node, ast.arg):
            bind(scope(node), node.arg, None)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            bind(scope(parents[node]), node.name, None)
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            for alias in node.names:
                bind(scope(node), alias.asname or alias.name.split(".")[0], None)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            bind(scope(node), node.name, None)

    def resolve(node, seen=frozenset()):
        if node in seen or len(seen) >= 32:
            return "", False
        seen = seen | {node}
        if isinstance(node, ast.Constant):
            if (isinstance(node.value, str) and len(node.value) <= 8192) or type(node.value) is int:
                return node.value, True
        elif isinstance(node, ast.Name):
            owner = scope(node)
            while True:
                values = bindings.get((owner, node.id))
                if values is not None:
                    if len(values) == 1 and values[0] is not None:
                        return resolve(values[0], seen)
                    break
                if owner not in parents:
                    break
                owner = scope(parents[owner])
                # A method's bare names use lexical scopes, not the class namespace.
                while isinstance(owner, ast.ClassDef) and owner in parents:
                    owner = scope(parents[owner])
        elif (isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name)
              and node.value.id == "self"):
            values = bindings.get((enclosing_class(node), node.attr), [])
            if len(values) == 1 and values[0] is not None:
                return resolve(values[0], seen)
        elif isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            left, complete = resolve(node.left, seen)
            if not isinstance(left, str) or not complete:
                return (left, False) if isinstance(left, str) else ("", False)
            right, complete = resolve(node.right, seen)
            if isinstance(right, str) and len(left) + len(right) <= 8192:
                return left + right, complete
        elif isinstance(node, ast.JoinedStr):
            text = ""
            for part in node.values:
                value, complete = resolve(part, seen)
                if not isinstance(value, str) or len(text) + len(value) > 8192:
                    return "", False
                text += value
                if not complete:
                    return text, False
            return text, True
        elif isinstance(node, ast.FormattedValue) and node.format_spec is None and node.conversion in {-1, 115}:
            value, complete = resolve(node.value, seen)
            return str(value), complete
        return "", False

    resolved, composed = {}, {}
    literal_expressions = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
            continue
        expression = node
        while isinstance(parents.get(expression), (ast.JoinedStr, ast.FormattedValue)) or (
                isinstance(parents.get(expression), ast.BinOp) and isinstance(parents[expression].op, ast.Add)):
            expression = parents[expression]
        if expression not in resolved:
            resolved[expression] = resolve(expression)
        text, complete = resolved[expression]
        if not isinstance(text, str):
            continue
        if node.value.startswith(("http://", "https://")):
            literal_expressions.add(expression)
            yield (expression, text) if complete else (node, node.value)
        elif complete or re.match(r"https?://[^/?#]+[/?#]", text):
            composed.setdefault(expression, text)
    # Keep existing literal routes and their order, then add newly resolved origins.
    yield from ((node, text) for node, text in composed.items() if node not in literal_expressions)


def source_origins(content):
    try:
        trees = source_trees(content)
    except (SyntaxError, ValueError):
        raise ValueError("SOURCE_SETUP_SYNTAX_INVALID") from None
    parents = {child: node for tree in trees for node in ast.walk(tree) for child in ast.iter_child_nodes(node)}
    origins = []
    for node, text in (item for tree in trees for item in static_strings(tree, parents)):
        if not text.startswith(("http://", "https://")):
            continue
        ancestor, excluded = node, False
        while ancestor in parents:
            ancestor = parents[ancestor]
            if isinstance(ancestor, ast.Expr) and isinstance(ancestor.value, ast.Constant):
                excluded = True
            if isinstance(ancestor, ast.Assign) and any(
                    isinstance(target, ast.Name) and target.id.upper() in {"META", "METADATA", "__METADATA__"}
                    for target in ancestor.targets):
                excluded = True
            if (isinstance(ancestor, ast.AnnAssign) and isinstance(ancestor.target, ast.Name)
                    and ancestor.target.id.upper() in {"META", "METADATA", "__METADATA__"}):
                excluded = True
        origin = None if excluded else http_origin(text)
        if origin is not None and origin not in origins:
            origins.append(origin)
    if not origins:
        raise ValueError("SOURCE_SETUP_ORIGINS_REQUIRED")
    if len(origins) > MAX_ROUTES:
        raise ValueError("SOURCE_SETUP_ORIGIN_LIMIT")
    return origins
