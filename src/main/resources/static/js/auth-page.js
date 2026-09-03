// Drives the login / register screen.

const SPECIALIZATIONS = [
    'TIRE', 'BATTERY', 'ENGINE', 'ELECTRICAL',
    'BRAKES', 'FUEL', 'TOWING', 'GENERAL'
];

const alertBox = document.getElementById('alert');

function showError(message) {
    alertBox.className = 'alert alert-danger';
    alertBox.textContent = message;
}

function showSuccess(message) {
    alertBox.className = 'alert alert-success';
    alertBox.textContent = message;
}

function clearAlert() {
    alertBox.className = 'alert d-none';
    alertBox.textContent = '';
}

// Stops someone double-clicking Sign in and firing two requests.
function busy(button, isBusy, idleText) {
    button.disabled = isBusy;
    button.innerHTML = isBusy
        ? '<span class="spinner-border spinner-border-sm me-2"></span>Please wait'
        : idleText;
}

// ---------- already signed in? ----------
// If a token is still sitting in this browser, skip the form entirely.
(async function skipIfAlreadyIn() {
    if (!Auth.token()) return;
    try {
        const me = await api('/api/auth/me');
        window.location.href = Auth.homePage(me.role);
    } catch {
        Auth.clear();   // token was stale
    }
})();

// ---------- build the skills checkboxes ----------
const skillsList = document.getElementById('skills-list');
SPECIALIZATIONS.forEach(skill => {
    const col = document.createElement('div');
    col.className = 'col-6';
    col.innerHTML = `
        <div class="form-check">
            <input class="form-check-input" type="checkbox" value="${skill}" id="skill-${skill}">
            <label class="form-check-label" for="skill-${skill}">${skill}</label>
        </div>`;
    skillsList.appendChild(col);
});

// Show the skills box only when Mechanic is selected.
const skillsGroup = document.getElementById('skills-group');
document.querySelectorAll('input[name="role"]').forEach(radio => {
    radio.addEventListener('change', () => {
        skillsGroup.classList.toggle('d-none', radio.value !== 'MECHANIC');
    });
});

// ---------- sign in ----------
const loginForm = document.getElementById('login-form');
const loginBtn = document.getElementById('login-btn');

loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearAlert();

    const username = document.getElementById('login-username').value.trim();
    const password = document.getElementById('login-password').value;

    if (!username || !password) {
        showError('Enter your username and password.');
        return;
    }

    busy(loginBtn, true);
    try {
        const auth = await api('/api/auth/login', {
            method: 'POST',
            body: JSON.stringify({ username, password })
        });
        Auth.save(auth);
        window.location.href = Auth.homePage(auth.role);
    } catch (err) {
        showError(err.message);
        busy(loginBtn, false, 'Sign in');
    }
});

// ---------- register ----------
const registerForm = document.getElementById('register-form');
const registerBtn = document.getElementById('register-btn');

registerForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearAlert();

    const role = document.querySelector('input[name="role"]:checked').value;
    const password = document.getElementById('reg-password').value;

    if (password.length < 6) {
        showError('Password needs to be at least 6 characters.');
        return;
    }

    const body = {
        username: document.getElementById('reg-username').value.trim(),
        email: document.getElementById('reg-email').value.trim(),
        password: password,
        role: role,
        phone: document.getElementById('reg-phone').value.trim() || null
    };

    if (role === 'MECHANIC') {
        body.specializations = Array.from(
            document.querySelectorAll('#skills-list input:checked')
        ).map(cb => cb.value);
    }

    busy(registerBtn, true);
    try {
        const auth = await api('/api/auth/register', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        Auth.save(auth);
        showSuccess('Account created. Taking you in...');
        window.location.href = Auth.homePage(auth.role);
    } catch (err) {
        showError(err.message);
        busy(registerBtn, false, 'Create account');
    }
});
