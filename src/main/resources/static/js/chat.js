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
                    <span class="chat-connection" data-chat-connection="${key}">Connecting...</span>
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

        loadHistory(key);
    }

    async function loadHistory(requestId) {
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

    return { mount };
})();
