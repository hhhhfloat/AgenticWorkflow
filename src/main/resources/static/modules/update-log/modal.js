// @anchor: ulModal_intro
// 覆盖层基座：轻提示（toast）与自定义模态框，彻底替代原生 alert / prompt / confirm

// @anchor: ulModal_toast
// 轻提示：按 key 去重（同 key 复用元素并重置计时），支持 info/success/warn/error
const UL_TOAST_LIMIT = 4;

function ulToast(message, type, options) {
    const opts = options || {};
    const layer = ULDom.get('ulToastLayer');
    if (!layer) return;
    const variant = type || 'info';
    const key = opts.key || '';
    let node = key ? layer.querySelector('[data-toast-key="' + key + '"]') : null;
    if (!node) {
        node = document.createElement('div');
        node.className = 'ul-toast';
        if (key) node.dataset.toastKey = key;
        layer.appendChild(node);
        while (layer.children.length > UL_TOAST_LIMIT) {
            layer.removeChild(layer.firstElementChild);
        }
    }
    node.className = 'ul-toast ul-toast-' + variant;
    node.textContent = message;
    clearTimeout(node._ulTimer);
    const duration = opts.duration || (variant === 'error' ? 4200 : 2600);
    node._ulTimer = setTimeout(() => {
        node.classList.add('ul-toast-out');
        setTimeout(() => node.remove(), 220);
    }, duration);
}

// @anchor: ulModal_open
// 通用模态框：标题 + 任意正文 + 页脚动作，Esc/遮罩关闭，动作可异步
const UL_MODAL_STACK = [];

function ulOpenModal(config) {
    const options = config || {};
    const layer = ULDom.get('ulModalLayer');
    const root = document.createElement('div');
    root.className = 'ul-modal-overlay';

    const card = document.createElement('div');
    card.className = 'ul-modal' + (options.wide ? ' ul-modal-wide' : '');
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const head = document.createElement('div');
    head.className = 'ul-modal-head';
    const titleWrap = document.createElement('div');
    titleWrap.className = 'ul-modal-titles';
    const title = document.createElement('h2');
    title.textContent = options.title || '';
    titleWrap.appendChild(title);
    if (options.subtitle) {
        const subtitle = document.createElement('p');
        subtitle.className = 'ul-modal-subtitle';
        subtitle.textContent = options.subtitle;
        titleWrap.appendChild(subtitle);
    }
    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'ul-modal-close';
    closeBtn.setAttribute('aria-label', '关闭');
    closeBtn.textContent = '✕';
    head.appendChild(titleWrap);
    head.appendChild(closeBtn);

    const body = document.createElement('div');
    body.className = 'ul-modal-body';
    if (typeof options.body === 'string') {
        body.innerHTML = options.body;
    } else if (options.body instanceof Node) {
        body.appendChild(options.body);
    }

    const foot = document.createElement('div');
    foot.className = 'ul-modal-foot';

    card.appendChild(head);
    card.appendChild(body);
    card.appendChild(foot);
    root.appendChild(card);
    layer.appendChild(root);

    const api = {
        root: root,
        card: card,
        body: body,
        closed: false,
        close(result) {
            if (api.closed) return;
            api.closed = true;
            const index = UL_MODAL_STACK.indexOf(api);
            if (index >= 0) UL_MODAL_STACK.splice(index, 1);
            root.classList.add('ul-modal-out');
            setTimeout(() => root.remove(), 160);
            if (typeof options.onClose === 'function') options.onClose(result);
        },
        setBusy(busy) {
            foot.querySelectorAll('button').forEach((btn) => {
                btn.disabled = !!busy;
            });
            card.classList.toggle('ul-modal-busy', !!busy);
        }
    };

    (options.actions || [{ label: '关闭', variant: 'ghost' }]).forEach((action) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'ul-btn ul-btn-' + (action.variant || 'ghost') + (action.wide ? ' ul-btn-block' : '');
        btn.textContent = action.label;
        btn.addEventListener('click', async () => {
            if (!action.onClick) {
                api.close();
                return;
            }
            api.setBusy(true);
            try {
                const result = await action.onClick(api);
                if (result !== false) api.close(result);
            } catch (err) {
                ulHandleActionError(err);
            } finally {
                api.setBusy(false);
            }
        });
        foot.appendChild(btn);
    });

    closeBtn.addEventListener('click', () => api.close());
    root.addEventListener('mousedown', (event) => {
        if (event.target === root && options.dismissible !== false) api.close();
    });

    UL_MODAL_STACK.push(api);
    const focusTarget = body.querySelector('input, textarea, select, button');
    setTimeout(() => {
        if (focusTarget) focusTarget.focus();
        else closeBtn.focus();
    }, 30);
    return api;
}

function ulCloseTopModal() {
    const top = UL_MODAL_STACK[UL_MODAL_STACK.length - 1];
    if (top) top.close();
    return !!top;
}

// @anchor: ulModal_confirm
// 二次确认：返回 Promise<boolean>
function ulConfirmModal(options) {
    const opts = options || {};
    return new Promise((resolve) => {
        let decided = false;
        ulOpenModal({
            title: opts.title || '确认操作',
            body: '<p class="ul-modal-text">' + ulEscapeHtml(opts.message || '') + '</p>',
            actions: [
                {
                    label: opts.cancelText || '取消',
                    variant: 'ghost',
                    onClick: (api) => {
                        decided = true;
                        resolve(false);
                        api.close();
                    }
                },
                {
                    label: opts.confirmText || '确认',
                    variant: opts.danger === false ? 'primary' : 'danger',
                    onClick: (api) => {
                        decided = true;
                        resolve(true);
                        api.close();
                    }
                }
            ],
            onClose: () => {
                if (!decided) resolve(false);
            }
        });
    });
}

// @anchor: ulModal_form
// 表单模态框：声明式字段（text / textarea / select），返回 Promise<values|null>
function ulBuildField(field) {
    const wrap = document.createElement('label');
    wrap.className = 'ul-field' + (field.wide ? ' ul-field-wide' : '');
    const labelText = document.createElement('span');
    labelText.className = 'ul-field-label';
    labelText.textContent = field.label || field.name;
    if (field.required) {
        const star = document.createElement('i');
        star.className = 'ul-field-star';
        star.textContent = '*';
        labelText.appendChild(star);
    }
    wrap.appendChild(labelText);

    let input;
    if (field.type === 'textarea') {
        input = document.createElement('textarea');
        input.rows = field.rows || 4;
    } else if (field.type === 'select') {
        input = document.createElement('select');
        (field.options || []).forEach((option) => {
            const opt = document.createElement('option');
            opt.value = option.value;
            opt.textContent = option.label;
            input.appendChild(opt);
        });
    } else {
        input = document.createElement('input');
        input.type = field.type || 'text';
    }
    input.name = field.name;
    input.id = ulUid('ul-field');
    input.value = field.value === undefined || field.value === null ? '' : String(field.value);
    if (field.placeholder) input.placeholder = field.placeholder;
    if (field.disabled) input.disabled = true;
    if (field.maxLength) input.maxLength = field.maxLength;
    wrap.appendChild(input);

    if (field.hint) {
        const hint = document.createElement('span');
        hint.className = 'ul-field-hint';
        hint.textContent = field.hint;
        wrap.appendChild(hint);
    }
    const error = document.createElement('span');
    error.className = 'ul-field-error';
    error.hidden = true;
    wrap.appendChild(error);
    return wrap;
}

function ulFormModal(options) {
    const opts = options || {};
    const fields = opts.fields || [];
    const container = document.createElement('div');
    container.className = 'ul-form';
    if (opts.description) {
        const desc = document.createElement('p');
        desc.className = 'ul-form-desc';
        desc.textContent = opts.description;
        container.appendChild(desc);
    }
    fields.forEach((field) => container.appendChild(ulBuildField(field)));

    return new Promise((resolve) => {
        let settled = false;
        const finish = (values) => {
            settled = true;
            resolve(values);
        };
        const modal = ulOpenModal({
            title: opts.title || '填写信息',
            subtitle: opts.subtitle,
            body: container,
            wide: opts.wide,
            actions: [
                {
                    label: opts.cancelText || '取消',
                    variant: 'ghost',
                    onClick: () => {
                        finish(null);
                    }
                },
                {
                    label: opts.submitText || '确定',
                    variant: 'primary',
                    onClick: () => {
                        const values = {};
                        const errors = [];
                        container.querySelectorAll('.ul-field-error').forEach((node) => {
                            node.hidden = true;
                            node.textContent = '';
                        });
                        let firstInvalid = null;
                        fields.forEach((field) => {
                            const el = container.querySelector('[name="' + field.name + '"]');
                            const raw = el ? el.value : '';
                            values[field.name] = typeof raw === 'string' ? raw.trim() : raw;
                            const wrap = el ? el.closest('.ul-field') : null;
                            const errorNode = wrap ? wrap.querySelector('.ul-field-error') : null;
                            let message = '';
                            if (field.required && !values[field.name]) {
                                message = (field.label || field.name) + ' 不能为空';
                            } else if (typeof field.validate === 'function') {
                                message = field.validate(values[field.name], values) || '';
                            }
                            if (message) {
                                errors.push(message);
                                if (errorNode) {
                                    errorNode.textContent = message;
                                    errorNode.hidden = false;
                                }
                                if (!firstInvalid) firstInvalid = el;
                            }
                        });
                        if (typeof opts.validate === 'function') {
                            const extra = opts.validate(values);
                            if (extra) {
                                errors.push(extra);
                                ulToast(extra, 'warn');
                            }
                        }
                        if (errors.length) {
                            if (firstInvalid) firstInvalid.focus();
                            return false;
                        }
                        finish(values);
                        return true;
                    }
                }
            ],
            onClose: () => {
                if (!settled) finish(null);
            }
        });
        if (modal.body.firstElementChild) {
            const firstInput = modal.body.querySelector('input, textarea, select');
            if (firstInput) setTimeout(() => firstInput.focus(), 40);
        }
    });
}
