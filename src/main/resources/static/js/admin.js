(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;

    document.getElementById('avatar').textContent = me.username.charAt(0).toUpperCase();
    document.getElementById('who-name').textContent = me.username;
    document.getElementById('logout-btn').addEventListener('click', logout);

    await refresh();
    setInterval(refresh, 5000);
    watchTheEngine();
    wireSafeMode();
    loadGateway();
    wireProbe();
})();

async function loadGateway() {
    const sub = document.getElementById('gateway-sub');
    const picker = document.getElementById('probe-mechanic');
    if (!sub || !picker) return;

    try {
        const gateway = await api('/api/admin/gateway');
        sub.textContent = gateway.listening
            ? `Listening on port ${gateway.port}, ${gateway.connected} device${gateway.connected === 1 ? '' : 's'} connected right now.`
            : 'Not listening. Nothing can reach the gateway.';

        picker.innerHTML = (gateway.mechanics || [])
            .map(m => `<option value="${escapeHtml(m.userId)}">${escapeHtml(m.username)} - ${formatLabel(m.status)}</option>`)
            .join('');
        document.getElementById('probe-btn').disabled = !gateway.listening || !picker.options.length;
    } catch (e) {
        sub.textContent = 'Could not read the gateway state.';
    }
}

function wireProbe() {
    const button = document.getElementById('probe-btn');
    if (!button) return;

    button.addEventListener('click', async () => {
        const picker = document.getElementById('probe-mechanic');
        const screen = document.getElementById('transcript');
        const verdict = document.getElementById('transcript-verdict');

        button.disabled = true;
        button.textContent = 'Connecting...';
        screen.hidden = false;
        screen.textContent = '';
        verdict.hidden = true;

        try {
            const result = await api('/api/admin/gateway/probe', {
                method: 'POST',
                body: JSON.stringify({ mechanicUserId: Number(picker.value) || null })
            });
            paintTranscript(result);
            note(result.reached
                ? `Opened a socket to the gateway as ${result.mechanic}`
                : 'Could not reach the gateway');
        } catch (e) {
            screen.textContent = 'The probe failed: ' + e.message;
        } finally {
            button.disabled = false;
            button.textContent = 'Open a connection';
            loadGateway();
        }
    });
}

function paintTranscript(result) {
    const screen = document.getElementById('transcript');
    const verdict = document.getElementById('transcript-verdict');

    screen.innerHTML = (result.lines || []).map(line => {
        const arrow = line.direction === 'sent' ? '&gt;&gt;' : line.direction === 'received' ? '&lt;&lt;' : '  ';
        return `<span class="t-line t-${line.direction}"><span class="t-at">${String(line.atMs).padStart(5)}ms</span> ${arrow} ${escapeHtml(line.text)}</span>`;
    }).join('\n');

    verdict.hidden = false;
    verdict.textContent = result.note || '';
}

async function refresh() {
    try {
        const overview = await api('/api/admin/overview');
        document.getElementById('metric-active').textContent = overview.activeRequests;
        document.getElementById('metric-online').textContent = overview.onlineMechanics;
        document.getElementById('metric-online-note').textContent = `of ${overview.mechanics} registered`;
        document.getElementById('metric-drivers').textContent = overview.drivers;
        document.getElementById('metric-mechanics').textContent = overview.mechanics;
        document.getElementById('dashboard-updated').textContent = `Updated ${formatTime(overview.generatedAt)}`;

        const engine = overview.engine || {};
        document.getElementById('metric-queue').textContent = engine.queueDepth ?? '--';
        document.getElementById('metric-broadcasts').textContent = engine.broadcasts ?? '--';
        paintSafeMode(engine.safeMode !== false);

        renderRequests(overview.recentRequests || [], overview.activeRequestLocations || []);
        renderMap(overview.activeRequestLocations || [], overview.mechanicLocations || []);
    } catch (error) {
        document.getElementById('dashboard-updated').textContent = 'Unable to load live data';
        document.getElementById('admin-request-list').innerHTML = '<div class="empty-state is-error">Could not load the admin overview.</div>';
    }
}

function paintSafeMode(on) {
    const toggle = document.getElementById('safe-toggle');
    if (!toggle || toggle.dataset.busy === '1') return;
    toggle.checked = on;
    document.getElementById('safe-label').textContent = on ? 'Lock on' : 'Lock OFF';
    document.getElementById('engine-warning').hidden = on;
    document.getElementById('engine-sub').textContent = on
        ? 'Every accept is taken under a per-request lock, so exactly one mechanic wins.'
        : 'Accepts are going through without the lock.';
}

function wireSafeMode() {
    const toggle = document.getElementById('safe-toggle');
    if (!toggle) return;

    toggle.addEventListener('change', async () => {
        const wanted = toggle.checked;
        toggle.dataset.busy = '1';
        try {
            const engine = await api('/api/admin/safe-mode', {
                method: 'POST',
                body: JSON.stringify({ enabled: wanted })
            });
            toggle.dataset.busy = '0';
            paintSafeMode(engine.safeMode);
            note(wanted
                ? 'The accept lock is back on'
                : 'The accept lock is OFF - two mechanics can now win the same job');
        } catch (e) {
            toggle.dataset.busy = '0';
            toggle.checked = !wanted;
            note('Could not change the lock: ' + e.message);
        }
    });
}

/* every offer, status change and reaper sweep is already broadcast; listen to it */
function watchTheEngine() {
    if (typeof Live === 'undefined') return;

    Live.onMessage((message) => {
        if (!message || !message.type) return;
        note(describe(message));
        refresh();
    });
    Live.connect(['/topic/admin']);

    const live = document.getElementById('feed-live');
    if (live) live.textContent = 'live';
}

function describe(message) {
    switch (message.type) {
        case 'OFFER': return `Request #${message.id} offered to nearby mechanics`;
        case 'STATUS': return `Request #${message.id} changed state`;
        case 'MECHANIC': return `Mechanic ${message.id} changed availability`;
        default: return message.type + (message.id ? ' #' + message.id : '');
    }
}

function note(text) {
    const feed = document.getElementById('admin-feed');
    if (!feed) return;

    const empty = feed.querySelector('.feed-empty');
    if (empty) empty.remove();

    const line = document.createElement('p');
    line.className = 'feed-line';
    line.innerHTML = `<time>${new Date().toLocaleTimeString()}</time>${escapeHtml(text)}`;
    feed.prepend(line);

    while (feed.children.length > 40) {
        feed.removeChild(feed.lastChild);
    }
}

async function redispatch(id) {
    try {
        await api(`/api/admin/requests/${id}/redispatch`, { method: 'POST' });
        note(`Request #${id} pushed back into the queue`);
        refresh();
    } catch (e) {
        note(`Could not re-dispatch #${id}: ${e.message}`);
    }
}

function renderRequests(requests, activeRequests) {
    const list = document.getElementById('admin-request-list');
    if (!requests.length) {
        list.innerHTML = '<div class="empty-state">No service requests yet.</div>';
        return;
    }

    list.innerHTML = requests.map(request => `
        <div class="admin-request-row" data-request-id="${escapeHtml(request.id)}">
            <div class="request-primary">
                <span class="request-id">#${escapeHtml(request.id)}</span>
                <strong>${formatLabel(request.issueType)}</strong>
                <span class="request-driver">${escapeHtml(request.driverUsername)}</span>
            </div>
            <div class="request-secondary">
                <span class="status-pill status-${request.status.toLowerCase()}">${formatLabel(request.status)}</span>
                <span class="request-time">${formatTime(request.createdAt)}</span>
                ${canRedispatch(request.status)
                    ? `<button class="redispatch-btn" data-redispatch="${escapeHtml(request.id)}"
                               title="Put this job back in the queue">Re-dispatch</button>`
                    : ''}
            </div>
        </div>`).join('');

    list.querySelectorAll('[data-request-id]').forEach(row => {
        row.addEventListener('click', (event) => {
            if (event.target.closest('[data-redispatch]')) return;
            focusRequest(row.dataset.requestId, activeRequests);
        });
    });

    list.querySelectorAll('[data-redispatch]').forEach(button => {
        button.addEventListener('click', () => redispatch(button.dataset.redispatch));
    });
}

function canRedispatch(status) {
    return !['COMPLETED', 'CANCELLED'].includes(status);
}

let adminPins = null;
let adminMap;
const requestMarkers = new Map();

function renderMap(requests, mechanics) {
    const points = [...requests, ...mechanics].filter(point => point.lat != null && point.lng != null);
    if (!points.length) {
        document.getElementById('map-empty').hidden = false;
        return;
    }
    document.getElementById('map-empty').hidden = true;

    /* the dashboard refreshes every few seconds, so build the map once and
       replace only what is drawn on it */
    if (!adminMap) {
        adminMap = L.map('admin-map', { zoomControl: true }).setView([points[0].lat, points[0].lng], 12);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(adminMap);
        adminPins = L.layerGroup().addTo(adminMap);
    }

    adminPins.clearLayers();
    requestMarkers.clear();

    const bounds = [];
    const firstDraw = !adminMap.__drawnOnce;
    requests.forEach(request => {
        if (request.lat == null || request.lng == null) return;
        const marker = L.circleMarker([request.lat, request.lng], {
            radius: 9, color: '#b91c1c', fillColor: '#ef4444', fillOpacity: .9, weight: 3
        }).addTo(adminPins);
        marker.bindPopup(`<strong>Request #${escapeHtml(request.id)}</strong><br>${formatLabel(request.issueType)}<br>${formatLabel(request.status)}<br>Driver: ${escapeHtml(request.driverUsername)}`);
        requestMarkers.set(String(request.id), marker);
        bounds.push([request.lat, request.lng]);
    });

    mechanics.forEach(mechanic => {
        if (mechanic.lat == null || mechanic.lng == null) return;
        const available = mechanic.status === 'ONLINE';
        const marker = L.circleMarker([mechanic.lat, mechanic.lng], {
            radius: 8,
            color: available ? '#047857' : '#b45309',
            fillColor: available ? '#10b981' : '#f59e0b',
            fillOpacity: .9,
            weight: 3
        }).addTo(adminPins);
        marker.bindPopup(`<strong>${escapeHtml(mechanic.username)}</strong><br>${formatLabel(mechanic.status)}<br>${escapeHtml(mechanic.shopName || 'Mobile mechanic')}`);
        bounds.push([mechanic.lat, mechanic.lng]);
    });

    if (firstDraw && bounds.length > 1) {
        adminMap.fitBounds(bounds, { padding: [28, 28] });
    }
    adminMap.__drawnOnce = true;
}

function focusRequest(id, requests) {
    const request = requests.find(item => String(item.id) === String(id));
    const marker = requestMarkers.get(String(id));
    if (!request || !marker || !adminMap) return;
    adminMap.setView([request.lat, request.lng], Math.max(adminMap.getZoom(), 14));
    marker.openPopup();
}

function formatLabel(value) {
    return value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, char => char.toUpperCase());
}

function formatTime(value) {
    return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(value));
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, character => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
    }[character]));
}