const SOS_POLL_MS = 15000;

const ISSUES = [
    ['FLAT_TIRE', 'Flat tyre'],
    ['DEAD_BATTERY', 'Dead battery'],
    ['ENGINE', 'Engine trouble'],
    ['ELECTRICAL', 'Electrical fault'],
    ['BRAKES', 'Brake problem'],
    ['FUEL_EMPTY', 'Out of fuel'],
    ['OVERHEATING', 'Overheating'],
    ['LOCKOUT', 'Locked out'],
    ['TOWING', 'Needs towing'],
    ['OTHER', 'Something else']
];

const STATUS_COPY = {
    CREATED: ['Sending your request', 'Just a moment.'],
    DIAGNOSING: ['Checking the problem', 'Working out who you need.'],
    SEARCHING: ['Looking for a mechanic', 'Finding people near you.'],
    OFFERED: ['Ringing nearby mechanics', 'The first to accept will come to you.'],
    ACCEPTED: ['A mechanic is coming', 'They have accepted your job.'],
    EN_ROUTE: ['On the way to you', 'Your mechanic is travelling now.'],
    ARRIVED: ['Your mechanic has arrived', 'Look out for them.'],
    IN_PROGRESS: ['Work in progress', 'They are fixing it now.'],
    COMPLETED: ['All done', 'Your job is finished.'],
    REASSIGNING: ['Finding someone else', 'Your mechanic dropped off, we are reassigning.'],
    ESCALATED: ['Still looking', 'Nobody is free nearby. Our team has been alerted.'],
    CANCELLED: ['Cancelled', 'This request was cancelled.']
};

const LIVE = ['CREATED', 'DIAGNOSING', 'SEARCHING', 'OFFERED', 'ACCEPTED',
    'EN_ROUTE', 'ARRIVED', 'IN_PROGRESS', 'REASSIGNING', 'ESCALATED'];

const sosEl = (id) => document.getElementById(id);
let activeRequest = null;
let pollTimer = null;

function sosToast(message, kind) {
    const stack = sosEl('toasts');
    if (!stack) return;
    const node = document.createElement('div');
    node.className = 'toast' + (kind ? ' ' + kind : '');
    node.textContent = message;
    stack.appendChild(node);
    setTimeout(() => {
        node.classList.add('is-going');
        setTimeout(() => node.remove(), 220);
    }, 3200);
}

function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function showForm() {
    sosEl('sos-panel').innerHTML = `
        <h1>Need help?</h1>
        <p class="map-panel-sub">Drag the pin to where you are, then tell us what is wrong.</p>

        <div class="coords">
            <span class="coords-label">Your location</span>
            <span class="coords-val" id="coords">getting your position…</span>
        </div>

        <div class="field" style="margin-top:14px">
            <label for="sos-issue">What is the problem?</label>
            <select id="sos-issue" class="sos-select">
                ${ISSUES.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}
            </select>
        </div>

        <div class="field">
            <label for="sos-note">Anything else <span class="optional">(optional)</span></label>
            <input type="text" id="sos-note" placeholder="e.g. front left tyre, near the flyover">
        </div>

        <div class="field">
            <label>Photo of the problem <span class="optional">(optional)</span></label>
            <label class="shop-image-drop" for="sos-photo">
                <div class="shop-image-preview" id="sos-photo-preview"></div>
                <span class="shop-image-hint" id="sos-photo-hint">
                    Add a photo and we will work out who you need<br>
                    <small>JPG, PNG or WEBP, up to 5 MB</small>
                </span>
            </label>
            <input type="file" id="sos-photo" accept="image/jpeg,image/png,image/webp" capture="environment" hidden>
        </div>

        <div class="map-panel-actions">
            <button class="btn btn-sos" id="sos-send">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
                     stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 9v4M12 17h.01"/>
                    <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/>
                </svg>
                Send SOS
            </button>
            <button class="btn btn-locate" id="locate-btn" type="button">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="3"/>
                    <path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>
                </svg>
                Recentre
            </button>
        </div>`;

    if (typeof SosRadar !== 'undefined') {
        SosRadar.hide();
    }
    if (window.RoadGuardMap) {
        window.RoadGuardMap.hideHelper();
        window.RoadGuardMap.lockPin(false);
        window.RoadGuardMap.bindCoords();
    }
}

function showStatus(req) {
    const [title, sub] = STATUS_COPY[req.status] || [req.status, ''];
    const finished = req.status === 'COMPLETED' || req.status === 'CANCELLED';
    const waiting = ['SEARCHING', 'OFFERED', 'REASSIGNING'].includes(req.status);
    const hunting = waiting || req.status === 'ESCALATED';
    const assigned = req.assignedMechanicName;

    const tracking = req.mechanicLat != null && req.mechanicLng != null;

    sosEl('sos-panel').innerHTML = `
        <div class="sos-live ${waiting ? 'is-waiting' : ''}">
            <span class="sos-live-dot"></span>
            <span>Request #${req.id}</span>
        </div>

        <h1>${esc(title)}</h1>
        <p class="map-panel-sub">${esc(sub)}</p>

        ${assigned ? `
        <div class="assigned">
            <span class="assigned-avatar">${esc(assigned.charAt(0).toUpperCase())}</span>
            <div>
                <strong>${esc(assigned)}</strong>
                <span id="helper-eta">${tracking ? 'working out the route' : 'your mechanic'}</span>
            </div>
            ${tracking ? '<button class="btn btn-locate" id="helper-fit" type="button">Track</button>' : ''}
        </div>` : ''}

        ${req.aiGuidance ? `
        <div class="ai-read">
            <div class="ai-read-head">
                <span class="ai-badge">Photo check</span>
                ${req.aiFaultCategory ? `<strong>${esc(req.aiFaultCategory)}</strong>` : ''}
                <span class="sev" data-v="${esc(req.severity)}">${esc(req.severity)}</span>
            </div>
            <p>${esc(req.aiGuidance)}</p>
        </div>` : ''}

        <dl class="job-grid" style="margin-top:14px">
            <div class="job-cell"><dt>Problem</dt><dd>${esc(req.issueType.replace('_', ' '))}</dd></div>
            <div class="job-cell"><dt>Needs</dt><dd>${esc(req.requiredSpecialization || '—')}</dd></div>
            <div class="job-cell"><dt>Search radius</dt><dd>${req.searchRadiusKm} km</dd></div>
        </dl>

        <div class="map-panel-actions">
            ${finished
                ? `<button class="btn btn-primary" id="sos-new">Send another request</button>`
                : `<button class="btn btn-decline" id="sos-cancel">Cancel request</button>`}
        </div>`;

    if (typeof SosRadar !== 'undefined') {
        if (hunting) {
            SosRadar.show(req.originLat, req.originLng, req.searchRadiusKm);
        } else {
            SosRadar.hide();
        }
    }

    if (window.RoadGuardMap) {
        if (!finished) {
            window.RoadGuardMap.setOrigin(req.originLat, req.originLng);
            window.RoadGuardMap.lockPin(true);
        }
        if (tracking) {
            window.RoadGuardMap.showHelper(
                req.mechanicLat, req.mechanicLng, req.originLat, req.originLng);
        } else {
            window.RoadGuardMap.hideHelper();
        }
    }

    const chatHost = sosEl('driver-chat');
    if (chatHost && typeof RoadGuardChat !== 'undefined') {
        const chatOpen = assigned && !finished;
        chatHost.hidden = !chatOpen;
        if (chatOpen) {
            RoadGuardChat.mount(req.id, 'DRIVER', chatHost, req.assignedMechanicName);
        }
    }
}

async function sendPhoto(requestId, photo) {
    const form = new FormData();
    form.append('file', photo);

    const res = await fetch('/api/requests/' + requestId + '/photo', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + Auth.token() },
        body: form
    });

    if (!res.ok) {
        sosToast('The photo could not be read, searching on the problem you picked', '');
        return await api('/api/requests/' + requestId);
    }
    return await res.json();
}

async function sendSos() {
    const btn = sosEl('sos-send');
    const pin = window.RoadGuardMap ? window.RoadGuardMap.pinPosition() : null;
    if (!pin) {
        sosToast('Could not read the pin position', 'bad');
        return;
    }

    const photo = sosEl('sos-photo') ? sosEl('sos-photo').files[0] : null;

    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span>Sending';
    try {
        let created = await api('/api/requests', {
            method: 'POST',
            body: JSON.stringify({
                issueType: sosEl('sos-issue').value,
                originLat: pin.lat,
                originLng: pin.lng,
                note: sosEl('sos-note').value.trim() || null,
                hasPhoto: !!photo
            })
        });

        if (photo) {
            btn.innerHTML = '<span class="spinner"></span>Checking the photo';
            activeRequest = created;
            showStatus(created);
            created = await sendPhoto(created.id, photo);
        }

        activeRequest = created;
        goLive(created.id);
        sosToast('Help is on the way, looking for a mechanic', 'win');
        showStatus(created);
        if (typeof SosRadar !== 'undefined') {
            setTimeout(() => SosRadar.fit(), 400);
        }
        startPolling();
    } catch (err) {
        sosToast(err.message, 'bad');
        btn.disabled = false;
        btn.textContent = 'Send SOS';
    }
}

async function cancelSos() {
    if (!activeRequest) return;
    const btn = sosEl('sos-cancel');
    btn.disabled = true;
    try {
        await api(`/api/requests/${activeRequest.id}/cancel`, { method: 'POST' });
        sosToast('Request cancelled', '');
        await poll();
    } catch (err) {
        sosToast(err.message, 'bad');
        btn.disabled = false;
    }
}

async function poll() {
    if (!activeRequest) return;
    try {
        const fresh = await api(`/api/requests/${activeRequest.id}`);
        const changed = fresh.status !== activeRequest.status;
        const wasKm = activeRequest.searchRadiusKm;
        activeRequest = fresh;
        showStatus(fresh);

        if (changed && fresh.status === 'ACCEPTED') {
            sosToast('A mechanic accepted your job', 'win');
        }
        if (changed && fresh.status === 'REASSIGNING') {
            sosToast('Your mechanic dropped off, finding another', 'lose');
        }
        if (changed && fresh.status === 'ESCALATED') {
            sosToast('Nobody free even at ' + fresh.searchRadiusKm + ' km, our team has been alerted', 'lose');
        }
        if (fresh.searchRadiusKm > wasKm) {
            sosToast('Nobody free within ' + wasKm + ' km, widening to ' + fresh.searchRadiusKm + ' km', '');
            if (typeof SosRadar !== 'undefined') {
                setTimeout(() => SosRadar.fit(), 900);
            }
        }

        if (!LIVE.includes(fresh.status)) {
            stopPolling();
        }
    } catch (e) {
        /* transient, next tick will retry */
    }
}

function startPolling() {
    stopPolling();
    pollTimer = setInterval(poll, SOS_POLL_MS);
}

function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
}

document.addEventListener('change', (e) => {
    if (!e.target.closest('#sos-photo')) return;

    const file = e.target.files[0];
    const preview = sosEl('sos-photo-preview');
    const hint = sosEl('sos-photo-hint');
    if (!file || !preview) return;

    if (file.size > 5 * 1024 * 1024) {
        sosToast('That photo is larger than 5 MB', 'bad');
        e.target.value = '';
        return;
    }

    const reader = new FileReader();
    reader.onload = () => {
        preview.style.backgroundImage = `url(${reader.result})`;
        preview.classList.add('has-image');
        if (hint) hint.innerHTML = 'Photo ready<br><small>tap to choose a different one</small>';
    };
    reader.readAsDataURL(file);
});

document.addEventListener('click', (e) => {
    if (e.target.closest('#helper-fit')) {
        window.RoadGuardMap.fitHelper();
        return;
    }
    if (e.target.closest('#sos-send')) sendSos();
    else if (e.target.closest('#sos-cancel')) cancelSos();
    else if (e.target.closest('#sos-new')) {
        activeRequest = null;
        stopPolling();
        showForm();
    }
});

function goLive(requestId) {
    Live.subscribeTopic('/topic/request/' + requestId);
}

(async function bootSos() {
    Live.onMessage((msg) => {
        if (msg && (msg.type === 'CHAT_ERROR' || (msg.senderRole && msg.requestId))) return;
        if (msg && msg.type === 'MOVED') {
            if (!activeRequest || msg.requestId !== activeRequest.id) return;
            activeRequest.mechanicLat = msg.lat;
            activeRequest.mechanicLng = msg.lng;
            if (window.RoadGuardMap) {
                window.RoadGuardMap.showHelper(
                    msg.lat, msg.lng, activeRequest.originLat, activeRequest.originLng, true);
            }
            return;
        }
        poll();
    });
    Live.connect();
    try {
        const mine = await api('/api/requests/mine');
        const live = (mine || []).find((r) => LIVE.includes(r.status));
        if (live) {
            activeRequest = live;
            goLive(live.id);
            showStatus(live);
            startPolling();
            return;
        }
    } catch (e) {
        /* fall through to the form */
    }
    showForm();
})();
