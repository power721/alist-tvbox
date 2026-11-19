"""Task-owned evidence transport; private HTTP session state never reaches spiders."""
import base64
import hashlib
import http.cookiejar
import json
import re
import threading
import time
import uuid
from email.message import Message
from http.cookies import SimpleCookie, CookieError
from types import SimpleNamespace
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from source_egress import retry_after


class GatewayError(Exception):
    def __init__(self, code, http_status=None, *, phase='response', retry_after=None):
        super().__init__(code)
        self.code = code
        self.http_status = http_status
        self.phase = phase
        self.retry_after = retry_after
        self.session_absent = False


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class HostCookieSession:
    """Origin-bound, in-memory cookies and UA, separate from the spider's jar."""
    def __init__(self, url, cookies, user_agent):
        parsed = urlsplit(url)
        self.origin = (parsed.scheme, parsed.hostname, parsed.port)
        self.jar = http.cookiejar.CookieJar()
        self.user_agent = user_agent
        self.deadline = time.monotonic() + 1800
        if (not isinstance(user_agent, str) or not 1 <= len(user_agent) <= 1024
                or re.search(r'[^\x20-\x7e]', user_agent) or not isinstance(cookies, list)):
            return
        now = int(time.time())
        for item in cookies[:32]:
            if not isinstance(item, dict):
                continue
            name, value = item.get('name'), item.get('value')
            domain, path = item.get('domain', parsed.hostname), item.get('path', '/')
            expiry = item.get('expires', item.get('expiry'))
            if (not isinstance(name, str) or not re.fullmatch(r'[!#$%&\x27*+.^_`|~0-9A-Za-z-]{1,128}', name)
                    or not isinstance(value, str) or len(value) > 4096 or re.search(r'[^\x21-\x3a\x3c-\x7e]', value)
                    or not isinstance(domain, str) or not (domain.lstrip('.') == parsed.hostname
                        or '.' in domain.lstrip('.') and parsed.hostname.endswith('.' + domain.lstrip('.')))
                    or not isinstance(path, str) or not path.startswith('/') or len(path) > 2048
                    or re.search(r'[^\x21-\x7e]', path)
                    or type(item.get('secure', False)) is not bool
                    or expiry is not None and (type(expiry) not in {int, float} or not -1 <= expiry <= 2 ** 53)):
                continue
            expiry = min(now + 1800, int(expiry)) if expiry is not None and expiry >= 0 else now + 1800
            if expiry <= now:
                continue
            self.jar.set_cookie(http.cookiejar.Cookie(
                0, name, value, None, False, domain, domain.startswith('.'), domain.startswith('.'),
                path, True, item.get('secure', False), expiry, False, None, None, {}))

    def headers(self, url):
        parsed = urlsplit(url)
        if ((parsed.scheme, parsed.hostname, parsed.port) != self.origin
                or time.monotonic() >= self.deadline):
            return {}
        self.jar.clear_expired_cookies()
        request = Request(url)
        self.jar.add_cookie_header(request)
        cookie = request.get_header('Cookie')
        return {'Cookie': cookie, 'User-Agent': self.user_agent} if cookie and len(cookie) <= 8192 else {}

    def update(self, url, headers):
        message = Message()
        for key, value in headers:
            if key.lower() == 'set-cookie' and len(value) <= 4096 and '\r' not in value and '\n' not in value:
                message.add_header('Set-Cookie', value)
        self.jar.extract_cookies(SimpleNamespace(info=lambda: message), Request(url))
        # Keep rotations private, bounded, and no longer lived than the original lease.
        for cookie in list(self.jar):
            cookie.expires = min(cookie.expires or int(time.time()) + 1800, int(time.time()) + 1800)
        if len(self.jar) > 32:
            self.jar.clear()


def challenge(response):
    headers = {key.lower(): str(value).lower() for key, value in response.get('headers', {}).items()}
    body = base64.b64decode(response.get('body', ''))[:32768].lower()
    return (headers.get('cf-mitigated') == 'challenge'
            or b'_cf_chl_opt' in body and b'/cdn-cgi/challenge-platform' in body
            or b'<title>just a moment' in body and b'cloudflare' in body)


def missing_session(message):
    return re.search(r"session.{0,160}(?:doesn't exist|does not exist|not found)", message) is not None


def gateway_failure(status, body, retry_header=None):
    try:
        value = body if isinstance(body, dict) else json.loads(body)
        message = json.dumps({key: value[key] for key in ('error', 'message', 'code') if key in value}).lower() \
            if isinstance(value, dict) else ''
    except (ValueError, UnicodeError):
        message = ''
    code = ('EVIDENCE_GATEWAY_BUSY' if status in {409, 429} or any(term in message for term in
                ('too many sessions', 'browser pool exhausted', 'queue full', 'server busy', 'concurrency limit')) else
            'EVIDENCE_GATEWAY_SESSION_LOST' if missing_session(message) or any(term in message for term in
                ('invalid session id', 'session not found', 'session does not exist', 'session deleted',
                 'browser has been closed', 'browser disconnected', 'target closed', 'no such window')) else
            'EVIDENCE_GATEWAY_TARGET_DNS_FAILED' if any(term in message for term in
                ('ns_error_unknown_host', 'err_name_not_resolved', 'dns resolution')) else
            'EVIDENCE_GATEWAY_TIMEOUT' if any(term in message for term in ('timeout', 'timed out')) else
            'EVIDENCE_GATEWAY_CHALLENGE_BLOCKED' if 'challenge' in message else
            'EVIDENCE_GATEWAY_BUSY' if status == 503 else
            'EVIDENCE_GATEWAY_RESPONSE_INVALID' if status == 200 else 'EVIDENCE_GATEWAY_HTTP_FAILED')
    error = GatewayError(code, status, retry_after=retry_after(retry_header, time.time()))
    error.session_absent = missing_session(message)
    return error


class EvidenceGateway:
    def __init__(self, settings, egress, deadline=None):
        self.adapter = settings['adapter']
        self.endpoint = settings['endpoint'].rstrip('/')
        parsed = urlsplit(self.endpoint)
        if (self.adapter not in {'trawl', 'flaresolverr', 'flaresolverr-rs-alt'}
                or parsed.scheme not in {'http', 'https'} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment):
            raise GatewayError('EVIDENCE_GATEWAY_CONFIGURATION_INVALID')
        self.timeout = min(60, max(1, settings['timeoutMillis'] / 1000))
        self.deadline = deadline or time.monotonic() + 60
        self.egress = egress
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.session = None
        self.created = False
        self.session_ready = False
        self.uncertain_creation = False
        self.requests = self.total = self.reused = 0
        self.responses = 0
        self.captured = {}
        self.cleanup = True
        self.lock = threading.RLock()
        self.max_requests = 8
        self.max_response_bytes = 2097152
        self.max_total_bytes = 4194304
        self.max_wire_bytes = 6291456
        self.capture_scripts = True
        self.failure = None
        self.retry_at = 0
        self.reset_required = False
        self.recovery_attempts = 0
        self.session_recoveries = 0
        self.session_uses = 0
        self.retain_host_cookies = False
        self.host_session = None
        self.host_cookie_status = 'not_requested'
        self.execution_counts = {key: 0 for key in ('tier1', 'tier2', 'tier3', 'tier4', 'unknown',
                                                   'cachedSession', 'unknownCache')}

    def command(self, path, payload=None, method='POST', cleanup=False):
        remaining = 10 if cleanup else self.deadline - time.monotonic() - 10
        if remaining <= 0:
            raise GatewayError('EVIDENCE_GATEWAY_DEADLINE')
        deadline = time.monotonic() + remaining
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(self.endpoint + path, data=data, method=method,
                          headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
        phase = 'connect'
        try:
            with self.opener.open(request, timeout=min(remaining, self.timeout + 5)) as response:
                phase = 'read'
                body = self.read(response, self.max_wire_bytes, deadline)
                phase = 'decode'
                result = json.loads(body)
                if not isinstance(result, dict):
                    raise GatewayError('EVIDENCE_GATEWAY_RESPONSE_INVALID')
                return result
        except HTTPError as error:
            try:
                with error:
                    body = self.read(error, 8192, deadline)
            except TimeoutError:
                raise GatewayError('EVIDENCE_GATEWAY_READ_TIMEOUT', phase='read') from None
            except OSError:
                raise GatewayError('EVIDENCE_GATEWAY_CONNECTION_FAILED', phase='read') from None
            raise gateway_failure(error.code, body, error.headers.get('Retry-After')) from None
        except URLError as error:
            code = 'CONNECT_TIMEOUT' if isinstance(error.reason, TimeoutError) else 'CONNECTION_FAILED'
            raise GatewayError('EVIDENCE_GATEWAY_' + code, phase='connect') from None
        except TimeoutError:
            raise GatewayError('EVIDENCE_GATEWAY_READ_TIMEOUT', phase=phase) from None
        except OSError:
            raise GatewayError('EVIDENCE_GATEWAY_CONNECTION_FAILED', phase=phase) from None
        except (ValueError, UnicodeError):
            raise GatewayError('EVIDENCE_GATEWAY_RESPONSE_INVALID', phase='decode') from None

    @staticmethod
    def read(response, limit, deadline):
        parts, total = [], 0
        while True:
            if time.monotonic() >= deadline:
                raise GatewayError('EVIDENCE_GATEWAY_DEADLINE')
            chunk = response.read1(min(65536, limit + 1 - total))
            if not chunk:
                return b''.join(parts)
            parts.append(chunk)
            total += len(chunk)
            if total > limit:
                raise GatewayError('EVIDENCE_GATEWAY_RESPONSE_TOO_LARGE')

    def create(self):
        self.session = 'at-evidence-' + str(uuid.uuid4())
        self.session_ready = False
        self.uncertain_creation = False
        self.cleanup = False
        if self.adapter == 'trawl':
            result = self.command('/sessions', {'id': self.session})
            if result.get('id') != self.session:
                if result.get('error') or result.get('status') == 'error':
                    raise gateway_failure(200, result)
                raise GatewayError('EVIDENCE_GATEWAY_SESSION_INVALID')
        else:
            result = self.command('/v1', {'cmd': 'sessions.create', 'session': self.session})
            session = result.get('session')
            if not session and self.adapter == 'flaresolverr-rs-alt':
                match = re.search(r'\b(?:at-evidence-)?[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\b',
                                  str(result.get('message', '')))
                session = match.group() if match else None
            if result.get('status') != 'ok':
                raise gateway_failure(200, result)
            if not isinstance(session, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', session):
                raise GatewayError('EVIDENCE_GATEWAY_SESSION_INVALID')
            self.session = session
        self.created = True
        self.session_ready = True
        self.session_uses = 0

    def public_url(self, url):
        _, host, port = self.egress.http_authority(url, 'EGRESS_URL_DENIED')
        self.egress.public_dns_addresses(host, port=port)

    def fetch(self, value):
        with self.lock:
            blocked = self.blocked_error()
            if blocked:
                raise blocked
            try:
                if self.failure:
                    self.recovery_attempts += 1
                    if self.reset_required:
                        # A timed-out navigation may still be running remotely.
                        if not self._close():
                            raise GatewayError('EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED', phase='cleanup')
                        self.session_recoveries += 1
                        self.reset_required = False
                    self.failure = None
                return self._fetch(value)
            except GatewayError as error:
                self.record_failure(error)
                raise

    def blocked_error(self):
        if self.failure and (self.retry_at is None or time.monotonic() < self.retry_at):
            return self.failure
        if self.failure and time.monotonic() + 10 >= self.deadline:
            return GatewayError('EVIDENCE_GATEWAY_DEADLINE')
        return None

    def record_failure(self, error):
        self.failure = error
        if self.session is not None and not self.session_ready and error.code in {
                'EVIDENCE_GATEWAY_TIMEOUT', 'EVIDENCE_GATEWAY_READ_TIMEOUT', 'EVIDENCE_GATEWAY_CONNECT_TIMEOUT',
                'EVIDENCE_GATEWAY_CONNECTION_FAILED', 'EVIDENCE_GATEWAY_UNAVAILABLE'}:
            self.uncertain_creation = True
        recoverable = error.code in {
            'EVIDENCE_GATEWAY_BUSY', 'EVIDENCE_GATEWAY_UNAVAILABLE', 'EVIDENCE_GATEWAY_CONNECTION_FAILED',
            'EVIDENCE_GATEWAY_CONNECT_TIMEOUT', 'EVIDENCE_GATEWAY_READ_TIMEOUT', 'EVIDENCE_GATEWAY_TIMEOUT',
            'EVIDENCE_GATEWAY_TARGET_DNS_FAILED', 'EVIDENCE_GATEWAY_SESSION_LOST'} or (
                error.code == 'EVIDENCE_GATEWAY_HTTP_FAILED' and error.http_status is not None
                and error.http_status >= 500)
        self.reset_required = self.reset_required or (self.session is not None and
            (not self.session_ready or error.code in {'EVIDENCE_GATEWAY_TIMEOUT', 'EVIDENCE_GATEWAY_READ_TIMEOUT',
                'EVIDENCE_GATEWAY_CONNECT_TIMEOUT', 'EVIDENCE_GATEWAY_CONNECTION_FAILED', 'EVIDENCE_GATEWAY_SESSION_LOST'}))
        timing = error.retry_after or {}
        delay = timing.get('seconds')
        delay = max(1, delay) if type(delay) is int else 2
        self.retry_at = time.monotonic() + delay if recoverable and self.recovery_attempts < 2 else None

    def recovery(self):
        if not self.failure:
            return None
        now = time.monotonic()
        wait = None if self.retry_at is None else max(0, int((self.retry_at - now) * 1000))
        budget = max(0, int((self.deadline - now - 10) * 1000))
        action = ('stopped' if wait is None else 'retry_ready' if wait == 0 else
                  'reset_after_cooldown' if self.reset_required else 'cooldown')
        return {'phase': self.failure.phase, 'action': action, 'remainingWaitMilliseconds': wait,
                'taskBudgetRemainingMilliseconds': budget,
                'fitsTaskBudget': None if wait is None else wait < budget}

    def _fetch(self, value):
        url = value['url']
        self.public_url(url)
        if (value.get('method') != 'GET' or value.get('body')
                or any(key.lower() in {'cookie', 'authorization', 'proxy-authorization'} for key in value['headers'])):
            raise GatewayError('EVIDENCE_GATEWAY_CREDENTIAL_FORWARDING_DENIED')
        if (self.responses >= self.max_requests or self.requests >= self.max_requests
                or self.total >= self.max_total_bytes):
            raise GatewayError('EVIDENCE_GATEWAY_BUDGET')
        if url in self.captured:
            return self.normalize(url, *self.captured.pop(url), representation='gateway_captured_text')
        if self.session is None:
            self.create()
        elif self.session_uses:
            self.reused += 1
        timeout = int(min(self.timeout, self.deadline - time.monotonic() - 10) * 1000)
        if timeout <= 0:
            raise GatewayError('EVIDENCE_GATEWAY_DEADLINE')
        self.requests += 1
        self.session_uses += 1
        if self.adapter == 'trawl':
            headers = {key: val for key, val in value['headers'].items()
                       if key.lower() in {'accept', 'referer', 'accept-language'}}
            payload = {'url': url, 'sessionId': self.session, 'maxTimeout': timeout,
                       'headers': headers, 'solveEmbeddedCaptchas': False,
                       'settleTimeout': min(1000, timeout // 4)}
            if self.capture_scripts:
                payload['captureResponses'] = ['*.js*', '*.mjs*']
            result = self.command('/scrape', payload)
            if result.get('error') or result.get('status') == 'error':
                raise gateway_failure(200, result)
            captured = result.get('capturedResponses', [])
            for item in captured[:16] if self.capture_scripts and isinstance(captured, list) else []:
                if not isinstance(item, dict):
                    continue
                address = item.get('url', '')
                body = item.get('body')
                if (isinstance(address, str) and isinstance(body, str) and not item.get('truncated') and not item.get('error')
                        and item.get('status') == 200 and urlsplit(address).path.lower().endswith(('.js', '.mjs'))):
                    try:
                        raw = base64.b64decode(body, validate=True) if item.get('base64Encoded') else body.encode()
                    except ValueError:
                        continue
                    if len(raw) <= 524288 and sum(len(entry[0]) for entry in self.captured.values()) + len(raw) <= 2097152:
                        self.captured[address] = (raw, address, 200, self.response_headers(item.get('headers')))
            body, final, status = result.get('html'), result.get('url'), result.get('statusCode')
            headers = self.response_headers(result.get('headers'))
            headers.setdefault('content-type', 'text/html; charset=utf-8')
        else:
            result = self.command('/v1', {'cmd': 'request.get', 'url': url, 'session': self.session, 'maxTimeout': timeout})
            if result.get('status') != 'ok':
                raise gateway_failure(200, result)
            if not isinstance(result.get('solution'), dict):
                raise GatewayError('EVIDENCE_GATEWAY_RESPONSE_INVALID')
            solution = result['solution']
            body, final, status = solution.get('response'), solution.get('url'), solution.get('status')
            headers = self.response_headers(solution.get('headers'))
        if (not isinstance(body, str) or not body.strip() and status != 429 or not isinstance(final, str)
                or type(status) is not int or not 100 <= status <= 599):
            raise GatewayError('EVIDENCE_GATEWAY_EMPTY_OR_INVALID_RESPONSE')
        self.public_url(final)
        execution = {'tier': result.get('tier') if self.adapter == 'trawl' and type(result.get('tier')) is int
                     and 1 <= result['tier'] <= 4 else None,
                     'sessionCached': result.get('sessionCached') if self.adapter == 'trawl'
                     and type(result.get('sessionCached')) is bool else None}
        normalized = self.normalize(url, body.encode(), final, status, headers,
                                    representation='gateway_rendered_document', execution=execution)
        if self.retain_host_cookies and final == url and 200 <= status < 300 and not challenge(normalized['response']):
            state = result if self.adapter == 'trawl' else solution
            self.host_session = HostCookieSession(url, state.get('cookies'), state.get('userAgent'))
            self.host_cookie_status = ('cookies_missing' if not state.get('cookies') else
                'user_agent_missing' if not state.get('userAgent') else
                'available' if self.host_session.headers(url) else 'cookie_scope_unusable')
        return normalized

    @staticmethod
    def response_headers(headers):
        return {key.lower(): val for key, val in (headers.items() if isinstance(headers, dict) else [])
                if isinstance(key, str) and isinstance(val, str) and len(val) <= 256
                and key.lower() in {'content-type', 'cf-mitigated', 'retry-after'}}

    def normalize(self, url, body, final, status, headers, representation, execution=None):
        self.public_url(final)
        if len(body) > self.max_response_bytes or self.total + len(body) > self.max_total_bytes:
            raise GatewayError('EVIDENCE_GATEWAY_RESPONSE_TOO_LARGE')
        self.responses += 1
        self.total += len(body)
        execution = execution or {'tier': None, 'sessionCached': None}
        self.execution_counts['tier' + str(execution['tier']) if execution['tier'] else 'unknown'] += 1
        if execution['sessionCached'] is True:
            self.execution_counts['cachedSession'] += 1
        elif execution['sessionCached'] is None:
            self.execution_counts['unknownCache'] += 1
        return {'response': {'status': status, 'headers': headers, 'body': base64.b64encode(body).decode(), 'finalUrl': final},
                'observation': {'responseComplete': True}, 'transport': 'external_evidence_gateway',
                'representation': representation, 'gatewayExecution': execution,
                'gatewaySessionReused': representation == 'gateway_rendered_document' and self.session_uses > 1}

    def close(self):
        with self.lock:
            return self._close()

    def _close(self):
        self.host_session = None
        if self.session is None:
            return self.cleanup
        try:
            result = (self.command('/sessions/' + self.session, method='DELETE', cleanup=True)
                      if self.adapter == 'trawl' else self.command('/v1',
                        {'cmd': 'sessions.destroy', 'session': self.session}, cleanup=True))
            self.cleanup = result.get('status') == 'ok'
            if not self.cleanup and not self.uncertain_creation:
                self.cleanup = missing_session(str(result.get('message', result.get('error', ''))).lower())
        except GatewayError as error:
            self.cleanup = not self.uncertain_creation and (
                self.adapter == 'trawl' and error.http_status == 404 or error.session_absent)
        if self.cleanup:
            self.session = None
            self.session_ready = False
            self.session_uses = 0
        self.captured.clear()
        return self.cleanup

    def summary(self):
        return {'adapter': self.adapter, 'sessionCreated': self.created, 'requestCount': self.requests,
                'responseCount': self.responses,
                'sessionReuseCount': self.reused, 'sessionScope': 'isolated_browser_context' if self.adapter == 'trawl'
                    else 'gateway_managed', 'cookiesExported': False, 'cleanupConfirmed': self.cleanup,
                'targetAddressPolicy': 'controller_public_preflight_gateway_managed_network',
                'sessionRecoveryCount': self.session_recoveries, 'recoveryAttemptCount': self.recovery_attempts,
                'executionCounts': dict(self.execution_counts)}


class GatewayFallback:
    def __init__(self, settings, direct, egress):
        self.direct = direct
        self.gateway = EvidenceGateway(settings, egress, direct.deadline)
        self.active = False

    def fetch(self, value):
        attempts = []
        direct_result = None
        blocked = self.gateway.blocked_error()
        if not self.active or blocked:
            result = self.direct.fetch(value)
            if not challenge(result['response']):
                return result
            direct_result = result
            response = result['response']
            attempts.append({'transport': 'maintenance_pinned_public_http', 'status': response['status'],
                'challengePage': True, 'responseSha256': hashlib.sha256(base64.b64decode(response['body'])).hexdigest()})
            self.active = True
        if blocked:
            direct_result['gatewayErrorCode'] = blocked.code
            direct_result['gatewayRecovery'] = self.gateway.recovery()
            direct_result['accessAttempts'] = attempts
            return direct_result
        try:
            result = self.gateway.fetch(value)
        except GatewayError as error:
            if self.gateway.failure is not error:
                self.gateway.record_failure(error)
            # Keep the original challenge receipt if the auxiliary gateway is unavailable.
            if attempts:
                direct_result['gatewayErrorCode'] = error.code
                direct_result['gatewayRecovery'] = self.gateway.recovery()
                direct_result['accessAttempts'] = attempts + [{'transport': 'external_evidence_gateway',
                    'errorCode': error.code, 'httpStatus': error.http_status}]
                return direct_result
            raise
        result['accessAttempts'] = attempts
        return result


def runtime_settings(value):
    if (not isinstance(value, dict) or set(value) != {'enabled', 'adapter', 'endpoint', 'timeoutMillis'}
            or value['enabled'] is not True or not isinstance(value['adapter'], str)
            or value['adapter'] not in {'trawl', 'flaresolverr', 'flaresolverr-rs-alt'}
            or not isinstance(value['endpoint'], str) or not 1 <= len(value['endpoint']) <= 2048
            or type(value['timeoutMillis']) is not int or not 1000 <= value['timeoutMillis'] <= 60000):
        raise GatewayError('EVIDENCE_GATEWAY_CONFIGURATION_INVALID')
    parsed = urlsplit(value['endpoint'])
    if (parsed.scheme not in {'http', 'https'} or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or any(ord(char) <= 32 or ord(char) == 127 for char in value['endpoint'])):
        raise GatewayError('EVIDENCE_GATEWAY_CONFIGURATION_INVALID')
    try:
        parsed.port
    except ValueError:
        raise GatewayError('EVIDENCE_GATEWAY_CONFIGURATION_INVALID') from None
    return dict(value)


class RuntimeGateway:
    """One task, origin-owned browsers and private lightweight HTTP sessions."""
    def __init__(self, settings, egress):
        self.settings = runtime_settings(settings)
        self.egress = egress
        self.clients = {}
        self.lightweight_counts = dict.fromkeys(('attempts', 'httpAccepted', 'rejected'), 0)

    @staticmethod
    def reproducible(broker, value):
        supported = {'accept', 'accept-language', 'referer', 'user-agent', 'cache-control', 'pragma'}
        return (value['method'] == 'GET' and not value.get('body') and not broker.host_credentials
                and not any(key.lower() not in supported for key in value['headers']))

    def request_headers(self, broker, value, expected_cookie):
        client = self.clients.get(broker.policy['origin'])
        if not client or not client.host_session:
            return {}
        if not self.reproducible(broker, value) or any(broker.source_cookies):
            broker.lightweight_cookie_skip = 'request_not_reproducible'
            return {}
        headers = client.host_session.headers(value['url'])
        if not headers:
            broker.lightweight_cookie_skip = 'scope_or_expiry'
            return {}
        # This is a fresh anonymous task session. Explicit administrator/source
        # credentials are excluded above; the gateway's paired session is authoritative.
        observed, private = SimpleCookie(), SimpleCookie()
        try:
            observed.load(expected_cookie)
            private.load(headers['Cookie'])
        except CookieError:
            return {}
        broker.private_gateway_cookie_names = set(private)
        for name in private:
            observed.pop(name, None)
        cookie = '; '.join(item.OutputString() for item in (*observed.values(), *private.values()))
        return {**headers, 'Cookie': cookie} if len(cookie) <= 8192 else {}

    def update_cookies(self, broker, value, headers):
        client = self.clients[broker.policy['origin']]
        if client.host_session:
            client.host_session.update(value['url'], headers)

    def fetch(self, broker, value, direct):
        event = broker.events[-1]
        if event.get('lightweightCookieReuse') == 'attempted':
            self.lightweight_counts['attempts'] += 1
            accepted = 200 <= direct['status'] < 300 and not challenge(direct)
            event['lightweightCookieReuse'] = 'http_accepted' if accepted else 'rejected'
            self.lightweight_counts['httpAccepted' if accepted else 'rejected'] += 1
            if not accepted:
                self.clients[broker.policy['origin']].host_session = None
        if not challenge(direct):
            return direct
        origin = broker.policy['origin']
        event = broker.events[-1]
        event['challengePage'] = True
        # Browser navigations cannot reproduce account, signed API, POST or byte-range requests.
        headers = value['headers']
        mime = event.get('responseContentType', '')
        if not self.reproducible(broker, value) or mime not in {'text/html', 'application/xhtml+xml'}:
            event['gatewayOutcome'] = 'request_not_reproducible'
            return direct
        client = self.clients.get(origin)
        blocked = client.blocked_error() if client else None
        if blocked:
            event['gatewayOutcome'] = blocked.code
            event['gatewayRecovery'] = client.recovery()
            return direct
        self.egress.require(broker.count < broker.policy['maxRequests'], 'EGRESS_REQUEST_BUDGET')
        broker.wait_for_slot()
        broker.count += 1
        broker.next_request = time.monotonic() + broker.policy['minIntervalSeconds']
        started = time.monotonic()
        if client is None:
            client = EvidenceGateway(self.settings, self.egress, broker.deadline)
            client.max_requests = broker.policy['maxRequests']
            client.max_response_bytes = broker.policy['maxResponseBytes']
            client.max_total_bytes = broker.policy['maxTotalResponseBytes']
            client.max_wire_bytes = max(6291456, broker.policy['maxResponseBytes'] * 6 + 16384)
            client.capture_scripts = False
            client.retain_host_cookies = True
            self.clients[origin] = client
        try:
            # The original egress request was already validated by Broker._fetch.
            result = client.fetch({'method': 'GET', 'url': value['url'], 'body': '',
                                   'headers': {key: val for key, val in headers.items()
                                               if key.lower() in {'accept', 'accept-language', 'referer'}}})
            self.egress.require(broker.cancelled is None or not broker.cancelled(), 'EGRESS_CANCELLED')
            response = result['response']
            final = response.pop('finalUrl')
            try:
                self.egress.validate_request({**value, 'url': final}, broker.policy,
                                             source_headers=broker.source_headers,
                                             referer_origins=broker.referer_origins)
            except self.egress.Denied:
                raise GatewayError('EVIDENCE_GATEWAY_FINAL_URL_DENIED') from None
            if final != value['url']:
                # Preserve the caller's redirect semantics and relative-URL base.
                raise GatewayError('EVIDENCE_GATEWAY_FINAL_URL_CHANGED')
            if result['representation'] != 'gateway_rendered_document':
                raise GatewayError('EVIDENCE_GATEWAY_REPRESENTATION_INVALID')
            raw = base64.b64decode(response['body'], validate=True)
            remaining = broker.policy['maxTotalResponseBytes'] - broker.total
            limit = min(broker.policy['maxResponseBytes'], remaining)
            if len(raw) > limit:
                raise self.egress.byte_budget_error(broker.policy, broker.total, limit, len(raw),
                                                    'gateway_response', 'total_response_bytes'
                                                    if remaining <= broker.policy['maxResponseBytes']
                                                    else 'single_response_bytes')
            broker.total += len(raw)
            status = response['status']
            if status < 200 or 300 <= status < 400 and status not in {301, 302, 303, 307, 308}:
                raise GatewayError('EVIDENCE_GATEWAY_HTTP_FAILED', status)
            mime = response['headers'].get('content-type', 'text/html').split(';', 1)[0].strip().lower()
            broker.events.append({
                'method': 'GET', 'pathSha256': hashlib.sha256(urlsplit(value['url']).path.encode()).hexdigest(),
                'requestUrlSha256': hashlib.sha256(value['url'].encode()).hexdigest(),
                'requestBodyBytes': 0, 'responseStatus': status, 'responseBytes': len(raw),
                'responseSha256': hashlib.sha256(raw).hexdigest(), 'responseComplete': True,
                'responseContentType': mime, 'responseClass': 'http_200' if status == 200 else
                    'partial_content' if status == 206 else
                    'textual_server_error' if status >= 500 else 'textual_client_error' if status >= 400 else
                    'http_redirect' if status in {301, 302, 303, 307, 308} else 'http_2xx',
                'requestCookiePresent': False, 'receivedSetCookieCount': 0,
                'transport': 'external_evidence_gateway', 'representation': 'gateway_rendered_document',
                'gatewaySessionReused': result.get('gatewaySessionReused', False), 'cookiesExported': False,
                'hostCookieState': client.host_cookie_status,
                'gatewayExecution': result.get('gatewayExecution', {'tier': None, 'sessionCached': None}),
                'elapsedMilliseconds': round((time.monotonic() - started) * 1000)})
            broker.last_http_observation = {key: broker.events[-1][key] for key in (
                'method', 'requestUrlSha256', 'responseStatus', 'responseContentType', 'transport')}
            broker.last_http_observation['requestNumber'] = broker.count
            if status == 429:
                event['gatewayOutcome'] = 'target_rate_limited'
                broker.raise_rate_limit(value['url'], response['headers'].get('retry-after'))
            if not 200 <= status < 300 or challenge(response):
                raise GatewayError('EVIDENCE_GATEWAY_CHALLENGE_BLOCKED' if challenge(response)
                                   else 'EVIDENCE_GATEWAY_HTTP_FAILED', status)
            event['gatewayOutcome'] = 'response_returned'
            return {**response, 'setCookies': []}
        except GatewayError as error:
            event['gatewayOutcome'] = error.code
            if client.failure is not error:
                client.record_failure(error)
            event['gatewayRecovery'] = client.recovery()
            return direct

    def close(self):
        results = [client.close() for client in self.clients.values()]
        return all(results)

    def summary(self):
        summaries = [client.summary() for client in self.clients.values()]
        return {'adapter': self.settings['adapter'], 'sessionCount': len(summaries),
                'requestCount': sum(row['requestCount'] for row in summaries),
                'responseCount': sum(row['responseCount'] for row in summaries),
                'sessionReuseCount': sum(row['sessionReuseCount'] for row in summaries),
                'sessionRecoveryCount': sum(row['sessionRecoveryCount'] for row in summaries),
                'recoveryAttemptCount': sum(row['recoveryAttemptCount'] for row in summaries),
                'lightweightCounts': dict(self.lightweight_counts),
                'executionCounts': {key: sum(row['executionCounts'][key] for row in summaries)
                                    for key in ('tier1', 'tier2', 'tier3', 'tier4', 'unknown',
                                                'cachedSession', 'unknownCache')},
                'cookiesExported': False, 'cleanupConfirmed': all(row['cleanupConfirmed'] for row in summaries),
                'sessionScope': 'task_and_origin',
                'targetAddressPolicy': 'controller_public_preflight_gateway_managed_network'}
