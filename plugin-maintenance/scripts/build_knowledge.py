"""Build pinned, data-only maintenance guidance from explicitly selected local skills."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


MODULE = Path(__file__).resolve().parents[1]
REPO = MODULE.parent
OUTPUT = MODULE / "server/src/main/resources/knowledge"
CORE = "adaptive-video-spider-workbench/references/core/instructions.md"
PLAY = "adaptive-video-spider-workbench/references/playback/instructions.md"
COMPAT = "alist-tvbox-compatibility-check/references/compatibility-skeleton.md"
SKILL = "alist-tvbox-compatibility-check/SKILL.md"
MATRIX = "video-spider-reachability-matrix/SKILL.md"
RECIPES = "docs/plugin-maintenance/crawler-repair-recipes.md"

# These selections are reviewed instructions distilled into product data, not an
# agent prompt or a list of executable skill scripts. A missing anchor fails the build.
RULES = [
    ("evidence-levels", "区分静态、夹具、服务端与实播证据", ["S03", "M01", "P02", "P04"],
     ["evidence"], CORE, "Keep static, fixture, server", 1,
     "静态通过只证明静态合同。报告保留执行环境、输入来源、采样范围与未测节点。",
     ["按实际执行层级给结论", "检查报告没有把取链或分片等同于客户端长播"]),
    ("duplicate-methods", "拒绝 Spider 中重复方法覆盖", ["S03"], ["duplicate_method"],
     SKILL, "Reject duplicate method definitions", 1,
     "Python 后定义的方法会覆盖前定义。修复应合并正确实现，并回归两边原有能力，不能随意删除一个方法。",
     ["列出每个重复定义的行号", "在真实运行器回归受影响方法"]),
    ("filter-serialization", "分类筛选兼容 dict 与 JSON 字符串", ["S05", "C04", "R04"],
     ["extend", "filter"], COMPAT, "Accept category `extend`", 2,
     "按目标运行器实际调用方式验证筛选参数。不要把只接受 dict 的 Python 夹具当作 Chaquopy 合同。",
     ["对相同筛选分别输入 dict 与 JSON 字符串", "核对 T4 ext 解码到 worker 的实参"]),
    ("raw-loader", "保持 raw PY 的外层加载合同", ["S02", "S06", "R02"],
     ["loader", "init"], COMPAT, "For upstream `1.22.0+` raw Python", 1,
     "raw 插件仍依赖容器生成的 loader/source/raw 外层配置。内部 EXT data 不能替代外层加载配置。",
     ["检查实际生成的订阅及解码 EXT", "核对 .py URI path 与实际加载源码 SHA"]),
    ("backend-mode", "按源的播放职责选择 backend_parse", ["S05", "C08", "R02"],
     ["backend_parse", "detail"], COMPAT, "Require the inner Spider's `backend_parse=True`", 1,
     "只有需要 AT 后端分类/详情/解析的源才启用对应包装。自主详情与直播放源要单独验证，不能统一强制打开。",
     ["比较原始与 ATVP 包装后的相同 ID", "以目标 AT 源码核对实际开关"]),
    ("episode-packing", "保留线路、选集与原始播放 ID", ["C10", "R01"],
     ["episode", "flag"], PLAY, "- `$$$` separates", 5,
     "线路以 $$$ 分组，组内以 # 分集，名称与 ID 以 $ 分隔。保持顺序、headers 与解析标记，不静默删除失败线路。",
     ["核对来源组与选集组一一对应", "从真实选集把原始 ID 和 flag 传入 player"]),
    ("challenge-classification", "把挑战、鉴权与网络问题归到正确层", ["C03", "C05", "M01"],
     ["waf", "403", "challenge", "login"], CORE, "- A challenge page", 2,
     "挑战页、登录页和权限不足不等于解析结构变化；先归类并停止无效重试，不生成绕过访问限制的补丁。",
     ["检查状态、内容类型和业务标记", "缺凭据标记 blocked，维护网络与解析故障分别统计"]),
    ("hls-evidence", "逐层验证 HLS 清单与媒体", ["M01", "M02", "M03", "M04", "M05"],
     ["hls", "manifest"], MATRIX, "- HLS: validate master syntax", 1,
     "对清单、选中 variant、片段库存与媒体字节分别取证，检查错误 HTML，不能因扩展名或 HTTP 200 判断可播。",
     ["沿真实 player 结果解析相对 URI", "记录选中 variant 与采样分片范围"]),
    ("mp4-ranges", "MP4 需要多个位置的 Range 证据", ["M06", "M08"],
     ["range", "mp4"], MATRIX, "- Direct MP4 or similar", 1,
     "首部、中央、尾部范围请求分别检查 Content-Range 与总长度。取样字节不能替代解码和长播。",
     ["限制每次读取字节并记录响应范围", "使用同一次取链的必要 headers 和会话"]),
    ("time-claims", "连续窗口与长播分开报告", ["M07", "P04"],
     ["continuity", "long_play"], MATRIX, "- A successful segment near", 4,
     "后段可达只证明该位置可达。长播需要经过相同客户端可见调用链的实际计时运行。",
     ["记录实际持续时间、重试与中断", "未运行真实客户端时保持 client_play 为未测"]),
    ("input-provenance", "下一节点使用上一节点的真实输出", ["C07", "C09", "C10", "R01", "R08"],
     ["provenance", "session"], MATRIX, "- Exercise `categoryContent", 3,
     "详情 ID、媒体 URL、headers 和代理路径必须来自被测实现；手工重建路径仅能作为辅助证据。",
     ["绑定源码 SHA、配置和运行器", "记录上游节点以及本次实际输入"]),
    ("incremental-identity", "按内容指纹更新与失效证据", ["S01", "S02", "S08", "R10"],
     ["sha", "cache", "revision"], CORE, "Compare current file SHA-256", 5,
     "文件名、mtime 和声明 version 不是内容身份。源码、配置或运行器变化后要失效相关节点证据。",
     ["保存不可变源码与对应报告", "加载身份核验前不标记新版本已生效"]),
]

PROJECT_RULES = [
    ("response-contract-first", "按真实响应与业务合同定位解析故障",
     ["C01", "C02", "C03", "C04", "C05", "C06", "C07", "C09", "R01", "R04", "M01"],
     ["pagination", "metadata", "output_invalid", "content", "mime", "redirect", "parameter"],
     RECIPES, "### response-contract-first", 31,
     "实发方法/参数、逐跳响应与正文完成分开；网络失败不是有效空列表，HTTP 200、JSON 合法不能替代业务字段。",
     ["比较首/次/末页与真实请求形式及重定向", "使用上一节点真实输出及原始字节验证下游"]),
    ("session-header-scope", "核对会话、凭证与逐跳请求头作用域",
     ["R01", "R05", "R06", "C09", "M01", "M02", "M04", "M05"],
     ["header", "origin", "cookie", "session", "saveResponseCookies", "proxy", "fetchResponseUid",
      "BrowserPool", "useIncognitoPages", "Referer", "Referrer-Policy"],
     RECIPES, "### session-header-scope", 33,
     "分开核对会话读取、写回、实际发送与失效，追踪 EXT 和逐跳头来源；链接发现不授予出站权限，不能复制登录态、删头、猜地址或扩大权限伪造修复。",
     ["定位宿主出站拒绝码及地址/凭证来源", "核对实际 EXT/媒体返回与批准的请求头来源"]),
    ("schema-candidate-replay", "模型生成代码必须固化候选并全节点重放",
     ["S03", "C01", "C03", "C05", "C07", "R01"],
     ["schema", "parser", "candidate", "selector_repair", "code_generator", "execution_result"],
     RECIPES, "### schema-candidate-replay", 26,
     "schema 提示、旧输出及模型自报不构成验证；每次改动按候选 SHA 绑定新执行输出，经 exact_edits_v1 校验及隔离装载器全必需节点复测。",
     ["检查 exact_edits_v1 和固定目标", "验证错误对象及 URL 回填后的原始合同，不在宿主执行模型产物"]),
    ("browser-evidence-only", "浏览器仅补充缺失的页面与网络证据",
     ["C01", "C03", "C05", "C07", "R01"],
     ["render", "dom", "xhr", "fetch", "debug", "node", "spider-flow", "iframe", "shadow", "focusLocator"],
     RECIPES, "### browser-evidence-only", 36,
     "必要渲染只用获准组件；清洗前保留取证。节点窗口绑定本次任务及页面/frame/session；范围回退不授权整页外发，表达式值不是实发证据，历史条目不是本次输出；文本截断不限制下载，头脱敏不覆盖 URL/正文。",
     ["确认当前页面、请求及清洗前后内容身份", "先脱敏再限量模型输入，核对正文读取与预处理副作用"]),
    ("learned-rules-holdout", "从示例生成的抽取规则需要独立页面验收",
     ["C01", "C03", "C05", "C07", "C09"],
     ["selector", "extract", "fields", "rule", "duplicate", "identity",
      "CONTENT_CARD_INVALID", "CONTENT_IDS_DUPLICATED", "DETAIL_IDENTITY_MISMATCH", "COVER_URL_EMPTY"],
     RECIPES, "### learned-rules-holdout", 24,
     "每张卡片独立记录并用相对选择器关联标题/ID/封面；区分匹配、过滤、累积和输出，禁止 zip 缺字段的列或用默认值补业务。",
     ["用独立页、缺节点页、空属性页及同名条目验收", "逐行核对必需字段及真实来源，保存规则和解析依赖身份"]),
    ("snapshot-cache-boundary", "按抽取输入形态隔离缓存证据",
     ["S02", "S06", "S08"], ["cache", "snapshot", "schema", "sha"],
     RECIPES, "### snapshot-cache-boundary", 19,
     "URL 缓存和页面新鲜度不证明新 schema，hit_fallback 不代表新鲜运行；复用内容指纹和冷/热验证，不新增缓存。",
     ["核对本次输入形态与缓存身份", "源码/画像/运行器变更后失效相关证据"]),
    ("bounded-retry-at-owner", "在请求所属组件内分类和限制重试",
     ["S06", "C03", "C05", "M01", "M05", "R09"],
     ["retry", "timeout", "deadline", "http", "429", "retry_after", "throttle", "stop", "pause", "join", "unroute", "safeRace",
      "PAGE_CLOSED", "releaseAllBrowsers"],
     RECIPES, "### bounded-retry-at-owner", 40,
     "在原预算内区分限流等待、请求重试和失败终态；不截短服务端等待或重置截止换成功，结束等待、注销处理器或隐藏错误不等于底层取消与清理。",
     ["记录原因及耗尽状态，不重置总截止", "确认取消、超时、退出及晚到结果的实际清理"]),
    ("opaque-data-scope", "混淆与密文分析保留身份及执行边界",
     ["S02", "S04", "R01", "M04"], ["opaque", "decrypt", "crypto", "key", "font", "decode"],
     RECIPES, "### opaque-data-scope", 15,
     "混淆转换进度不证明解码完整；缺原生组件保留失败。字形名称和组件引用不是数字语义，授权包恢复仍需匹配加载链及双 SHA。",
     ["核对解码数量/类型、剩余依赖和原始/转换 SHA", "区分响应解码、包恢复、媒体加密及字体语义，不在宿主执行未知代码"]),
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def render(skill_root):
    rules = []
    for ident, title, nodes, tags, ref, anchor, count, guidance, checks in RULES + PROJECT_RULES:
        project_reference = ref == RECIPES
        payload = ((REPO if project_reference else skill_root) / ref).read_bytes()
        lines = payload.decode("utf-8-sig").splitlines()
        matches = [i for i, line in enumerate(lines) if anchor in line]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one anchor in {ref}: {anchor}")
        start = matches[0]
        excerpt = "\n".join(lines[start:start + count])
        rules.append({
            "id": ident, "title": title, "nodes": nodes, "tags": tags,
            "runtimes": ["atvp", "t4", "fongmi"],
            "guidance": guidance, "validationSteps": checks,
            "failureCodes": (["duplicate_method"] if ident == "duplicate-methods" else
                             ["EGRESS_HEADER_ORIGIN_DENIED", "PLAYER_HEADERS_INVALID"]
                             if ident == "session-header-scope" else []),
            "evidenceClass": "guidance", "authority": "reference_only",
            "versionScope": "按目标运行器核对；历史技能记录不是当前部署证明",
            "citation": {
                "skill": "project-reviewed-resources" if project_reference else ref.split("/")[0], "path": ref,
                "sha256": digest(payload), "startLine": start + 1,
                "endLine": min(start + count, len(lines)), "excerpt": excerpt,
            },
        })
    matrix = REPO / "docs/planning/plugin-maintenance/capability-matrix.md"
    nodes = []
    for line in matrix.read_text(encoding="utf-8").splitlines():
        cells = [cell.strip() for cell in line.split("|")[1:-1]]
        if len(cells) == 5 and len(cells[0]) == 3 and cells[0][0] in "SCRM P" and cells[0][1:].isdigit():
            nodes.append({"id": cells[0], "title": cells[1], "inputs": cells[2],
                          "assertions": cells[3], "boundary": cells[4]})
    if len(nodes) != 44 or len({n["id"] for n in nodes}) != 44:
        raise ValueError("Expected 44 unique capability nodes")
    return {
        "catalog.json": {"schemaVersion": 1, "authority": "data_only",
                         "rules": rules},
        "nodes.json": {"schemaVersion": 1, "matrixSha256": digest(matrix.read_bytes()),
                       "nodes": nodes},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skills-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = render(args.skills_root.resolve())
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, value in outputs.items():
        expected = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        target = OUTPUT / name
        if args.check:
            if not target.exists() or target.read_bytes() != expected:
                raise SystemExit(f"Knowledge snapshot changed: {name}; review before rebuilding")
        else:
            target.write_bytes(expected)
    print(json.dumps({"rules": len(RULES) + len(PROJECT_RULES), "nodes": len(outputs["nodes.json"]["nodes"]),
                      "mode": "check" if args.check else "build"}))


if __name__ == "__main__":
    main()
