const FALLBACK = [23.8103, 90.4125];

const coordsEl = document.getElementById('coords');

const map = L.map('map', { zoomControl: true }).setView(FALLBACK, 15);

L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

const driverIcon = L.divIcon({
    className: 'pin-driver',
    html: '<span></span>',
    iconSize: [26, 26],
    iconAnchor: [13, 13]
});

const driverPin = L.marker(FALLBACK, { draggable: true, icon: driverIcon })
    .addTo(map)
    .bindTooltip('You are here - drag me', { direction: 'top', offset: [0, -14] });

function showCoords(lat, lng) {
    coordsEl.textContent = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

showCoords(FALLBACK[0], FALLBACK[1]);

driverPin.on('drag', (e) => {
    const { lat, lng } = e.target.getLatLng();
    showCoords(lat, lng);
});

const mechanicIcon = L.divIcon({
    className: 'pin-mech',
    html: '<span></span>',
    iconSize: [20, 20],
    iconAnchor: [10, 10]
});

const sampleMechanics = [
    { name: 'Rafiq (Tire, Battery)', d: [0.004, 0.003] },
    { name: 'Karim (Engine)', d: [-0.005, 0.004] },
    { name: 'Sadia (General)', d: [0.003, -0.006] }
];

let mechLayer = L.layerGroup().addTo(map);

function placeMechanics(centreLat, centreLng) {
    mechLayer.clearLayers();
    sampleMechanics.forEach(m => {
        L.marker([centreLat + m.d[0], centreLng + m.d[1]], { icon: mechanicIcon })
            .addTo(mechLayer)
            .bindTooltip(m.name + ' · demo', { direction: 'top', offset: [0, -12] });
    });
}

placeMechanics(FALLBACK[0], FALLBACK[1]);

function locate() {
    if (!navigator.geolocation) {
        coordsEl.textContent = 'this browser cannot share location';
        return;
    }
    coordsEl.textContent = 'getting your position…';
    navigator.geolocation.getCurrentPosition(
        (pos) => {
            const lat = pos.coords.latitude;
            const lng = pos.coords.longitude;
            map.setView([lat, lng], 16);
            driverPin.setLatLng([lat, lng]);
            placeMechanics(lat, lng);
            showCoords(lat, lng);
        },
        () => {

            coordsEl.textContent = FALLBACK[0].toFixed(5) + ', ' + FALLBACK[1].toFixed(5)
                + '  (location off - drag the pin)';
        },
        { enableHighAccuracy: true, timeout: 8000 }
    );
}

document.getElementById('locate-btn').addEventListener('click', locate);

locate();

const mapEl = document.getElementById('map');
if (window.ResizeObserver) {
    new ResizeObserver(() => map.invalidateSize()).observe(mapEl);
}
window.addEventListener('load', () => map.invalidateSize());
map.whenReady(() => setTimeout(() => map.invalidateSize(), 100));
