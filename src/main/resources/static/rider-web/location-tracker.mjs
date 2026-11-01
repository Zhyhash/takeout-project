const REPORT_INTERVAL_MS = 30_000;
const MAX_FIX_AGE_MS = 60_000;
const MAX_LATITUDE = 85.05112878;

export function geolocationError(error) {
    const messages = {
        1: "定位权限已被拒绝。请在浏览器的网站设置中允许定位，然后重新开始。",
        2: "暂时无法获取真实位置，请检查设备定位服务与网络；页面会继续尝试。",
        3: "获取真实位置超时，页面会继续尝试，也可以停止后重新开始。",
    };
    return {kind: "location", code: error?.code, message: messages[error?.code] || "无法获取真实位置，请检查设备定位服务。"};
}

export function createLocationTracker({
    api,
    geolocation = globalThis.navigator?.geolocation,
    secureContext = globalThis.isSecureContext,
    onChange = () => {},
    now = () => Date.now(),
    timers = globalThis,
    getRadius = () => 5,
} = {}) {
    let generation = 0;
    let watchId = null;
    let heartbeat = null;
    let uploadTimer = null;
    let pendingFix = null;
    let uploadPromise = null;
    let cleanupPromise = null;
    let cleanupKeepalive = false;
    let taskRequest = null;
    let nextUploadAt = 0;
    let lastFixAt = -Infinity;
    let requestingFix = false;
    const state = {
        phase: "idle", active: false, position: null, lastUploadedAt: null,
        tasks: [], refreshing: false, locationError: null, uploadError: null,
        taskError: null, cleanupError: null,
    };
    const current = session => state.active && generation === session;
    const emit = () => onChange({...state});
    const errorInfo = (error, fallback) => ({
        kind: error?.kind || "network", code: error?.code,
        message: error?.message || fallback,
    });
    function invalidateTasks() {
        taskRequest = null;
        state.refreshing = false;
        state.tasks = [];
    }
    const clearUploadTimer = () => {
        if (uploadTimer !== null) timers.clearTimeout(uploadTimer);
        uploadTimer = null;
    };
    const clearTracking = () => {
        if (watchId !== null) geolocation.clearWatch(watchId);
        watchId = null;
        if (heartbeat !== null) timers.clearInterval(heartbeat);
        heartbeat = null;
        clearUploadTimer();
        pendingFix = null;
        requestingFix = false;
    };

    async function refreshTasks(session = generation) {
        if (!current(session) || state.phase !== "tracking" || state.lastUploadedAt === null || state.locationError || state.uploadError) return false;
        if (taskRequest?.session === session) return taskRequest.promise;
        state.refreshing = true;
        state.taskError = null;
        emit();
        const request = {session};
        taskRequest = request;
        request.promise = (async () => {
            try {
                const tasks = await api.getAvailableTasks(getRadius());
                if (!Array.isArray(tasks)) throw new Error("任务服务返回的数据格式不正确");
                if (current(session) && taskRequest === request && !state.locationError && !state.uploadError) {
                    state.tasks = tasks;
                    state.taskError = null;
                }
                return true;
            } catch (error) {
                if (current(session) && taskRequest === request) {
                    state.tasks = [];
                    state.taskError = errorInfo(error, "附近任务查询失败，请重试");
                }
                return false;
            } finally {
                if (taskRequest === request) {
                    taskRequest = null;
                    state.refreshing = false;
                    emit();
                }
            }
        })();
        return request.promise;
    }

    function scheduleUpload(session) {
        if (!current(session) || !pendingFix || uploadPromise) return;
        clearUploadTimer();
        const delay = Math.max(0, nextUploadAt - now());
        if (delay > 0) {
            uploadTimer = timers.setTimeout(() => {
                uploadTimer = null;
                void upload(session);
            }, delay);
        } else {
            void upload(session);
        }
    }

    async function upload(session) {
        if (!current(session) || uploadPromise || !pendingFix) return;
        const fix = pendingFix;
        pendingFix = null;
        if (now() - fix.timestamp > MAX_FIX_AGE_MS) return;
        // Failed attempts are throttled too. Never issue concurrent location writes.
        nextUploadAt = now() + REPORT_INTERVAL_MS;
        const writer = Promise.resolve().then(() => api.updateLocation({
            longitude: fix.longitude, latitude: fix.latitude,
        }));
        uploadPromise = writer;
        try {
            await writer;
            if (current(session)) {
                invalidateTasks();
                state.lastUploadedAt = now();
                state.uploadError = null;
                state.phase = state.locationError ? "locating" : "tracking";
                emit();
                if (!state.locationError) void refreshTasks(session);
            }
        } catch (error) {
            if (current(session)) {
                invalidateTasks();
                state.phase = "locating";
                state.tasks = [];
                state.uploadError = errorInfo(error, "位置上报失败，请检查网络后重试");
                emit();
                if (error?.kind === "auth") void stop();
            }
        } finally {
            if (uploadPromise === writer) uploadPromise = null;
            scheduleUpload(session);
        }
    }

    function receivePosition(position, session) {
        if (!current(session)) return;
        const longitude = position?.coords?.longitude;
        const latitude = position?.coords?.latitude;
        const timestamp = position?.timestamp;
        if (!Number.isFinite(longitude) || !Number.isFinite(latitude)
            || Math.abs(longitude) > 180 || Math.abs(latitude) > MAX_LATITUDE
            || !Number.isFinite(timestamp) || now() - timestamp > MAX_FIX_AGE_MS
            || timestamp > now() + 10_000) {
            pendingFix = null;
            clearUploadTimer();
            invalidateTasks();
            state.phase = "locating";
            state.tasks = [];
            state.locationError = {kind: "location", message: "浏览器返回的位置无效、已过时或超出服务范围，等待新的真实位置。"};
            emit();
            return;
        }
        lastFixAt = now();
        state.position = {longitude, latitude, timestamp, accuracy: position.coords.accuracy};
        state.locationError = null;
        pendingFix = state.position;
        emit();
        scheduleUpload(session);
    }

    function receiveError(error, session) {
        if (!current(session)) return;
        pendingFix = null;
        clearUploadTimer();
        invalidateTasks();
        state.locationError = geolocationError(error);
        state.phase = "locating";
        state.tasks = [];
        emit();
        if (error?.code === 1) void stop();
    }

    function requestFreshFix(session) {
        // A stationary device may not produce watch callbacks. Request a new fix,
        // rather than repeatedly submitting an old coordinate to keep it alive.
        if (!current(session) || requestingFix || now() - lastFixAt < REPORT_INTERVAL_MS) return;
        requestingFix = true;
        try {
            geolocation.getCurrentPosition(position => {
                if (current(session)) requestingFix = false;
                receivePosition(position, session);
            }, error => {
                if (current(session)) requestingFix = false;
                receiveError(error, session);
            }, {enableHighAccuracy: true, maximumAge: 0, timeout: 15_000});
        } catch (error) {
            requestingFix = false;
            receiveError({code: error?.name === "NotAllowedError" ? 1 : 2}, session);
        }
    }

    function start() {
        if (state.active || cleanupPromise || state.cleanupError) return false;
        if (!secureContext) {
            state.locationError = {kind: "security", message: "当前地址不支持安全定位。请使用本机 localhost，或使用设备信任的 HTTPS 地址。手机访问局域网 HTTP 地址通常无法定位。"};
            emit();
            return false;
        }
        if (!geolocation?.watchPosition || !geolocation?.getCurrentPosition) {
            state.locationError = {kind: "unsupported", message: "当前浏览器不支持定位，请使用支持 Geolocation 的浏览器。"};
            emit();
            return false;
        }
        const session = ++generation;
        nextUploadAt = 0;
        lastFixAt = -Infinity;
        Object.assign(state, {
            active: true, phase: "locating", tasks: [], position: null,
            lastUploadedAt: null, locationError: null, uploadError: null,
            taskError: null, cleanupError: null, refreshing: false,
        });
        emit();
        try {
            const id = geolocation.watchPosition(
                position => receivePosition(position, session),
                error => receiveError(error, session),
                {enableHighAccuracy: true, maximumAge: 0, timeout: 15_000});
            if (current(session)) {
                watchId = id;
                heartbeat = timers.setInterval(() => requestFreshFix(session), REPORT_INTERVAL_MS);
            } else {
                geolocation.clearWatch(id);
            }
            return current(session);
        } catch (error) {
            receiveError({code: error?.name === "NotAllowedError" ? 1 : 2}, session);
            if (current(session)) void stop();
            return false;
        }
    }

    function stop({keepalive = false} = {}) {
        cleanupKeepalive = cleanupKeepalive || keepalive;
        if (cleanupPromise) return cleanupPromise;
        const session = ++generation;
        state.active = false;
        clearTracking();
        taskRequest = null;
        Object.assign(state, {phase: "stopping", refreshing: false, tasks: [], cleanupError: null, position: null, lastUploadedAt: null, taskError: null, uploadError: null});
        emit();
        const writer = uploadPromise;
        const cleanup = (async () => {
            try {
                // clearWatch is immediate; server deletion follows any pending PUT.
                // Do not abort PUT and assume that its server-side write was cancelled.
                if (writer) await writer.catch(() => {});
                await Promise.resolve().then(() => api.removeLocation({keepalive: cleanupKeepalive}));
                if (generation === session) state.phase = "idle";
                return true;
            } catch (error) {
                if (generation === session) {
                    state.phase = "cleanup-error";
                    state.cleanupError = errorInfo(error, "定位已停止，但服务器位置删除失败，请重试清理");
                }
                return false;
            } finally {
                if (generation === session) {
                    cleanupPromise = null;
                    cleanupKeepalive = false;
                    emit();
                }
            }
        })();
        cleanupPromise = cleanup;
        return cleanup;
    }

    function reset() {
        if (state.active || uploadPromise || cleanupPromise) return false;
        ++generation;
        Object.assign(state, {phase: "idle", position: null, lastUploadedAt: null,
            tasks: [], refreshing: false, locationError: null, uploadError: null,
            taskError: null, cleanupError: null});
        emit();
        return true;
    }

    return {start, stop, reset, refreshTasks, getState: () => ({...state})};
}
