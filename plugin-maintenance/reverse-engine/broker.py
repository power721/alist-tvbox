"""Task-local browser transport using the maintenance product's pinned HTTP broker."""
import importlib.util
import ast
import base64
import hashlib
import http.cookiejar
import json
import re
import ssl
import ipaddress
from pathlib import Path
from html.parser import HTMLParser
import xml.etree.ElementTree as ET
import sys
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from urllib.parse import urlsplit, urljoin, parse_qsl, urlencode


spec = importlib.util.spec_from_file_location("source_egress", Path(sys.argv[1]))
egress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(egress)
sys.modules["source_egress"] = egress
sys.path.insert(0, str(Path(sys.argv[1]).parent))
from source_origin_discovery import source_origins, static_strings, http_origin


def resolve_replay_page(content, targets, hints):
    """Resolve recorded addresses from static literals only; never probe guessed endpoints."""
    trees = egress.source_trees(content)
    parents = {child: node for tree in trees for node in ast.walk(tree) for child in ast.iter_child_nodes(node)}
    urls = {text for tree in trees for _, text in static_strings(tree, parents)
            if isinstance(text, str) and http_origin(text)}
    urls.update(value for value in hints if isinstance(value, str) and http_origin(value))
    origins = {http_origin(value) for value in urls}
    paths = {node.value for tree in trees for node in ast.walk(tree)
             if isinstance(node, ast.Constant) and isinstance(node.value, str)
             and node.value.startswith('/') and not node.value.startswith('//') and len(node.value) <= 2048}
    for origin in origins:
        urls.update(urljoin(origin + '/', path) for path in paths)
    by_sha = {hashlib.sha256(url.encode()).hexdigest(): url for url in urls}
    return next((by_sha[target] for target in targets if target in by_sha), '')


def source_origin_profile(content):
    """Read declared page-host pools, not arbitrary URLs, without executing the Spider."""
    candidates, markers = [], []
    excluded = {'image', 'images', 'pic', 'media', 'cdn', 'subtitle', 'gateway', 'proxy', 'blocked', 'deny', 'api'}
    for tree in egress.source_trees(content):
        for node in ast.walk(tree):
            if not isinstance(node, (ast.Assign, ast.AnnAssign)) or node.value is None:
                continue
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            names = [target.id if isinstance(target, ast.Name) else target.attr
                     for target in targets if isinstance(target, (ast.Name, ast.Attribute))]
            for name in names:
                tokens = set(re.findall(r'[a-z]+', re.sub(r'([a-z])([A-Z])', r'\1_\2', name).lower()))
                marker_binding = name.upper() in {'CONTENT_MARKERS', 'BUSINESS_MARKERS'}
                host_binding = bool(tokens & {'host', 'hosts', 'domain', 'domains', 'mirror', 'mirrors'})
                host_binding |= bool(tokens & {'base'}) and bool(tokens & {'url', 'urls'})
                if not marker_binding and (not host_binding or tokens & excluded):
                    continue
                try:
                    values = ast.literal_eval(node.value)
                except (ValueError, TypeError, SyntaxError, RecursionError):
                    continue
                values = [values] if isinstance(values, str) else values
                if not isinstance(values, (tuple, list)):
                    continue
                for value in values[:32]:
                    if marker_binding:
                        if isinstance(value, str) and 4 <= len(value) <= 160 and len(markers) < 16:
                            if value.lower() not in markers:
                                markers.append(value.lower())
                    elif isinstance(value, str):
                        origin = http_origin(value)
                        parsed = urlsplit(value)
                        if (origin and parsed.path in {'', '/'} and not parsed.query and not parsed.fragment
                                and origin not in [row['origin'] for row in candidates] and len(candidates) < 4):
                            candidates.append({'origin': origin, 'binding': name,
                                               'basis': 'source_declared_page_host'})
    return {'candidates': candidates, 'businessMarkers': markers}


def search_origin_candidates(transport, content, page_url):
    host = urlsplit(page_url).hostname or ''
    brand = host.removeprefix('www.').split('.')[0]
    query = '"' + brand + '" official website alternate domain'
    report = {'provider': 'bing_public_rss', 'query': query, 'originalOrigin': http_origin(page_url),
              'searchPerformed': False, 'modelHasSearchTools': False, 'results': [], 'status': 'unavailable',
              'httpResponses': []}
    try:
        url = 'https://www.bing.com/search?' + urlencode({'format': 'rss', 'q': query})
        seen = set()
        for hop in range(4):
            seen.add(url)
            reply = transport.fetch({'method': 'GET', 'url': url,
                                     'headers': {'Accept': 'application/rss+xml, application/xml'}, 'body': ''})
            body = base64.b64decode(reply['response']['body'], validate=True)
            report.update(searchPerformed=True, httpStatus=reply['response']['status'],
                          responseComplete=reply['observation'].get('responseComplete') is True,
                          responseSha256=hashlib.sha256(body).hexdigest(), responseBytes=len(body))
            row = {key: report[key] for key in ('httpStatus', 'responseComplete', 'responseSha256', 'responseBytes')}
            row.update(requestUrlSha256=hashlib.sha256(url.encode()).hexdigest(), origin=http_origin(url))
            report['httpResponses'].append(row)
            if not report['responseComplete']:
                return report
            if reply['response']['status'] not in {301, 302, 303, 307, 308}:
                break
            headers = {key.lower(): value for key, value in reply['response'].get('headers', {}).items()}
            target = urljoin(url, headers.get('location', ''))
            parsed = urlsplit(target)
            parameters = dict(parse_qsl(parsed.query))
            row['redirectUrlSha256'] = hashlib.sha256(target.encode()).hexdigest()
            if (parsed.scheme != 'https' or parsed.username is not None or parsed.password is not None
                    or parsed.port not in {None, 443} or parsed.fragment or parsed.path != '/search'
                    or not (parsed.hostname == 'bing.com' or (parsed.hostname or '').endswith('.bing.com'))
                    or parameters.get('q') != query or parameters.get('format') != 'rss'):
                report['errorCode'] = 'ORIGIN_SEARCH_REDIRECT_UNSUPPORTED'
                return report
            if target in seen or hop == 3:
                report['errorCode'] = 'ORIGIN_SEARCH_REDIRECT_LIMIT'
                return report
            url = target
        if reply['response']['status'] != 200:
            return report
        root = ET.fromstring(body)
        if root.tag != 'rss':
            report['errorCode'] = 'ORIGIN_SEARCH_RESPONSE_INVALID'
            return report
        for item in root.findall('./channel/item')[:10]:
            link = (item.findtext('link') or '').strip()
            if not http_origin(link) or len(link) > 2048:
                continue
            report['results'].append({'url': link, 'origin': http_origin(link),
                                      'title': (item.findtext('title') or '')[:300],
                                      'description': (item.findtext('description') or '')[:600]})
        report['status'] = 'results_observed' if report['results'] else 'no_results'
    except Exception as error:
        report['errorCode'] = error.code if isinstance(error, egress.Denied) else 'ORIGIN_SEARCH_UNAVAILABLE'
    return report


class IdentityDocument(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tokens, self.links, self.brands = set(), [], []
        self.in_title = False

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        self.tokens.update((values.get('class') or '').split())
        if values.get('id'):
            self.tokens.add(values['id'])
        if tag == 'a' and values.get('href'):
            self.links.append(values['href'])
        if tag == 'title':
            self.in_title = True
        if tag == 'meta' and (values.get('property') or values.get('name')) in {'og:site_name', 'application-name'}:
            self.brands.append(values.get('content') or '')

    def handle_endtag(self, tag):
        if tag == 'title':
            self.in_title = False

    def handle_data(self, text):
        if self.in_title:
            self.brands.append(text)


def match_site_identity(content, original_origin, body, markers):
    document = IdentityDocument()
    document.feed(body)
    host = urlsplit(original_origin).hostname or ''
    brand = host.removeprefix('www.').split('.')[0].lower()
    brand_match = len(brand) >= 3 and any(brand in re.sub(r'[^a-z0-9]', '', value.lower())
                                         for value in document.brands)
    selectors = set()
    for tree in egress.source_trees(content):
        for node in ast.walk(tree):
            if not isinstance(node, ast.Constant) or not isinstance(node.value, str) or len(node.value) > 2048:
                continue
            value = node.value
            if not any(char in value for char in ['/', '[', '.', '#']):
                continue
            selectors.update(re.findall(r'[@\[]class\s*=\s*[\'"]([^\'"]+)', value))
            selectors.update(re.findall(r'[@\[]id\s*=\s*[\'"]([^\'"]+)', value))
            selectors.update(re.findall(r'(?:^|[\s>/])(?:\w+)?[.#]([A-Za-z_][\w-]{2,80})', value))
    selectors = {token for value in selectors for token in value.split()}
    selectors.difference_update({'container', 'row', 'main', 'content', 'header', 'footer', 'wrapper', 'active'})
    matched = sorted(selectors.intersection(document.tokens))[:16]
    backlink = any(http_origin(urljoin(original_origin, link)) == original_origin
                   for link in document.links if link.startswith(('http://', 'https://')))
    business_links = sum(1 for link in document.links if link.startswith('/') and not link.startswith('//')
                         and urlsplit(link).path not in {'', '/'} and not re.search(r'login|signup|privacy|terms', link, re.I))
    marker_match = any(marker in body.lower() for marker in markers)
    verified = brand_match and (bool(matched) or marker_match) and business_links > 0
    return {'status': 'consistent_site_identity' if verified else 'identity_not_established',
            'brandMatched': brand_match, 'sourceBacklinkObserved': backlink,
            'matchedSourceSelectorTokens': matched, 'businessLinkCount': business_links,
            'ownershipVerified': False}


def inspect_origin_pool(transport, content, page_url, published_redirects=None, model_origins=None):
    profile = source_origin_profile(content)
    page, _, _ = egress.http_authority(page_url, 'EGRESS_URL_DENIED')
    origin = http_origin(page_url)
    candidates = profile['candidates']
    result = {'authority': 'maintenance_pinned_http_origin_observed',
              'requestedPageUrlSha256': hashlib.sha256(page_url.encode()).hexdigest(),
              'candidateBasis': 'source_declared_page_hosts', 'candidates': candidates, 'observations': [],
              'credentialsForwarded': False, 'redirectsFollowed': False, 'sourceCodeExecuted': False,
              'businessChainVerified': False, 'selectionApplied': False,
              'inspectionStage': 'published_redirects' if published_redirects else 'source_declared_hosts',
              'nextAction': 'no_declared_alternate_origin'}
    if not model_origins and (len(candidates) < 2 or origin not in [row['origin'] for row in candidates]):
        return result
    if any(re.search(r'token|auth|secret|session|signature|credential|password|api.?key|^sig$', key, re.I)
           for key, _ in parse_qsl(page.query, keep_blank_values=True)):
        result['nextAction'] = 'sensitive_query_not_forwarded'
        return result
    candidates = sorted(candidates, key=lambda row: row['origin'] != origin)
    path = page.path or '/'
    if page.query:
        path += '?' + page.query
    if model_origins:
        candidates = []
        for proposed in model_origins[:4]:
            target = proposed.get('origin', '')
            if (http_origin(target) != target or not target.startswith('https://') or target == origin
                    or target in [item['origin'] for item in candidates]):
                continue
            candidates.append({'origin': target, 'basis': 'model_search_result_origin',
                               'evidenceUrlSha256': hashlib.sha256(proposed.get('evidenceUrl', '').encode()).hexdigest()})
        result.update(candidates=candidates, candidateBasis='model_search_results', inspectionStage='model_search_results')
        if not candidates:
            result['nextAction'] = 'no_model_alternate_origin'
            return result
    elif published_redirects:
        declared = {row['origin'] for row in candidates}
        candidates = []
        for redirect in published_redirects[:4]:
            target = redirect.get('redirectOrigin', '')
            if (redirect.get('status') != 'redirect' or redirect.get('httpStatus') not in {301, 308}
                    or redirect.get('origin') not in declared
                    or redirect.get('requestUrlSha256') != hashlib.sha256((redirect['origin'] + path).encode()).hexdigest()
                    or not target.startswith('https://') or http_origin(target) != target
                    or target in declared or target in [item['origin'] for item in candidates]
                    or redirect.get('redirectUrlSha256') != hashlib.sha256((target + path).encode()).hexdigest()):
                continue
            candidates.append({'origin': target, 'basis': 'source_published_same_path_redirect',
                               'publishedByOrigin': redirect['origin']})
        result.update(candidates=candidates, candidateBasis='source_published_same_path_redirects')
        if not candidates:
            result['nextAction'] = 'no_same_path_published_alternate_origin'
            return result
    for candidate in candidates:
        url = candidate['origin'] + path
        row = {'origin': candidate['origin'], 'requestUrlSha256': hashlib.sha256(url.encode()).hexdigest(),
               'sameActionPath': True, 'method': 'GET', 'status': 'unavailable'}
        result['observations'].append(row)
        try:
            reply = transport.fetch({'method': 'GET', 'url': url, 'headers': {'Accept': 'text/html'}, 'body': ''})
            response, observation = reply['response'], reply['observation']
            body = base64.b64decode(response['body'], validate=True)
            text = body.decode('utf-8', errors='replace').lower()
            headers = {name.lower(): value for name, value in response['headers'].items()}
            complete = observation.get('responseComplete') is True
            row.update(httpStatus=response['status'], responseComplete=complete,
                       responseSha256=hashlib.sha256(body).hexdigest(), responseBytes=len(body),
                       responseContentType=observation.get('responseContentType', ''),
                       matchedMarkerIndexes=[index for index, marker in enumerate(profile['businessMarkers'])
                                             if marker in text], rawBodyStored=False)
            if 'connectionEvidence' in reply:
                row['connectionEvidence'] = reply['connectionEvidence']
            status = response['status']
            if status in {301, 302, 303, 307, 308} and headers.get('location'):
                redirected = urljoin(url, headers.get('location', ''))
                redirect_origin = http_origin(redirected)
                row.update(status='redirect', redirectOrigin=redirect_origin,
                           redirectUrlSha256=hashlib.sha256(redirected.encode()).hexdigest(),
                           redirectToRequestedPage=redirected == page_url,
                           redirectSameActionPath=urlsplit(redirected).path == page.path
                               and urlsplit(redirected).query == page.query,
                           redirectWithinDeclaredPool=redirect_origin in [item['origin'] for item in profile['candidates']])
            elif status in {301, 302, 303, 307, 308}:
                row['status'] = 'redirect_target_missing'
            elif (headers.get('cf-mitigated', '').lower() == 'challenge'
                  or '_cf_chl_opt' in text and '/cdn-cgi/challenge-platform/' in text
                  and ('just a moment' in text or 'enable javascript and cookies' in text)):
                row['status'] = 'challenge'
            elif status == 429:
                row['status'] = 'rate_limited'
            elif status in {401, 403}:
                row['status'] = 'access_denied'
            elif status >= 400:
                row['status'] = 'http_error'
            elif not complete:
                row['status'] = 'incomplete'
            elif re.search(r'<title[^>]*>[^<]*(?:under maintenance|domain (?:expired|for sale)|'
                           r'\u7f51\u7ad9\u7ef4\u62a4|\u57df\u540d\u51fa\u552e)', text):
                row['status'] = 'maintenance'
            elif model_origins and status == 200 and 'html' in row['responseContentType']:
                identity = match_site_identity(content, origin, body.decode('utf-8', errors='replace'), profile['businessMarkers'])
                row['siteIdentity'] = identity
                if identity['status'] == 'consistent_site_identity':
                    row['status'] = 'business_content_candidate'
                    result.update(preferredCandidateOrigin=candidate['origin'],
                                  nextAction='validate_candidate_in_original_workflow')
                    break
                row['status'] = 'site_identity_unconfirmed'
            elif (status == 200 and 'html' in row['responseContentType'] and row['matchedMarkerIndexes']):
                row['status'] = 'business_content_candidate'
                result.update(preferredCandidateOrigin=candidate['origin'],
                              nextAction='validate_candidate_in_original_workflow')
                break
            else:
                row['status'] = 'business_markers_missing'
        except Exception as error:
            row['errorCode'] = error.code if isinstance(error, egress.Denied) else (
                'REVERSE_HTTP_TIMEOUT' if isinstance(error, TimeoutError) else
                'REVERSE_TLS_FAILED' if isinstance(error, ssl.SSLError) else 'REVERSE_CONNECTION_FAILED')
            if hasattr(error, 'connection_evidence'):
                row['connectionEvidence'] = error.connection_evidence
    if not result.get('preferredCandidateOrigin'):
        result['nextAction'] = 'no_usable_declared_alternate_origin'
    return result


class BrowserTransport:
    def __init__(self):
        self.routes = {}
        self.count = self.total = 0
        self.deadline = time.monotonic() + 60
        self.reserved = 0
        self.condition = threading.Condition()
        self.origin_locks = {}

    def fetch(self, value):
        parsed, host, port = egress.http_authority(value["url"], "EGRESS_URL_DENIED")
        origin = parsed.scheme + "://" + parsed.netloc
        with self.condition:
            egress.require(origin in self.origin_locks or len(self.origin_locks) < 32, "EGRESS_REQUEST_BUDGET")
            lock = self.origin_locks.setdefault(origin, threading.Lock())
        # Preserve each origin's Cookie/token ordering while unrelated origins make progress.
        with lock:
            if origin not in self.routes:
                dns_started = time.monotonic()
                addresses = egress.public_dns_addresses(host, port=port)
                broker = BrowserBroker({
                    "schemaVersion": 2, "origin": origin, "paths": [], "queryKeys": [],
                    "exactUrls": [], "requestScope": "origin", "pinnedIpv4": addresses,
                    "methods": ["GET", "HEAD", "POST"], "maxRequests": 64,
                    "maxResponseBytes": 2097152, "maxTotalResponseBytes": 4194304,
                    "minIntervalSeconds": 1, "sessionToken": {"responseScope": "origin"}
                }, allow_empty=True)
                broker.bind_execution(self.deadline, lambda: False)
                broker.dns_evidence = {'status': 'resolved', 'addressCount': len(addresses),
                                       'elapsedMilliseconds': round((time.monotonic() - dns_started) * 1000)}
                with self.condition:
                    self.routes[origin] = broker
            broker = self.routes[origin]
            with self.condition:
                while self.total + self.reserved >= 4194304 and self.reserved:
                    remaining = self.deadline - time.monotonic()
                    egress.require(remaining > 0, "EGRESS_HTTP_DEADLINE")
                    self.condition.wait(remaining)
                egress.require(self.count < 64 and self.total < 4194304, "EGRESS_REQUEST_BUDGET")
                grant = min(2097152, 4194304 - self.total - self.reserved)
                self.reserved += grant
                self.count += 1
                origins = tuple(self.routes)
            broker.referer_origins = origins
            broker.policy["headerOrigins"] = list(origins)[:4]
            broker.source_headers.update(key.lower() for key in value["headers"]
                                         if key.lower() not in {"authorization", "cookie", "host"})
            broker.policy["maxResponseBytes"] = grant
            broker.policy["maxTotalResponseBytes"] = broker.total + grant
            before = broker.total
            network_before = len(broker.network_observations)
            try:
                result = broker.fetch(value)
            except Exception as error:
                error.network_observations = broker.network_observations[network_before:]
                if hasattr(broker, 'connection_evidence'):
                    error.connection_evidence = broker.connection_evidence()
                raise
            finally:
                with self.condition:
                    self.total += broker.total - before
                    self.reserved -= grant
                    self.condition.notify_all()
            observation = broker.events[-1].copy()
            observation.pop("connectedPublicIpv4", None)
            result = {"response": result, "observation": observation,
                      "networkObservations": broker.network_observations[network_before:]}
            if hasattr(broker, 'connection_evidence'):
                result['connectionEvidence'] = broker.connection_evidence()
            return result


class BrowserCookies(http.cookiejar.CookieJar):
    """The task browser, not a second cookie store, owns request cookie selection."""
    request_header = ''

    def add_cookie_header(self, request):
        if self.request_header:
            request.add_unredirected_header('Cookie', self.request_header)


def transport_error_evidence(error):
    """Expose typed causes, never exception text, hostnames or certificate subjects."""
    kind = ('certificate' if isinstance(error, ssl.SSLCertVerificationError) else
            'tls_eof' if isinstance(error, ssl.SSLEOFError) else
            'tls_closed' if isinstance(error, ssl.SSLZeroReturnError) else
            'timeout' if isinstance(error, TimeoutError) else
            'tls' if isinstance(error, ssl.SSLError) else
            'connection_refused' if isinstance(error, ConnectionRefusedError) else
            'connection_reset' if isinstance(error, ConnectionResetError) else
            'connection' if isinstance(error, OSError) else 'unclassified')
    result = {'kind': kind}
    reasons = {'CERTIFICATE_VERIFY_FAILED', 'UNEXPECTED_EOF_WHILE_READING', 'WRONG_VERSION_NUMBER',
               'SSLV3_ALERT_HANDSHAKE_FAILURE', 'TLSV1_ALERT_INTERNAL_ERROR', 'NO_SHARED_CIPHER',
               'UNSUPPORTED_PROTOCOL', 'TLSV1_ALERT_PROTOCOL_VERSION', 'SSLV3_ALERT_BAD_CERTIFICATE',
               'TLSV1_ALERT_UNKNOWN_CA', 'DH_KEY_TOO_SMALL', 'EE_KEY_TOO_SMALL', 'UNEXPECTED_MESSAGE'}
    if isinstance(error, ssl.SSLError):
        reason = getattr(error, 'reason', None)
        result['tlsReason'] = reason if reason in reasons else 'TLS_REASON_UNCLASSIFIED'
        if isinstance(error, ssl.SSLCertVerificationError):
            code = getattr(error, 'verify_code', None)
            if type(code) is int and 0 <= code <= 65535:
                result['certificateVerifyCode'] = code
    return result


class BrowserBroker(egress.Broker):
    """Retain browser response policy headers without changing the Spider transport contract."""
    HEADERS = {"access-control-allow-origin", "access-control-allow-credentials",
               "access-control-expose-headers", "content-security-policy",
               "cross-origin-resource-policy", "cross-origin-opener-policy",
               "cross-origin-embedder-policy", "x-content-type-options", "referrer-policy", "cf-mitigated"}

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cookies = BrowserCookies()
        self.connection_attempts = []

    def connection_evidence(self):
        return {'authority': 'maintenance_pinned_http_connection', 'tlsRuntime': ssl.OPENSSL_VERSION,
                'certificateVerificationEnabled': self.origin.scheme == 'https',
                'hostnameVerificationEnabled': self.origin.scheme == 'https',
                'dns': getattr(self, 'dns_evidence', {}), 'attempts': self.connection_attempts.copy()}

    def record_network_failure(self, url, index, phase, error, started):
        before = len(self.network_observations)
        super().record_network_failure(url, index, phase, error, started)
        if len(self.network_observations) > before:
            self.network_observations[-1]['transportError'] = transport_error_evidence(error)
        if self.connection_attempts:
            self.connection_attempts[-1].update(status='failed', failureStage=phase,
                                               transportError=transport_error_evidence(error))

    def connect(self, *args):
        connector = self.connector

        def observed_connector(address, **kwargs):
            started = time.monotonic()
            row = {'addressSha256': hashlib.sha256(address[0].encode()).hexdigest(),
                   'addressFamily': 'ipv6' if ipaddress.ip_address(address[0]).version == 6 else 'ipv4',
                   'status': 'connecting', 'tcpConnected': False}
            self.connection_attempts.append(row)
            try:
                sock = connector(address, **kwargs)
                row.update(tcpConnected=True, tcpMilliseconds=round((time.monotonic() - started) * 1000))
                return sock
            except Exception:
                row['tcpMilliseconds'] = round((time.monotonic() - started) * 1000)
                raise

        self.connector = observed_connector
        try:
            connection, index = super().connect(*args)
        finally:
            self.connector = connector
        if self.connection_attempts:
            row = self.connection_attempts[-1]
            row['status'] = 'connected'
            row['tlsVerified'] = self.origin.scheme == 'https'
            if row['tlsVerified']:
                version = connection.sock.version()
                row['tlsVersion'] = version if version in {'TLSv1.2', 'TLSv1.3'} else 'unclassified'
        getresponse = connection.getresponse

        def response():
            value = getresponse()
            self.browser_headers = {key: val for key, val in value.getheaders() if key.lower() in self.HEADERS}
            self.browser_set_cookies = [val for key, val in value.getheaders()
                                       if key.lower() == 'set-cookie' and len(val) <= 4096
                                       and '\r' not in val and '\n' not in val][:32]
            return value

        connection.getresponse = response
        return connection, index

    def fetch(self, value):
        self.connection_attempts = []
        self.browser_headers = {}
        self.browser_set_cookies = []
        cookies = [val for key, val in value['headers'].items() if key.lower() == 'cookie']
        egress.require_header(len(cookies) <= 1, 'duplicate_name', 'cookie')
        header = cookies[0] if cookies else ''
        egress.require_header(isinstance(header, str), 'value_type', 'cookie')
        egress.require_header(len(header) <= 8192, 'value_size', 'cookie')
        egress.require_header(header.isascii(), 'value_encoding', 'cookie')
        egress.require_header(not re.search(r'[\x00-\x1f\x7f]', header), 'value_control', 'cookie')
        self.cookies.request_header = header
        result = super().fetch(value)
        result["headers"].update(self.browser_headers)
        # Chrome applies Domain/Path/HttpOnly/deletion rules, including parent-domain cookies.
        result['setCookies'] = self.browser_set_cookies
        if self.events:
            self.events[-1]['browserForwardedSetCookieCount'] = len(self.browser_set_cookies)
        return result


def serve(transport, lines, output):
    output_lock = threading.Lock()
    scheduling = threading.Condition()
    queues = {}

    def respond(envelope, queued_at, queued_ahead):
        started = time.monotonic()
        try:
            value = envelope["value"]
            if "discoverOrigins" in value:
                content = value["discoverOrigins"]
                result = {"origins": source_origins(content)}
                result['originProfile'] = source_origin_profile(content)
                if value.get("replayTargets"):
                    result["replayPageUrl"] = resolve_replay_page(content, value["replayTargets"], value.get("replayHints", []))
            elif 'inspectOriginPool' in value:
                probe = value['inspectOriginPool']
                result = {'originPool': inspect_origin_pool(transport, probe['sourceContent'], probe['pageUrl'],
                                                           probe.get('publishedRedirects'), probe.get('modelOrigins'))}
            elif 'searchOriginCandidates' in value:
                probe = value['searchOriginCandidates']
                result = {'originSearch': search_origin_candidates(transport, probe['sourceContent'], probe['pageUrl'])}
            else:
                result = transport.fetch(value)
        except Exception as error:
            code = (error.code if isinstance(error, egress.Denied) else
                    "REVERSE_HTTP_TIMEOUT" if isinstance(error, TimeoutError) else
                    "REVERSE_TLS_FAILED" if isinstance(error, ssl.SSLError) else
                    "REVERSE_CONNECTION_FAILED" if isinstance(error, OSError) else
                    "REVERSE_TRANSPORT_FAILED")
            result = {"error": code, "networkObservations": getattr(error, "network_observations", [])}
            if hasattr(error, 'connection_evidence'):
                result['connectionEvidence'] = error.connection_evidence
            if not isinstance(error, egress.Denied):
                result['transportError'] = transport_error_evidence(error)
            if isinstance(error, egress.Denied) and hasattr(error, 'request_diagnostic'):
                result['requestDiagnostic'] = error.request_diagnostic
        if 'discoverOrigins' not in envelope['value']:
            result['transportTiming'] = {
                'schedulerQueueMilliseconds': round((started - queued_at) * 1000),
                'queuedAhead': queued_ahead,
                'executionMilliseconds': round((time.monotonic() - started) * 1000)}
        with output_lock:
            print(json.dumps({"requestId": envelope["requestId"], "result": result}, ensure_ascii=True), file=output, flush=True)

    with ThreadPoolExecutor(max_workers=4) as workers:
        def completed(origin, future):
            with scheduling:
                queues[origin].popleft()
                if queues[origin]:
                    workers.submit(respond, *queues[origin][0]).add_done_callback(
                        lambda task: completed(origin, task))
                else:
                    del queues[origin]
                scheduling.notify_all()

        for line in lines:
            envelope = json.loads(line)
            origin = urlsplit(envelope["value"].get("url", "")).netloc
            with scheduling:
                queue = queues.setdefault(origin, deque())
                queue.append((envelope, time.monotonic(), len(queue)))
                if len(queue) == 1:
                    workers.submit(respond, *queue[0]).add_done_callback(
                        lambda task, key=origin: completed(key, task))
        # Drain before executor shutdown so per-origin continuations remain schedulable.
        with scheduling:
            while queues:
                scheduling.wait()


def main():
    if sys.argv[2:] == ['--inspect-origin']:
        print(json.dumps(origin_inspection(json.load(sys.stdin)), ensure_ascii=True))
    else:
        serve(BrowserTransport(), sys.stdin, sys.stdout)


def origin_inspection(value, transport=None):
    """Run the existing credential-free origin contract without a Node/browser dependency."""
    content = value['sourceContent']
    origins = source_origins(content)
    recovered = (resolve_replay_page(content, value.get('replayTargets', []), value.get('replayHints', []))
                 if not value.get('pageUrl') else '')
    page = value.get('pageUrl') or recovered or origins[0] + '/'
    page_sha = hashlib.sha256(page.encode()).hexdigest()
    if http_origin(page) in origins:
        binding = 'source_literal_origin'
    elif page_sha == (hashlib.sha256(recovered.encode()).hexdigest() if recovered else value.get('observedPageUrlSha256')):
        egress.http_authority(page, 'EGRESS_URL_DENIED')
        binding = 'same_run_observed_page'
    else:
        raise ValueError('REVERSE_PAGE_NOT_BOUND_TO_SOURCE')
    transport = transport or BrowserTransport()
    report = {'schemaVersion': 1, 'authority': 'host_source_origin_observed', 'mode': 'reverse_analysis_v1',
              'operation': 'source_origin_inspection', 'taskId': value['taskId'], 'repairId': value['repairId'],
              'runId': value['runId'], 'artifactSha256': value['artifactSha256'], 'pageUrlSha256': page_sha,
              'pageSourceBinding': binding, 'pageTargetSelection': 'explicit_observed_target' if value.get('pageUrl')
                  else 'host_observed_static_resolution' if recovered else 'source_origin_fallback',
              'transport': 'maintenance_pinned_public_http', 'networkLocation': 'controller_host',
              'browserStarted': False, 'sourceCodeExecuted': False, 'credentialsImported': False,
              'cookieAuthority': 'none', 'businessChainVerified': False, 'publishEligible': False,
              'cleanupConfirmed': True, 'requests': [], 'failures': []}
    if value.get('searchForOrigins'):
        report['originSearch'] = search_origin_candidates(transport, content, page)
        report['originPool'] = {'selectionApplied': False, 'businessChainVerified': False}
    else:
        report['originPool'] = inspect_origin_pool(transport, content, page,
            value.get('publishedRedirects'), value.get('modelOrigins'))
    return report


if __name__ == "__main__":
    main()
