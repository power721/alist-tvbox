ARG PYTHON_IMAGE
FROM ${PYTHON_IMAGE}
ARG RUNTIME_SHA256
ARG ENTRY_SHA256
LABEL org.alist-tvbox.maintenance.runtime=${RUNTIME_SHA256}
LABEL org.alist-tvbox.maintenance.entry=${ENTRY_SHA256}
COPY requirements.txt /opt/at-maintenance/requirements.txt
RUN python -m pip install --disable-pip-version-check --no-cache-dir -r /opt/at-maintenance/requirements.txt
COPY runtime/ /opt/at-maintenance/runtime/
COPY container_entry.py /opt/at-maintenance/container_entry.py
ENV PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 HOME=/work TMPDIR=/work PYTHONDONTWRITEBYTECODE=1
WORKDIR /work
USER 65532:65532
HEALTHCHECK NONE
ENTRYPOINT ["python", "-I", "/opt/at-maintenance/container_entry.py"]
