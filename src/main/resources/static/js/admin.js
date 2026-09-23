(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;

    document.getElementById('avatar').textContent = me.username.charAt(0).toUpperCase();
    document.getElementById('who-name').textContent = me.username;
    document.getElementById('logout-btn').addEventListener('click', logout);

    try {
        const overview = await api('/api/admin/overview');
        document.getElementById('metric-active').textContent = overview.activeRequests;
        document.getElementById('metric-online').textContent = overview.onlineMechanics;
        document.getElementById('metric-online-note').textContent = `of ${overview.mechanics} registered`;
        document.getElementById('metric-drivers').textContent = overview.drivers;
        document.getElementById('metric-mechanics').textContent = overview.mechanics;
        document.getElementById('dashboard-updated').textContent = `Updated ${formatTime(overview.generatedAt)}`;
        renderRequests(overview.recentRequests || [], overview.activeRequestLocations || []);
        renderMap(overview.activeRequestLocations || [], overview.mechanicLocations || []);
    } catch (error) {
        document.getElementById('dashboard-updated').textContent = 'Unable to load live data';
        document.getElementById('admin-request-list').innerHTML = '<div class="empty-state is-error">Could not load the admin overview.</div>';
    }
})();

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
            </div>
        </div>`).join('');

    list.querySelectorAll('[data-request-id]').forEach(row => {
        row.addEventListener('click', () => focusRequest(row.dataset.requestId, activeRequests));
    });
}

let adminMap;
const requestMarkers = new Map();

function renderMap(requests, mechanics) {
    const points = [...requests, ...mechanics].filter(point => point.lat != null && point.lng != null);
    if (!points.length) {
        document.getElementById('map-empty').hidden = false;
        return;
    }

    adminMap = L.map('admin-map', { zoomControl: true }).setView([points[0].lat, points[0].lng], 12);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors'
    }).addTo(adminMap);

    const bounds = [];
    requests.forEach(request => {
        if (request.lat == null || request.lng == null) return;
        const marker = L.circleMarker([request.lat, request.lng], {
            radius: 9, color: '#b91c1c', fillColor: '#ef4444', fillOpacity: .9, weight: 3
        }).addTo(adminMap);
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
        }).addTo(adminMap);
        marker.bindPopup(`<strong>${escapeHtml(mechanic.username)}</strong><br>${formatLabel(mechanic.status)}<br>${escapeHtml(mechanic.shopName || 'Mobile mechanic')}`);
        bounds.push([mechanic.lat, mechanic.lng]);
    });

    if (bounds.length > 1) adminMap.fitBounds(bounds, { padding: [28, 28] });
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