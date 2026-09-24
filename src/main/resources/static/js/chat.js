const RoadGuardChat = (() => {
    const mounted = new Map();
    const seenTopics = new Set();
    const seenMessages = new Map();

    function esc(value) {
        return String(value || '').replace(/[&<>"']/g, character => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
        }[character]));
    }

    function mount(requestId, role, host) {
        if (!host || mounted.get(String(requestId)) === host) return;
        const key = String(requestId);
        mounted.set(key, host);
        host.innerHTML = `
            <section class="chat-panel" aria-label="Chat for request ${esc(requestId)}">
                <div class="chat-heading">
                    <div>
                        <p class="section-label">Live chat</p>
                        <p class="chat-subtitle">Message your ${role === 'DRIVER' ? 'mechanic' : 'driver'} securely.</p>
                    </div>
                    <div class="chat-heading-right">
                        <span class="chat-connection" data-chat-connection="${key}">Connecting...</span>
                        <button type="button" class="chat-toggle" data-chat-toggle="${key}"
                                aria-expanded="true" title="Hide the chat">
                            <svg class="chat-icon-bubble" viewBox="0 0 24 24" fill="none"
                                 stroke="currentColor" stroke-width="2"
                                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 9 9 0 0 1-4.2-1L3 20l1.2-4.4A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4z"/>
                            </svg>
                            <span class="chat-min-label">Message your ${role === 'DRIVER' ? 'mechanic' : 'driver'}</span>
                            <span class="chat-unread" data-chat-unread="${key}" hidden>0</span>
                            <span class="chat-chevron" aria-hidden="true"></span>
                        </button>
                    </div>
                </div>
                <div class="chat-messages" data-chat-messages="${key}" aria-live="polite">
                    <p class="chat-empty">No messages yet. Share an update when you are ready.</p>
                </div>
                <div class="chat-attachment" data-chat-attachment="${key}" hidden></div>
                <form class="chat-compose" data-chat-form="${key}">
                    <label class="chat-attach-btn" title="Attach an image">
                        <input type="file" accept="image/jpeg,image/png,image/webp" data-chat-file="${key}" hidden>
                        <span aria-hidden="true">+</span>
                        <span class="sr-only">Attach image</span>
                    </label>
                    <input type="text" maxlength="2000" autocomplete="off" placeholder="Write a message..." data-chat-input="${key}">
                    <button class="btn btn-primary" type="submit">Send</button>
                </form>
                <p class="chat-hint">Images up to 5 MB</p>
            </section>`;

        if (!seenTopics.has(key)) {
            seenTopics.add(key);
            Live.subscribeTopic('/topic/chat/' + key);
        }
        host.querySelector(`[data-chat-form="${key}"]`).addEventListener('submit', event => send(event, key));
        host.querySelector(`[data-chat-file="${key}"]`).addEventListener('change', event => previewFile(event, key));
        host.querySelector(`[data-chat-connection="${key}"]`).textContent = Live.isConnected() ? 'Live' : 'Reconnecting...';

        host.querySelector(`[data-chat-toggle="${key}"]`)
            .addEventListener('click', () => setMinimised(key, !host.classList.contains('is-min')));

        setMinimised(key, localStorage.getItem(MIN_KEY + key) === '1', true);

        loadHistory(key);
    }

    const MIN_KEY = 'roadguard.chat.min.';

    function setMinimised(requestId, minimised, quiet) {
        const host = mounted.get(String(requestId));
        if (!host) return;

        host.classList.toggle('is-min', minimised);

        const toggle = host.querySelector(`[data-chat-toggle="${requestId}"]`);
        if (toggle) {
            toggle.setAttribute('aria-expanded', String(!minimised));
            toggle.title = minimised ? 'Show the chat' : 'Hide the chat';
        }

        try {
            localStorage.setItem(MIN_KEY + requestId, minimised ? '1' : '0');
        } catch (e) {
            /* a browser that refuses storage still opens and closes the panel */
        }

        if (!minimised) {
            unread.set(String(requestId), 0);
            paintUnread(requestId);
            const list = host.querySelector(`[data-chat-messages="${requestId}"]`);
            if (list && !quiet) list.scrollTop = list.scrollHeight;
        }
    }

    const unread = new Map();

    function paintUnread(requestId) {
        const host = mounted.get(String(requestId));
        if (!host) return;
        const badge = host.querySelector(`[data-chat-unread="${requestId}"]`);
        if (!badge) return;
        const count = unread.get(String(requestId)) || 0;
        badge.hidden = count === 0;
        badge.textContent = count > 9 ? '9+' : String(count);

        document.dispatchEvent(new CustomEvent('chat-unread', {
            detail: { requestId: String(requestId), count }
        }));
    }

    const loadingHistory = new Set();

    async function loadHistory(requestId) {
        loadingHistory.add(String(requestId));
        try {
            const earlier = await api('/api/requests/' + requestId + '/chat');
            (earlier || []).forEach(render);
        } catch (e) {
            const host = mounted.get(String(requestId));
            const list = host && host.querySelector(`[data-chat-messages="${requestId}"]`);
            const empty = list && list.querySelector('.chat-empty');
            if (empty) {
                empty.textContent = 'Earlier messages could not be loaded. New ones will still arrive.';
            }
        } finally {
            loadingHistory.delete(String(requestId));
        }
    }

    function send(event, requestId) {
        event.preventDefault();
        const host = mounted.get(String(requestId));
        if (!host) return;
        const input = host.querySelector(`[data-chat-input="${requestId}"]`);
        const fileInput = host.querySelector(`[data-chat-file="${requestId}"]`);
        const attachment = host.querySelector(`[data-chat-attachment="${requestId}"]`);
        const text = input.value.trim();
        const file = fileInput.files[0];
        if (!text && !file) return;
        if (file && file.size > 5 * 1024 * 1024) {
            attachment.hidden = false;
            attachment.textContent = 'That image is larger than 5 MB.';
            return;
        }

        const publish = mediaDataUrl => {
            if (!Live.publish('/app/chat.send', { requestId: Number(requestId), text, mediaDataUrl })) {
                attachment.hidden = false;
                attachment.textContent = 'Chat is reconnecting. Try again in a moment.';
                return;
            }
            input.value = '';
            fileInput.value = '';
            attachment.hidden = true;
            attachment.textContent = '';
        };

        if (file) {
            const reader = new FileReader();
            reader.onload = () => publish(reader.result);
            reader.readAsDataURL(file);
        } else {
            publish(null);
        }
    }

    function previewFile(event, requestId) {
        const file = event.target.files[0];
        const host = mounted.get(String(requestId));
        if (!file || !host) return;
        const attachment = host.querySelector(`[data-chat-attachment="${requestId}"]`);
        attachment.hidden = false;
        attachment.textContent = `Attached: ${file.name}`;
    }

    function render(message) {
        const host = mounted.get(String(message.requestId));
        if (!host) return;

        if (message.id != null) {
            const key = String(message.requestId);
            let seen = seenMessages.get(key);
            if (!seen) {
                seen = new Set();
                seenMessages.set(key, seen);
            }
            if (seen.has(message.id)) return;
            seen.add(message.id);
        }

        const list = host.querySelector(`[data-chat-messages="${message.requestId}"]`);
        const empty = list.querySelector('.chat-empty');
        if (empty) empty.remove();
        const mine = message.senderRole === (document.body.dataset.role || '');
        const row = document.createElement('div');
        row.className = 'chat-message ' + (mine ? 'is-mine' : 'is-theirs');
        row.innerHTML = `<div class="chat-bubble"><strong>${esc(message.sender)}</strong>${message.text ? `<p>${esc(message.text)}</p>` : ''}${message.mediaUrl ? `<img src="${esc(message.mediaUrl)}" alt="Shared image" loading="lazy">` : ''}<time>${new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(message.sentAt))}</time></div>`;
        list.appendChild(row);
        list.scrollTop = list.scrollHeight;

        if (host.classList.contains('is-min') && !mine
                && !loadingHistory.has(String(message.requestId))) {
            const key = String(message.requestId);
            unread.set(key, (unread.get(key) || 0) + 1);
            paintUnread(key);
        }
    }

    if (typeof Live !== 'undefined') {
        Live.onMessage(message => {
            if (message && message.type === 'CHAT_ERROR') {
                document.querySelectorAll('.chat-attachment').forEach(node => {
                    node.hidden = false;
                    node.textContent = message.message;
                });
                return;
            }
            if (message && message.requestId && message.senderRole) render(message);
        });
    }

    return {
        mount,
        reveal(requestId) {
            setMinimised(requestId, false);
        }
    };
})();
