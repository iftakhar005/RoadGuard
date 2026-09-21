const PinPicker = (function () {

    const NOMINATIM = 'https://nominatim.openstreetmap.org/search';
    const NEAR_BANGLADESH = '88.0,26.7,92.7,20.5';

    let map = null;
    let active = null;
    let inFlight = null;
    let lastSearchAt = 0;
    const pins = {};

    const el = (id) => document.getElementById(id);

    function say(message, kind) {
        if (typeof toast === 'function') {
            toast(message, kind);
            return;
        }
        const hint = el('place-note');
        if (hint) hint.textContent = message;
    }

    function writeBoxes(lat, lng) {
        const latBox = el('loc-lat');
        const lngBox = el('loc-lng');
        if (latBox) latBox.value = lat.toFixed(6);
        if (lngBox) lngBox.value = lng.toFixed(6);
    }

    function announce(key, settled) {
        const p = pins[key].marker.getLatLng();
        if (pins[key].onMove) pins[key].onMove(p.lat, p.lng, settled);
        if (key === active) writeBoxes(p.lat, p.lng);
    }

    function select(key) {
        if (!pins[key]) return;
        active = key;

        document.querySelectorAll('.loc-tab').forEach((b) =>
            b.classList.toggle('is-on', b.dataset.target === key));

        Object.keys(pins).forEach((k) =>
            pins[k].marker.setZIndexOffset(k === key ? 800 : 0));

        const p = pins[key].marker.getLatLng();
        writeBoxes(p.lat, p.lng);
    }

    function add(key, marker, onMove) {
        pins[key] = { marker, onMove };
        marker.on('drag', () => announce(key, false));
        marker.on('dragend', () => announce(key, true));
        marker.on('mousedown', () => select(key));
        if (!active) select(key);
    }

    function moveActive(lat, lng, zoom) {
        if (!active) return;
        const marker = pins[active].marker;
        if (marker.dragging && !marker.dragging.enabled()) {
            map.setView([lat, lng], zoom || map.getZoom());
            say('This pin is locked while the job is running', 'bad');
            return;
        }
        marker.setLatLng([lat, lng]);
        map.setView([lat, lng], zoom || Math.max(map.getZoom(), 15));
        announce(active, true);
    }

    function closeResults() {
        const box = el('place-results');
        if (box) {
            box.hidden = true;
            box.innerHTML = '';
        }
    }

    function showSearching() {
        const box = el('place-results');
        if (!box) return;
        box.innerHTML = '';
        const note = document.createElement('p');
        note.className = 'place-none';
        note.textContent = 'Searching…';
        box.appendChild(note);
        box.hidden = false;
    }

    function showResults(items) {
        const box = el('place-results');
        if (!box) return;
        box.innerHTML = '';

        if (!items.length) {
            const none = document.createElement('p');
            none.className = 'place-none';
            none.textContent = 'Nothing found with that name. Try a bigger area, or type the coordinates.';
            box.appendChild(none);
            box.hidden = false;
            return;
        }

        items.forEach((item) => {
            const row = document.createElement('button');
            row.type = 'button';
            row.className = 'place-row';

            const parts = String(item.display_name).split(', ');
            const head = document.createElement('strong');
            head.textContent = parts.shift();
            const rest = document.createElement('span');
            rest.textContent = parts.join(', ');

            row.appendChild(head);
            row.appendChild(rest);
            row.addEventListener('click', () => {
                moveActive(parseFloat(item.lat), parseFloat(item.lon), 16);
                closeResults();
                const input = el('place-search');
                if (input) input.value = parts.length ? head.textContent : item.display_name;
            });
            box.appendChild(row);
        });

        box.hidden = false;
    }

    async function search() {
        const input = el('place-search');
        if (!input) return;
        const q = input.value.trim();

        if (q.length < 3) {
            say('Type at least three letters to search', 'bad');
            return;
        }

        if (inFlight) inFlight.abort();
        inFlight = new AbortController();

        const btn = el('place-search-btn');
        if (btn) btn.disabled = true;
        showSearching();

        try {
            const gap = Date.now() - lastSearchAt;
            if (gap < 1100) {
                await new Promise((r) => setTimeout(r, 1100 - gap));
            }
            lastSearchAt = Date.now();

            const url = NOMINATIM
                + '?format=jsonv2&limit=6&addressdetails=0'
                + '&viewbox=' + NEAR_BANGLADESH
                + '&q=' + encodeURIComponent(q);

            const res = await fetch(url, {
                headers: { Accept: 'application/json' },
                signal: inFlight.signal
            });
            if (!res.ok) throw new Error('search is not answering');
            showResults(await res.json());
        } catch (e) {
            if (e.name === 'AbortError') return;
            closeResults();
            say('Could not search right now, type the coordinates instead', 'bad');
        } finally {
            inFlight = null;
            if (btn) btn.disabled = false;
        }
    }

    function applyTyped() {
        const lat = parseFloat((el('loc-lat').value || '').trim());
        const lng = parseFloat((el('loc-lng').value || '').trim());

        if (!isFinite(lat) || !isFinite(lng)) {
            say('Those do not look like coordinates', 'bad');
            return;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            say('Latitude is -90 to 90 and longitude is -180 to 180', 'bad');
            return;
        }
        moveActive(lat, lng, 16);
        say('Pin moved', 'win');
    }

    function useGps() {
        if (!navigator.geolocation) {
            say('This browser cannot share a location', 'bad');
            return;
        }
        const btn = el('loc-gps');
        if (btn) btn.disabled = true;

        navigator.geolocation.getCurrentPosition(
            (pos) => {
                moveActive(pos.coords.latitude, pos.coords.longitude, 16);
                say('Moved to where you are', 'win');
                if (btn) btn.disabled = false;
            },
            () => {
                say('Location access was refused, place the pin by hand', 'bad');
                if (btn) btn.disabled = false;
            },
            { enableHighAccuracy: true, timeout: 8000 }
        );
    }

    function bindControls() {
        document.querySelectorAll('.loc-tab').forEach((b) =>
            b.addEventListener('click', () => {
                select(b.dataset.target);
                const p = pins[b.dataset.target];
                if (p) map.panTo(p.marker.getLatLng());
            }));

        const input = el('place-search');
        if (input) {
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    search();
                }
                if (e.key === 'Escape') closeResults();
            });
        }

        const btn = el('place-search-btn');
        if (btn) btn.addEventListener('click', search);

        const apply = el('loc-apply');
        if (apply) apply.addEventListener('click', applyTyped);

        const gps = el('loc-gps');
        if (gps) gps.addEventListener('click', useGps);

        ['loc-lat', 'loc-lng'].forEach((id) => {
            const box = el(id);
            if (box) box.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    applyTyped();
                }
            });
        });

        if (active) {
            const p = pins[active].marker.getLatLng();
            writeBoxes(p.lat, p.lng);
        }
    }

    let mounted = false;

    function mount(leafletMap) {
        map = leafletMap;
        bindControls();

        if (mounted) return;
        mounted = true;
        document.addEventListener('click', (e) => {
            if (!e.target.closest('.loc-search')) closeResults();
        });
    }

    return { mount, bindControls, add, select };
})();
