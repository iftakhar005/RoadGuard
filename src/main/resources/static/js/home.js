// Shared by the driver, mechanic and admin pages. Each page says which role it
// is for with a data-role attribute on <body>.

(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;   // requireLogin already sent us back to the login screen

    document.getElementById('avatar').textContent = me.username.charAt(0).toUpperCase();
    document.getElementById('who-name').textContent = me.username;

    // These came back from a protected endpoint, not from anything the browser
    // had lying around - which is the point worth showing.
    const rows = document.getElementById('session-rows');
    if (rows) {
        rows.innerHTML = `
            <div class="row"><span class="k">User ID</span><span class="v">${me.userId}</span></div>
            <div class="row"><span class="k">Username</span><span class="v">${me.username}</span></div>
            <div class="row"><span class="k">Role</span><span class="v">${me.role}</span></div>
            <div class="row"><span class="k">Token</span><span class="v">${Auth.token().slice(0, 28)}…</span></div>`;
    }

    document.getElementById('logout-btn').addEventListener('click', logout);
})();
