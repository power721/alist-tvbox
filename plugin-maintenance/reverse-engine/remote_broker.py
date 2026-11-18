"""Run the same bounded browser HTTP broker on the configured runner's SSH host."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import sys
import threading
import time


BOOTSTRAP = r'''
import base64,hashlib,json,pathlib,runpy,sys,tempfile
try:
 header=sys.stdin.buffer.readline(1048577)
 assert len(header)<=1048576
 bundle=json.loads(header)
 assert set(bundle['files'])=={'broker.py','source_egress.py','source_origin_discovery.py'}
 task=bundle['taskId']
 assert len(task)==36 and all(c in '0123456789abcdef-' for c in task)
 assert task==sys.argv[1]
 with tempfile.TemporaryDirectory(prefix='at-maintenance-reverse-'+task+'-') as directory:
  root=pathlib.Path(directory)
  for name,item in bundle['files'].items():
   body=base64.b64decode(item['body'],validate=True)
   assert len(body)<=524288 and hashlib.sha256(body).hexdigest()==item['sha256']
   (root/name).write_bytes(body)
  print(json.dumps({'transportState':{'phase':'started','taskId':task}}),flush=True)
  sys.argv=[str(root/'broker.py'),str(root/'source_egress.py')]
  runpy.run_path(sys.argv[0],run_name='__main__')
 print(json.dumps({'transportState':{'phase':'cleaned','taskId':task}}),flush=True)
except Exception:
 sys.exit(1)
'''


def bundle(egress, task_id):
    paths = {'broker.py': Path(__file__).with_name('broker.py'),
             'source_egress.py': Path(egress),
             'source_origin_discovery.py': Path(egress).with_name('source_origin_discovery.py')}
    files = {}
    for name, path in paths.items():
        body = path.read_bytes()
        if len(body) > 524288:
            raise ValueError('REVERSE_REMOTE_BUNDLE_LIMIT')
        files[name] = {'body': base64.b64encode(body).decode('ascii'),
                       'sha256': hashlib.sha256(body).hexdigest()}
    result = json.dumps({'taskId': task_id, 'files': files}).encode() + b'\n'
    if len(result) > 1048576:
        raise ValueError('REVERSE_REMOTE_BUNDLE_LIMIT')
    return result


def relay(channel, outgoing, incoming):
    def send():
        try:
            for line in incoming:
                channel.sendall(line)
            channel.shutdown_write()
        except (OSError, EOFError):
            channel.close()

    sender = threading.Thread(target=send, daemon=True)
    sender.start()
    total, deadline = 0, time.monotonic() + 72
    while True:
        if channel.recv_ready():
            body = channel.recv(65536)
            total += len(body)
            if total > 10 * 1024 * 1024:
                raise ValueError('REVERSE_REMOTE_OUTPUT_LIMIT')
            outgoing.write(body)
            outgoing.flush()
        if channel.recv_stderr_ready():
            channel.recv_stderr(65536)
        if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
            if channel.recv_exit_status() != 0:
                raise ValueError('REVERSE_REMOTE_FAILED')
            return
        if time.monotonic() > deadline:
            raise TimeoutError('REVERSE_REMOTE_DEADLINE')
        time.sleep(0.01)


def main():
    egress, config_path, tunnel_script, task_id = sys.argv[1:]
    spec = importlib.util.spec_from_file_location('reverse_runner_tunnel', tunnel_script)
    tunnel = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tunnel)
    config = tunnel.Config(config_path)
    transport = tunnel.Transport(config)
    client = transport.connect()
    channel = None
    try:
        channel = client.get_transport().open_session(timeout=8)
        channel.settimeout(8)
        channel.exec_command(shlex.join(['/usr/bin/python3', '-I', '-c', BOOTSTRAP, task_id]))
        channel.sendall(bundle(egress, task_id))
        relay(channel, sys.stdout.buffer, sys.stdin.buffer)
    finally:
        if channel is not None:
            channel.close()
        client.close()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        sys.exit(1)
