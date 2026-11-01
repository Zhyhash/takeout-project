const DEFAULT_BASE_URL = new URL('../', import.meta.url);
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

export class ApiError extends Error {
    constructor(message, {code = null, status = null, kind = 'server'} = {}) {
        super(message);
        this.name = 'ApiError';
        this.code = code;
        this.status = status;
        this.kind = kind;
    }
}

function normalizeBaseUrl(baseUrl) {
    const url = baseUrl instanceof URL
        ? new URL(baseUrl.href)
        : new URL(baseUrl, import.meta.url);
    if (!url.pathname.endsWith('/')) {
        url.pathname += '/';
    }
    return url;
}

function errorKind(code, status) {
    if (status === 401 || status === 403 || code === 401 || code === 403) {
        return 'auth';
    }
    if (code === 5000) {
        return 'geo';
    }
    if (code === 400) {
        return 'validation';
    }
    return 'server';
}

function responseError(response, payload = null) {
    const bodyCode = Number.isInteger(payload?.code) ? payload.code : null;
    const ok = response.ok === true;
    const code = bodyCode !== null && bodyCode !== 200
        ? bodyCode
        : (!ok ? response.status : bodyCode);
    const kind = errorKind(code, response.status);
    const fallbackMessages = {
        auth: '请先使用骑手账号登录',
        geo: '位置服务暂时不可用，请稍后重试',
        validation: '请求参数不合法',
        server: '请求失败，请稍后重试',
    };
    const message = typeof payload?.message === 'string' && payload.message.length > 0
        ? payload.message
        : fallbackMessages[kind];

    return new ApiError(message, {code, status: response.status, kind});
}

function invalidResponseError(status) {
    return new ApiError('服务器返回了无法识别的响应', {
        code: null,
        status,
        kind: 'response',
    });
}

export function createRiderApi({
    getToken = () => '',
    fetchImpl = globalThis.fetch,
    baseUrl = DEFAULT_BASE_URL,
    requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
} = {}) {
    const base = normalizeBaseUrl(baseUrl);

    function authorizationValue() {
        let token;
        try {
            token = getToken();
        } catch {
            throw new ApiError('无法读取登录凭证，请重新登录', {
                code: 401,
                status: 401,
                kind: 'auth',
            });
        }

        if (typeof token !== 'string' || token.trim().length === 0) {
            throw new ApiError('请先使用骑手账号登录', {
                code: 401,
                status: 401,
                kind: 'auth',
            });
        }
        return `Bearer ${token.trim()}`;
    }

    async function request(path, {
        method = 'GET',
        body,
        authenticated = true,
        timeout = true,
        keepalive = false,
    } = {}) {
        const url = new URL(path, base);
        const headers = {Accept: 'application/json'};
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        if (authenticated) {
            headers.Authorization = authorizationValue();
        }

        const options = {method, headers};
        if (body !== undefined) {
            options.body = JSON.stringify(body);
        }
        if (keepalive) {
            options.keepalive = true;
        }

        let controller;
        let timeoutId;
        let timedOut = false;
        if (timeout && Number.isFinite(requestTimeoutMs) && requestTimeoutMs > 0) {
            controller = new AbortController();
            options.signal = controller.signal;
            timeoutId = setTimeout(() => {
                timedOut = true;
                controller.abort();
            }, requestTimeoutMs);
        }

        try {
            if (typeof fetchImpl !== 'function') {
                throw new Error('Fetch API is unavailable');
            }

            const response = await fetchImpl(url.href, options);
            let payload;
            try {
                payload = await response.json();
            } catch {
                if (timedOut) {
                    throw new ApiError('请求超时，请稍后重试', {
                        code: null,
                        status: null,
                        kind: 'network',
                    });
                }
                if (response.ok !== true) {
                    throw responseError(response);
                }
                throw invalidResponseError(response.status);
            }

            if (timedOut) {
                throw new ApiError('请求超时，请稍后重试', {
                    code: null,
                    status: null,
                    kind: 'network',
                });
            }
            if (response.ok !== true) {
                throw responseError(response, payload);
            }
            if (payload === null || typeof payload !== 'object' || Array.isArray(payload)
                    || !Object.hasOwn(payload, 'code')) {
                throw invalidResponseError(response.status);
            }
            if (payload.code !== 200) {
                throw responseError(response, payload);
            }
            if (!Object.hasOwn(payload, 'data')) {
                throw invalidResponseError(response.status);
            }
            return payload.data;
        } catch (error) {
            if (error instanceof ApiError) {
                throw error;
            }
            throw new ApiError(
                timedOut ? '请求超时，请稍后重试' : '网络请求失败，请检查网络后重试',
                {code: null, status: null, kind: 'network'},
            );
        } finally {
            if (timeoutId !== undefined) {
                clearTimeout(timeoutId);
            }
        }
    }

    return {
        login({name, password}) {
            return request('rider/login', {
                method: 'POST',
                body: {name, password},
                authenticated: false,
                timeout: true,
            });
        },

        getLocation() {
            return request('rider/location');
        },

        updateLocation({longitude, latitude}) {
            return request('rider/location', {
                method: 'PUT',
                body: {longitude, latitude},
                timeout: false,
            });
        },

        removeLocation({keepalive = false} = {}) {
            return request('rider/location', {
                method: 'DELETE',
                timeout: false,
                keepalive,
            });
        },

        getAvailableTasks(radiusKm = 5) {
            const query = new URLSearchParams({radiusKm: String(radiusKm)});
            return request(`rider/delivery-tasks/available?${query}`);
        },
    };
}
