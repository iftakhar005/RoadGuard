const TOKEN_KEY = 'roadguard.token';
const USER_KEY = 'roadguard.user';

const Auth = {
    save(authResponse) {
        localStorage.setItem(TOKEN_KEY, authResponse.token);
        localStorage.setItem(USER_KEY, JSON.stringify({
            userId: authResponse.userId,
            username: authResponse.username,
            role: authResponse.role
        }));
    },

    token() {
        return localStorage.getItem(TOKEN_KEY);
    },

    user() {
        const raw = localStorage.getItem(USER_KEY);
        return raw ? JSON.parse(raw) : null;
    },

    clear() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
    },

    homePage(role) {
        if (role === 'DRIVER') return 'driver.html';
        if (role === 'MECHANIC') return 'mechanic.html';
        if (role === 'ADMIN') return 'admin.html';
        return 'index.html';
    }
};

async function api(path, options = {}) {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };

    const token = Auth.token();
    if (token) {
        headers['Authorization'] = 'Bearer ' + token;
    }

    const res = await fetch(path, { ...options, headers });

    if (res.status === 204) return null;

    let body = null;
    const text = await res.text();
    if (text) {
        try {
            body = JSON.parse(text);
        } catch {
            body = { message: text };
        }
    }

    if (!res.ok) {
        throw new Error((body && body.message) || `Request failed (${res.status})`);
    }
    return body;
}

async function requireLogin(expectedRole) {
    if (!Auth.token()) {
        window.location.href = 'index.html';
        return null;
    }
    try {
        const me = await api('/api/auth/me');
        if (expectedRole && me.role !== expectedRole) {
            window.location.href = Auth.homePage(me.role);
            return null;
        }
        return me;
    } catch {
        Auth.clear();
        window.location.href = 'index.html';
        return null;
    }
}

function logout() {
    Auth.clear();
    window.location.href = 'index.html';
}
