"""Start the AT JAR; same-host T4 setup uses private IPC, never a workbench Docker socket."""
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys


def main():
    os.umask(0o077)
    mode = os.environ.get("MAINTENANCE_DEPLOYMENT", "embedded")
    if mode != "embedded":
        raise SystemExit("INVALID_DEPLOYMENT_MODE")
    data = Path("/data")
    data.mkdir(exist_ok=True)
    java = os.environ.get("JAVA_EXECUTABLE", "/jre/bin/java")
    arguments = [java, *shlex.split(os.environ.get("JAVA_OPTS", "-Xms128m -Xmx768m")),
                 "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=/tmp", "-jar",
                 "/opt/maintenance/application.jar"]
    for directory in ("log", "atv", "index"):
        (data / directory).mkdir(exist_ok=True)
    for directory in ("/www/static", "/www/tvbox", "/opt/alist/data"):
        Path(directory).mkdir(parents=True, exist_ok=True)
    config_path = Path("/opt/alist/data/config.json")
    if not config_path.exists():
        config = json.loads(Path("/alist.json").read_bytes())
        config["jwt_secret"] = secrets.token_urlsafe(32)
        config["scheme"]["address"] = "127.0.0.1"
        config["scheme"]["http_port"] = 5244
        config_path.write_text(json.dumps(config), encoding="utf-8")
        with (data / "log/alist-init.log").open("ab") as log:
            subprocess.run(["/opt/alist/alist", "admin"], cwd="/opt/alist",
                           stdout=log, stderr=log, check=True, timeout=60)
    arguments.extend(["--spring.profiles.active=docker",
                      "--spring.datasource.jdbc-url=jdbc:h2:file:/data/atv;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",
                      "--spring.sql.init.mode=never", "--logging.file.name=/data/log/app.log"])
    arguments.extend([
        "--server.address=" + os.environ.get("MAINTENANCE_BIND", "0.0.0.0"),
        "--server.port=" + os.environ.get("MAINTENANCE_PORT", "4567"),
        "--maintenance.connections-file=/data/maintenance-connections.json",
        "--maintenance.model.auto-submit-enabled=" + os.environ.get("MAINTENANCE_MODEL_AUTO_SUBMIT_ENABLED", "false"),
        "--maintenance.at-publish.automatic-enabled=" + os.environ.get("MAINTENANCE_AT_AUTO_PUBLISH_ENABLED", "false"),
        "--maintenance.at-publish.automatic-instance-key=" + os.environ.get("MAINTENANCE_AT_AUTO_PUBLISH_INSTANCE_KEY", ""),
        "--maintenance.scheduling.enabled=" + os.environ.get("MAINTENANCE_SCHEDULING_ENABLED", "false"),
    ])
    routes = os.environ.get("MAINTENANCE_MANAGED_SOURCE_ROUTES_FILE")
    if routes:
        arguments.extend(["--maintenance.managed-source.enabled=true",
                          "--maintenance.managed-source.routes-file=" + routes])
    setup_socket = os.environ.get("MAINTENANCE_MANAGED_SOURCE_SETUP_SOCKET")
    if setup_socket:
        if not routes:
            raise SystemExit("LOCAL_T4_ROUTES_REQUIRED")
        arguments.extend(["--maintenance.managed-source.setup.enabled=true",
                          "--maintenance.managed-source.setup.socket=" + setup_socket])
    os.chdir(data)
    os.execve(java, [*arguments, *sys.argv[1:]], os.environ.copy())


if __name__ == "__main__":
    main()
