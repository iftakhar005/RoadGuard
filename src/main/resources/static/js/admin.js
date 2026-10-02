(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;

    document.getElementById('avatar').textContent = me.username.charAt(0).toUpperCase();
    document.getElementById('who-name').textContent = me.username;
    document.getElementById('logout-btn').addEventListener('click', logout);

    await refresh();
    setInterval(refresh, 5000);
    setInterval(() => { if (fleetRunning) refreshFleet(); }, 1500);
    watchTheEngine();
    wireSafeMode();
    wireFleet();
})();

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
        paintUnlocated(overview.unlocatedMechanics);
        await refreshFleet();
    } catch (error) {
        document.getElementById('dashboard-updated').textContent = 'Unable to load live data';
        document.getElementById('admin-request-list').innerHTML = '<div class="empty-state is-error">Could not load the admin overview.</div>';
    }
}

let currentSafeMode = true;

function paintSafeMode(on) {
    currentSafeMode = on;
    const toggle = document.getElementById('safe-toggle');
    if (!toggle || toggle.dataset.busy === '1') return;
    toggle.checked = on;
    document.getElementById('safe-label').textContent = on ? 'Lock on' : 'Lock OFF';
    document.getElementById('engine-warning').hidden = on;
    document.getElementById('engine-sub').textContent = on
        ? 'Every accept is taken under a per-request lock, so exactly one mechanic wins.'
        : 'Accepts are going through without the lock.';
}

async function refreshFleet() {
    try {
        const fleet = await api('/api/admin/fleet');
        paintFleet(fleet);
    } catch (e) {
        /* quiet fallback */
    }
}

function paintFleet(fleet) {
    if (!fleet) return;
    const startBtn = document.getElementById('fleet-start-btn');
    const stopBtn = document.getElementById('fleet-stop-btn');
    const dropBtn = document.getElementById('fleet-drop-btn');
    const countInput = document.getElementById('fleet-count');
    const raceToggle = document.getElementById('fleet-race-toggle');
    const raceLabel = document.getElementById('fleet-race-label');
    const pill = document.getElementById('fleet-running-pill');
    const resultLine = document.getElementById('fleet-result-line');

    if (startBtn) startBtn.disabled = fleet.running;
    if (stopBtn) stopBtn.disabled = !fleet.running;
    if (dropBtn) dropBtn.disabled = !fleet.running;
    if (countInput) countInput.disabled = fleet.running;

    if (raceToggle && raceToggle.dataset.busy !== '1') {
        raceToggle.checked = fleet.raceArmed;
        if (raceLabel) raceLabel.textContent = fleet.raceArmed ? 'Race ON' : 'Race off';
    }

    if (pill) {
        if (fleet.running) {
            pill.textContent = `Running: ${fleet.connected}/${fleet.size} connected`;
            pill.className = 'fleet-pill is-running';
        } else {
            pill.textContent = 'Stopped';
            pill.className = 'fleet-pill';
        }
    }

    paintBoard(fleet);
    paintStory(fleet);
    fleetRunning = !!fleet.running;

    if (resultLine) {
        const outcomes = fleet.outcomes || {};
        const accepted = outcomes['ACCEPTED'] || 0;
        if (accepted === 0) {
            resultLine.textContent = fleet.running ? 'No race run yet.' : 'Fleet idle.';
        } else if (accepted === 1) {
            resultLine.textContent = currentSafeMode
                ? '1 mechanic won the last job (accept lock on).'
                : '1 mechanic won the last job.';
        } else {
            resultLine.textContent = `${accepted} mechanics won the same job! (the accept lock was OFF).`;
        }
    }
}

let fleetRunning = false;

function paintBoard(fleet) {
    const board = document.getElementById('fleet-board');
    if (!board) return;

    const mechanics = fleet.mechanics || [];
    board.hidden = !fleet.running || !mechanics.length;
    if (board.hidden) return;

    board.innerHTML = mechanics.map(m => `
        <div class="fleet-chip is-${escapeHtml(m.state.toLowerCase())}">
            <span class="chip-name">${escapeHtml(m.name.replace('sim_mech_', 'Mechanic '))}</span>
            <span class="chip-doing">${escapeHtml(m.doing || '')}</span>
        </div>`).join('');
}

function paintStory(fleet) {
    const story = document.getElementById('fleet-story');
    const legend = document.getElementById('fleet-legend');
    if (!story) return;

    const lines = fleet.story || [];
    const show = lines.length > 0;
    story.hidden = !show;
    if (legend) legend.hidden = !show;
    if (!show) return;

    story.innerHTML = lines.map(line => `
        <p class="story-line">
            <time>${escapeHtml(line.at)}</time>
            <span class="story-text"><b>${escapeHtml(line.who.replace('sim_mech_', 'Mechanic '))}</b> ${escapeHtml(line.text)}</span>
            ${line.wire ? `<code class="wire">${escapeHtml(line.wire)}</code>` : ''}
        </p>`).join('');
}

function wireFleet() {
    const startBtn = document.getElementById('fleet-start-btn');
    const stopBtn = document.getElementById('fleet-stop-btn');
    const dropBtn = document.getElementById('fleet-drop-btn');
    const countInput = document.getElementById('fleet-count');
    const raceToggle = document.getElementById('fleet-race-toggle');

    if (startBtn) {
        startBtn.addEventListener('click', async () => {
            const count = parseInt(countInput.value, 10) || 8;
            startBtn.disabled = true;
            try {
                const res = await api('/api/admin/fleet/start', {
                    method: 'POST',
                    body: JSON.stringify({ count })
                });
                paintFleet(res);
                note(`Started simulated fleet with ${count} mechanics`);
            } catch (e) {
                note('Could not start fleet: ' + e.message);
                startBtn.disabled = false;
            }
        });
    }

    if (stopBtn) {
        stopBtn.addEventListener('click', async () => {
            stopBtn.disabled = true;
            try {
                const res = await api('/api/admin/fleet/stop', { method: 'POST' });
                paintFleet(res);
                note('Stopped simulated fleet');
            } catch (e) {
                note('Could not stop fleet: ' + e.message);
                stopBtn.disabled = false;
            }
        });
    }

    if (raceToggle) {
        raceToggle.addEventListener('change', async () => {
            const wanted = raceToggle.checked;
            raceToggle.dataset.busy = '1';
            try {
                const res = await api('/api/admin/fleet/race', {
                    method: 'POST',
                    body: JSON.stringify({ armed: wanted })
                });
                raceToggle.dataset.busy = '0';
                paintFleet(res);
                note(wanted
                    ? 'Race armed: every offered mechanic accepts at the same instant'
                    : 'Race disarmed: mechanics think before accepting');
            } catch (e) {
                raceToggle.dataset.busy = '0';
                raceToggle.checked = !wanted;
                note('Could not change fleet race mode: ' + e.message);
            }
        });
    }

    if (dropBtn) {
        dropBtn.addEventListener('click', async () => {
            try {
                const res = await api('/api/admin/fleet/drop-winner', { method: 'POST' });
                paintFleet(res);
                note('Dropped assigned winner - waiting for heartbeat reaper to detect disconnect');
            } catch (e) {
                note('Could not drop winner: ' + e.message);
            }
        });
    }
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
                <a href="/replay.html?id=${escapeHtml(request.id)}" class="btn-locate" style="text-decoration:none; padding:4px 8px; font-size:0.75rem; border-radius:4px;" title="Watch incident replay">Replay</a>
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
let adminBounds = [];
let operatorTookOver = false;
const requestMarkers = new Map();

/* the map's height comes from the layout, and a fit made before it is measured is a
   fit to a box of no size, which Leaflet answers with the maximum zoom and a blank
   map. So it is measured first, and fitted again whenever the box settles, until the
   operator has panned or zoomed it themselves. */
function fitAdminMap() {
    if (!adminMap || adminBounds.length < 2 || operatorTookOver) return;
    adminMap.invalidateSize();
    adminMap.fitBounds(adminBounds, { padding: [28, 28], maxZoom: 15 });
}

function watchAdminMapBox(container) {
    ['mousedown', 'wheel', 'touchstart'].forEach(name =>
        container.addEventListener(name, () => { operatorTookOver = true; }, { passive: true }));

    if (typeof ResizeObserver !== 'undefined') {
        new ResizeObserver(() => fitAdminMap()).observe(container);
    }
}

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
        watchAdminMapBox(document.getElementById('admin-map'));
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

        const offline = mechanic.status === 'OFFLINE';
        if (offline && mechanic.username.startsWith('sim_mech_')) return;

        const style = pinStyleFor(mechanic.status);
        const marker = L.circleMarker([mechanic.lat, mechanic.lng], style).addTo(adminPins);
        marker.bindPopup(`<strong>${escapeHtml(mechanic.username)}</strong><br>${formatLabel(mechanic.status)}<br>${escapeHtml(mechanic.shopName || 'Mobile mechanic')}`);
        bounds.push([mechanic.lat, mechanic.lng]);
    });

    adminBounds = bounds;
    if (firstDraw) {
        fitAdminMap();
    }
    adminMap.__drawnOnce = true;
}

function pinStyleFor(status) {
    if (status === 'ONLINE') {
        return { radius: 8, color: '#047857', fillColor: '#10b981', fillOpacity: .9, weight: 3 };
    }
    if (status === 'BUSY') {
        return { radius: 8, color: '#b45309', fillColor: '#f59e0b', fillOpacity: .9, weight: 3 };
    }
    return { radius: 6, color: '#94a3b8', fillColor: '#cbd5e1', fillOpacity: .75, weight: 2 };
}

function paintUnlocated(names) {
    const note = document.getElementById('map-unlocated');
    if (!note) return;

    note.hidden = !names || !names.length;
    if (note.hidden) return;

    note.textContent = `Not on the map, because they have never set a location: ${names.join(', ')}. `
        + 'A mechanic appears once they sign in and share their position.';
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