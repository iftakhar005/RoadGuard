const JobMap = (function () {

    const REFETCH_MS = 25000;
    const MOVED_KM = 0.15;

    let map = null;
    let meMarker = null;
    let themMarker = null;
    let line = null;

    let shownJobId = null;
    let routedFrom = null;
    let routedAt = 0;
    let pending = false;

    const el = (id) => document.getElementById(id);

    function icons() {
        return {
            me: L.divIcon({
                className: 'pin-mech-self',
                html: '<span></span>',
                iconSize: [26, 26],
                iconAnchor: [13, 13]
            }),
            them: L.divIcon({
                className: 'pin-driver',
                html: '<span></span>',
                iconSize: [26, 26],
                iconAnchor: [13, 13]
            })
        };
    }

    function build(me, them) {
        const pins = icons();

        map = L.map('job-map', { zoomControl: true }).setView([them.lat, them.lng], 14);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);

        line = L.polyline([], {
            className: 'job-route',
            color: '#2F6BFF',
            weight: 5,
            opacity: 0.85,
            lineJoin: 'round',
            lineCap: 'round'
        }).addTo(map);

        meMarker = L.marker([me.lat, me.lng], { icon: pins.me })
            .addTo(map)
            .bindTooltip('You', { direction: 'top', offset: [0, -14] });

        themMarker = L.marker([them.lat, them.lng], { icon: pins.them })
            .addTo(map)
            .bindTooltip('The breakdown', { direction: 'top', offset: [0, -14] });
    }

    function frame() {
        if (!map || !line) return;
        const points = line.getLatLngs();
        if (points.length > 1) {
            map.fitBounds(L.latLngBounds(points).pad(0.18));
        }
    }

    async function draw(me, them) {
        if (pending) return;
        pending = true;
        try {
            const result = await Route.between(me, them);
            line.setLatLngs(result.line);
            line.setStyle({ dashArray: result.onRoads ? null : '9 10' });

            el('route-summary').textContent = Route.describe(result);
            el('route-note').textContent = result.onRoads
                ? 'Following the road network.'
                : 'Road routing is unreachable, showing the direct line.';

            routedFrom = me;
            routedAt = Date.now();
            frame();
        } finally {
            pending = false;
        }
    }

    function show(job, me) {
        if (typeof L === 'undefined' || !me || me.lat == null) return;

        const them = { lat: job.originLat, lng: job.originLng };
        el('job-map-card').style.display = '';

        if (!map) {
            build(me, them);
            setTimeout(() => map.invalidateSize(), 120);
        }

        meMarker.setLatLng([me.lat, me.lng]);
        themMarker.setLatLng([them.lat, them.lng]);

        const isNewJob = shownJobId !== job.id;
        const moved = routedFrom ? Route.haversineKm(routedFrom, me) : Infinity;
        const stale = Date.now() - routedAt > REFETCH_MS;

        if (isNewJob || moved > MOVED_KM || stale) {
            shownJobId = job.id;
            draw(me, them);
        }
    }

    function hide() {
        const card = el('job-map-card');
        if (card) card.style.display = 'none';
        shownJobId = null;
        routedFrom = null;
        routedAt = 0;
    }

    function refit() {
        if (map) {
            map.invalidateSize();
            frame();
        }
    }

    return { show, hide, refit };
})();
