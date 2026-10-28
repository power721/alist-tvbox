# -*- coding: utf-8 -*-
"""T4 服务端插件 worker:以子进程形态运行 Atvp.py 装载的爬虫插件。

Java 侧(PluginWorkerService)经 stdin/stdout 的 JSON 行协议调用:
  请求 {"id": str, "method": str, "params": {...}}
  响应 {"id": str, "ok": bool, "result": ..., "error": {"code": str, "message": str}}

约定(来自 sidecar worker 的踩坑记录, docs/t4-sidecar-borrow-assessment.md A1):
- 失败响应必须沿用请求 id,否则真实错误会被误判为协议错误;
- 插件调用期间 stdout 重定向到 stderr(插件乱 print 不污染协议通道);
- Atvp 与 inner 插件经 exec 装载进隔离模块,base.spider 做空 shim;
- 插件期望的 category 筛选参数形态(dict/JSON 字符串)以对密文源码的
  AST 静态探测为准(categoryContent 内是否 json.loads(extend))。

用法: python3 t4worker.py <atvp_path>
Python 3.10+(Alpine 3.21 自带 3.12)。
"""
import ast
import contextlib
import json
import sys
import types

ATVP_PATH = sys.argv[1] if len(sys.argv) > 1 else "Atvp.py"

# base.spider 空 shim:inner 插件 import base.spider 时由 Atvp 的 _FallbackSpider 回填
_base = types.ModuleType("base")
_base_spider = types.ModuleType("base.spider")
_base.__path__ = []
sys.modules["base"] = _base
sys.modules["base.spider"] = _base_spider

_spider = None
_category_filter_json = False


@contextlib.contextmanager
def _quiet_stdout():
    buf = sys.stdout
    sys.stdout = sys.stderr
    try:
        yield
    finally:
        sys.stdout = buf


def _load_atvp():
    global _spider
    module = types.ModuleType("t4_atvp")
    module.__file__ = ATVP_PATH
    with open(ATVP_PATH, "rb") as handle:
        code = handle.read()
    exec(compile(code, ATVP_PATH, "exec"), module.__dict__)
    spider_cls = getattr(module, "Spider", None)
    if spider_cls is None:
        raise RuntimeError("Atvp.py does not export Spider")
    _install_filter_probe(spider_cls)
    _spider = spider_cls()
    fallback = getattr(module, "_FallbackSpider", None)
    if fallback is not None:
        _base_spider.Spider = fallback


def _install_filter_probe(spider_cls):
    # 钩住密文源码装载点,拿到源码后 AST 探测 categoryContent 的筛选参数形态
    global _category_filter_json
    original = getattr(spider_cls, "_load_inner_spider_class", None)
    if original is None:
        return

    def load_inner(instance, source_text):
        global _category_filter_json
        _category_filter_json = _category_expects_json(source_text)
        return original(instance, source_text)

    spider_cls._load_inner_spider_class = load_inner


def _category_expects_json(source_text):
    # categoryContent 内对最后一个参数做 json.loads(或 `x or '{}'` 形态) → 期望 JSON 字符串;
    # 其余(含直接 .get) → 期望 dict(设备端 Chaquopy 传 dict 的默认形态)
    try:
        tree = ast.parse(source_text)
    except (SyntaxError, ValueError):
        return False
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef) or node.name != "categoryContent":
            continue
        args = node.args.args
        if not args:
            continue
        parameter = args[-1].arg
        for call in ast.walk(node):
            if not isinstance(call, ast.Call) or not call.args:
                continue
            func = call.func
            name = func.attr if isinstance(func, ast.Attribute) else (
                func.id if isinstance(func, ast.Name) else "")
            argument = call.args[0]
            direct = isinstance(argument, ast.Name) and argument.id == parameter
            defaulted = (isinstance(argument, ast.BoolOp) and isinstance(argument.op, ast.Or)
                         and isinstance(argument.values[0], ast.Name)
                         and argument.values[0].id == parameter)
            if name == "loads" and (direct or defaulted):
                return True
    return False


def _invoke(name, *args):
    method = getattr(_spider, name, None)
    if method is None:
        raise RuntimeError("Atvp method unavailable: " + name)
    result = method(*args)
    json.dumps(result, ensure_ascii=False, default=str)
    return result


def _params_extend(params):
    if _category_filter_json:
        value = params.get("extend")
        if value is None:
            return "{}"
        if isinstance(value, dict):
            return json.dumps(value, ensure_ascii=False)
        return str(value)
    value = params.get("extend")
    if value is None:
        return {}
    if isinstance(value, str):
        try:
            return json.loads(value) if value.strip() else {}
        except ValueError:
            return {}
    return value


def _normalize_result(method, result):
    # TVBox spider 契约:home/category/detail/search/play 返回 JSON 字符串;
    # 行协议层反序列化为对象,Java 侧直接透传(否则响应成带引号的字符串)
    if method in ("home", "category", "detail", "search", "play") and isinstance(result, str):
        try:
            return json.loads(result)
        except ValueError:
            return result
    return result


def handle(method, params):
    if method == "ping":
        return {"status": "ready"}
    if _spider is None:
        if method != "init":
            raise RuntimeError("worker is not initialized")
        _load_atvp()
        extend = str(params.get("extend") or "")
        with _quiet_stdout():
            result = _invoke("init", extend)
            name = _invoke("getName")
        return {"result": result, "name": name}

    if method == "home":
        with _quiet_stdout():
            return _invoke("homeContent", bool(params.get("filter", True)))
    if method == "category":
        with _quiet_stdout():
            return _invoke("categoryContent", str(params.get("t") or ""), str(params.get("pg") or "1"),
                           bool(params.get("filter", True)), _params_extend(params))
    if method == "detail":
        with _quiet_stdout():
            return _invoke("detailContent", [str(params.get("ids") or "")])
    if method == "search":
        with _quiet_stdout():
            return _invoke("searchContent", str(params.get("wd") or ""), bool(params.get("quick", False)),
                           str(params.get("pg") or "1"))
    if method == "play":
        with _quiet_stdout():
            return _invoke("playerContent", str(params.get("flag") or ""), str(params.get("play") or ""), [])
    if method == "action":
        with _quiet_stdout():
            return _invoke("action", str(params.get("do") or ""))
    if method == "local":
        with _quiet_stdout():
            return _invoke("localProxy", dict(params))
    if method == "destroy":
        with _quiet_stdout():
            destroy = getattr(_spider, "destroy", None)
            if destroy is not None:
                destroy()
        return {"status": "closed"}
    raise RuntimeError("unknown method: " + method)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = ""
        try:
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError("request must be an object")
            request_id = str(request.get("id") or "")
            method = str(request.get("method") or "")
            params = request.get("params")
            if not method:
                raise ValueError("method is required")
            if params is None:
                params = {}
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            result = _normalize_result(method, handle(method, params))
            sys.stdout.write(json.dumps({"id": request_id, "ok": True, "result": result},
                                        ensure_ascii=False, default=str) + "\n")
        except Exception as exc:  # noqa: BLE001 - 协议边界:一切异常转错误响应,沿用请求 id
            sys.stdout.write(json.dumps(
                {"id": request_id, "ok": False,
                 "error": {"code": type(exc).__name__, "message": str(exc)}},
                ensure_ascii=False, default=str) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
