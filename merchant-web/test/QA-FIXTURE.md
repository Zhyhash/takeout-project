# Local UI QA fixture

This in-memory HTTP service exists only for manual browser QA. It does not modify the application API client or enable a mock mode. Its test accounts and data are disposable; do not reuse real passwords or tokens.

In PowerShell, start the fixture in one terminal:

```powershell
$env:TAKEOUT_UI_QA_FIXTURE = '1'
node test/api-fixture.mjs
```

In a second terminal, start Vite with the fixture as its development proxy target:

```powershell
$env:TAKEOUT_API_TARGET = 'http://127.0.0.1:18080'
node_modules/.bin/vite
```

Open the URL printed by Vite. Sign in with `test_customer`, `test_merchant`, or `test_rider`; the fixture accepts any password that passes the UI's 8–20 character form rule. Fixture credentials and API tokens are hard-coded test values and are never printed by the service.

The fixture starts with two open shops and one closed shop, a paid merchant order, and an in-progress rider task. Customer-created orders can be paid, accepted or rejected by the merchant, prepared into an available rider task, claimed after a real browser location is successfully uploaded, and completed. The preloaded rider task can be viewed and completed without enabling location. The fixture never fabricates GPS coordinates; the browser's normal geolocation permission and a successful location PUT are required for available tasks.

It also supports role registration, per-customer browse history, merchant categories, product creation/editing/version checks, multipart image upload, shelving, session-level restore, shop profile/status updates, and merchant SSE plus unseen/seen queries. Newly registered accounts use their submitted password within fixture memory. Product deletion is recoverable; ordinary category deletion moves products into the default category, whose deletion is rejected. These are manual QA substitutes, not an implementation of production persistence or authorization guarantees.

Order creation requires numeric `longitude` and `latitude`, bounded by ±180 and ±90 respectively, matching the order DTO. Use the checkout form's explicit example-fill button for disposable coordinate data; no device location is collected.

Only `TAKEOUT_UI_QA_FIXTURE=1` starts the service. Importing the fixture or running it without that variable does not bind a port. The fixture listens on `127.0.0.1:18080` and resets its data when restarted. Setting `TAKEOUT_API_TARGET` only in the second terminal routes the Vite development proxy to the fixture; the default application target remains unchanged.

To verify the hall and claim UI without reading device location, use the separate serve-only configuration:

```powershell
$env:TAKEOUT_UI_QA_FIXTURE = '1'
npm run dev -- --config test/vite.qa.config.mjs --host 127.0.0.1
```

It adds an explicit QA banner and substitutes a synthetic Geolocation implementation. Click Start Location to exercise the tracker, PUT, hall, claim and cleanup flows. It does not open the device permission prompt or access GPS. Regular dev and production never load this substitute; the QA configuration refuses production builds. Synthetic coordinates verify frontend behavior only.
