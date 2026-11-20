"""Run the trusted offline fixture worker with network entrypoints disabled."""
import runpy
import socket
import sys
import requests


def blocked(*args, **kwargs):
    raise RuntimeError("NETWORK_DISABLED_IN_FIXTURE")


socket.socket.connect = blocked
socket.create_connection = blocked
socket.getaddrinfo = blocked
requests.sessions.Session.request = blocked
sys.argv = sys.argv[1:]
runpy.run_path(sys.argv[0], run_name="__main__")
