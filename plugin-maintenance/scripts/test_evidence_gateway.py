import base64
import io
import json
import time
import unittest
from urllib.error import HTTPError, URLError
from unittest.mock import Mock, patch
import source_egress as E
from evidence_gateway import EvidenceGateway, GatewayFallback, GatewayError, HostCookieSession, RuntimeGateway, challenge, gateway_failure


PAGE = 'https://fixture.example.com/play'


def settings(adapter='trawl'):
    return {'enabled': True, 'adapter': adapter, 'endpoint': 'http://gateway.invalid:8191', 'timeoutMillis': 30000}


def request(url=PAGE):
    return {'method': 'GET', 'url': url, 'body': '', 'headers': {'Accept': 'text/html'}}


def response(body=b'<html>_cf_chl_opt=1;/cdn-cgi/challenge-platform/</html>', status=403):
    return {'response': {'status': status, 'headers': {}, 'body': base64.b64encode(body).decode()},
            'observation': {'responseComplete': True}}


class GatewayTest(unittest.TestCase):
    def gateway(self, adapter='trawl'):
        egress = Mock()
        egress.http_authority.return_value = (None, 'fixture.example.com', 443)
        egress.public_dns_addresses.return_value = ['93.184.216.34']
        return EvidenceGateway(settings(adapter), egress)

    def command(self, gateway, captured=None):
        calls = []

        def command(path, payload=None, method='POST', cleanup=False):
            calls.append((path, payload, method))
            if path == '/sessions':
                return {'id': payload['id']}
            if method == 'DELETE' or payload.get('cmd') == 'sessions.destroy':
                return {'status': 'ok'}
            if payload.get('cmd') == 'sessions.create':
                return ({'status': 'ok', 'session': payload['session']} if gateway.adapter == 'flaresolverr'
                        else {'status': 'ok', 'message': 'Session created: ' + payload['session']})
            if path == '/scrape':
                return {'url': payload['url'], 'html': '<html>business</html>', 'statusCode': 200,
                        'capturedResponses': captured or [], 'cookies': [{'value': 'private-cookie'}]}
            return {'status': 'ok', 'solution': {'url': payload['url'], 'response': '<html>business</html>',
                    'status': 200, 'headers': {'Set-Cookie': 'private-cookie'}, 'cookies': [{'value': 'private-cookie'}]}}

        gateway.command = command
        return calls

    def test_adapters_share_create_reuse_cleanup_contract_without_cookie_export(self):
        for adapter in ('trawl', 'flaresolverr', 'flaresolverr-rs-alt'):
            with self.subTest(adapter=adapter):
                gateway = self.gateway(adapter)
                calls = self.command(gateway)
                results = [gateway.fetch(request()), gateway.fetch(request(PAGE + '/next'))]
                self.assertTrue(gateway.close())
                self.assertTrue(gateway.close())
                self.assertEqual(4, len(calls))
                self.assertEqual(2, gateway.summary()['requestCount'])
                self.assertEqual(1, gateway.summary()['sessionReuseCount'])
                self.assertEqual('gateway_rendered_document', results[0]['representation'])
                self.assertNotIn('private-cookie', json.dumps(results + [gateway.summary()]))
                session_field = 'sessionId' if adapter == 'trawl' else 'session'
                self.assertEqual(calls[1][1][session_field], calls[2][1][session_field])
                self.assertEqual(calls[0][1]['id' if adapter == 'trawl' else 'session'], calls[1][1][session_field])
                if adapter == 'trawl':
                    self.assertNotIn('publicOnly', calls[0][1])
                    self.assertFalse(calls[1][1]['solveEmbeddedCaptchas'])
                else:
                    self.assertNotIn('headers', calls[1][1])

    def test_trawl_captured_script_is_not_a_rendered_document_or_extra_navigation(self):
        gateway = self.gateway()
        url = 'https://fixture.example.com/play.js?v=1'
        calls = self.command(gateway, [
            {'url': url, 'status': 200, 'body': 'const resData = atob(value)', 'headers': {'Content-Type': 'text/javascript'}},
            {'url': url + 'bad', 'status': 200, 'body': '@@@', 'base64Encoded': True},
            {'url': url + 'cut', 'status': 200, 'body': 'partial', 'truncated': True}])
        gateway.fetch(request())
        script = gateway.fetch(request(url))
        self.assertEqual('gateway_captured_text', script['representation'])
        self.assertEqual(b'const resData = atob(value)', base64.b64decode(script['response']['body']))
        self.assertEqual(2, len(calls))
        self.assertEqual(1, gateway.summary()['requestCount'])
        self.assertTrue(gateway.close())

    def test_fallback_retains_direct_challenge_and_reuses_one_gateway(self):
        direct = Mock(deadline=time.monotonic() + 60)
        direct.fetch.return_value = response()
        fallback = GatewayFallback(settings(), direct, self.gateway().egress)
        self.command(fallback.gateway)
        first, second = fallback.fetch(request()), fallback.fetch(request(PAGE + '/next'))
        self.assertEqual(1, direct.fetch.call_count)
        self.assertTrue(first['accessAttempts'][0]['challengePage'])
        self.assertFalse(challenge(first['response']))
        self.assertEqual([], second['accessAttempts'])
        self.assertTrue(fallback.gateway.close())

    def test_gateway_unavailable_preserves_challenge_and_does_not_repeat_gateway(self):
        direct = Mock(deadline=time.monotonic() + 60)
        direct.fetch.return_value = response()
        fallback = GatewayFallback(settings(), direct, self.gateway().egress)
        fallback.gateway.fetch = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_BUSY', 429))
        for _ in range(2):
            result = fallback.fetch(request())
            self.assertTrue(challenge(result['response']))
            self.assertEqual('EVIDENCE_GATEWAY_BUSY', result['gatewayErrorCode'])
        self.assertEqual(1, fallback.gateway.fetch.call_count)

    def test_normal_direct_reply_does_not_create_a_gateway_session(self):
        direct = Mock(deadline=time.monotonic() + 60)
        direct.fetch.return_value = response(b'normal business', 200)
        fallback = GatewayFallback(settings(), direct, self.gateway().egress)
        fallback.gateway.command = Mock(side_effect=AssertionError('gateway must stay idle'))
        self.assertIs(direct.fetch.return_value, fallback.fetch(request()))
        self.assertTrue(fallback.gateway.close())
        self.assertFalse(fallback.gateway.summary()['sessionCreated'])

    def test_credentials_private_redirect_and_deadline_are_not_accepted(self):
        gateway = self.gateway()
        self.command(gateway)
        value = request()
        value['headers']['Cookie'] = 'private'
        with self.assertRaisesRegex(GatewayError, 'CREDENTIAL_FORWARDING_DENIED'):
            gateway.fetch(value)
        gateway.egress.public_dns_addresses.side_effect = GatewayError('PRIVATE_TARGET')
        with self.assertRaisesRegex(GatewayError, 'PRIVATE_TARGET'):
            gateway.normalize(PAGE, b'x', 'http://127.0.0.1/', 200, {}, 'gateway_rendered_document')
        gateway.deadline = time.monotonic() - 1
        with self.assertRaisesRegex(GatewayError, 'DEADLINE'):
            EvidenceGateway.command(gateway, '/scrape', {})

    def test_cleanup_failure_is_not_claimed_as_success(self):
        gateway = self.gateway()
        self.command(gateway)
        gateway.fetch(request())
        gateway.command = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_UNAVAILABLE'))
        self.assertFalse(gateway.close())
        self.assertFalse(gateway.close())
        self.assertFalse(gateway.summary()['cleanupConfirmed'])

    def test_bounded_read_and_finite_error_classification_never_return_raw_messages(self):
        with self.assertRaisesRegex(GatewayError, 'RESPONSE_TOO_LARGE'):
            EvidenceGateway.read(io.BytesIO(b'x' * 20), 10, time.monotonic() + 1)
        for status, message, expected in [(500, 'goto: NS_ERROR_UNKNOWN_HOST secret', 'TARGET_DNS_FAILED'),
                                          (429, 'private', 'BUSY'), (500, 'challenge persisted', 'CHALLENGE_BLOCKED')]:
            error = gateway_failure(status, json.dumps({'error': message}).encode())
            self.assertEqual('EVIDENCE_GATEWAY_' + expected, error.code)
            self.assertNotIn('secret', str(error))

    def test_http200_command_errors_keep_finite_session_dns_and_timeout_causes(self):
        for message, code in [('invalid session id PRIVATE', 'SESSION_LOST'),
                              ("The session 'PRIVATE' doesn't exist.", 'SESSION_LOST'),
                              ('browser has been closed PRIVATE', 'SESSION_LOST'),
                              ('browser pool exhausted PRIVATE', 'BUSY'),
                              ('NS_ERROR_UNKNOWN_HOST PRIVATE', 'TARGET_DNS_FAILED'),
                              ('Timeout after 30 seconds PRIVATE', 'TIMEOUT'),
                              ('unknown failure PRIVATE', 'RESPONSE_INVALID')]:
            gateway = self.gateway('flaresolverr')
            original = self.command(gateway)
            command = gateway.command

            def failed(path, payload=None, **kwargs):
                if payload and payload.get('cmd') == 'request.get':
                    return {'status': 'error', 'message': message}
                return command(path, payload, **kwargs)

            gateway.command = failed
            with self.assertRaises(GatewayError) as caught:
                gateway.fetch(request())
            self.assertEqual('EVIDENCE_GATEWAY_' + code, caught.exception.code)
            self.assertNotIn('PRIVATE', json.dumps(gateway.recovery()))
            self.assertEqual(1, len(original))

    def test_transport_failures_distinguish_connect_read_and_protocol_without_messages(self):
        for error, code, phase in [(URLError(ConnectionRefusedError('PRIVATE')), 'CONNECTION_FAILED', 'connect'),
                                   (URLError(TimeoutError('PRIVATE')), 'CONNECT_TIMEOUT', 'connect'),
                                   (TimeoutError('PRIVATE'), 'READ_TIMEOUT', 'connect')]:
            gateway = self.gateway()
            gateway.opener.open = Mock(side_effect=error)
            with self.assertRaises(GatewayError) as caught:
                gateway.command('/scrape', {})
            self.assertEqual('EVIDENCE_GATEWAY_' + code, caught.exception.code)
            self.assertEqual(phase, caught.exception.phase)
            self.assertNotIn('PRIVATE', str(caught.exception))
        gateway.opener.open = Mock(return_value=io.BytesIO(b'not-json PRIVATE'))
        with self.assertRaisesRegex(GatewayError, 'RESPONSE_INVALID'):
            gateway.command('/scrape', {})
        reply = io.BytesIO()
        gateway.opener.open = Mock(return_value=reply)
        with patch.object(reply, 'read1', side_effect=TimeoutError('PRIVATE')):
            with self.assertRaises(GatewayError) as caught:
                gateway.command('/scrape', {})
        self.assertEqual('EVIDENCE_GATEWAY_READ_TIMEOUT', caught.exception.code)
        self.assertEqual('read', caught.exception.phase)

    def test_gateway_retry_after_cools_then_allows_later_request_not_immediate_retry(self):
        gateway = self.gateway()
        failure = HTTPError('http://gateway.invalid', 429, 'PRIVATE', {'Retry-After': '120'}, io.BytesIO(b'{}'))
        gateway.opener.open = Mock(side_effect=failure)
        with self.assertRaises(GatewayError) as caught:
            gateway.fetch(request())
        self.assertEqual({'kind': 'delay', 'seconds': 120}, caught.exception.retry_after)
        self.assertFalse(gateway.recovery()['fitsTaskBudget'])
        self.assertEqual('reset_after_cooldown', gateway.recovery()['action'])
        with self.assertRaises(GatewayError):
            gateway.fetch(request())
        self.assertEqual(1, gateway.opener.open.call_count)
        self.assertEqual(0, gateway.recovery_attempts)

    def test_timeout_or_lost_session_requires_confirmed_cleanup_before_new_navigation(self):
        for code in ('EVIDENCE_GATEWAY_READ_TIMEOUT', 'EVIDENCE_GATEWAY_SESSION_LOST'):
            gateway = self.gateway()
            calls = self.command(gateway)
            command = gateway.command

            def fail_once(path, payload=None, **kwargs):
                if path == '/scrape' and not gateway.failure:
                    gateway.command = command
                    raise GatewayError(code)
                return command(path, payload, **kwargs)

            gateway.command = fail_once
            with self.assertRaises(GatewayError):
                gateway.fetch(request())
            old_session = gateway.session
            with self.assertRaises(GatewayError):
                gateway.fetch(request())
            self.assertEqual(1, len(calls))
            gateway.retry_at = time.monotonic() - 1
            self.assertFalse(challenge(gateway.fetch(request())['response']))
            self.assertEqual('/sessions/' + old_session, calls[1][0])
            self.assertEqual(['/sessions', '/scrape'], [row[0] for row in calls[2:]])
            self.assertNotEqual(old_session, gateway.session)
            self.assertEqual(1, gateway.summary()['sessionRecoveryCount'])
            self.assertEqual(0, gateway.summary()['sessionReuseCount'])
            self.assertTrue(gateway.fetch(request())['gatewaySessionReused'])
            gateway.record_failure(GatewayError(code))
            gateway.retry_at = time.monotonic() - 1
            self.assertFalse(gateway.fetch(request())['gatewaySessionReused'])
            self.assertTrue(gateway.close())

    def test_failed_cleanup_never_rebuilds_or_overlaps_an_uncertain_navigation(self):
        gateway = self.gateway()
        calls = self.command(gateway)
        gateway.fetch(request())
        gateway.record_failure(GatewayError('EVIDENCE_GATEWAY_READ_TIMEOUT'))
        gateway.retry_at = time.monotonic() - 1
        gateway.command = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_UNAVAILABLE'))
        for _ in range(2):
            with self.assertRaisesRegex(GatewayError, 'CLEANUP_UNCONFIRMED'):
                gateway.fetch(request())
        self.assertEqual(1, gateway.command.call_count)
        self.assertEqual(2, len(calls))
        self.assertEqual('stopped', gateway.recovery()['action'])

    def test_missing_session_does_not_confirm_cleanup_of_an_inflight_create(self):
        gateway = self.gateway()
        gateway.command = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_READ_TIMEOUT'))
        with self.assertRaises(GatewayError):
            gateway.fetch(request())
        gateway.retry_at = time.monotonic() - 1
        gateway.command = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_HTTP_FAILED', 404))
        with self.assertRaisesRegex(GatewayError, 'CLEANUP_UNCONFIRMED'):
            gateway.fetch(request())
        self.assertFalse(gateway.cleanup)
        self.assertEqual(1, gateway.command.call_count)
        gateway = self.gateway('flaresolverr')
        self.command(gateway)
        gateway.fetch(request())
        gateway.command = Mock(return_value={'status': 'error', 'message': "The session doesn't exist."})
        self.assertTrue(gateway.close())

    def test_recovery_is_bounded_by_attempts_and_existing_deadline(self):
        gateway = self.gateway()
        self.command(gateway)
        gateway.fetch(request())
        gateway.command = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_BUSY', 429))
        for attempt in range(3):
            if attempt:
                gateway.retry_at = time.monotonic() - 1
            with self.assertRaises(GatewayError):
                gateway.fetch(request())
        self.assertIsNone(gateway.retry_at)
        self.assertEqual(2, gateway.recovery_attempts)
        with self.assertRaises(GatewayError):
            gateway.fetch(request())
        self.assertEqual(3, gateway.command.call_count)
        gateway = self.gateway()
        gateway.record_failure(GatewayError('EVIDENCE_GATEWAY_BUSY'))
        gateway.retry_at = time.monotonic() - 1
        gateway.deadline = time.monotonic() + 5
        gateway.command = Mock(side_effect=AssertionError('budget exhausted'))
        with self.assertRaisesRegex(GatewayError, 'DEADLINE'):
            gateway.fetch(request())
        self.assertFalse(gateway.recovery()['fitsTaskBudget'])

    def test_execution_evidence_uses_only_actual_typed_fields_not_session_existence(self):
        for metadata, expected in [({'tier': 1, 'sessionCached': True}, {'tier': 1, 'sessionCached': True}),
                                    ({'tier': 2, 'sessionCached': False}, {'tier': 2, 'sessionCached': False}),
                                    ({'tier': True, 'sessionCached': 'PRIVATE'}, {'tier': None, 'sessionCached': None}),
                                    ({}, {'tier': None, 'sessionCached': None})]:
            gateway = self.gateway()
            self.command(gateway)
            command = gateway.command

            def execution(path, payload=None, **kwargs):
                result = command(path, payload, **kwargs)
                return {**result, **metadata, 'extra': 'PRIVATE'} if path == '/scrape' else result

            gateway.command = execution
            result = gateway.fetch(request())
            self.assertEqual(expected, result['gatewayExecution'])
            self.assertEqual(1, gateway.summary()['executionCounts']['tier' + str(expected['tier'])
                                                                     if expected['tier'] else 'unknown'])
            self.assertNotIn('PRIVATE', json.dumps([result, gateway.summary()]))
            self.assertTrue(gateway.close())

    def test_auxiliary_fallback_can_recover_after_busy_cooldown(self):
        direct = Mock(deadline=time.monotonic() + 60)
        direct.fetch.return_value = response()
        fallback = GatewayFallback(settings(), direct, self.gateway().egress)
        self.command(fallback.gateway)
        fetch = fallback.gateway.fetch
        fallback.gateway.fetch = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_BUSY', 429))
        first = fallback.fetch(request())
        self.assertEqual('cooldown', first['gatewayRecovery']['action'])
        fallback.gateway.retry_at = time.monotonic() - 1
        fallback.gateway.fetch = fetch
        self.assertFalse(challenge(fallback.fetch(request())['response']))
        self.assertEqual(1, fallback.gateway.recovery_attempts)
        self.assertTrue(fallback.gateway.close())


class RuntimeGatewayTest(unittest.TestCase):
    def cookie_factory(self, clients):
        factory = self.factory(clients)

        def create(*args):
            client = factory(*args)
            command = client.command

            def with_cookies(path, payload=None, **kwargs):
                result = command(path, payload, **kwargs)
                if path == '/scrape':
                    result.update(cookies=[{'name': 'fixture_session', 'value': 'PRIVATE',
                        'domain': 'fixture.example.com', 'path': '/', 'secure': True}], userAgent='Fixture/1.0')
                return result

            client.command = with_cookies
            return client
        return create

    def test_lightweight_http_uses_private_cookie_ua_and_keeps_rotations_out_of_spider(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        first, second, third = self.direct(), self.direct(b'<html>business</html>', 200,
            headers=[('Set-Cookie', 'fixture_session=ROTATED; Secure; Path=/')]), self.direct(b'ok', 200)
        with patch.object(broker, 'connect', side_effect=[(first, 0), (second, 0), (third, 0)]), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.cookie_factory(clients)):
            broker.fetch(request())
            reply = broker.fetch(request())
            broker.fetch(request())
        self.assertEqual('fixture_session=PRIVATE', second.request.call_args.kwargs['headers']['Cookie'])
        self.assertEqual('Fixture/1.0', second.request.call_args.kwargs['headers']['User-Agent'])
        self.assertEqual('fixture_session=ROTATED', third.request.call_args.kwargs['headers']['Cookie'])
        self.assertEqual([], reply['setCookies'])
        self.assertEqual(0, len(broker.cookies))
        self.assertEqual({}, broker.observed_cookie_updates)
        self.assertEqual('http_accepted', broker.events[-1]['lightweightCookieReuse'])
        self.assertEqual({'attempts': 2, 'httpAccepted': 2, 'rejected': 0},
                         broker.runtime_gateway.summary()['lightweightCounts'])
        self.assertEqual(1, clients[0].requests)
        self.assertEqual(4, broker.count)
        self.assertNotIn('PRIVATE', json.dumps([reply, broker.events, broker.runtime_gateway.summary()]))
        self.assertTrue(broker.runtime_gateway.close())
        self.assertIsNone(clients[0].host_session)

    def test_rejected_lightweight_cookie_returns_to_existing_gateway_session(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.cookie_factory(clients)):
            broker.fetch(request())
            result = broker.fetch(request())
        self.assertFalse(challenge(result))
        self.assertEqual('rejected', broker.events[-2]['lightweightCookieReuse'])
        self.assertTrue(broker.events[-1]['gatewaySessionReused'])
        self.assertEqual({'attempts': 1, 'httpAccepted': 0, 'rejected': 1},
                         broker.runtime_gateway.summary()['lightweightCounts'])
        self.assertTrue(broker.runtime_gateway.close())

    def test_private_session_scope_expiry_path_secure_and_bad_data(self):
        cookies = [{'name': 'session', 'value': 'PRIVATE', 'domain': '.example.com',
                    'path': '/play', 'secure': True}]
        state = HostCookieSession(PAGE, cookies, 'Fixture/1.0')
        self.assertTrue(state.headers(PAGE))
        for url in ('https://other.example.com/play', 'http://fixture.example.com/play',
                    PAGE + 'back', 'https://fixture.example.com:444/play'):
            self.assertEqual({}, state.headers(url))
        for rows, ua in [(cookies, None), (cookies, 'bad\r\nUA'), ([{**cookies[0], 'domain': '.invalid.com'}], 'UA'),
                         ([{**cookies[0], 'value': 'bad;injection'}], 'UA'),
                         ([{**cookies[0], 'expires': time.time() - 1}], 'UA')]:
            self.assertEqual({}, HostCookieSession(PAGE, rows, ua).headers(PAGE))
        state.deadline = 0
        self.assertEqual({}, state.headers(PAGE))

    def test_all_adapters_capture_private_state_only_when_enabled_and_valid(self):
        for adapter in ('trawl', 'flaresolverr', 'flaresolverr-rs-alt'):
            with self.subTest(adapter=adapter):
                client = GatewayTest().gateway(adapter)
                GatewayTest().command(client)
                command = client.command

                def with_cookie(path, payload=None, **kwargs):
                    result = command(path, payload, **kwargs)
                    if path == '/scrape' or payload and payload.get('cmd') == 'request.get':
                        state = result if adapter == 'trawl' else result['solution']
                        state.update(cookies=[{'name': 'fixture_session', 'value': 'PRIVATE'}], userAgent='Fixture/1.0')
                    return result

                client.command = with_cookie
                client.fetch(request())
                self.assertIsNone(client.host_session)
                client.retain_host_cookies = True
                result = client.fetch(request())
                self.assertEqual('available', client.host_cookie_status)
                self.assertEqual('fixture_session=PRIVATE', client.host_session.headers(PAGE)['Cookie'])
                self.assertNotIn('PRIVATE', json.dumps(result))
                self.assertTrue(client.close())
                self.assertIsNone(client.host_session)

    def test_private_cookie_does_not_join_account_or_signed_requests(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.cookie_factory(clients)):
            broker.fetch(request())
        value = request()
        value['headers']['Authorization'] = 'Bearer account'
        self.assertEqual({}, broker.runtime_gateway.request_headers(broker, value, ''))
        merged = broker.runtime_gateway.request_headers(broker, request(), 'observed=public')
        self.assertIn('observed=public', merged['Cookie'])
        self.assertIn('fixture_session=PRIVATE', merged['Cookie'])
        self.assertIn('fixture_session=PRIVATE', broker.runtime_gateway.request_headers(
            broker, request(), 'fixture_session=observed')['Cookie'])
        broker.host_credentials = {'cookie': 'account=private'}
        self.assertEqual({}, broker.runtime_gateway.request_headers(broker, request(), ''))
        self.assertTrue(broker.runtime_gateway.close())

    def test_observed_origin_cookie_does_not_prevent_private_http_reuse(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        first = self.direct(headers=[('Set-Cookie', 'visitor=guest; Secure; Path=/')])
        second = self.direct(b'<html>business</html>', 200)
        second.getresponse.return_value.getheaders.return_value = [
            ('Content-Type', 'text/html'), ('Set-Cookie', 'visitor=updated; Secure; Path=/'),
            ('Set-Cookie', 'fixture_session=ROTATED_PRIVATE; Secure; Path=/')]
        with patch.object(broker, 'connect', side_effect=[(first, 0), (second, 0)]), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.cookie_factory(clients)):
            broker.fetch(request())
            reply = broker.fetch(request())
        sent = second.request.call_args.kwargs['headers']['Cookie']
        self.assertIn('visitor=guest', sent)
        self.assertIn('fixture_session=PRIVATE', sent)
        self.assertEqual('http_accepted', broker.events[-1]['lightweightCookieReuse'])
        self.assertEqual(1, len(broker.cookies))
        self.assertEqual(['visitor=updated; Secure; Path=/'], reply['setCookies'])
        self.assertEqual('updated', next(iter(broker.cookies)).value)
        self.assertNotIn('PRIVATE', str(broker.observed_cookie_updates))
        self.assertTrue(broker.runtime_gateway.close())

    def test_guest_cookie_collision_rotates_privately_and_explicit_account_is_excluded(self):
        broker = self.broker()
        gateway = RuntimeGateway(settings(), E)
        client = GatewayTest().gateway()
        client.host_session = HostCookieSession(PAGE, [
            {'name': 'guest_session', 'value': 'PRIVATE_NEW'}], 'Fixture/1.0')
        gateway.clients[broker.policy['origin']] = client
        headers = gateway.request_headers(broker, request(), 'guest_session=observed_old; visitor=public')
        self.assertIn('guest_session=PRIVATE_NEW', headers['Cookie'])
        self.assertIn('visitor=public', headers['Cookie'])
        self.assertNotIn('observed_old', headers['Cookie'])
        self.assertEqual({'guest_session'}, broker.private_gateway_cookie_names)
        broker.host_credentials = {'cookie': 'account=private'}
        self.assertEqual({}, gateway.request_headers(broker, request(), 'account=source_account'))

    def test_rotated_imported_cookie_never_reenters_existing_public_jar_under_same_name(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        first = self.direct(headers=[('Set-Cookie', 'fixture_session=observed; Secure; Path=/')])
        second = self.direct(b'ok', 200, headers=[('Set-Cookie', 'fixture_session=PRIVATE_ROTATED; Secure; Path=/')])
        with patch.object(broker, 'connect', side_effect=[(first, 0), (second, 0)]), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.cookie_factory(clients)):
            broker.fetch(request())
            result = broker.fetch(request())
        self.assertEqual('fixture_session=PRIVATE', second.request.call_args.kwargs['headers']['Cookie'])
        self.assertEqual([], result['setCookies'])
        self.assertEqual('observed', next(iter(broker.cookies)).value)
        self.assertNotIn('PRIVATE', str(broker.observed_cookie_updates))
        self.assertTrue(broker.runtime_gateway.close())

    def broker(self, origin='https://fixture.example.com', **limits):
        policy = {'schemaVersion': 2, 'origin': origin, 'paths': [],
                  'exactUrls': [origin + '/play'], 'requestScope': 'origin',
                  'methods': ['GET', 'HEAD', 'POST'], 'maxRequests': 8, 'maxResponseBytes': 2048,
                  'maxTotalResponseBytes': 8192, 'minIntervalSeconds': 1,
                  'queryKeys': [], 'pinnedIpv4': ['93.184.216.34'], **limits}
        broker = E.Broker(policy)
        broker.bind_execution(time.monotonic() + 90, lambda: False)
        broker.wait_for_slot = Mock()
        return broker

    def direct(self, body=None, status=403, content_type='text/html', headers=()):
        raw = body if body is not None else base64.b64decode(response()['response']['body'])
        fields = {'Content-Type': content_type, **dict(headers)}
        reply = Mock(status=status)
        reply.getheader.side_effect = lambda key, default=None: fields.get(key, default)
        reply.getheaders.return_value = list(fields.items())
        reply.read.return_value = raw
        connection = Mock(sock=None)
        connection.getresponse.return_value = reply
        return connection

    def factory(self, clients):
        def create(settings, egress, deadline):
            client = EvidenceGateway(settings, egress, deadline)
            client.public_url = Mock()
            client.test_calls = GatewayTest().command(client)
            clients.append(client)
            return client
        return create

    def test_actual_broker_reuses_origin_session_without_exporting_clearance(self):
        broker, clients = self.broker(), []
        gateway = RuntimeGateway(settings(), E)
        broker.runtime_gateway = gateway
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.factory(clients)):
            first = broker.fetch(request())
            second = broker.fetch(request(PAGE + '/next'))
        self.assertEqual(b'<html>business</html>', base64.b64decode(first['body']))
        self.assertEqual([], second['setCookies'])
        self.assertEqual(4, broker.count)
        self.assertEqual(4, len(broker.events))
        self.assertEqual([403, 200, 403, 200], [row['responseStatus'] for row in broker.events])
        self.assertTrue(broker.events[0]['challengePage'])
        self.assertTrue(broker.events[-1]['gatewaySessionReused'])
        self.assertEqual({'tier': None, 'sessionCached': None}, broker.events[-1]['gatewayExecution'])
        self.assertNotIn('connectedPublicIpv4', broker.events[-1])
        self.assertNotIn('connectedAddressIndex', broker.events[-1])
        self.assertTrue(gateway.close())
        self.assertEqual(1, gateway.summary()['sessionReuseCount'])
        self.assertNotIn('private-cookie', json.dumps([first, second, broker.events, gateway.summary()]))
        self.assertEqual(4, len(clients[0].test_calls))

    def test_origins_do_not_share_browser_state(self):
        brokers, clients = [self.broker(), self.broker('https://other.example.com')], []
        gateway = RuntimeGateway(settings(), E)
        with patch('evidence_gateway.EvidenceGateway', side_effect=self.factory(clients)):
            for broker in brokers:
                broker.runtime_gateway = gateway
                with patch.object(broker, 'connect', return_value=(self.direct(), 0)):
                    broker.fetch(request(broker.policy['origin'] + '/page'))
        self.assertEqual(2, len(clients))
        self.assertNotEqual(clients[0].session, clients[1].session)
        self.assertTrue(gateway.close())

    def test_multi_origin_runtime_routes_use_gateway_and_keep_shared_accounting(self):
        policy = {'schemaVersion': 3, 'methods': ['GET', 'HEAD'], 'maxRequests': 8,
                  'maxResponseBytes': 2048, 'maxTotalResponseBytes': 8192, 'minIntervalSeconds': 1,
                  'routes': [{'origin': 'https://fixture.example.com', 'exactUrls': [PAGE, PAGE + '/next'],
                              'pinnedIpv4': ['93.184.216.34']}]}
        mediator, clients = E.Routes(policy), []
        mediator.bind_execution(time.monotonic() + 90, lambda: False)
        mediator.runtime_gateway = RuntimeGateway(settings(), E)
        for broker in mediator.routes.values():
            broker.wait_for_slot = Mock()
        with patch.object(E.Broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.factory(clients)):
            first = mediator.fetch(request())
            second = mediator.fetch(request(PAGE + '/next'))
        self.assertEqual(4, mediator.count)
        self.assertEqual(4, len(mediator.events))
        self.assertGreater(mediator.total, len(base64.b64decode(first['body'])) + len(base64.b64decode(second['body'])))
        self.assertEqual('external_evidence_gateway', mediator.events[-1]['transport'])
        self.assertTrue(mediator.runtime_gateway.close())

    def test_runtime_uses_existing_source_budget_instead_of_evidence_only_eight_request_limit(self):
        broker, clients = self.broker(maxRequests=20), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.factory(clients)):
            for _ in range(9):
                self.assertFalse(challenge(broker.fetch(request())))
        self.assertEqual(18, broker.count)
        self.assertEqual(9, broker.runtime_gateway.summary()['requestCount'])
        self.assertEqual(8, broker.runtime_gateway.summary()['sessionReuseCount'])
        self.assertNotIn('captureResponses', clients[0].test_calls[1][1])
        self.assertTrue(broker.runtime_gateway.close())

    def test_normal_error_rate_limit_and_sensitive_requests_never_start_a_gateway(self):
        for value, direct, credential in [
                (request(), self.direct(b'<html>business</html>', 200), None),
                (request(), self.direct(b'Access denied', 403), None),
                ({**request(), 'method': 'POST'}, self.direct(), None),
                ({**request(), 'headers': {'Cookie': ''}}, self.direct(), None),
                (request(), self.direct(), {'origin': 'https://fixture.example.com',
                                          'cookie': 'account=private', 'authorization': ''})]:
            broker = self.broker()
            broker.runtime_gateway = RuntimeGateway(settings(), E)
            if credential:
                broker.bind_credentials(credential)
            with patch.object(broker, 'connect', return_value=(direct, 0)), \
                    patch('evidence_gateway.EvidenceGateway', side_effect=AssertionError('must stay idle')):
                broker.fetch(value)
            self.assertEqual(0, broker.runtime_gateway.summary()['sessionCount'])
        broker = self.broker()
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        with patch.object(broker, 'connect', return_value=(self.direct(status=429), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=AssertionError('must stay idle')):
            with self.assertRaisesRegex(E.Denied, 'EGRESS_RATE_LIMITED'):
                broker.fetch(request())

    def test_browser_navigation_headers_reach_gateway_without_becoming_api_headers(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        broker.source_headers.add('accept-encoding')
        headers = {'Accept': 'text/html', 'Accept-Language': 'zh-CN', 'User-Agent': 'Fixture/1.0',
                   'Accept-Encoding': 'gzip, deflate, br', 'Connection': 'keep-alive',
                   'Upgrade-Insecure-Requests': '1', 'DNT': '1', 'Priority': 'u=0, i',
                   'Sec-Fetch-Dest': 'document', 'Sec-Fetch-Mode': 'navigate',
                   'Sec-Fetch-Site': 'none', 'Sec-Fetch-User': '?1',
                   'Sec-Fetch-Storage-Access': 'none', 'Sec-CH-UA': '"Fixture";v="1"',
                   'Sec-CH-UA-Mobile': '?0', 'Sec-CH-UA-Platform': '"Windows"'}
        value = {**request(), 'headers': headers}
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=self.factory(clients)):
            result = broker.fetch(value)
        self.assertFalse(challenge(result))
        self.assertEqual('response_returned', broker.events[0]['gatewayOutcome'])
        self.assertNotIn('gatewaySkipReason', broker.events[0])
        self.assertEqual(1, broker.runtime_gateway.summary()['requestCount'])
        payload = clients[0].test_calls[1][1]
        self.assertEqual({'Accept': 'text/html', 'Accept-Language': 'zh-CN'}, payload['headers'])
        self.assertNotIn('userAgent', payload)
        self.assertEqual(headers, value['headers'])
        self.assertTrue(broker.runtime_gateway.close())

    def test_gateway_skip_reasons_are_specific_and_do_not_disclose_request_values(self):
        credential = {'origin': 'https://fixture.example.com', 'cookie': 'account=PRIVATE', 'authorization': ''}
        for changes, bound, expected in [
                ({'method': 'POST'}, None, 'request_method'),
                ({'body': 'PRIVATE'}, None, 'request_body'),
                ({}, credential, 'host_credentials'),
                ({'headers': {'Cookie': ''}}, None, 'request_headers'),
                ({'headers': {'X-Sign': 'PRIVATE'}}, None, 'request_headers'),
                ({'headers': {'Range': 'bytes=0-63'}}, None, 'request_headers')]:
            with self.subTest(expected=expected, changes=changes):
                broker = self.broker()
                gateway = RuntimeGateway(settings(), E)
                broker.runtime_gateway = gateway
                if bound:
                    broker.bind_credentials(bound)
                broker.events.append({'responseContentType': 'text/html'})
                direct = response()['response']
                with patch('evidence_gateway.EvidenceGateway', side_effect=AssertionError('must stay idle')):
                    self.assertIs(direct, gateway.fetch(broker, {**request(), **changes}, direct))
                self.assertEqual(expected, broker.events[-1]['gatewaySkipReason'])
                self.assertEqual('request_not_reproducible', broker.events[-1]['gatewayOutcome'])
                self.assertNotIn('PRIVATE', json.dumps(broker.events))
                self.assertEqual(0, gateway.summary()['requestCount'])
        broker = self.broker()
        broker.events.append({'responseContentType': 'application/json'})
        gateway = RuntimeGateway(settings(), E)
        direct = response()['response']
        self.assertIs(direct, gateway.fetch(broker, request(), direct))
        self.assertEqual('response_content_type', broker.events[-1]['gatewaySkipReason'])

    def test_failed_gateway_keeps_original_challenge_and_is_not_retried(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        factory = self.factory(clients)

        def busy(*args):
            client = factory(*args)
            client.fetch = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_BUSY', 429))
            return client

        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=busy):
            self.assertTrue(challenge(broker.fetch(request())))
            self.assertTrue(challenge(broker.fetch(request(PAGE + '/next'))))
        self.assertEqual(1, clients[0].fetch.call_count)
        self.assertEqual('EVIDENCE_GATEWAY_BUSY', broker.events[-1]['gatewayOutcome'])
        self.assertTrue(broker.runtime_gateway.close())

    def test_gateway_response_still_challenged_or_redirected_cannot_be_business_content(self):
        for defect in ('challenge', 'private_redirect', 'changed_path'):
            broker, clients = self.broker(), []
            broker.runtime_gateway = RuntimeGateway(settings(), E)
            factory = self.factory(clients)

            def invalid(*args):
                client = factory(*args)
                reply = {'status': 200, 'headers': {}, 'body': base64.b64encode(b'<html>business</html>').decode(),
                         'finalUrl': PAGE}
                if defect == 'challenge':
                    reply['body'] = response()['response']['body']
                elif defect == 'private_redirect':
                    reply['finalUrl'] = 'http://127.0.0.1/private'
                else:
                    reply['finalUrl'] = PAGE + '/redirect'
                client.fetch = Mock(return_value={'response': reply, 'representation': 'gateway_rendered_document'})
                return client

            with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                    patch('evidence_gateway.EvidenceGateway', side_effect=invalid):
                self.assertTrue(challenge(broker.fetch(request())))
            self.assertNotEqual('response_returned', broker.events[0]['gatewayOutcome'])
            self.assertEqual(2 if defect == 'challenge' else 1, len(broker.events))

    def test_gateway_busy_recovers_without_becoming_target_rate_limit(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        factory = self.factory(clients)

        def busy(*args):
            client = factory(*args)
            fetch = client.fetch
            client.fetch = Mock(side_effect=GatewayError('EVIDENCE_GATEWAY_BUSY', 429))
            client.test_fetch = fetch
            return client

        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=busy):
            self.assertTrue(challenge(broker.fetch(request())))
            self.assertEqual('cooldown', broker.events[0]['gatewayRecovery']['action'])
            self.assertEqual([], broker.rate_limits)
            self.assertTrue(challenge(broker.fetch(request())))
            self.assertEqual(1, clients[0].fetch.call_count)
            clients[0].retry_at = time.monotonic() - 1
            clients[0].fetch = clients[0].test_fetch
            self.assertFalse(challenge(broker.fetch(request())))
        self.assertEqual(5, broker.count)
        self.assertEqual(1, broker.runtime_gateway.summary()['recoveryAttemptCount'])
        self.assertTrue(broker.runtime_gateway.close())

    def test_target429_uses_source_retry_after_cooldown_and_keeps_gateway_available(self):
        broker, clients = self.broker(), []
        broker.runtime_gateway = RuntimeGateway(settings('flaresolverr'), E)
        factory = self.factory(clients)

        def limited(*args):
            client = factory(*args)
            command = client.command

            def rate_limit(path, payload=None, **kwargs):
                result = command(path, payload, **kwargs)
                if payload and payload.get('cmd') == 'request.get':
                    result['solution'].update(status=429, response='', headers={'Retry-After': '120'})
                return result

            client.command = rate_limit
            return client

        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=limited):
            with self.assertRaises(E.Denied) as caught:
                broker.fetch(request())
        self.assertEqual('EGRESS_RATE_LIMITED', caught.exception.code)
        self.assertEqual({'kind': 'delay', 'seconds': 120}, caught.exception.retry_after)
        self.assertEqual('target_rate_limited', broker.events[0]['gatewayOutcome'])
        self.assertEqual(429, broker.events[1]['responseStatus'])
        self.assertFalse(broker.rate_limits[0]['cooldownContext']['fitsTaskBudget'])
        self.assertIsNone(clients[0].failure)
        broker.wait_for_slot = E.Broker.wait_for_slot.__get__(broker)
        with self.assertRaisesRegex(E.Denied, 'COOLDOWN_BUDGET'):
            broker.wait_for_slot()
        self.assertTrue(broker.runtime_gateway.close())

    def test_gateway_target_rate_limit_reaches_shared_routes_and_call_evidence(self):
        mediator = E.Routes({'schemaVersion': 3, 'methods': ['GET'], 'maxRequests': 6,
                            'maxResponseBytes': 2048, 'maxTotalResponseBytes': 8192, 'minIntervalSeconds': 1,
                            'routes': [{'origin': 'https://fixture.example.com', 'exactUrls': [PAGE],
                                        'pinnedIpv4': ['93.184.216.34']}]})
        mediator.bind_execution(time.monotonic() + 30, lambda: False)
        call = {'id': 1, 'method': 'home', 'inputSha256': 'a' * 64}
        mediator.set_source_call(call)
        mediator.runtime_gateway = RuntimeGateway(settings(), E)
        child = mediator.routes['https://fixture.example.com']
        child.wait_for_slot = Mock()
        clients = []
        factory = self.factory(clients)

        def limited(*args):
            client = factory(*args)
            command = client.command

            def rate_limit(path, payload=None, **kwargs):
                result = command(path, payload, **kwargs)
                return {**result, 'statusCode': 429, 'html': '', 'headers': {'Retry-After': '60'},
                        'tier': 2, 'sessionCached': True} if path == '/scrape' else result

            client.command = rate_limit
            return client

        with patch.object(child, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=limited):
            with self.assertRaisesRegex(E.Denied, 'EGRESS_RATE_LIMITED'):
                mediator.fetch(request())
        self.assertEqual(2, mediator.count)
        self.assertEqual(1, len(mediator.rate_limits))
        self.assertEqual(call, mediator.rate_limits[0]['observedDuringCall'])
        self.assertEqual(60, mediator.rate_limits[0]['retryAfter']['seconds'])
        self.assertEqual({'tier': 2, 'sessionCached': True}, mediator.events[-1]['gatewayExecution'])
        child.wait_for_slot = E.Broker.wait_for_slot.__get__(child)
        with self.assertRaisesRegex(E.Denied, 'COOLDOWN_BUDGET'):
            mediator.fetch(request())
        self.assertEqual(2, mediator.count)
        self.assertTrue(mediator.runtime_gateway.close())

    def test_gateway_honors_request_bytes_and_cancellation_budgets(self):
        broker = self.broker(maxRequests=1)
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=AssertionError('budget exhausted')):
            with self.assertRaisesRegex(E.Denied, 'EGRESS_REQUEST_BUDGET'):
                broker.fetch(request())
        broker, clients = self.broker(maxResponseBytes=80, maxTotalResponseBytes=90), []
        broker.runtime_gateway = RuntimeGateway(settings(), E)
        factory = self.factory(clients)

        def oversized(*args):
            client = factory(*args)
            client.fetch = Mock(return_value={'response': {'status': 200, 'headers': {},
                'body': base64.b64encode(b'x' * 81).decode(), 'finalUrl': PAGE},
                'representation': 'gateway_rendered_document'})
            return client

        with patch.object(broker, 'connect', return_value=(self.direct(), 0)), \
                patch('evidence_gateway.EvidenceGateway', side_effect=oversized):
            with self.assertRaisesRegex(E.Denied, 'EGRESS_BYTE_BUDGET'):
                broker.fetch(request())
        broker.cancelled = lambda: True
        broker.wait_for_slot = E.Broker.wait_for_slot.__get__(broker)
        with self.assertRaisesRegex(E.Denied, 'EGRESS_CANCELLED'):
            broker.wait_for_slot()


if __name__ == '__main__':
    unittest.main()
