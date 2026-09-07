(async function () {
    const expectedRole = document.body.dataset.role;
    const me = await requireLogin(expectedRole);
    if (!me) return;

    document.getElementById('avatar').textContent = me.username.charAt(0).toUpperCase();
    document.getElementById('who-name').textContent = me.username;

    const rows = document.getElementById('session-rows');
    if (rows) {
        rows.innerHTML = `
            <div class="row"><span class="k">User ID</span><span class="v">${me.userId}</span></div>
            <div class="row"><span class="k">Username</span><span class="v">${me.username}</span></div>
            <div class="row"><span class="k">Role</span><span class="v">${me.role}</span></div>`;
    }

    document.getElementById('logout-btn').addEventListener('click', logout);
})();
