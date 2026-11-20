"""Fixed no-network Atvp init probe. Its post-load output is explicitly untrusted."""
from __future__ import annotations

import base64
import contextlib
import json
from pathlib import Path
import runpy
import sys

ROOT = Path("/opt/at-maintenance")


def main():
    sys.path[:0] = [str(ROOT / "runtime"), str(ROOT / "managed/vendor"),
                    str(ROOT / "managed/vendor/pyquery.whl"), str(ROOT / "managed/vendor/cssselect.whl")]
    sys.argv = [str(ROOT / "runtime/t4worker.py"), str(ROOT / "runtime/Atvp.py")]
    result = {"evidenceClass": "untrusted_source_output", "operation": "atvp_init_empty_ext",
              "status": "source_exception", "businessChainVerified": False}
    try:
        with contextlib.redirect_stdout(sys.stderr):
            namespace = runpy.run_path(sys.argv[0], run_name="managed_source")
            worker = namespace["main"].__globals__
            extend = base64.b64encode(json.dumps({
                "source": "/work/managed-source.py", "raw": True}).encode()).decode("ascii")
            worker["handle"]("init", {"extend": extend})
        result["status"] = "init_returned"
    except ModuleNotFoundError:
        result["status"] = "missing_dependency"
    except BaseException:
        result["status"] = "source_exception"
    print(json.dumps(result, separators=(",", ":")), flush=True)


if __name__ == "__main__":
    main()
