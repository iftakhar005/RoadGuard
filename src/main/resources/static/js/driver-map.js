// The driver's map: shows where they are, lets them drag a pin to the exact
// breakdown spot, and shows a few nearby mechanics so the map is not empty.
//
// Nothing here talks to our server yet - dragging the pin only updates the
// readout. Sending the location is the SOS feature in the next update. But it
// is a real map with the driver's real position, not a mock-up.

// Dhaka, used only until the browser tells us where the driver actually is.
const FALLBACK = [23.8103, 90.4125];

const coordsEl = document.getElementById('coords');

const map = L.map('map', { zoomControl: true }).setView(FALLBACK, 15);

// The map images themselves - free, from OpenStreetMap. The attribution is
// required by their licence, so leave it in.
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

// ---- the driver's pin ----
// A round orange marker built in CSS, so it matches the rest of the app instead
// of Leaflet's default blue teardrop.
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

// Keep the readout in step with the pin as it is dragged.
driverPin.on('drag', (e) => {
    const { lat, lng } = e.target.getLatLng();
    showCoords(lat, lng);
});

// ---- a few sample mechanics, clearly demo data ----
// Placed at small offsets around the start point purely so the map looks alive.
// These are not real and do not come from the server.
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

// ---- centre on the driver's real position ----
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
            // Denied or unavailable - the fallback view is already shown, so
            // just say so rather than leaving "getting…" forever.
            coordsEl.textContent = FALLBACK[0].toFixed(5) + ', ' + FALLBACK[1].toFixed(5)
                + '  (location off - drag the pin)';
        },
        { enableHighAccuracy: true, timeout: 8000 }
    );
}

document.getElementById('locate-btn').addEventListener('click', locate);

// Try once on load. If the driver blocks it, the fallback map is already there.
locate();

// Leaflet measures its container once at start-up. If the container is still
// settling then - fonts loading, the card growing - it paints for the wrong
// size and half the map is grey. So re-measure whenever the container actually
// changes size, plus once after the page has fully loaded, rather than betting
// on a single fixed delay.
const mapEl = document.getElementById('map');
if (window.ResizeObserver) {
    new ResizeObserver(() => map.invalidateSize()).observe(mapEl);
}
window.addEventListener('load', () => map.invalidateSize());
map.whenReady(() => setTimeout(() => map.invalidateSize(), 100));
