(function() {
    const urlParams = new URLSearchParams(window.location.search);
    const requestId = urlParams.get('id');

    if (!requestId) {
        document.getElementById('replay-title').textContent = 'No Request Specified';
        document.getElementById('replay-meta').textContent = 'Please provide a valid request ID, e.g. replay.html?id=1';
        return;
    }

    let request = null;
    let points = [];
    let currentIndex = 0;
    let isPlaying = false;
    let speed = 1;
    let timer = null;

    let map = null;
    let driverMarker = null;
    let mechanicMarker = null;
    let routePolyline = null;
    let routeCoords = [];

    const el = (id) => document.getElementById(id);

    function initMap(originLat, originLng) {
        map = L.map('replay-map').setView([originLat, originLng], 14);

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);

        const driverIcon = L.divIcon({
            className: 'pin-driver',
            html: '<span></span>',
            iconSize: [26, 26],
            iconAnchor: [13, 13]
        });

        driverMarker = L.marker([originLat, originLng], { icon: driverIcon }).addTo(map);
        driverMarker.bindPopup('<strong>Driver SOS Location</strong>');

        const mechIcon = L.divIcon({
            className: 'pin-mech',
            html: '<span></span>',
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        });

        mechanicMarker = L.marker([originLat, originLng], { icon: mechIcon });

        routePolyline = L.polyline([], {
            color: '#2E9E5B',
            weight: 4,
            opacity: 0.85,
            lineJoin: 'round'
        }).addTo(map);
    }

    function formatTime(isoStr) {
        if (!isoStr) return '--:--';
        try {
            const d = new Date(isoStr);
            return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        } catch (e) {
            return isoStr;
        }
    }

    function renderPoint(index) {
        if (!points || index < 0 || index >= points.length) return;
        const pt = points[index];

        el('replay-scrubber').value = index;
        el('time-current').textContent = formatTime(pt.t);

        if (pt.status) {
            const badge = el('replay-badge');
            badge.textContent = pt.status.replace('_', ' ');
            badge.className = 'replay-status-badge status-' + pt.status.toLowerCase();
        }

        const announcement = el('event-announcement');
        const detail = el('event-detail');

        switch (pt.type) {
            case 'CREATED':
                announcement.textContent = 'SOS Request Created';
                detail.textContent = 'Driver reported: ' + (request.issueType || 'Breakdown');
                break;
            case 'OFFER':
                announcement.textContent = 'Broadcasted to Mechanics';
                detail.textContent = 'Searching for closest qualified responders';
                break;
            case 'ACCEPTED':
                announcement.textContent = 'Job Accepted';
                detail.textContent = (request.assignedMechanicName || 'Mechanic') + ' accepted the call';
                break;
            case 'LOCATION':
                announcement.textContent = 'Mechanic En Route';
                detail.textContent = 'Live position update received';
                break;
            case 'EN_ROUTE':
                announcement.textContent = 'Mechanic Heading Over';
                detail.textContent = 'Travelling towards driver coordinates';
                break;
            case 'ARRIVED':
                announcement.textContent = 'Mechanic Arrived';
                detail.textContent = 'On scene with stranded vehicle';
                break;
            case 'IN_PROGRESS':
                announcement.textContent = 'Repairs in Progress';
                detail.textContent = 'Diagnosing and repairing the issue';
                break;
            case 'COMPLETED':
                announcement.textContent = 'Job Completed';
                detail.textContent = 'All repairs done. Vehicle ready.';
                break;
            case 'CANCELLED':
                announcement.textContent = 'Request Cancelled';
                detail.textContent = 'Cancelled before completion';
                break;
            default:
                announcement.textContent = pt.type;
                detail.textContent = 'Status update: ' + (pt.status || pt.type);
                break;
        }

        routeCoords = [];
        for (let i = 0; i <= index; i++) {
            if (points[i].lat != null && points[i].lng != null) {
                routeCoords.push([points[i].lat, points[i].lng]);
            }
        }
        routePolyline.setLatLngs(routeCoords);

        if (pt.lat != null && pt.lng != null) {
            mechanicMarker.setLatLng([pt.lat, pt.lng]);
            if (!map.hasLayer(mechanicMarker)) {
                mechanicMarker.addTo(map);
            }
        }
    }

    function step() {
        if (currentIndex >= points.length - 1) {
            pause();
            return;
        }
        currentIndex++;
        renderPoint(currentIndex);

        const currentT = new Date(points[currentIndex - 1].t).getTime();
        const nextT = new Date(points[currentIndex].t).getTime();
        let delta = nextT - currentT;
        if (isNaN(delta) || delta < 200) delta = 800;
        if (delta > 8000) delta = 8000;

        const interval = Math.max(80, Math.round(delta / speed));
        timer = setTimeout(step, interval);
    }

    function play() {
        if (isPlaying) return;
        if (currentIndex >= points.length - 1) {
            currentIndex = 0;
            renderPoint(0);
        }
        isPlaying = true;
        el('btn-play').textContent = 'Pause';
        el('btn-play').classList.add('is-active');
        step();
    }

    function pause() {
        isPlaying = false;
        if (timer) {
            clearTimeout(timer);
            timer = null;
        }
        el('btn-play').textContent = 'Play';
        el('btn-play').classList.remove('is-active');
    }

    function restart() {
        pause();
        currentIndex = 0;
        renderPoint(0);
        fitRoute();
    }

    /* the map's height comes from the layout, so it has to be measured again before
       fitting, or the route is fitted to a box of no size and zoomed right in */
    function fitRoute() {
        if (!map || !request) return;

        map.invalidateSize();
        const spots = points.filter(p => p.lat != null && p.lng != null).map(p => [p.lat, p.lng]);
        spots.push([request.originLat, request.originLng]);
        map.fitBounds(L.latLngBounds(spots), { padding: [70, 70], maxZoom: 17 });
    }

    async function boot() {
        try {
            request = await api('/api/requests/' + requestId);
            points = await api('/api/requests/' + requestId + '/replay');

            el('replay-title').textContent = 'Request #' + request.id;
            el('replay-meta').innerHTML = `
                <div><strong>Issue:</strong> ${request.issueType ? request.issueType.replace('_', ' ') : 'Breakdown'}</div>
                <div><strong>Mechanic:</strong> ${request.assignedMechanicName || 'Unassigned'}</div>
                <div><strong>Events:</strong> ${points.length} recorded</div>
            `;

            initMap(request.originLat, request.originLng);

            if (points.length > 0) {
                el('replay-scrubber').max = points.length - 1;
                el('time-total').textContent = formatTime(points[points.length - 1].t);
                renderPoint(0);
                fitRoute();
                window.addEventListener('load', fitRoute);
                window.addEventListener('resize', fitRoute);
            } else {
                el('event-announcement').textContent = 'No Events';
                el('event-detail').textContent = 'No replay events found for this request yet.';
            }
        } catch (err) {
            el('replay-title').textContent = 'Error Loading Replay';
            el('replay-meta').textContent = err.message || 'Could not load replay data.';
        }
    }

    el('btn-play').addEventListener('click', () => {
        if (isPlaying) pause();
        else play();
    });

    el('btn-restart').addEventListener('click', restart);

    el('replay-scrubber').addEventListener('input', (e) => {
        pause();
        currentIndex = parseInt(e.target.value, 10);
        renderPoint(currentIndex);
    });

    document.querySelectorAll('.speed-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.speed-btn').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
            speed = parseInt(btn.dataset.speed, 10) || 1;
        });
    });

    boot();
})();
