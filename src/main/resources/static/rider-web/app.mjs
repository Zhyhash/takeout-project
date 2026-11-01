import {createRiderApi} from "./api.mjs";
import {createLocationTracker} from "./location-tracker.mjs";
import {formatPickupDistance} from "./distance-display.mjs";

const element = id => document.getElementById(id);
const SESSION_KEY = "takeout.rider.web.session";
let session = null;
try {
    const saved = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
    if (typeof saved?.token === "string" && saved.token && typeof saved.name === "string") session = saved;
} catch {
    // Disabled storage does not prevent an in-memory login for this page.
}
let loginBusy = false;
let logoutBusy = false;
let lastTasks = null;
const api = createRiderApi({getToken: () => session?.token || ""});
const tracker = createLocationTracker({api, onChange: render, getRadius: () => Number(element("radius").value)});

function setSession(value) {
    session = value;
    try {
        if (value) sessionStorage.setItem(SESSION_KEY, JSON.stringify(value));
        else sessionStorage.removeItem(SESSION_KEY);
    } catch {
        // Credentials remain in memory if tab storage is unavailable.
    }
    render(tracker.getState());
}

function showMessage(id, message) {
    element(id).textContent = message || "";
    element(id).hidden = !message;
}

function stageMessage(error, stage) {
    if (!error) return "";
    if (error.kind === "auth") return `${stage}：登录已失效或账号不可用，请停止定位后重新登录。${error.message}`;
    if (error.kind === "geo") return `${stage}：后端位置服务暂时不可用，请稍后重试。${error.message}`;
    return `${stage}：${error.message}`;
}

function renderTasks(tasks, state) {
    element("task-count").textContent = `${tasks.length} 项`;
    element("tasks-empty").hidden = tasks.length > 0;
    element("tasks-empty").textContent = state.refreshing ? "正在获取任务…"
        : state.phase === "tracking" && !state.taskError ? "当前没有可接任务，稍后可以刷新。"
            : "真实位置成功上报后，这里会显示可接任务。";
    if (tasks === lastTasks) return;
    lastTasks = tasks;
    const cards = tasks.map(task => {
        const card = document.createElement("li");
        card.className = "task-card";
        const header = document.createElement("header");
        const merchant = document.createElement("span");
        merchant.textContent = task.merchantName || "商家名称未提供";
        const reward = document.createElement("span");
        reward.className = "reward";
        const amount = Number(task.deliveryReward);
        reward.textContent = Number.isFinite(amount) ? `¥ ${amount.toFixed(2)}` : "奖励待确认";
        header.append(merchant, reward);
        card.append(header);
        for (const text of [
            `取货：${task.merchantAddress || "地址未提供"}`,
            `送达：${task.receiverAddress || "地址未提供"}`,
        ]) {
            const paragraph = document.createElement("p");
            paragraph.textContent = text;
            card.append(paragraph);
        }
        const meta = document.createElement("p");
        meta.className = "task-meta";
        const metaParts = [`任务 ${task.taskId ?? "—"}`];
        metaParts.push(formatPickupDistance(task.pickupDistanceKm));
        metaParts.push(task.statusDesc || "待接任务");
        meta.textContent = metaParts.join(" · ");
        card.append(meta);
        return card;
    });
    element("task-list").replaceChildren(...cards);
}

function render(state) {
    element("login-panel").hidden = Boolean(session);
    element("rider-workspace").hidden = !session;
    element("rider-name").textContent = session ? `${session.name}，欢迎回来` : "";
    const labels = {idle: "未定位", locating: "正在获取位置", tracking: "持续定位中", stopping: "正在清理位置", "cleanup-error": "待清理位置"};
    element("work-status").textContent = session ? labels[state.phase] : "未登录";
    element("work-status").dataset.active = String(state.phase === "tracking");
    element("start-button").disabled = !session || state.active || state.phase === "stopping" || Boolean(state.cleanupError) || logoutBusy;
    element("stop-button").disabled = !session || state.phase === "stopping" || logoutBusy || (!state.active && !state.cleanupError);
    element("stop-button").textContent = state.cleanupError ? "重试删除位置" : "停止定位";
    element("logout-button").disabled = logoutBusy || state.phase === "stopping";
    element("logout-button").textContent = logoutBusy ? "正在退出…" : "退出登录";
    const canRefresh = session && state.phase === "tracking" && !state.locationError && !state.refreshing;
    element("refresh-button").disabled = !canRefresh;
    element("refresh-button").textContent = state.refreshing ? "加载中…" : "刷新任务";
    element("radius").disabled = !session || state.refreshing;
    element("location-description").textContent = {
        idle: "尚未开始定位。点击开始后，浏览器会请求定位权限。",
        locating: "等待有效位置并上报，成功后会自动刷新任务。",
        tracking: "真实位置已上报，正在持续获取新的位置。",
        stopping: "本地定位已停止，正在等待在途上报结束并删除服务器位置。",
        "cleanup-error": "本地定位已停止，服务器位置尚未确认删除，请重试。",
    }[state.phase];
    element("longitude").textContent = state.position ? state.position.longitude.toFixed(6) : "—";
    element("latitude").textContent = state.position ? state.position.latitude.toFixed(6) : "—";
    const accuracy = state.position?.accuracy;
    element("position-detail").textContent = Number.isFinite(accuracy)
        ? `浏览器报告精度约 ${Math.round(accuracy)} 米` : "等待浏览器提供真实位置";
    element("upload-detail").textContent = state.lastUploadedAt
        ? `最近成功上报：${new Date(state.lastUploadedAt).toLocaleTimeString()}` : "尚未成功上报";
    showMessage("position-error", state.locationError?.message);
    showMessage("upload-error", stageMessage(state.uploadError, "位置上报失败"));
    showMessage("task-error", stageMessage(state.taskError, "任务查询失败"));
    showMessage("cleanup-error", stageMessage(state.cleanupError, "位置删除失败"));
    renderTasks(state.tasks, state);
}

element("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    if (loginBusy) return;
    loginBusy = true;
    element("login-button").disabled = true;
    element("login-button").textContent = "正在登录…";
    showMessage("login-error", "");
    try {
        const result = await api.login({name: element("login-name").value.trim(), password: element("login-password").value});
        if (!result?.token || typeof result.name !== "string") throw new Error("登录返回的数据不完整，请重试");
        element("login-password").value = "";
        setSession({token: result.token, id: result.id, name: result.name});
    } catch (error) {
        showMessage("login-error", error.message || "登录失败，请重试");
    } finally {
        loginBusy = false;
        element("login-button").disabled = false;
        element("login-button").textContent = "登录工作台";
    }
});

element("start-button").addEventListener("click", () => {
    if (session && document.visibilityState !== "hidden") tracker.start();
});
element("stop-button").addEventListener("click", () => void tracker.stop());
element("refresh-button").addEventListener("click", () => {
    if (!element("radius").reportValidity()) return;
    void tracker.refreshTasks();
});
element("radius").addEventListener("change", () => {
    if (element("radius").reportValidity()) void tracker.refreshTasks();
});
element("logout-button").addEventListener("click", async () => {
    if (!session || logoutBusy) return;
    logoutBusy = true;
    render(tracker.getState());
    const deleted = await tracker.stop();
    logoutBusy = false;
    if (deleted || tracker.getState().cleanupError?.kind === "auth") {
        tracker.reset();
        setSession(null);
        if (!deleted) showMessage("login-error", "登录凭证已失效，已退出本页；服务器位置删除未确认，将由位置过期机制兜底。请重新登录。");
    } else render(tracker.getState());
});

function pauseForLifecycle() {
    const state = tracker.getState();
    if (!session || (!state.active && state.phase !== "stopping" && state.phase !== "cleanup-error")) return;
    element("lifecycle-note").textContent = "页面已暂停定位，返回后请重新点击开始。离开页面时的服务器清理尽力执行，建议先主动停止。";
    void tracker.stop({keepalive: true});
}
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") pauseForLifecycle();
});
window.addEventListener("pagehide", pauseForLifecycle);
render(tracker.getState());
