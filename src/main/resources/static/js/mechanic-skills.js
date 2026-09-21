const SKILLS = [
    ['TIRE', 'Tyres'],
    ['BATTERY', 'Battery'],
    ['ENGINE', 'Engine'],
    ['ELECTRICAL', 'Electrical'],
    ['BRAKES', 'Brakes'],
    ['FUEL', 'Fuel'],
    ['TOWING', 'Towing'],
    ['GENERAL', 'Anything else']
];

const Skills = (function () {
    let saved = [];
    let built = false;

    function elid(id) {
        return document.getElementById(id);
    }

    function picked() {
        return Array.from(elid('skill-chips').querySelectorAll('.chip.is-on'))
            .map((c) => c.dataset.skill);
    }

    function same(a, b) {
        return a.length === b.length && a.every((v) => b.includes(v));
    }

    function refreshStatus() {
        const now = picked();
        const note = elid('skill-status');

        if (!now.length) {
            note.textContent = 'Pick nothing and you will be listed as a general mechanic.';
            note.className = 'shop-status';
            return;
        }
        if (same(now, saved)) {
            note.textContent = now.length === 1
                ? 'You take one kind of job.'
                : `You take ${now.length} kinds of job.`;
            note.className = 'shop-status is-live';
            return;
        }
        note.textContent = 'Not saved yet.';
        note.className = 'shop-status';
    }

    function build() {
        const box = elid('skill-chips');
        box.innerHTML = '';

        SKILLS.forEach(([value, label]) => {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'chip';
            chip.dataset.skill = value;
            chip.textContent = label;
            chip.setAttribute('aria-pressed', 'false');
            chip.addEventListener('click', () => {
                const on = chip.classList.toggle('is-on');
                chip.setAttribute('aria-pressed', String(on));
                refreshStatus();
            });
            box.appendChild(chip);
        });

        elid('skill-save').addEventListener('click', save);
        built = true;
    }

    function show(specializations) {
        if (!built) build();
        saved = (specializations || []).slice();

        elid('skill-chips').querySelectorAll('.chip').forEach((chip) => {
            const on = saved.includes(chip.dataset.skill);
            chip.classList.toggle('is-on', on);
            chip.setAttribute('aria-pressed', String(on));
        });
        refreshStatus();
    }

    async function save() {
        const btn = elid('skill-save');
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner"></span>Saving';
        try {
            const profile = await api('/api/mechanic/skills', {
                method: 'POST',
                body: JSON.stringify({ specializations: picked() })
            });
            show(profile.specializations);
            toast('Skills saved', 'win');
            if (typeof onSkillsSaved === 'function') onSkillsSaved(profile);
        } catch (err) {
            toast(err.message, 'bad');
        } finally {
            btn.disabled = false;
            btn.textContent = 'Save skills';
        }
    }

    return { show };
})();
