const FALLBACK = [23.8103, 90.4125];

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

function coordsBox() {
    return document.getElementById('coords');
}

function showCoords(lat, lng) {
    const box = coordsBox();
    if (box) {
        box.textContent = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    }
}

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

const mechLayer = L.layerGroup().addTo(map);

function locate() {
    const box = coordsBox();
    if (!navigator.geolocation) {
        if (box) box.textContent = 'this browser cannot share location';
        return;
    }
    if (box) box.textContent = 'getting your position…';

    navigator.geolocation.getCurrentPosition(
        (pos) => {
            const lat = pos.coords.latitude;
            const lng = pos.coords.longitude;
            map.setView([lat, lng], 16);
            driverPin.setLatLng([lat, lng]);
            showCoords(lat, lng);
        },
        () => {
            const here = driverPin.getLatLng();
            showCoords(here.lat, here.lng);
        },
        { enableHighAccuracy: true, timeout: 8000 }
    );
}

document.addEventListener('click', (e) => {
    if (e.target.closest('#locate-btn')) {
        locate();
    }
});

window.RoadGuardMap = {
    pinPosition() {
        const p = driverPin.getLatLng();
        return { lat: p.lat, lng: p.lng };
    },
    bindCoords() {
        const p = driverPin.getLatLng();
        showCoords(p.lat, p.lng);
    },
    lockPin(locked) {
        if (locked) {
            driverPin.dragging.disable();
            driverPin.unbindTooltip();
            driverPin.bindTooltip('Where you broke down', { direction: 'top', offset: [0, -14] });
        } else {
            driverPin.dragging.enable();
        }
    },
    showMechanics(points) {
        mechLayer.clearLayers();
        (points || []).forEach((m) => {
            L.marker([m.lat, m.lng], { icon: mechanicIcon })
                .addTo(mechLayer)
                .bindTooltip(m.name || 'mechanic', { direction: 'top', offset: [0, -12] });
        });
    }
};

const mapEl = document.getElementById('map');
if (window.ResizeObserver) {
    new ResizeObserver(() => map.invalidateSize()).observe(mapEl);
}
window.addEventListener('load', () => map.invalidateSize());
map.whenReady(() => setTimeout(() => map.invalidateSize(), 100));

locate();
