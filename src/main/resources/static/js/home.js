// Shared by the three signed-in pages. Each page tells us which role it is for
// via a data-role attribute on <body>, so one script covers all of them.

(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;   // requireLogin already redirected

    document.getElementById('who').textContent = `${me.username} (${me.role})`;

    // Proof the protected call actually worked - these values came back from
    // the server, not from anything stored in the browser.
    const details = document.getElementById('session-details');
    if (details) {
        details.innerHTML = `
            <div class="kv"><span>User ID</span><span>${me.userId}</span></div>
            <div class="kv"><span>Username</span><span>${me.username}</span></div>
            <div class="kv"><span>Role</span><span>${me.role}</span></div>
            <div class="kv"><span>Token</span><span>${Auth.token().slice(0, 24)}...</span></div>`;
    }

    document.getElementById('logout-btn').addEventListener('click', logout);
})();
