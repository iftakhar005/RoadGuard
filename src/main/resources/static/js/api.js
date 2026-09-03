// Everything that talks to the server goes through here, so the token handling
// lives in one place instead of being repeated on every page.

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

    // Where each role goes after logging in.
    homePage(role) {
        if (role === 'DRIVER') return 'driver.html';
        if (role === 'MECHANIC') return 'mechanic.html';
        if (role === 'ADMIN') return 'admin.html';
        return 'index.html';
    }
};

// Thin wrapper over fetch. Attaches the token, and turns the server's error
// JSON into a thrown Error so callers can just try/catch.
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

// Called at the top of every signed-in page. Bounces you back to the login
// screen if there is no token, or if the one we have is stale.
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
