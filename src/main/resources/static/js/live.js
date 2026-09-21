const Live = (function () {
    let client = null;
    let connected = false;
    const handlers = [];
    const subscriptions = [];

    function fire(message) {
        handlers.forEach((h) => {
            try {
                h(message);
            } catch (e) {
                /* one bad handler must not stop the others */
            }
        });
    }

    function markStatus(ok) {
        connected = ok;
        document.querySelectorAll('.live-status-dot').forEach((dot) => {
            dot.style.background = ok ? '' : '#C2CAD6';
        });
        document.querySelectorAll('[data-live-label]').forEach((el) => {
            el.textContent = ok ? 'Live' : 'Reconnecting';
        });
    }

    let wanted = [];
    let retry = null;

    function connect(topics) {
        wanted = topics || wanted;

        const token = Auth.token();
        if (!token || typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
            return;
        }

        client = Stomp.over(new SockJS('/ws'));
        client.debug = null;

        client.connect(
            { Authorization: 'Bearer ' + token },
            () => {
                markStatus(true);
                subscriptions.length = 0;

                subscriptions.push(client.subscribe('/user/queue/updates', (frame) => {
                    fire(JSON.parse(frame.body));
                }));

                wanted.forEach((t) => {
                    subscriptions.push(client.subscribe(t, (frame) => {
                        fire(JSON.parse(frame.body));
                    }));
                });
            },
            () => {
                markStatus(false);
                scheduleRetry();
            }
        );
    }

    function scheduleRetry() {
        if (retry) return;
        retry = setTimeout(() => {
            retry = null;
            connect();
        }, 3000);
    }

    return {
        connect,
        onMessage(handler) {
            handlers.push(handler);
        },
        isConnected() {
            return connected;
        },
        subscribeTopic(topic) {
            if (!wanted.includes(topic)) {
                wanted.push(topic);
            }
            if (client && connected) {
                subscriptions.push(client.subscribe(topic, (frame) => fire(JSON.parse(frame.body))));
            }
        }
    };
})();
