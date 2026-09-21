const POLL_MS = 15000;
const HEARTBEAT_MS = 5000;

const STEPS = [
    { key: 'EN_ROUTE', label: 'On my way' },
    { key: 'ARRIVED', label: 'Arrived' },
    { key: 'IN_PROGRESS', label: 'Working' },
    { key: 'COMPLETED', label: 'Done' }
];

const ISSUE_LABEL = {
    FLAT_TIRE: 'Flat tyre',
    DEAD_BATTERY: 'Dead battery',
    ENGINE: 'Engine trouble',
    ELECTRICAL: 'Electrical fault',
    BRAKES: 'Brake problem',
    FUEL_EMPTY: 'Out of fuel',
    OVERHEATING: 'Overheating',
    LOCKOUT: 'Locked out',
    TOWING: 'Needs towing',
    OTHER: 'Other problem'
};

const el = (id) => document.getElementById(id);
const state = {
    profile: null,
    offers: [],
    job: null,
    lastPosition: null,
    busy: false
};

function toast(message, kind) {
    const stack = el('toasts');
    const node = document.createElement('div');
    node.className = 'toast' + (kind ? ' ' + kind : '');
    node.textContent = message;
    stack.appendChild(node);
    setTimeout(() => {
        node.classList.add('is-going');
        setTimeout(() => node.remove(), 220);
    }, 3200);
}

function position() {
    return new Promise((resolve) => {
        if (!navigator.geolocation) {
            resolve(state.lastPosition);
            return;
        }
        navigator.geolocation.getCurrentPosition(
            (pos) => {
                state.lastPosition = { lat: pos.coords.latitude, lng: pos.coords.longitude };
                resolve(state.lastPosition);
            },
            () => resolve(state.lastPosition),
            { enableHighAccuracy: true, timeout: 6000 }
        );
    });
}

function renderDuty() {
    const p = state.profile;
    if (!p) return;

    const card = el('duty-card');
    card.classList.toggle('is-online', p.status === 'ONLINE');
    card.classList.toggle('is-busy', p.status === 'BUSY');

    const title = { ONLINE: 'Available for jobs', BUSY: 'On a job', OFFLINE: 'Off duty' }[p.status];
    const sub = {
        ONLINE: 'You will be offered breakdowns near you.',
        BUSY: 'Finish your current job to take another.',
        OFFLINE: 'Go on duty to start receiving jobs.'
    }[p.status];

    el('duty-title').textContent = title;
    el('duty-sub').textContent = sub;

    const toggle = el('duty-toggle');
    toggle.checked = p.status !== 'OFFLINE';
    toggle.disabled = p.status === 'BUSY';
    el('duty-toggle-label').textContent = p.status === 'BUSY' ? 'On a job' : (toggle.checked ? 'On duty' : 'Off duty');

    el('duty-skills').innerHTML = (p.specializations || [])
        .map((s) => `<span class="skill-tag">${s}</span>`).join('');
}

function severityBadge(sev) {
    return `<span class="sev" data-v="${sev}">${sev}</span>`;
}

function renderOffers() {
    const list = el('offer-list');
    const count = el('offer-count');
    const offers = state.offers;

    count.textContent = offers.length;
    count.style.display = offers.length ? '' : 'none';

    if (state.job) {
        list.innerHTML = `<div class="empty">
            <strong>You are on a job</strong>
            <span>New offers will appear once you finish.</span>
        </div>`;
        return;
    }

    if (!offers.length) {
        const offDuty = !state.profile || state.profile.status === 'OFFLINE';
        list.innerHTML = `<div class="empty">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                 stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 2 4 6v6c0 5 3.4 8.6 8 10 4.6-1.4 8-5 8-10V6l-8-4z"/>
            </svg>
            <strong>${offDuty ? 'You are off duty' : 'No jobs right now'}</strong>
            <span>${offDuty ? 'Go on duty to start receiving breakdowns.' : 'Waiting for a nearby breakdown.'}</span>
        </div>`;
        return;
    }

    list.innerHTML = offers.map((o) => {
        const distance = o.distanceKm == null ? '' : `<span>${o.distanceKm.toFixed(1)} km away</span><span>&middot;</span>`;
        const note = o.note ? `<p class="offer-note">${escapeHtml(o.note)}</p>` : '';
        return `
        <article class="offer" data-severity="${o.severity}" data-request="${o.requestId}">
            <div class="offer-top">
                <div>
                    <h3 class="offer-issue">${ISSUE_LABEL[o.issueType] || o.issueType}</h3>
                    <div class="offer-meta">
                        ${distance}
                        <span>needs ${o.requiredSpecialization}</span>
                        <span>&middot;</span>
                        ${severityBadge(o.severity)}
                    </div>
                </div>
            </div>
            ${note}
            <div class="offer-actions">
                <button class="btn btn-accept" data-act="accept"
                        data-request="${o.requestId}" data-token="${o.offerToken}">Accept job</button>
                <button class="btn btn-decline" data-act="decline"
                        data-request="${o.requestId}" data-token="${o.offerToken}">Decline</button>
            </div>
            <div class="countdown"><span style="width:100%"></span></div>
        </article>`;
    }).join('');
}

function renderJob() {
    const wrap = el('job-wrap');
    const job = state.job;

    if (!job) {
        wrap.innerHTML = '';
        wrap.style.display = 'none';
        return;
    }
    wrap.style.display = '';

    const order = ['ACCEPTED', 'EN_ROUTE', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED'];
    const atIndex = order.indexOf(job.status);

    const steps = STEPS.map((s) => {
        const idx = order.indexOf(s.key);
        const cls = idx < atIndex ? 'done' : (idx === atIndex ? 'current' : '');
        return `<div class="step ${cls}">${s.label}</div>`;
    }).join('');

    const next = STEPS.find((s) => order.indexOf(s.key) > atIndex);
    const isLast = next && next.key === 'COMPLETED';

    wrap.innerHTML = `
    <p class="section-label">Current job</p>
    <section class="card job">
        <div class="job-head">
            <div>
                <h2>${ISSUE_LABEL[job.issueType] || job.issueType}</h2>
                <div class="offer-meta">
                    ${severityBadge(job.severity)}
                    <span>&middot;</span>
                    <span>request #${job.id}</span>
                </div>
            </div>
        </div>

        <div class="stepper">${steps}</div>

        <dl class="job-grid">
            <div class="job-cell"><dt>Status</dt><dd>${job.status.replace('_', ' ')}</dd></div>
            <div class="job-cell"><dt>Location</dt><dd>${job.originLat.toFixed(4)}, ${job.originLng.toFixed(4)}</dd></div>
            <div class="job-cell"><dt>Needs</dt><dd>${job.requiredSpecialization}</dd></div>
        </dl>

        ${job.note ? `<p class="offer-note">${escapeHtml(job.note)}</p>` : ''}

        <div class="job-actions" style="margin-top:16px">
            ${next ? `<button class="btn ${isLast ? 'btn-done' : 'btn-step'}"
                        data-act="step" data-status="${next.key}">${next.label}</button>` : ''}
            <a class="btn btn-decline" style="text-decoration:none"
               href="https://www.openstreetmap.org/?mlat=${job.originLat}&mlon=${job.originLng}#map=16/${job.originLat}/${job.originLng}"
               target="_blank" rel="noopener">Open in map</a>
        </div>
    </section>`;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function refresh() {
    try {
        state.profile = await api('/api/mechanic/me');
    } catch (e) {
        return;
    }

    try {
        const mine = await api('/api/requests/mine-assigned');
        state.job = mine && mine.length ? mine[0] : null;
    } catch (e) {
        state.job = null;
    }

    try {
        state.offers = state.job ? [] : await api('/api/mechanic/offers');
    } catch (e) {
        state.offers = [];
    }

    renderDuty();
    renderJob();
    renderOffers();
}

async function setDuty(on) {
    const toggle = el('duty-toggle');
    toggle.disabled = true;
    try {
        const body = { status: on ? 'ONLINE' : 'OFFLINE' };
        if (on) {
            let pos = await position();
            if (!pos && state.profile && state.profile.currentLat != null) {
                pos = { lat: state.profile.currentLat, lng: state.profile.currentLng };
                state.lastPosition = pos;
                toast('Using your last known location', '');
            }
            if (!pos) {
                toast('Allow location access so jobs can reach you', 'bad');
                toggle.checked = false;
                return;
            }
            body.lat = pos.lat;
            body.lng = pos.lng;
        }
        state.profile = await api('/api/mechanic/status', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        toast(on ? 'You are on duty' : 'You are off duty', on ? 'win' : '');
        await refresh();
    } catch (err) {
        toast(err.message, 'bad');
        toggle.checked = !on;
    } finally {
        toggle.disabled = false;
    }
}

async function onAccept(requestId, token, button) {
    if (state.busy) return;
    state.busy = true;
    button.disabled = true;
    button.innerHTML = '<span class="spinner"></span>Taking it';
    try {
        const res = await api(`/api/requests/${requestId}/accept`, {
            method: 'POST',
            body: JSON.stringify({ offerToken: token })
        });
        toast(res.won ? 'The job is yours' : 'Someone got there first', res.won ? 'win' : 'lose');
    } catch (err) {
        const already = /ALREADY_TAKEN|OFFER_EXPIRED/.test(err.message);
        toast(already ? 'Someone got there first' : err.message, already ? 'lose' : 'bad');
    } finally {
        state.busy = false;
        await refresh();
    }
}

async function onDecline(requestId, token, card) {
    card.classList.add('is-going');
    try {
        await api(`/api/requests/${requestId}/decline`, {
            method: 'POST',
            body: JSON.stringify({ offerToken: token })
        });
    } catch (e) {
        /* declining is best effort */
    }
    setTimeout(refresh, 220);
}

async function onStep(status, button) {
    button.disabled = true;
    try {
        await api(`/api/requests/${state.job.id}/status`, {
            method: 'POST',
            body: JSON.stringify({ status })
        });
        if (status === 'COMPLETED') {
            toast('Job finished, you are available again', 'win');
        }
    } catch (err) {
        toast(err.message, 'bad');
    } finally {
        await refresh();
    }
}

document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;

    if (act === 'accept') {
        onAccept(btn.dataset.request, btn.dataset.token, btn);
    } else if (act === 'decline') {
        onDecline(btn.dataset.request, btn.dataset.token, btn.closest('.offer'));
    } else if (act === 'step') {
        onStep(btn.dataset.status, btn);
    }
});

el('duty-toggle').addEventListener('change', (e) => setDuty(e.target.checked));

let saveTimer = null;

function saveMyLocation(lat, lng) {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(async () => {
        try {
            state.profile = await api('/api/mechanic/location', {
                method: 'POST',
                body: JSON.stringify({ lat, lng })
            });
            toast('Location saved', 'win');
        } catch (e) {
            toast(e.message, 'bad');
        }
    }, 400);
}

function setupMap() {
    if (typeof L === 'undefined') return;

    const start = (state.profile && state.profile.currentLat != null)
        ? [state.profile.currentLat, state.profile.currentLng]
        : [23.8103, 90.4125];

    const map = L.map('mech-map', { zoomControl: true }).setView(start, 14);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);

    const pin = L.marker(start, {
        draggable: true,
        icon: L.divIcon({ className: 'pin-mech-self', html: '<span></span>', iconSize: [26, 26], iconAnchor: [13, 13] })
    }).addTo(map).bindTooltip('Where you are - drag to move', { direction: 'top', offset: [0, -14] });

    state.lastPosition = { lat: start[0], lng: start[1] };
    el('mech-coords').textContent = `${start[0].toFixed(5)}, ${start[1].toFixed(5)}`;

    PinPicker.mount(map);
    PinPicker.add('me', pin, (lat, lng, settled) => {
        state.lastPosition = { lat, lng };
        el('mech-coords').textContent = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
        if (settled) saveMyLocation(lat, lng);
    });

    state.map = map;
    state.pin = pin;

    if (typeof Shop !== 'undefined') {
        Shop.bind();
        Shop.load(map, start);
    }

    PinPicker.select('me');

    setTimeout(() => map.invalidateSize(), 200);
}

function onSkillsSaved(profile) {
    state.profile = profile;
    renderDuty();
}

(async function boot() {
    await refresh();
    setupMap();

    if (typeof Skills !== 'undefined' && state.profile) {
        Skills.show(state.profile.specializations);
    }

    Live.onMessage(() => refresh());
    Live.connect();

    setInterval(refresh, POLL_MS);

    setInterval(async () => {
        if (!state.profile || state.profile.status === 'OFFLINE') return;
        try {
            const pos = state.lastPosition;
            if (pos) {
                await api('/api/mechanic/location', {
                    method: 'POST',
                    body: JSON.stringify({ lat: pos.lat, lng: pos.lng })
                });
            } else {
                await api('/api/mechanic/heartbeat', { method: 'POST' });
            }
        } catch (e) {
            /* a missed heartbeat is handled by the server */
        }
    }, HEARTBEAT_MS);
})();
