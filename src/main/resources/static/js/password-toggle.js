(function () {
    const EYE = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
            stroke-linecap="round" stroke-linejoin="round">
            <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z"/>
            <circle cx="12" cy="12" r="3"/>
        </svg>`;

    const EYE_OFF = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
            stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.6 6.1A9.9 9.9 0 0 1 12 6c6.4 0 10 7 10 7a15.7 15.7 0 0 1-3.2 4"/>
            <path d="M6.6 6.6A15.8 15.8 0 0 0 2 13s3.6 7 10 7a9.8 9.8 0 0 0 4.5-1.05"/>
            <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>
            <line x1="3" y1="3" x2="21" y2="21"/>
        </svg>`;

    function attach(input) {
        if (input.dataset.pwToggle === 'on') {
            return;
        }
        input.dataset.pwToggle = 'on';

        const wrap = document.createElement('div');
        wrap.className = 'pw-wrap';
        input.parentNode.insertBefore(wrap, input);
        wrap.appendChild(input);

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'pw-toggle';
        button.innerHTML = EYE;
        button.setAttribute('aria-label', 'Show password');
        button.setAttribute('aria-pressed', 'false');
        button.title = 'Show password';
        wrap.appendChild(button);

        button.addEventListener('click', () => {
            const showing = input.type === 'text';
            input.type = showing ? 'password' : 'text';
            button.innerHTML = showing ? EYE : EYE_OFF;
            button.setAttribute('aria-pressed', String(!showing));
            const label = showing ? 'Show password' : 'Hide password';
            button.setAttribute('aria-label', label);
            button.title = label;
            input.focus();
        });
    }

    function scan() {
        document.querySelectorAll('input[type="password"]').forEach(attach);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scan);
    } else {
        scan();
    }

    // panels on this page are rendered after load, so pick up new fields too
    const observer = new MutationObserver(scan);
    observer.observe(document.documentElement, { childList: true, subtree: true });
})();
