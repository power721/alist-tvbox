"""Export the product HTTP verifier JAR as an offline NAS image context."""
from __future__ import annotations

import argparse
from email.parser import BytesParser
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

MAIN = "cn.har01d.maintenance.lab.ManagedT4HttpAcceptance"
MODULE = Path(__file__).resolve().parents[1]


def digest(body):
    return hashlib.sha256(body).hexdigest()


def write_entry(archive, name, body):
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
    archive.writestr(entry, body, compress_type=zipfile.ZIP_DEFLATED)


def prepare(jar, jar_sha, base_image, base_image_id):
    if (not re.fullmatch(r"[a-f0-9]{64}", jar_sha)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9/._:@-]{0,250}", base_image)
            or not re.fullmatch(r"sha256:[a-f0-9]{64}", base_image_id)):
        raise ValueError("HTTP_CONTEXT_IDENTITY_INVALID")
    body = Path(jar).read_bytes()
    if digest(body) != jar_sha:
        raise ValueError("HTTP_CONTEXT_JAR_CHANGED")
    classes, libraries = {}, {}
    with zipfile.ZipFile(io.BytesIO(body)) as source:
        if len(source.namelist()) != len(set(source.namelist())):
            raise ValueError("HTTP_CONTEXT_DUPLICATE_JAR_ENTRY")
        headers = BytesParser().parsebytes(source.read("META-INF/MANIFEST.MF"), headersonly=True)
        if headers.get("Start-Class") != MAIN:
            raise ValueError("HTTP_CONTEXT_WRONG_APPLICATION")
        for entry in source.infolist():
            if entry.is_dir():
                continue
            path = Path(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
                raise ValueError("HTTP_CONTEXT_JAR_PATH_INVALID")
            if entry.filename.startswith("BOOT-INF/classes/"):
                name = entry.filename.removeprefix("BOOT-INF/classes/")
                if (name.endswith(".class")
                        or name.startswith("db/migration/") and name.endswith(".sql")
                        or name == "ua.txt"):
                    classes[name] = source.read(entry)
                else:
                    raise ValueError("HTTP_CONTEXT_UNEXPECTED_RESOURCE")
            elif entry.filename.startswith("BOOT-INF/lib/"):
                name = entry.filename.removeprefix("BOOT-INF/lib/")
                if ("/" in name or not name.endswith(".jar")
                        or name.startswith(("junit-", "mockito-", "spring-test-", "spring-boot-test-"))):
                    raise ValueError("HTTP_CONTEXT_TEST_DEPENDENCY")
                libraries["lib/" + name] = source.read(entry)
    if (MAIN.replace(".", "/") + ".class" not in classes
            or "cn/har01d/maintenance/lab/HttpAcceptanceConfiguration.class" not in classes
            or "cn/har01d/alist_tvbox/web/T4Controller.class" not in classes
            or "cn/har01d/alist_tvbox/service/ManagedT4BrokerClient.class" not in classes
            or "ua.txt" not in classes or not libraries):
        raise ValueError("HTTP_CONTEXT_DRIVER_MISSING")
    if any(name.endswith(("Test.class", "Test$Persistence.class", "Test$Mvc.class")) for name in classes):
        raise ValueError("HTTP_CONTEXT_TEST_CLASS")
    packed = io.BytesIO()
    with zipfile.ZipFile(packed, "w", compression=zipfile.ZIP_DEFLATED) as target:
        directories = set()
        for name, content in sorted(classes.items()):
            for parent in reversed(Path(name).parents):
                folder = parent.as_posix() + "/"
                if parent != Path(".") and folder not in directories:
                    write_entry(target, folder, b"")
                    directories.add(folder)
            write_entry(target, name, content)
    files = {"lib/owners.jar": packed.getvalue(), **libraries}
    files["Dockerfile"] = (
        "FROM " + base_image + " AS java_runtime\nFROM scratch\n"
        "COPY --from=java_runtime / /\nUSER 0\n"
        "RUN /jre/bin/java -version\n"
        "COPY lib/ /opt/at-maintenance/http/lib/\n"
        "RUN chmod 755 /opt/at-maintenance/http /opt/at-maintenance/http/lib "
        "&& chmod 644 /opt/at-maintenance/http/lib/*\n"
        "ENV PATH=/jre/bin:/usr/bin:/bin HOME=/tmp LANG=C.UTF-8\n"
        "WORKDIR /tmp\nUSER 65532:65532\nHEALTHCHECK NONE\n"
        'ENTRYPOINT ["java"]\n'
        'CMD ["-cp","/opt/at-maintenance/http/classes:/opt/at-maintenance/http/lib/*","' + MAIN + '"]\n'
    ).encode("ascii")
    manifest = {
        "schemaVersion": 2, "authority": "expected_product_http_build_context",
        "baseImage": base_image, "baseImageId": base_image_id,
        "applicationJarSha256": jar_sha, "mainClass": MAIN,
        "runtimeTestDependencies": False, "historicalOwnerReceiptRequired": False,
        "files": {name: {"bytes": len(content), "sha256": digest(content)}
                  for name, content in files.items()},
        "boundary": "Build context only. Check the local base image ID before building without a pull. "
                    "Not image existence, current source acceptance, or client playback.",
    }
    return files, manifest


def export(output, files, manifest):
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, body in sorted(files.items()):
            write_entry(archive, name, body)
        write_entry(archive, "build-context-manifest.json",
                    (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--base-image", required=True)
    parser.add_argument("--base-image-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    files, manifest = prepare(args.jar, args.jar_sha256, args.base_image, args.base_image_id)
    export(args.output, files, manifest)
    print(json.dumps({"output": str(args.output.resolve()), "files": len(files),
                      "sha256": digest(args.output.read_bytes()),
                      "applicationJarSha256": args.jar_sha256, "imageBuilt": False}))


if __name__ == "__main__":
    main()
