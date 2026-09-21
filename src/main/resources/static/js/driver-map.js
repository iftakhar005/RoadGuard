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

const shopLayer = L.layerGroup().addTo(map);

const shopIcon = L.divIcon({
    className: 'pin-shop',
    html: '<span></span>',
    iconSize: [30, 30],
    iconAnchor: [15, 15]
});

function shopPopup(shop) {
    const open = shop.status === 'ONLINE';
    const busy = shop.status === 'BUSY';
    const statusText = open ? 'Open now' : (busy ? 'On a job' : 'Closed');
    const statusClass = open ? 'shop-open' : 'shop-shut';
    const rating = shop.ratingCount > 0
        ? shop.avgRating.toFixed(1) + ' / 5 (' + shop.ratingCount + ')'
        : 'No ratings yet';
    const skills = (shop.specializations || []).join(', ') || 'General';
    const safe = (v) => String(v == null ? '' : v)
        .replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

    return `
        <div class="shop-pop">
            ${shop.imageUrl ? `<img src="${safe(shop.imageUrl)}" alt="${safe(shop.shopName)}">` : ''}
            <h4>${safe(shop.shopName)}</h4>
            <span class="shop-pop-type">${safe(shop.shopTypeLabel || shop.shopType || '')}</span>
            <dl>
                <div><dt>Status</dt><dd class="${statusClass}">${statusText}</dd></div>
                <div><dt>Phone</dt><dd>${safe(shop.contactPhone)}</dd></div>
                ${shop.shopAddress ? `<div><dt>Address</dt><dd>${safe(shop.shopAddress)}</dd></div>` : ''}
                <div><dt>Does</dt><dd>${safe(skills)}</dd></div>
                <div><dt>Rating</dt><dd>${safe(rating)}</dd></div>
            </dl>
        </div>`;
}

async function loadShops() {
    try {
        const shops = await api('/api/shops');
        shopLayer.clearLayers();
        (shops || []).forEach((shop) => {
            if (shop.shopLat == null || shop.shopLng == null) return;
            L.marker([shop.shopLat, shop.shopLng], { icon: shopIcon })
                .addTo(shopLayer)
                .bindTooltip(shop.shopName, { direction: 'top', offset: [0, -16] })
                .bindPopup(shopPopup(shop), { maxWidth: 260 });
        });
    } catch (e) {
        /* shops are a nice to have on this screen */
    }
}

window.RoadGuardMap = {
    refreshShops: loadShops,
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

loadShops();
setInterval(loadShops, 30000);
