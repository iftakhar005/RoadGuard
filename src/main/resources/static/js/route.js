const Route = (function () {

    const OSRM = 'https://router.project-osrm.org/route/v1/driving/';
    const CITY_KMH = 22;

    function haversineKm(a, b) {
        const R = 6371.0088;
        const rad = (d) => d * Math.PI / 180;
        const dLat = rad(b.lat - a.lat);
        const dLng = rad(b.lng - a.lng);
        const h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }

    function straightLine(from, to) {
        const km = haversineKm(from, to);
        return {
            line: [[from.lat, from.lng], [to.lat, to.lng]],
            km,
            minutes: (km / CITY_KMH) * 60,
            onRoads: false
        };
    }

    async function between(from, to) {
        try {
            const url = OSRM
                + from.lng + ',' + from.lat + ';' + to.lng + ',' + to.lat
                + '?overview=full&geometries=geojson&alternatives=false&steps=false';

            const res = await fetch(url, { headers: { Accept: 'application/json' } });
            if (!res.ok) throw new Error('router replied ' + res.status);

            const data = await res.json();
            const best = data.routes && data.routes[0];
            if (!best || !best.geometry || !best.geometry.coordinates.length) {
                throw new Error('no road route');
            }

            return {
                line: best.geometry.coordinates.map((c) => [c[1], c[0]]),
                km: best.distance / 1000,
                minutes: best.duration / 60,
                onRoads: true
            };
        } catch (e) {
            return straightLine(from, to);
        }
    }

    function describe(result) {
        const km = result.km < 1
            ? Math.round(result.km * 1000) + ' m'
            : result.km.toFixed(1) + ' km';

        const mins = Math.max(1, Math.round(result.minutes));
        const time = mins < 60
            ? mins + ' min'
            : Math.floor(mins / 60) + ' h ' + (mins % 60) + ' min';

        return km + ' · about ' + time;
    }

    return { between, describe, haversineKm, straightLine };
})();
