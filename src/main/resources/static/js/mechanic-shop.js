const SHOP_TYPES = [
    ['GARAGE', 'Full garage'],
    ['TYRE_SHOP', 'Tyre shop'],
    ['BATTERY_SHOP', 'Battery shop'],
    ['MOBILE_MECHANIC', 'Mobile mechanic'],
    ['TOWING_SERVICE', 'Towing service'],
    ['FUEL_DELIVERY', 'Fuel delivery'],
    ['BODY_REPAIR', 'Body repair'],
    ['GENERAL_REPAIR', 'General repair']
];

const Shop = (function () {
    let shop = null;
    let shopPin = null;
    let pendingImage = null;

    function elid(id) {
        return document.getElementById(id);
    }

    function render() {
        const hasShop = shop && shop.shopName;

        elid('shop-name').value = hasShop ? shop.shopName : '';
        elid('shop-phone').value = hasShop && shop.contactPhone ? shop.contactPhone : '';
        elid('shop-address').value = hasShop && shop.shopAddress ? shop.shopAddress : '';

        const select = elid('shop-type');
        select.innerHTML = SHOP_TYPES
            .map(([v, l]) => `<option value="${v}">${l}</option>`).join('');
        if (hasShop && shop.shopType) {
            select.value = shop.shopType;
        }

        const preview = elid('shop-image-preview');
        if (pendingImage) {
            preview.style.backgroundImage = `url(${pendingImage})`;
            preview.classList.add('has-image');
        } else if (hasShop && shop.imageUrl) {
            preview.style.backgroundImage = `url(${shop.imageUrl}?t=${Date.now()})`;
            preview.classList.add('has-image');
        } else {
            preview.style.backgroundImage = '';
            preview.classList.remove('has-image');
        }

        elid('shop-status').textContent = hasShop
            ? 'Saved. Drivers can see your shop on the map.'
            : 'Not set up yet.';
        elid('shop-status').className = 'shop-status' + (hasShop ? ' is-live' : '');
    }

    function showCoords(lat, lng) {
        elid('shop-coords').textContent = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    }

    function placePin(map, lat, lng, placed) {
        const icon = L.divIcon({
            className: 'pin-shop',
            html: '<span></span>',
            iconSize: [30, 30],
            iconAnchor: [15, 15]
        });

        if (shopPin) {
            shopPin.setLatLng([lat, lng]);
        } else {
            shopPin = L.marker([lat, lng], { draggable: true, icon })
                .addTo(map)
                .bindTooltip(placed ? 'Your shop' : 'Your shop - put it where the shop is',
                    { direction: 'top', offset: [0, -16] });
            PinPicker.add('shop', shopPin, showCoords);
        }
        showCoords(lat, lng);
    }

    async function load(map, fallback) {
        try {
            shop = await api('/api/mechanic/shop');
        } catch (e) {
            shop = null;
        }
        render();

        const placed = shop && shop.shopLat != null && shop.shopLng != null;
        const lat = placed ? shop.shopLat : fallback[0] + 0.0014;
        const lng = placed ? shop.shopLng : fallback[1] + 0.0014;
        placePin(map, lat, lng, placed);
    }

    async function save() {
        const btn = elid('shop-save');
        const pin = shopPin ? shopPin.getLatLng() : null;
        if (!pin) {
            toast('Place your shop on the map first', 'bad');
            return;
        }

        btn.disabled = true;
        btn.innerHTML = '<span class="spinner"></span>Saving';
        try {
            shop = await api('/api/mechanic/shop', {
                method: 'POST',
                body: JSON.stringify({
                    shopName: elid('shop-name').value.trim(),
                    shopType: elid('shop-type').value,
                    contactPhone: elid('shop-phone').value.trim(),
                    shopAddress: elid('shop-address').value.trim() || null,
                    shopLat: pin.lat,
                    shopLng: pin.lng
                })
            });

            const file = elid('shop-image').files[0];
            if (file) {
                const form = new FormData();
                form.append('file', file);
                const res = await fetch('/api/mechanic/shop/image', {
                    method: 'POST',
                    headers: { Authorization: 'Bearer ' + Auth.token() },
                    body: form
                });
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.message || 'Could not upload the picture');
                }
                shop = await res.json();
                elid('shop-image').value = '';
                pendingImage = null;
            }

            toast('Shop saved', 'win');
            if (shopPin) shopPin.setTooltipContent('Your shop');
            render();
        } catch (err) {
            toast(err.message, 'bad');
        } finally {
            btn.disabled = false;
            btn.textContent = 'Save shop';
        }
    }

    function bind() {
        elid('shop-save').addEventListener('click', save);

        elid('shop-image').addEventListener('change', (e) => {
            const file = e.target.files[0];
            if (!file) return;
            if (file.size > 5 * 1024 * 1024) {
                toast('That picture is larger than 5 MB', 'bad');
                e.target.value = '';
                return;
            }
            const reader = new FileReader();
            reader.onload = () => {
                pendingImage = reader.result;
                render();
            };
            reader.readAsDataURL(file);
        });
    }

    return { load, bind };
})();
