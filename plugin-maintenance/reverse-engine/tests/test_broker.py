import importlib.util
from pathlib import Path
import sys
import json
import io
import hashlib
import base64
import html
import os
import re
import threading
import ssl
import time
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('reverse_broker_test', ROOT / 'reverse-engine/broker.py')
module = importlib.util.module_from_spec(spec)
with patch.object(sys, 'argv', ['broker.py', str(ROOT / 'scripts/source_egress.py')]):
    spec.loader.exec_module(module)


class FakeBroker:
    execute = None

    def __init__(self, policy, **kwargs):
        self.policy = policy
        self.source_headers = set()
        self.total = 0
        self.events = []
        self.network_observations = []

    def bind_execution(self, *args):
        pass

    def fetch(self, value):
        size = type(self).execute(value, self)
        self.total += size
        self.events.append({'responseBytes': size, 'responseComplete': True})
        return {'status': 200, 'body': '', 'headers': {}}


class BrokerConcurrencyTest(unittest.TestCase):
    def script_request(self, page='https://fixture.example.com/play/123'):
        return {'taskId': 'task', 'repairId': 'repair', 'runId': 'run', 'artifactSha256': 'a' * 64,
                'pageUrl': page, 'observedPageUrlSha256': hashlib.sha256(page.encode()).hexdigest(),
                'consumerFields': ['resData']}

    def script_transport(self, resources):
        transport = unittest.mock.Mock()

        def fetch(value):
            body, status, mime, complete = resources[value['url']]
            return {'response': {'status': status, 'body': base64.b64encode(body).decode(),
                                 'headers': {'content-type': mime}},
                    'observation': {'responseComplete': complete}}

        transport.fetch.side_effect = fetch
        return transport

    def test_script_gateway_uses_one_session_and_retains_real_captured_script_with_provenance(self):
        request = self.script_request()
        page, script = request['pageUrl'], request['pageUrl'] + '/play.js'
        request['evidenceGateway'] = {'enabled': True, 'adapter': 'trawl',
                                     'endpoint': 'http://gateway.invalid:8191', 'timeoutMillis': 30000}
        direct = self.script_transport({page: (b'<html>_cf_chl_opt=1;/cdn-cgi/challenge-platform/</html>',
                                              403, 'text/html', True)})
        direct.deadline = time.monotonic() + 60
        code, calls = 'const resData = JSON.parse(data)', []

        def command(gateway, path, payload=None, method='POST', cleanup=False):
            calls.append((path, payload, method))
            if path == '/sessions':
                return {'id': payload['id']}
            if method == 'DELETE':
                return {'status': 'ok'}
            return {'url': page, 'html': '<script src="' + script + '"></script>', 'statusCode': 200,
                    'tier': 2, 'sessionCached': True,
                    'capturedResponses': [{'url': script, 'body': code, 'status': 200,
                                            'headers': {'Content-Type': 'application/javascript'}}]}

        with patch.object(module.egress, 'public_dns_addresses', return_value=['93.184.216.34']), \
                patch('evidence_gateway.EvidenceGateway.command', command):
            report = module.script_inspection(request, direct)
        self.assertEqual(code, report['scripts'][0]['excerpt']['text'])
        self.assertEqual('gateway_captured_text', report['requests'][1]['representation'])
        self.assertEqual({'tier': 2, 'sessionCached': True}, report['requests'][0]['gatewayExecution'])
        self.assertEqual({'tier': None, 'sessionCached': None}, report['requests'][1]['gatewayExecution'])
        self.assertEqual(1, report['evidenceGateway']['executionCounts']['tier2'])
        self.assertTrue(report['requests'][0]['accessAttempts'][0]['challengePage'])
        self.assertEqual('controller_host_and_external_gateway', report['networkLocation'])
        self.assertEqual(1, report['evidenceGateway']['requestCount'])
        self.assertTrue(report['evidenceGateway']['cleanupConfirmed'])
        self.assertEqual('DELETE', calls[-1][2])
        self.assertFalse(report['publishEligible'])
        self.assertNotIn('gateway.invalid', json.dumps(report))

    def test_script_gateway_still_challenged_is_not_business_or_script_success(self):
        request = self.script_request()
        request['evidenceGateway'] = {'enabled': True, 'adapter': 'flaresolverr',
                                     'endpoint': 'http://gateway.invalid:8191', 'timeoutMillis': 30000}
        direct = self.script_transport({request['pageUrl']: (b'<html>_cf_chl_opt=1;/cdn-cgi/challenge-platform/</html>',
                                                           403, 'text/html', True)})
        direct.deadline = time.monotonic() + 60

        def command(gateway, path, payload=None, **kwargs):
            if payload['cmd'] == 'sessions.create':
                return {'status': 'ok', 'session': payload['session']}
            if payload['cmd'] == 'sessions.destroy':
                return {'status': 'ok'}
            return {'status': 'ok', 'solution': {'url': request['pageUrl'], 'status': 200,
                    'response': '<html>_cf_chl_opt=1;/cdn-cgi/challenge-platform/</html>'}}

        with patch.object(module.egress, 'public_dns_addresses', return_value=['93.184.216.34']), \
                patch('evidence_gateway.EvidenceGateway.command', command):
            report = module.script_inspection(request, direct)
        self.assertEqual('unavailable', report['collectionStatus'])
        self.assertEqual('challenge', report['requests'][0]['contentStatus'])
        self.assertEqual([], report['scripts'])
        self.assertTrue(report['cleanupConfirmed'])

    def test_script_inspection_follows_declared_consumer_and_module_not_arbitrary_urls(self):
        request = self.script_request()
        page = request['pageUrl']
        target = 'https://assets.example.com/play.js?v=2'
        request['scriptTargets'] = [hashlib.sha256(target.encode()).hexdigest()]
        code = b'import {convert} from "./convert.mjs"; const rows = JSON.parse(data.resData); convert(rows[0].url);'
        transport = self.script_transport({
            page: (b'<script src="/library.js"></script><script src="' + target.encode() + b'"></script>', 200, 'text/html', True),
            target: (code, 200, 'application/javascript', True),
            'https://fixture.example.com/library.js': (b'const arbitrary = "https://evil.example.com/not-imported.js";', 200, 'text/javascript', True),
            'https://assets.example.com/convert.mjs': (b'export const convert = value => atob(value);', 200, 'text/javascript', True)})
        result = module.script_inspection(request, transport)
        self.assertEqual('script_resource_inspection', result['operation'])
        self.assertEqual('host_public_scripts_observed', result['authority'])
        self.assertTrue(result['scripts'][0]['targetMatched'])
        self.assertEqual(code.decode(), result['scripts'][0]['excerpt']['text'])
        self.assertTrue(result['scripts'][0]['excerpt']['complete'])
        self.assertEqual(4, transport.fetch.call_count)
        self.assertEqual(page, transport.fetch.call_args_list[1].args[0]['headers']['Referer'])
        self.assertFalse(result['sourceCodeExecuted'])
        self.assertFalse(result['transformationVerified'])
        self.assertFalse(result['consumerExecutionVerified'])

    def test_script_challenge_html_and_incomplete_bodies_are_not_retained_as_code(self):
        request = self.script_request()
        page = request['pageUrl']
        transport = self.script_transport({
            page: (b'<script src="/play.js"></script><script src="/common.js"></script><script src="/short.js"></script>', 200, 'text/html', True),
            'https://fixture.example.com/play.js': (b'<html>_cf_chl_opt = {}; /cdn-cgi/challenge-platform/</html>', 403, 'text/html', True),
            'https://fixture.example.com/common.js': (b'<html>login</html>', 200, 'text/html', True),
            'https://fixture.example.com/short.js': (b'const resData =', 200, 'text/javascript', False)})
        result = module.script_inspection(request, transport)
        self.assertEqual([], result['scripts'])
        self.assertEqual(['observed', 'challenge', 'script_returned_html', 'incomplete'],
                         [row['contentStatus'] for row in result['requests']])
        self.assertNotIn('_cf_chl_opt', json.dumps(result))

    def test_script_inspection_requires_exact_observed_page_and_does_not_forward_sensitive_queries(self):
        request = self.script_request()
        request['observedPageUrlSha256'] = 'a' * 64
        transport = unittest.mock.Mock()
        with self.assertRaisesRegex(ValueError, 'REVERSE_PAGE_NOT_BOUND_TO_SOURCE'):
            module.script_inspection(request, transport)
        transport.fetch.assert_not_called()
        request = self.script_request('https://fixture.example.com/play?token=private-value')
        report = module.script_inspection(request, transport)
        transport.fetch.assert_not_called()
        self.assertNotIn('private-value', json.dumps(report))

    def test_script_inspection_keeps_fetch_and_text_budgets_and_targeted_offset(self):
        request = self.script_request()
        page = request['pageUrl']
        resources = {page: (''.join('<script src="/play%d.js"></script>' % i for i in range(20)).encode(), 200, 'text/html', True)}
        script = b' ' * 7000 + b'function play(data){return JSON.parse(data.resData)[0].url;}' + b' ' * 7000
        resources.update({'https://fixture.example.com/play%d.js' % i: (script, 200, 'text/javascript', True) for i in range(20)})
        transport = self.script_transport(resources)
        report = module.script_inspection(request, transport)
        self.assertEqual(8, transport.fetch.call_count)
        self.assertEqual(18000, report['textBytes'])
        self.assertTrue(report['truncated'])
        self.assertFalse(report['scripts'][0]['excerpt']['complete'])
        self.assertIn('resData', report['scripts'][0]['excerpt']['text'])
        self.assertGreater(report['scripts'][0]['excerpt']['offset'], 0)

    def test_prior_exact_script_can_be_observed_when_page_is_challenged_without_guessing(self):
        request = self.script_request()
        page, script = request['pageUrl'], 'https://fixture.example.com/play.js?v=2'
        request['observedScriptUrls'] = [script]
        request['scriptTargets'] = [hashlib.sha256(script.encode()).hexdigest()]
        transport = self.script_transport({
            page: (b'<html>_cf_chl_opt = {}; /cdn-cgi/challenge-platform/</html>', 403, 'text/html', True),
            script: (b'function consume(data){return JSON.parse(data.resData)[0].url;}', 200, 'text/javascript', True)})
        report = module.script_inspection(request, transport)
        self.assertEqual('challenge', report['requests'][0]['contentStatus'])
        self.assertEqual('prior_host_observed_script', report['scripts'][0]['discoveryBasis'])
        self.assertIn('JSON.parse', report['scripts'][0]['excerpt']['text'])
        request['observedScriptUrls'] = [script + '&guess=1']
        transport.fetch.reset_mock()
        with self.assertRaisesRegex(ValueError, 'REVERSE_PAGE_NOT_BOUND_TO_SOURCE'):
            module.script_inspection(request, transport)
        transport.fetch.assert_not_called()

    def test_lightweight_origin_entry_reuses_existing_pool_without_executing_source_or_starting_browser(self):
        source = "HOST='https://fixture.example.com'\nraise RuntimeError('MUST_NOT_EXECUTE')"
        value = {'taskId': 'task', 'repairId': 'repair', 'runId': 'run', 'artifactSha256': 'a' * 64,
                 'sourceContent': source, 'pageUrl': ''}
        transport = unittest.mock.Mock()
        report = module.origin_inspection(value, transport)
        self.assertEqual('source_origin_inspection', report['operation'])
        self.assertEqual('no_declared_alternate_origin', report['originPool']['nextAction'])
        self.assertFalse(report['browserStarted'])
        self.assertFalse(report['sourceCodeExecuted'])
        self.assertFalse(report['credentialsImported'])
        transport.fetch.assert_not_called()
        value['pageUrl'] = 'https://foreign.example.com/'
        with self.assertRaisesRegex(ValueError, 'REVERSE_PAGE_NOT_BOUND_TO_SOURCE'):
            module.origin_inspection(value, transport)

    def request(self, origin):
        return {'url': 'https://' + origin + '/', 'method': 'GET', 'headers': {}, 'body': ''}

    def test_replay_resolves_literal_paths_only_when_the_exact_address_was_observed(self):
        source = "host = 'https://example.org'\npath = '/videos/one/'\nraise AssertionError('must not execute')"
        page = 'https://canonical.example/videos/one/'
        target = hashlib.sha256(page.encode()).hexdigest()
        hints = ['https://canonical.example/static/player.js']
        self.assertEqual(page, module.resolve_replay_page(source, [target], hints))
        self.assertEqual('', module.resolve_replay_page(source, ['f' * 64], hints))
        self.assertEqual('', module.resolve_replay_page(source, [target], []))

    @unittest.skipUnless(os.environ.get('MAINTENANCE_REVERSE_TARGET_FIXTURE'), 'existing platform receipt not configured')
    def test_replay_resolves_the_existing_platform_challenge_without_source_execution_or_network(self):
        receipt = json.loads(Path(os.environ['MAINTENANCE_REVERSE_TARGET_FIXTURE']).read_bytes())
        observed = receipt['candidates'][-1]['result']['runtimeEvidence']
        targets = [row['urlSha256'] for row in observed['resourceEvidence']['resources'] if row['kind'] == 'page']
        hints = [match.group() for candidate in receipt['candidates']
                 for resource in candidate['result']['runtimeEvidence']['resourceEvidence']['resources']
                 for excerpt in resource['excerpts']
                 for match in re.finditer(r'https?://[^\s<>"\']+', html.unescape(excerpt['text']))]
        page = module.resolve_replay_page(receipt['sourceContent'], targets, hints)
        self.assertTrue(page)
        selected = hashlib.sha256(page.encode()).hexdigest()
        self.assertIn(selected, targets)
        self.assertTrue(any(row['requestUrlSha256'] == selected and row['method'] == 'GET'
                            and row['responseStatus'] == 403 and row['responseComplete']
                            for row in observed['httpObservations']))

    def test_slow_origin_does_not_block_an_unrelated_origin(self):
        started, release = threading.Event(), threading.Event()

        def execute(value, broker):
            if 'slow.example' in value['url']:
                started.set()
                if not release.wait(3):
                    raise TimeoutError()
            return 100

        with patch.object(module, 'BrowserBroker', FakeBroker), patch.object(
                module.egress, 'public_dns_addresses', return_value=['93.184.216.34']), patch.object(
                FakeBroker, 'execute', execute), ThreadPoolExecutor(max_workers=2) as workers:
            transport = module.BrowserTransport()
            slow = workers.submit(transport.fetch, self.request('slow.example'))
            try:
                self.assertTrue(started.wait(1))
                fast = workers.submit(transport.fetch, self.request('fast.example'))
                self.assertEqual(200, fast.result(timeout=1)['response']['status'])
                self.assertFalse(slow.done())
            finally:
                release.set()
            self.assertEqual(200, slow.result(timeout=1)['response']['status'])
            self.assertEqual(200, transport.total)
            self.assertEqual(0, transport.reserved)

    def test_parallel_reservations_preserve_the_total_byte_budget(self):
        def execute(value, broker):
            return broker.policy['maxResponseBytes']

        with patch.object(module, 'BrowserBroker', FakeBroker), patch.object(
                module.egress, 'public_dns_addresses', return_value=['93.184.216.34']), patch.object(
                FakeBroker, 'execute', execute), ThreadPoolExecutor(max_workers=3) as workers:
            transport = module.BrowserTransport()
            jobs = [workers.submit(transport.fetch, self.request(str(index) + '.example')) for index in range(3)]
            accepted, denied = 0, 0
            for job in jobs:
                try:
                    job.result(timeout=1)
                    accepted += 1
                except module.egress.Denied as error:
                    self.assertEqual('EGRESS_REQUEST_BUDGET', error.code)
                    denied += 1
            self.assertEqual((2, 1), (accepted, denied))
            self.assertEqual(4194304, transport.total)
            self.assertEqual(0, transport.reserved)

    def test_same_origin_queue_does_not_occupy_every_worker(self):
        release, fast_done = threading.Event(), threading.Event()

        class Transport:
            def fetch(self, value):
                if 'slow.example' in value['url']:
                    if not release.wait(3):
                        raise TimeoutError()
                return {'done': True}

        class Output:
            def write(self, text):
                if text.strip() and json.loads(text)['requestId'] == 5:
                    fast_done.set()

            def flush(self):
                pass

        def lines():
            for index in range(1, 6):
                yield json.dumps({'requestId': index, 'value': self.request(
                    'fast.example' if index == 5 else 'slow.example')})
            try:
                self.assertTrue(fast_done.wait(1))
            finally:
                release.set()

        module.serve(Transport(), lines(), Output())

    def test_failed_request_preserves_only_its_existing_network_diagnostics(self):
        diagnostic = {'requestUrlSha256': 'a' * 64, 'requestNumber': 1,
                      'addressIndex': 0, 'stage': 'response_headers', 'kind': 'timeout',
                      'errno': None, 'elapsedMilliseconds': 5000}

        def execute(value, broker):
            broker.network_observations.append(diagnostic.copy())
            broker.connection_evidence = lambda: {'authority': 'maintenance_pinned_http_connection',
                                                  'attempts': [{'tcpConnected': True, 'status': 'failed'}]}
            raise TimeoutError('private transport exception')

        with patch.object(module, 'BrowserBroker', FakeBroker), patch.object(
                module.egress, 'public_dns_addresses', return_value=['93.184.216.34']), patch.object(
                FakeBroker, 'execute', execute):
            transport = module.BrowserTransport()
            output = io.StringIO()
            lines = [json.dumps({'requestId': number, 'value': self.request('slow.example')})
                     for number in (1, 2)]
            module.serve(transport, lines, output)
            results = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual([1, 2], [row['requestId'] for row in results])
            for row in results:
                self.assertEqual('REVERSE_HTTP_TIMEOUT', row['result']['error'])
                self.assertEqual([diagnostic], row['result']['networkObservations'])
                self.assertTrue(row['result']['connectionEvidence']['attempts'][0]['tcpConnected'])
                timing = row['result']['transportTiming']
                self.assertGreaterEqual(timing['schedulerQueueMilliseconds'], 0)
                self.assertGreaterEqual(timing['executionMilliseconds'], 0)
            self.assertNotIn('private transport exception', output.getvalue())
            self.assertEqual(0, transport.reserved)


class BrowserCookieTest(unittest.TestCase):
    def policy(self):
        return {'schemaVersion': 2, 'origin': 'https://reader.example.com', 'paths': [], 'queryKeys': [],
                'exactUrls': [], 'requestScope': 'origin', 'pinnedIpv4': ['93.184.216.34'],
                'methods': ['GET'], 'maxRequests': 8, 'maxResponseBytes': 1024,
                'maxTotalResponseBytes': 4096, 'minIntervalSeconds': 1}

    def test_connection_attempts_distinguish_tcp_failure_tls_failure_and_verified_tls(self):
        class Sock:
            def __init__(self, address):
                self.address = address

            def getpeername(self):
                return self.address

            def version(self):
                return 'TLSv1.3'

            def close(self):
                pass

        class Connection:
            def connect(self):
                self.sock = self._create_connection(('reader.example.com', 443), timeout=5)
                if self.sock.address[0] == '1.1.1.1':
                    raise ssl.SSLZeroReturnError('PRIVATE peer')

            def getresponse(self):
                raise AssertionError('connection-only fixture')

            def close(self):
                pass

        def connector(address, **kwargs):
            if address[0] == '8.8.8.8':
                raise TimeoutError('PRIVATE timeout')
            return Sock(address)

        def connection(*args, **kwargs):
            self.assertTrue(kwargs['context'].check_hostname)
            self.assertEqual(ssl.CERT_REQUIRED, kwargs['context'].verify_mode)
            return Connection()

        policy = {**self.policy(), 'pinnedIpv4': ['8.8.8.8', '1.1.1.1', '4.2.2.2']}
        broker = module.BrowserBroker(policy, connector=connector, allow_empty=True)
        with patch.object(module.egress.http.client, 'HTTPSConnection', side_effect=connection):
            broker.connect('https://reader.example.com/PRIVATE', time.monotonic() + 5, time.monotonic())
        evidence = broker.connection_evidence()
        attempts = evidence['attempts']
        self.assertEqual([False, True, True], [row['tcpConnected'] for row in attempts])
        self.assertEqual(['tcp_connect', 'tls_handshake'], [row['failureStage'] for row in attempts[:2]])
        self.assertEqual('tls_closed', attempts[1]['transportError']['kind'])
        self.assertTrue(attempts[2]['tlsVerified'])
        self.assertEqual('TLSv1.3', attempts[2]['tlsVersion'])
        self.assertIs(broker.connector, connector)
        serialized = json.dumps(evidence)
        for secret in ['PRIVATE', *policy['pinnedIpv4'], 'reader.example.com']:
            self.assertNotIn(secret, serialized)

    def test_typed_tls_failures_keep_the_phase_without_exception_or_certificate_text(self):
        broker = module.BrowserBroker(self.policy(), allow_empty=True)
        certificate = ssl.SSLCertVerificationError('PRIVATE certificate subject')
        certificate.verify_code = 62
        failures = [TimeoutError('PRIVATE timeout'), ssl.SSLEOFError('PRIVATE EOF'), certificate,
                    ssl.SSLError('PRIVATE handshake')]
        for error in failures:
            broker.record_network_failure('https://reader.example.com/PRIVATE', 0, 'tls_handshake', error,
                                          time.monotonic())
        rows = broker.network_observations
        self.assertEqual(['timeout', 'tls_eof', 'certificate', 'tls'],
                         [row['transportError']['kind'] for row in rows])
        self.assertTrue(all(row['stage'] == 'tls_handshake' for row in rows))
        self.assertEqual(62, rows[2]['transportError']['certificateVerifyCode'])
        self.assertNotIn('PRIVATE', json.dumps(rows))
        for _ in range(40):
            broker.record_network_failure('https://reader.example.com/', 0, 'tls_handshake', failures[0],
                                          time.monotonic())
        self.assertEqual(32, len(rows))
        self.assertEqual(12, broker.network_observations_dropped)

    def test_tls_error_kind_survives_the_broker_response_without_raw_error_text(self):
        class Transport:
            def fetch(self, value):
                raise ssl.SSLEOFError('PRIVATE TLS peer')
        output = io.StringIO()
        module.serve(Transport(), [json.dumps({'requestId': 1, 'value': {'url': 'https://example.org/'}})], output)
        result = json.loads(output.getvalue())['result']
        self.assertEqual('REVERSE_TLS_FAILED', result['error'])
        self.assertEqual('tls_eof', result['transportError']['kind'])
        self.assertNotIn('PRIVATE', output.getvalue())

    def test_guest_browser_cookie_selection_preserves_duplicates_and_deletion(self):
        setters = ['parent=one; Domain=.example.com; Path=/; Secure; HttpOnly',
                   'parent=; Domain=.example.com; Path=/; Max-Age=0']

        class Response:
            status = 200

            def getheader(self, name, default=None):
                return {'content-type': 'text/html', 'content-length': '2'}.get(name.lower(), default)

            def getheaders(self):
                return [('Content-Type', 'text/html'), ('cf-mitigated', 'challenge'),
                        *[('Set-Cookie', value) for value in setters]]

            def read(self, limit):
                return b'ok'

        class Connection:
            sock = None

            def connect(self):
                pass

            def request(self, method, target, body, headers):
                self.sent = headers

            def getresponse(self):
                return Response()

            def close(self):
                pass

        value = {'method': 'GET', 'url': 'https://reader.example.com/page',
                 'headers': {'Cookie': 'same=one; same=two; js=guest-fixture'}, 'body': ''}
        with self.assertRaises(module.egress.Denied) as denied:
            module.egress.Broker(self.policy(), allow_empty=True).fetch(value)
        self.assertEqual('cookie_not_observed', denied.exception.request_diagnostic['reason'])
        connection = Connection()
        with patch.object(module.egress.http.client, 'HTTPSConnection', return_value=connection):
            broker = module.BrowserBroker(self.policy(), allow_empty=True)
            result = broker.fetch(value)
            self.assertEqual(value['headers']['Cookie'], connection.sent['Cookie'])
            self.assertEqual(setters, result['setCookies'])
            self.assertEqual('challenge', result['headers']['cf-mitigated'])
            self.assertTrue(broker.events[-1]['requestCookiePresent'])
            broker.next_request = 0
            broker.fetch({**value, 'headers': {}})
            self.assertNotIn('Cookie', connection.sent)
            self.assertFalse(broker.events[-1]['requestCookiePresent'])
            self.assertIsNone(broker.host_credentials)

    def test_browser_cookies_retain_header_validation_and_redacted_diagnostics(self):
        broker = module.BrowserBroker(self.policy(), allow_empty=True)
        request = {'method': 'GET', 'url': 'https://reader.example.com/',
                   'headers': {'Cookie': 'fixture=PRIVATE\r\nX-Injected: PRIVATE'}, 'body': ''}
        output = io.StringIO()
        module.serve(broker, [json.dumps({'requestId': 1, 'value': request})], output)
        result = json.loads(output.getvalue())['result']
        self.assertEqual('EGRESS_HEADERS_DENIED', result['error'])
        self.assertEqual({'kind': 'header', 'reason': 'value_control', 'headerName': 'cookie'},
                         result['requestDiagnostic'])
        self.assertNotIn('PRIVATE', output.getvalue())
        self.assertEqual(0, broker.count)


if __name__ == '__main__':
    unittest.main()
