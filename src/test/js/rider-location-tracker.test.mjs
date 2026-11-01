import test from "node:test";
import assert from "node:assert/strict";
import {createLocationTracker} from "../../main/resources/static/rider-web/location-tracker.mjs";

const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}
function fixture(overrides = {}) {
    let time = 1_000_000;
    let nextId = 0;
    const jobs = new Map();
    const timers = {
        setTimeout(fn, delay) { const id = ++nextId; jobs.set(id, {fn, at: time + delay}); return id; },
        clearTimeout(id) { jobs.delete(id); },
        setInterval(fn, delay) { const id = ++nextId; jobs.set(id, {fn, at: time + delay, interval: delay}); return id; },
        clearInterval(id) { jobs.delete(id); },
    };
    const calls = [];
    const watches = [];
    const cleared = [];
    const freshRequests = [];
    const geo = {
        watchPosition(success, error, options) { watches.push({success, error, options}); return watches.length - 1; },
        clearWatch(id) { cleared.push(id); },
        getCurrentPosition(success, error, options) { freshRequests.push({success, error, options}); },
    };
    const api = {
        async updateLocation(point) { calls.push(["put", point]); },
        async getAvailableTasks(radius) { calls.push(["tasks", radius]); return [{taskId: 9}, {taskId: 2}]; },
        async removeLocation(options) { calls.push(["delete", options]); },
        ...overrides.api,
    };
    const tracker = createLocationTracker({api, geolocation: geo, secureContext: true,
        now: () => time, timers, ...overrides, api});
    return {
        tracker, api, geo, watches, cleared, calls, freshRequests,
        position(longitude = 121.4737, timestamp = time) {
            return {coords: {longitude, latitude: 31.2304, accuracy: 12}, timestamp};
        },
        async advance(ms) {
            const target = time + ms;
            for (;;) {
                const next = [...jobs].filter(([, job]) => job.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
                if (!next) break;
                const [id, job] = next;
                time = job.at;
                if (job.interval) job.at += job.interval;
                else jobs.delete(id);
                job.fn();
                await settle();
            }
            time = target;
            await settle();
        },
    };
}

test("unsupported and insecure environments never start a watch or send coordinates", () => {
    const unsupported = fixture({geolocation: {}});
    assert.equal(unsupported.tracker.start(), false);
    assert.equal(unsupported.tracker.getState().locationError.kind, "unsupported");
    const insecure = fixture({secureContext: false});
    assert.equal(insecure.tracker.start(), false);
    assert.equal(insecure.tracker.getState().locationError.kind, "security");
    assert.equal(insecure.watches.length, 0);
    assert.deepEqual(insecure.calls, []);
});

test("first real fix is uploaded before task query and backend order is retained", async () => {
    const f = fixture();
    assert.equal(f.tracker.start(), true);
    assert.deepEqual(f.calls, []);
    f.watches[0].success(f.position());
    await settle();
    assert.deepEqual(f.calls, [["put", {longitude: 121.4737, latitude: 31.2304}], ["tasks", 5]]);
    assert.deepEqual(f.tracker.getState().tasks.map(task => task.taskId), [9, 2]);
    assert.equal(f.tracker.getState().phase, "tracking");
    await f.tracker.stop();
});

test("many callbacks are coalesced to the latest fix with a 30-second minimum interval", async () => {
    const f = fixture();
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    for (const longitude of [121.48, 121.49, 121.50]) f.watches[0].success(f.position(longitude));
    await f.advance(29_999);
    assert.equal(f.calls.filter(([kind]) => kind === "put").length, 1);
    await f.advance(1);
    assert.deepEqual(f.calls.filter(([kind]) => kind === "put").map(([, point]) => point.longitude), [121.4737, 121.50]);
    await f.tracker.stop();
});

test("location PUTs remain serial even when an earlier PUT takes longer than the interval", async () => {
    const first = deferred();
    let writers = 0;
    const f = fixture({api: {updateLocation: () => (++writers === 1 ? first.promise : Promise.resolve())}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    await f.advance(30_000);
    f.watches[0].success(f.position(122));
    await settle();
    assert.equal(writers, 1);
    first.resolve();
    await settle();
    assert.equal(writers, 2);
    await f.tracker.stop();
});

test("stop clears watch ID zero immediately, waits for PUT, then DELETEs and ignores old callbacks", async () => {
    const writer = deferred();
    const f = fixture({api: {updateLocation: () => writer.promise}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    const stopping = f.tracker.stop();
    assert.deepEqual(f.cleared, [0]);
    assert.equal(f.tracker.getState().phase, "stopping");
    assert.equal(f.tracker.start(), false);
    f.watches[0].success(f.position(123));
    assert.deepEqual(f.calls, []);
    writer.resolve();
    assert.equal(await stopping, true);
    assert.deepEqual(f.calls.map(([kind]) => kind), ["delete"]);
    assert.equal(f.tracker.getState().position, null);
    assert.equal(f.tracker.getState().phase, "idle");
});

test("start cannot overlap an outstanding DELETE from the preceding session", async () => {
    const deletion = deferred();
    const f = fixture({api: {removeLocation: () => deletion.promise}});
    f.tracker.start();
    const stopping = f.tracker.stop();
    await settle();
    assert.equal(f.tracker.start(), false);
    deletion.resolve();
    await stopping;
    assert.equal(f.tracker.start(), true);
    assert.equal(f.watches.length, 2);
    await f.tracker.stop();
});

test("permission denial stops listening and removes the server location", async () => {
    const f = fixture();
    f.tracker.start();
    f.watches[0].error({code: 1});
    await settle();
    assert.equal(f.tracker.getState().active, false);
    assert.match(f.tracker.getState().locationError.message, /权限/);
    assert.deepEqual(f.cleared, [0]);
    assert.equal(f.calls[0][0], "delete");
});

test("timeout and unavailable errors are distinct and can recover with a new real fix", async () => {
    const f = fixture();
    f.tracker.start();
    f.watches[0].error({code: 3});
    assert.match(f.tracker.getState().locationError.message, /超时/);
    f.watches[0].error({code: 2});
    assert.match(f.tracker.getState().locationError.message, /无法获取/);
    assert.equal(f.tracker.getState().active, true);
    assert.equal(f.calls.length, 0);
    f.watches[0].success(f.position());
    await settle();
    assert.equal(f.tracker.getState().locationError, null);
    assert.equal(f.tracker.getState().phase, "tracking");
    await f.tracker.stop();
});

test("backend GEO upload failure is visible and does not query tasks", async () => {
    const error = Object.assign(new Error("位置服务暂时不可用"), {kind: "geo", code: 5000});
    const f = fixture({api: {updateLocation: async () => { throw error; }}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    assert.equal(f.tracker.getState().uploadError.kind, "geo");
    assert.equal(f.tracker.getState().lastUploadedAt, null);
    assert.equal(f.calls.length, 0);
    await f.tracker.stop();
});

test("task query failure remains distinct from a successful location upload", async () => {
    const f = fixture({api: {getAvailableTasks: async () => { throw new Error("任务查询失败"); }}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    assert.equal(f.tracker.getState().phase, "tracking");
    assert.equal(f.tracker.getState().uploadError, null);
    assert.match(f.tracker.getState().taskError.message, /任务查询失败/);
    await f.tracker.stop();
});

test("invalid and stale coordinates never produce fallback location writes", async () => {
    const f = fixture();
    f.tracker.start();
    f.watches[0].success(f.position(NaN));
    f.watches[0].success(f.position(122, 1));
    f.watches[0].success({coords: {longitude: 122, latitude: 89}, timestamp: 1_000_000});
    await settle();
    assert.equal(f.calls.length, 0);
    assert.ok(f.tracker.getState().locationError);
    await f.tracker.stop();
});

test("stationary heartbeat requests a fresh browser fix instead of resending old coordinates", async () => {
    const f = fixture();
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    await f.advance(30_000);
    assert.equal(f.freshRequests.length, 1);
    assert.deepEqual(f.freshRequests[0].options, {enableHighAccuracy: true, maximumAge: 0, timeout: 15_000});
    assert.equal(f.calls.filter(([kind]) => kind === "put").length, 1);
    f.freshRequests[0].success(f.position(122));
    await settle();
    assert.equal(f.calls.filter(([kind]) => kind === "put").length, 2);
    await f.tracker.stop();
});

test("late task responses cannot repopulate the stopped page", async () => {
    const tasks = deferred();
    const f = fixture({api: {getAvailableTasks: () => tasks.promise}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    await f.tracker.stop();
    tasks.resolve([{taskId: 100}]);
    await settle();
    assert.deepEqual(f.tracker.getState().tasks, []);
});

test("failed DELETE can be retried and prevents a new tracking session until cleanup succeeds", async () => {
    let attempts = 0;
    const f = fixture({api: {removeLocation: async () => { if (++attempts === 1) throw new Error("Redis down"); }}});
    f.tracker.start();
    assert.equal(await f.tracker.stop({keepalive: true}), false);
    assert.equal(f.tracker.getState().phase, "cleanup-error");
    assert.equal(f.tracker.start(), false);
    assert.equal(await f.tracker.stop(), true);
    assert.equal(f.tracker.start(), true);
    await f.tracker.stop();
});

test("failed PUT still settles before stop DELETE and leaves no background heartbeat", async () => {
    const writer = deferred();
    const f = fixture({api: {updateLocation: () => writer.promise}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    const stopping = f.tracker.stop({keepalive: true});
    writer.reject(new Error("network down"));
    await stopping;
    assert.deepEqual(f.calls, [["delete", {keepalive: true}]]);
    await f.advance(90_000);
    assert.equal(f.freshRequests.length, 0);
    assert.equal(f.tracker.getState().uploadError, null);
});

test("a late task response cannot overwrite a newer geolocation failure", async () => {
    const tasks = deferred();
    const f = fixture({api: {getAvailableTasks: () => tasks.promise}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    f.watches[0].error({code: 3});
    tasks.resolve([{taskId: 100}]);
    await settle();
    assert.deepEqual(f.tracker.getState().tasks, []);
    assert.equal(f.tracker.getState().phase, "locating");
    await f.tracker.stop();
});

test("page lifecycle can upgrade cleanup to keepalive while PUT is pending", async () => {
    const writer = deferred();
    const f = fixture({api: {updateLocation: () => writer.promise}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    const stopping = f.tracker.stop();
    assert.equal(f.tracker.stop({keepalive: true}), stopping);
    writer.resolve();
    await stopping;
    assert.deepEqual(f.calls, [["delete", {keepalive: true}]]);
});

test("expired authentication stops collection and local reset allows a new login", async () => {
    const error = Object.assign(new Error("token expired"), {kind: "auth", code: 401});
    const f = fixture({api: {
        updateLocation: async () => { throw error; },
        removeLocation: async () => { throw error; },
    }});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    assert.equal(f.tracker.getState().active, false);
    assert.deepEqual(f.cleared, [0]);
    assert.equal(f.tracker.getState().cleanupError.kind, "auth");
    assert.equal(f.tracker.reset(), true);
    assert.equal(f.tracker.getState().phase, "idle");
});

test("recovery after a positioning error queries again and discards the old-position task response", async () => {
    const oldTasks = deferred();
    let queries = 0;
    const f = fixture({api: {getAvailableTasks: () => ++queries === 1 ? oldTasks.promise : Promise.resolve([{taskId: 2}])}});
    f.tracker.start();
    f.watches[0].success(f.position());
    await settle();
    f.watches[0].error({code: 3});
    await f.advance(30_000);
    f.watches[0].success(f.position(122));
    await settle();
    assert.equal(queries, 2);
    assert.deepEqual(f.tracker.getState().tasks, [{taskId: 2}]);
    oldTasks.resolve([{taskId: 1}]);
    await settle();
    assert.deepEqual(f.tracker.getState().tasks, [{taskId: 2}]);
    await f.tracker.stop();
});
