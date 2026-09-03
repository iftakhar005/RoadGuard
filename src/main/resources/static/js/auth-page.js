// Drives the sign-in / register screen.

const SPECIALIZATIONS = [
    'TIRE', 'BATTERY', 'ENGINE', 'ELECTRICAL',
    'BRAKES', 'FUEL', 'TOWING', 'GENERAL'
];

const notice = document.getElementById('notice');

function showError(message) {
    notice.className = 'notice is-shown is-error';
    notice.textContent = message;
}

function showOk(message) {
    notice.className = 'notice is-shown is-ok';
    notice.textContent = message;
}

function clearNotice() {
    notice.className = 'notice';
    notice.textContent = '';
}

// Stops a double click firing two requests.
function busy(button, isBusy, idleText) {
    button.disabled = isBusy;
    button.innerHTML = isBusy
        ? '<span class="spinner"></span>Just a moment'
        : idleText;
}

// ---------- already signed in? ----------
(async function skipIfAlreadyIn() {
    if (!Auth.token()) return;
    try {
        const me = await api('/api/auth/me');
        window.location.href = Auth.homePage(me.role);
    } catch {
        Auth.clear();   // token had expired
    }
})();

// ---------- the sign in / register switch ----------
const seg = document.querySelector('.seg');
const headTitle = document.getElementById('head-title');
const headSub = document.getElementById('head-sub');

const HEADINGS = {
    login: ['Welcome back', 'Sign in to pick up where you left off.'],
    register: ['Create your account', 'Takes about thirty seconds.']
};

document.querySelectorAll('.seg-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const target = btn.dataset.pane;
        clearNotice();

        seg.dataset.active = target;
        document.querySelectorAll('.seg-btn').forEach(b =>
            b.classList.toggle('is-active', b === btn));

        document.getElementById('login-form')
            .classList.toggle('is-active', target === 'login');
        document.getElementById('register-form')
            .classList.toggle('is-active', target === 'register');

        headTitle.textContent = HEADINGS[target][0];
        headSub.textContent = HEADINGS[target][1];
    });
});

// ---------- skill chips ----------
const chips = document.getElementById('chips');
SPECIALIZATIONS.forEach(skill => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.dataset.skill = skill;
    chip.setAttribute('aria-pressed', 'false');
    // TIRE -> Tire, reads better than shouting
    chip.textContent = skill.charAt(0) + skill.slice(1).toLowerCase();
    chip.addEventListener('click', () => {
        const on = chip.classList.toggle('is-on');
        chip.setAttribute('aria-pressed', String(on));
    });
    chips.appendChild(chip);
});

// Skills only make sense for a mechanic.
const skillsBlock = document.getElementById('skills-block');
document.querySelectorAll('input[name="role"]').forEach(radio => {
    radio.addEventListener('change', () => {
        skillsBlock.classList.toggle('is-open', radio.value === 'MECHANIC' && radio.checked);
    });
});

// ---------- sign in ----------
const loginForm = document.getElementById('login-form');
const loginBtn = document.getElementById('login-btn');

loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearNotice();

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
    clearNotice();

    const role = document.querySelector('input[name="role"]:checked').value;
    const password = document.getElementById('reg-password').value;
    const username = document.getElementById('reg-username').value.trim();
    const email = document.getElementById('reg-email').value.trim();

    if (username.length < 3) {
        showError('Username needs at least 3 characters.');
        return;
    }
    if (!email.includes('@')) {
        showError('That email does not look right.');
        return;
    }
    if (password.length < 6) {
        showError('Password needs at least 6 characters.');
        return;
    }

    const body = {
        username,
        email,
        password,
        role,
        phone: document.getElementById('reg-phone').value.trim() || null
    };

    if (role === 'MECHANIC') {
        body.specializations = Array.from(chips.querySelectorAll('.chip.is-on'))
            .map(chip => chip.dataset.skill);
    }

    busy(registerBtn, true);
    try {
        const auth = await api('/api/auth/register', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        Auth.save(auth);
        showOk('Account created. Taking you in...');
        window.location.href = Auth.homePage(auth.role);
    } catch (err) {
        showError(err.message);
        busy(registerBtn, false, 'Create account');
    }
});
