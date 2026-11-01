import assert from 'node:assert/strict';
import test from 'node:test';

import {ApiError, createRiderApi} from '../../main/resources/static/rider-web/api.mjs';

const baseUrl = new URL('https://takeout.example/app/');

function jsonResponse(payload, {status = 200, ok = status >= 200 && status < 300} = {}) {
    return {
        ok,
        status,
        async json() {
            return payload;
        },
    };
}

function apiWith(fetchImpl, options = {}) {
    return createRiderApi({
        baseUrl,
        getToken: () => 'rider-token',
        fetchImpl,
        ...options,
    });
}

test('login posts credentials to the rider endpoint, skips Authorization, and unwraps data', async () => {
    let request;
    const api = createRiderApi({
        baseUrl,
        getToken: () => {
            throw new Error('login must not read a token');
        },
        fetchImpl: async (url, options) => {
            request = {url, options};
            return jsonResponse({code: 200, message: 'success', data: {id: 9, name: 'rider', token: 'issued'}});
        },
        requestTimeoutMs: 60_000,
    });

    const data = await api.login({name: 'rider', password: 'password123'});

    assert.equal(request.url, 'https://takeout.example/app/rider/login');
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.headers['Content-Type'], 'application/json');
    assert.equal('Authorization' in request.options.headers, false);
    assert.deepEqual(JSON.parse(request.options.body), {name: 'rider', password: 'password123'});
    assert.ok(request.options.signal instanceof AbortSignal);
    assert.deepEqual(data, {id: 9, name: 'rider', token: 'issued'});
});

test('location GET uses the bearer token and returns only envelope data', async () => {
    let request;
    const api = apiWith(async (url, options) => {
        request = {url, options};
        return jsonResponse({code: 200, message: 'success', data: {longitude: 121.4, latitude: 31.2}});
    });

    const data = await api.getLocation();

    assert.equal(request.url, 'https://takeout.example/app/rider/location');
    assert.equal(request.options.method, 'GET');
    assert.equal(request.options.headers.Authorization, 'Bearer rider-token');
    assert.deepEqual(data, {longitude: 121.4, latitude: 31.2});
});

test('location PUT and DELETE use authenticated requests without automatic abort signals', async () => {
    const requests = [];
    const api = apiWith(async (url, options) => {
        requests.push({url, options});
        return jsonResponse({code: 200, message: 'success', data: 'success'});
    });

    assert.equal(await api.updateLocation({longitude: 121.4, latitude: 31.2}), 'success');
    assert.equal(await api.removeLocation(), 'success');
    assert.equal(await api.removeLocation({keepalive: true}), 'success');

    assert.deepEqual(requests.map(({url, options}) => [url, options.method]), [
        ['https://takeout.example/app/rider/location', 'PUT'],
        ['https://takeout.example/app/rider/location', 'DELETE'],
        ['https://takeout.example/app/rider/location', 'DELETE'],
    ]);
    assert.equal(requests[0].options.headers.Authorization, 'Bearer rider-token');
    assert.equal(requests[0].options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(requests[0].options.body), {longitude: 121.4, latitude: 31.2});
    assert.equal('signal' in requests[0].options, false);
    assert.equal('signal' in requests[1].options, false);
    assert.equal(requests[1].options.keepalive, undefined);
    assert.equal(requests[2].options.keepalive, true);
    assert.equal(requests[2].options.headers.Authorization, 'Bearer rider-token');
});

test('available-task radius is encoded in the relative API URL', async () => {
    let requestUrl;
    const api = apiWith(async url => {
        requestUrl = url;
        return jsonResponse({code: 200, message: 'success', data: [{id: 101}]});
    });

    const data = await api.getAvailableTasks(3.5);

    assert.equal(requestUrl, 'https://takeout.example/app/rider/delivery-tasks/available?radiusKm=3.5');
    assert.deepEqual(data, [{id: 101}]);
});

test('missing bearer token rejects protected calls before fetching, while login remains public', async () => {
    let fetchCalls = 0;
    const api = createRiderApi({
        baseUrl,
        getToken: () => '',
        fetchImpl: async () => {
            fetchCalls++;
            return jsonResponse({code: 200, data: null});
        },
    });

    await assert.rejects(api.getLocation(), error => {
        assert.ok(error instanceof ApiError);
        assert.equal(error.kind, 'auth');
        assert.equal(error.code, 401);
        assert.equal(error.status, 401);
        return true;
    });
    assert.equal(fetchCalls, 0);
    await api.login({name: 'rider', password: 'password123'});
    assert.equal(fetchCalls, 1);
});

test('backend result codes map to auth, geo, validation, and server errors', async t => {
    const cases = [
        {code: 401, kind: 'auth'},
        {code: 403, kind: 'auth'},
        {code: 5000, kind: 'geo'},
        {code: 400, kind: 'validation'},
        {code: 500, kind: 'server'},
    ];

    for (const {code, kind} of cases) {
        await t.test(`code ${code} maps to ${kind}`, async () => {
            const api = apiWith(async () => jsonResponse({code, message: `failure ${code}`, data: null}));

            await assert.rejects(api.getLocation(), error => {
                assert.ok(error instanceof ApiError);
                assert.equal(error.code, code);
                assert.equal(error.status, 200);
                assert.equal(error.kind, kind);
                assert.equal(error.message, `failure ${code}`);
                return true;
            });
        });
    }
});

test('HTTP errors are rejected even if their JSON body claims success', async () => {
    const api = apiWith(async () => jsonResponse(
        {code: 200, message: 'misleading success', data: null},
        {status: 403, ok: false},
    ));

    await assert.rejects(api.getLocation(), error => {
        assert.equal(error.kind, 'auth');
        assert.equal(error.code, 403);
        assert.equal(error.status, 403);
        return true;
    });
});

test('network failures and malformed JSON have distinct error kinds', async t => {
    await t.test('fetch rejection', async () => {
        const api = apiWith(async () => {
            throw new TypeError('network unavailable');
        });

        await assert.rejects(api.getLocation(), error => {
            assert.equal(error.kind, 'network');
            assert.equal(error.code, null);
            assert.equal(error.status, null);
            return true;
        });
    });

    await t.test('invalid JSON response', async () => {
        const api = apiWith(async () => ({
            ok: true,
            status: 200,
            async json() {
                throw new SyntaxError('invalid JSON');
            },
        }));

        await assert.rejects(api.getLocation(), error => {
            assert.equal(error.kind, 'response');
            assert.equal(error.status, 200);
            assert.equal(error.code, null);
            return true;
        });
    });

    await t.test('invalid response envelope', async () => {
        const api = apiWith(async () => jsonResponse({message: 'missing code and data'}));

        await assert.rejects(api.getLocation(), error => {
            assert.equal(error.kind, 'response');
            assert.equal(error.status, 200);
            assert.equal(error.code, null);
            return true;
        });
    });
});

test('GET requests are aborted after the configured timeout', async () => {
    let observedSignal;
    const api = apiWith((url, options) => new Promise((resolve, reject) => {
        observedSignal = options.signal;
        options.signal.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted', 'AbortError'));
        }, {once: true});
    }), {requestTimeoutMs: 5});

    await assert.rejects(api.getLocation(), error => {
        assert.equal(error.kind, 'network');
        assert.equal(error.status, null);
        assert.equal(error.code, null);
        assert.match(error.message, /超时/);
        return true;
    });
    assert.equal(observedSignal.aborted, true);
});
