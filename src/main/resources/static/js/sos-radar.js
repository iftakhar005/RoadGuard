const SosRadar = (function () {

    const PING_COUNT = 3;
    const PING_MS = 2600;
    const GROW_EASE = 0.06;
    const FRAME_MS = 33;

    let map = null;
    let layer = null;
    let boundary = null;
    let fill = null;
    const pings = [];

    let centre = null;
    let shownM = 0;
    let targetM = 0;
    let timer = null;
    let startedAt = 0;

    function attach(leafletMap) {
        map = leafletMap;
    }

    function build() {
        layer = L.layerGroup().addTo(map);

        fill = L.circle(centre, {
            radius: 0,
            className: 'sos-fill',
            stroke: false,
            fillColor: '#E5484D',
            fillOpacity: 0.07,
            interactive: false
        }).addTo(layer);

        boundary = L.circle(centre, {
            radius: 0,
            className: 'sos-boundary',
            color: '#E5484D',
            weight: 2,
            fill: false,
            interactive: false
        }).addTo(layer);

        for (let i = 0; i < PING_COUNT; i++) {
            pings.push(L.circle(centre, {
                radius: 0,
                className: 'sos-ping',
                color: '#E5484D',
                weight: 2,
                fill: false,
                interactive: false
            }).addTo(layer));
        }
    }

    function frame() {
        if (!layer) return;

        shownM += (targetM - shownM) * GROW_EASE;
        if (Math.abs(targetM - shownM) < 1) {
            shownM = targetM;
        }

        fill.setRadius(shownM);
        boundary.setRadius(shownM);

        const elapsed = Date.now() - startedAt;
        for (let i = 0; i < pings.length; i++) {
            const phase = ((elapsed / PING_MS) + (i / pings.length)) % 1;
            pings[i].setRadius(shownM * (0.06 + 0.94 * phase));
            pings[i].setStyle({ opacity: 0.55 * (1 - phase) });
        }
    }

    function show(lat, lng, radiusKm) {
        if (!map || typeof L === 'undefined') return;

        const next = [lat, lng];
        centre = next;
        targetM = radiusKm * 1000;

        if (!layer) {
            build();
            shownM = targetM * 0.25;
            startedAt = Date.now();
        } else {
            fill.setLatLng(next);
            boundary.setLatLng(next);
            pings.forEach((p) => p.setLatLng(next));
        }

        if (!timer) {
            timer = setInterval(frame, FRAME_MS);
        }
    }

    function hide() {
        if (timer) {
            clearInterval(timer);
            timer = null;
        }
        if (layer) {
            map.removeLayer(layer);
            layer = null;
            boundary = null;
            fill = null;
            pings.length = 0;
        }
        shownM = 0;
        targetM = 0;
    }

    function fit() {
        if (!map || !centre || !targetM) return;

        const options = {
            paddingTopLeft: [40, 40],
            paddingBottomRight: [40, 40]
        };

        const panel = document.querySelector('.map-panel');
        if (panel) {
            const box = panel.getBoundingClientRect();
            if (box.width > window.innerWidth * 0.7) {
                options.paddingBottomRight = [40, Math.round(box.height) + 40];
            } else {
                options.paddingTopLeft = [Math.round(box.right) + 24, 40];
            }
        }

        map.fitBounds(L.latLng(centre).toBounds(targetM * 2), options);
    }

    return { attach, show, hide, fit };
})();

SosRadar.attach(map);
