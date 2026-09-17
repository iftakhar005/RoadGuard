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

function busy(button, isBusy, idleText) {
    button.disabled = isBusy;
    button.innerHTML = isBusy
        ? '<span class="spinner"></span>Just a moment'
        : idleText;
}

(async function skipIfAlreadyIn() {
    if (!Auth.token()) return;
    try {
        const me = await api('/api/auth/me');
        window.location.href = Auth.homePage(me.role);
    } catch {
        Auth.clear();
    }
})();

const seg = document.querySelector('.seg');
const headTitle = document.getElementById('head-title');
const headSub = document.getElementById('head-sub');
const carPill = document.getElementById('auth-car-pill');
const carStatus = document.getElementById('auth-car-status');

const HEADINGS = {
    login: ['Welcome back', 'Sign in to pick up where you left off.'],
    register: ['Create your account', 'Takes about thirty seconds.'],
    forgot: ['Reset password', 'Follow the steps to recover your account.']
};

function switchTo(target) {
    clearNotice();
    const loginForm = document.getElementById('login-form');
    const registerForm = document.getElementById('register-form');
    const forgotPane = document.getElementById('forgot-pane');

    if (target === 'forgot') {
        seg.style.display = 'none';
        loginForm.classList.remove('is-active');
        registerForm.classList.remove('is-active');
        forgotPane.classList.add('is-active');
        showForgotStep(1);
    } else {
        seg.style.display = 'grid';
        seg.dataset.active = target;
        document.querySelectorAll('.seg-btn').forEach(b =>
            b.classList.toggle('is-active', b.dataset.pane === target));

        loginForm.classList.toggle('is-active', target === 'login');
        registerForm.classList.toggle('is-active', target === 'register');
        forgotPane.classList.remove('is-active');
    }

    headTitle.textContent = HEADINGS[target][0];
    headSub.textContent = HEADINGS[target][1];

    if (carStatus) {
        if (target === 'forgot') carStatus.textContent = 'Account Recovery Active';
        else if (target === 'login') carStatus.textContent = 'Emergency Dispatch Active';
        else {
            const isMech = document.querySelector('input[name="role"]:checked')?.value === 'MECHANIC';
            carStatus.textContent = isMech ? 'Mechanic Fleet Onboarding' : 'Driver Account Setup';
        }
    }
}

document.querySelectorAll('.seg-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        switchTo(btn.dataset.pane);
    });
});

const chips = document.getElementById('chips');
SPECIALIZATIONS.forEach(skill => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.dataset.skill = skill;
    chip.setAttribute('aria-pressed', 'false');

    chip.textContent = skill.charAt(0) + skill.slice(1).toLowerCase();
    chip.addEventListener('click', () => {
        const on = chip.classList.toggle('is-on');
        chip.setAttribute('aria-pressed', String(on));
    });
    chips.appendChild(chip);
});

const skillsBlock = document.getElementById('skills-block');

document.querySelectorAll('input[name="role"]').forEach(radio => {
    radio.addEventListener('change', () => {
        const isMech = radio.value === 'MECHANIC' && radio.checked;
        skillsBlock.classList.toggle('is-open', isMech);
        if (carStatus) {
            carStatus.textContent = isMech ? 'Mechanic Fleet Onboarding' : 'Driver Account Setup';
        }
    });
});

document.querySelectorAll('input').forEach(input => {
    input.addEventListener('focus', () => {
        if (carPill) carPill.classList.add('is-active');
    });
    input.addEventListener('blur', () => {
        if (carPill) carPill.classList.remove('is-active');
    });
});

// ============================================================
// MEDIA MODE CONTROLLER (AI Video vs Vector Animation)
// ============================================================
const btnShowVideo = document.getElementById('btnShowVideo');
const btnShowAnimation = document.getElementById('btnShowAnimation');
const heroVideoPlayer = document.getElementById('heroVideoPlayer');
const storyTimeline = document.getElementById('storyTimeline');

function setMediaMode(mode) {
    if (!roadScene) return;
    roadScene.dataset.mediaMode = mode;

    if (btnShowVideo) btnShowVideo.classList.toggle('is-active', mode === 'video');
    if (btnShowAnimation) btnShowAnimation.classList.toggle('is-active', mode === 'animation');

    if (mode === 'video') {
        if (heroVideoPlayer) {
            heroVideoPlayer.play().catch(() => {});
        }
    } else {
        if (heroVideoPlayer) {
            heroVideoPlayer.pause();
        }
    }
}

if (btnShowVideo && btnShowAnimation) {
    btnShowVideo.addEventListener('click', () => setMediaMode('video'));
    btnShowAnimation.addEventListener('click', () => setMediaMode('animation'));
}

// ============================================================
// STORY TIMELINE CONTROLLER (Cruising -> Breakdown -> SOS -> Rescued)
// ============================================================
const roadScene = document.getElementById('roadScene');
const storyBadgeIcon = document.getElementById('storyBadgeIcon');
const storyBadgeText = document.getElementById('storyBadgeText');
const storyStepBtns = document.querySelectorAll('.story-step-btn');

const STORY_STEPS = [
    { num: 1, icon: '🚗', label: 'Cruising on Highway', duration: 3800 },
    { num: 2, icon: '⚠️', label: 'Engine Breakdown! Stopped', duration: 3300 },
    { num: 3, icon: '📡', label: 'One-Tap SOS: Finding Mechanics...', duration: 3100 },
    { num: 4, icon: '🛠️', label: 'RoadGuard Arrived — Rescued!', duration: 3800 }
];

let currentStoryIdx = 0;
let storyTimerId = null;
let manualPauseTimer = null;

function renderStoryStep(idx) {
    const step = STORY_STEPS[idx];
    if (!step) return;

    if (storyBadgeIcon) storyBadgeIcon.textContent = step.icon;
    if (storyBadgeText) storyBadgeText.textContent = step.label;

    storyStepBtns.forEach((btn, i) => {
        btn.classList.toggle('is-active', i === idx);
    });
}

function startStoryLoop() {
    clearTimeout(storyTimerId);
    renderStoryStep(currentStoryIdx);

    const stepDuration = STORY_STEPS[currentStoryIdx].duration;
    storyTimerId = setTimeout(() => {
        currentStoryIdx = (currentStoryIdx + 1) % STORY_STEPS.length;
        startStoryLoop();
    }, stepDuration);
}

// Sync timeline buttons with video playback when in video mode
if (heroVideoPlayer) {
    heroVideoPlayer.addEventListener('timeupdate', () => {
        if (roadScene && roadScene.dataset.mediaMode === 'video' && heroVideoPlayer.duration) {
            const progress = heroVideoPlayer.currentTime / heroVideoPlayer.duration;
            const stepIndex = Math.min(3, Math.floor(progress * 4));
            renderStoryStep(stepIndex);
        }
    });
}

if (roadScene && storyStepBtns.length > 0) {
    startStoryLoop();

    // Clickable Scrubber Pills (Works for both AI Video & Vector Scene)
    storyStepBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const stepNum = parseInt(btn.dataset.step, 10);
            const targetIdx = stepNum - 1;
            currentStoryIdx = targetIdx;

            // In Video Mode: Seek video to chapter!
            if (roadScene && roadScene.dataset.mediaMode === 'video' && heroVideoPlayer && heroVideoPlayer.duration) {
                heroVideoPlayer.currentTime = (targetIdx / 4) * heroVideoPlayer.duration;
                heroVideoPlayer.play().catch(() => {});
                renderStoryStep(targetIdx);
                return;
            }

            // In Vector Scene Mode: Apply chapter override
            clearTimeout(storyTimerId);
            clearTimeout(manualPauseTimer);

            roadScene.dataset.activeStep = String(stepNum);
            renderStoryStep(targetIdx);

            manualPauseTimer = setTimeout(() => {
                delete roadScene.dataset.activeStep;
                currentStoryIdx = (targetIdx + 1) % STORY_STEPS.length;
                startStoryLoop();
            }, 7000);
        });
    });
}

const loginForm = document.getElementById('login-form');
const loginBtn = document.getElementById('login-btn');

loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearNotice();

    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;

    if (!email || !password) {
        showError('Enter your email and password.');
        return;
    }

    busy(loginBtn, true);
    try {
        const auth = await api('/api/auth/login', {
            method: 'POST',
            body: JSON.stringify({ email, password })
        });
        Auth.save(auth);
        window.location.href = Auth.homePage(auth.role);
    } catch (err) {
        showError(err.message);
        busy(loginBtn, false, 'Sign in');
    }
});

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

// --- Forgot Password Flow ---
let currentRecoveryEmail = '';

function showForgotStep(step) {
    const step1 = document.getElementById('forgot-step1-form');
    const step2 = document.getElementById('forgot-step2-form');
    const demoBanner = document.getElementById('demo-code-banner');

    if (step === 1) {
        step1.style.display = 'block';
        step2.style.display = 'none';
        demoBanner.style.display = 'none';
    } else {
        step1.style.display = 'none';
        step2.style.display = 'block';
        document.getElementById('sent-email-label').textContent = currentRecoveryEmail;
        document.getElementById('reset-code').value = '';
        document.getElementById('reset-new-password').value = '';
    }
}

document.getElementById('to-forgot-btn').addEventListener('click', () => {
    switchTo('forgot');
    const loginEmail = document.getElementById('login-email').value.trim();
    if (loginEmail) {
        document.getElementById('forgot-email').value = loginEmail;
    }
});

document.querySelectorAll('.back-to-login-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        switchTo('login');
    });
});

const forgotStep1Form = document.getElementById('forgot-step1-form');
const forgotSendBtn = document.getElementById('forgot-send-btn');

forgotStep1Form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearNotice();

    const email = document.getElementById('forgot-email').value.trim();
    if (!email || !email.includes('@')) {
        showError('Please enter a valid email address.');
        return;
    }

    busy(forgotSendBtn, true);
    try {
        const res = await api('/api/auth/forgot-password', {
            method: 'POST',
            body: JSON.stringify({ email })
        });
        currentRecoveryEmail = email;
        showForgotStep(2);
        showOk(res.message || 'Verification code sent to your email.');

        const demoBanner = document.getElementById('demo-code-banner');
        if (res.codeHint) {
            demoBanner.style.display = 'block';
            demoBanner.innerHTML = `<strong>Demo Helper:</strong> 6-digit recovery code is <strong style="letter-spacing:1px">${res.codeHint}</strong> (also logged to server console).`;
        }
    } catch (err) {
        showError(err.message);
    } finally {
        busy(forgotSendBtn, false, 'Send recovery code');
    }
});

document.getElementById('resend-code-btn').addEventListener('click', async () => {
    clearNotice();
    if (!currentRecoveryEmail) return;

    try {
        const res = await api('/api/auth/forgot-password', {
            method: 'POST',
            body: JSON.stringify({ email: currentRecoveryEmail })
        });
        showOk('A new verification code has been dispatched.');

        const demoBanner = document.getElementById('demo-code-banner');
        if (res.codeHint) {
            demoBanner.style.display = 'block';
            demoBanner.innerHTML = `<strong>Demo Helper:</strong> New recovery code is <strong style="letter-spacing:1px">${res.codeHint}</strong> (also logged to server console).`;
        }
    } catch (err) {
        showError(err.message);
    }
});

const forgotStep2Form = document.getElementById('forgot-step2-form');
const resetSubmitBtn = document.getElementById('reset-submit-btn');

forgotStep2Form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearNotice();

    const code = document.getElementById('reset-code').value.trim();
    const newPassword = document.getElementById('reset-new-password').value;

    if (!code || code.length !== 6) {
        showError('Please enter the 6-digit verification code.');
        return;
    }
    if (!newPassword || newPassword.length < 6) {
        showError('New password must be at least 6 characters.');
        return;
    }

    busy(resetSubmitBtn, true);
    try {
        const res = await api('/api/auth/reset-password', {
            method: 'POST',
            body: JSON.stringify({
                email: currentRecoveryEmail,
                code,
                newPassword
            })
        });

        switchTo('login');
        document.getElementById('login-email').value = currentRecoveryEmail;
        document.getElementById('login-password').value = '';
        showOk(res.message || 'Password successfully reset! You can now sign in.');
    } catch (err) {
        showError(err.message);
    } finally {
        busy(resetSubmitBtn, false, 'Reset password');
    }
});
