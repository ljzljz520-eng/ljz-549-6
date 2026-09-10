(function () {
    'use strict';

    var form = document.getElementById('repair-form');
    var submitBtn = document.getElementById('submit-btn');
    var btnText = submitBtn.querySelector('.btn__text');
    var spinner = submitBtn.querySelector('.spinner');
    var statusBox = document.getElementById('status');

    var FIELDS = ['location', 'building', 'description', 'contact'];

    /* 服务端错误 code → 前端友好文案 */
    var ERROR_MESSAGES = {
        MISSING_FIELD: '提交的信息不完整，请补全后重试。',
        INVALID_FIELD: '提交的信息有误，请检查后重试。',
        SERVER_BUSY: '服务器正忙，请稍后再试。',
        METHOD_NOT_ALLOWED: '请求方式不正确，请勿直接访问接口地址。'
    };

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text != null) {
            node.textContent = text; // textContent 防止返回内容造成 XSS
        }
        return node;
    }

    function clearFieldErrors() {
        FIELDS.forEach(function (name) {
            var input = form.elements[name];
            var wrap = input.closest('.field');
            wrap.classList.remove('field--invalid');
            var old = wrap.querySelector('.field-error');
            if (old) {
                old.remove();
            }
        });
    }

    function showFieldError(name, message) {
        var input = form.elements[name];
        if (!input) {
            return;
        }
        var wrap = input.closest('.field');
        wrap.classList.add('field--invalid');
        wrap.appendChild(el('span', 'field-error', message));
    }

    function hideStatus() {
        statusBox.hidden = true;
        statusBox.className = 'status';
        statusBox.innerHTML = '';
    }

    function showLoading() {
        statusBox.hidden = false;
        statusBox.className = 'status status--loading';
        statusBox.appendChild(el('p', 'status__title', '正在提交报修单…'));
        statusBox.appendChild(el('p', 'status__detail', '请稍候，不要重复点击提交按钮。'));
    }

    function showSuccess(ticketNo, message) {
        statusBox.hidden = false;
        statusBox.className = 'status status--success';
        statusBox.appendChild(el('p', 'status__title', '提交成功'));
        statusBox.appendChild(el('p', 'status__detail',
            message || '报修单已受理，后勤师傅将尽快上门处理。'));

        var ticket = el('div', 'ticket');
        ticket.appendChild(el('span', null, '工单号：'));
        ticket.appendChild(el('span', 'ticket__no', ticketNo));
        var copyBtn = el('button', 'btn btn--ghost', '复制工单号');
        copyBtn.type = 'button';
        copyBtn.addEventListener('click', function () {
            copyText(ticketNo, copyBtn);
        });
        ticket.appendChild(copyBtn);
        statusBox.appendChild(ticket);

        var actions = el('div', 'status__actions');
        var againBtn = el('button', 'btn btn--ghost', '再报一单');
        againBtn.type = 'button';
        againBtn.addEventListener('click', function () {
            form.reset();
            clearFieldErrors();
            hideStatus();
            form.elements.location.focus();
        });
        actions.appendChild(againBtn);
        statusBox.appendChild(actions);
    }

    function showError(code, message, field) {
        statusBox.hidden = false;
        statusBox.className = 'status status--error';
        statusBox.appendChild(el('p', 'status__title', '提交失败'));
        statusBox.appendChild(el('p', 'status__detail',
            message || ERROR_MESSAGES[code] || '发生未知错误，请稍后重试。'));

        // 字段类错误高亮对应输入框
        if (field && FIELDS.indexOf(field) !== -1) {
            showFieldError(field, message || '该项填写有误。');
        }

        var actions = el('div', 'status__actions');
        var retryBtn = el('button', 'btn btn--ghost', '重新填写 / 重试');
        retryBtn.type = 'button';
        retryBtn.addEventListener('click', function () {
            hideStatus();
            if (field && form.elements[field]) {
                form.elements[field].focus();
            }
        });
        actions.appendChild(retryBtn);
        statusBox.appendChild(actions);
    }

    function copyText(text, btn) {
        function fallback() {
            var tmp = document.createElement('textarea');
            tmp.value = text;
            document.body.appendChild(tmp);
            tmp.select();
            try {
                document.execCommand('copy');
                btn.textContent = '已复制';
            } catch (e) {
                btn.textContent = '复制失败，请手动选择';
            }
            tmp.remove();
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function () {
                btn.textContent = '已复制';
            }, fallback);
        } else {
            fallback();
        }
    }

    function setLoading(isLoading) {
        submitBtn.disabled = isLoading;
        spinner.hidden = !isLoading;
        btnText.textContent = isLoading ? '提交中…' : '提交报修';
    }

    /* 前端必填校验，通过后再发请求 */
    function validateForm() {
        clearFieldErrors();
        var firstInvalid = null;

        var checks = [
            ['location', '请选择报修地点类型。'],
            ['building', '请填写具体位置。'],
            ['description', '请填写问题描述。'],
            ['contact', '请填写联系方式。']
        ];

        checks.forEach(function (item) {
            var name = item[0];
            var tip = item[1];
            var value = (form.elements[name].value || '').trim();
            if (!value) {
                showFieldError(name, tip);
                if (!firstInvalid) {
                    firstInvalid = name;
                }
            }
        });

        return firstInvalid;
    }

    function submitRepair() {
        var payload = new URLSearchParams();
        FIELDS.forEach(function (name) {
            payload.append(name, (form.elements[name].value || '').trim());
        });

        return fetch(form.action, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
            },
            body: payload.toString()
        }).then(function (response) {
            // 405/503 同样返回 JSON，按 HTTP 状态与业务 code 双重处理
            return response.json().then(function (data) {
                return { status: response.status, data: data };
            }).catch(function () {
                return {
                    status: response.status,
                    data: {
                        code: response.ok ? 'BAD_RESPONSE' : 'HTTP_' + response.status,
                        message: response.ok
                            ? '服务器返回格式异常，请稍后重试。'
                            : '服务暂时不可用，请稍后重试。'
                    }
                };
            });
        });
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();

        var firstInvalid = validateForm();
        if (firstInvalid) {
            hideStatus();
            form.elements[firstInvalid].focus();
            return;
        }

        hideStatus();
        showLoading();
        setLoading(true);

        submitRepair().then(function (result) {
            var data = result.data || {};
            if (result.status === 200 && data.code === 'OK' && data.ticketNo) {
                showSuccess(data.ticketNo, data.message);
                form.reset();
                clearFieldErrors();
            } else {
                // 区分：MISSING_FIELD / INVALID_FIELD / SERVER_BUSY / METHOD_NOT_ALLOWED
                showError(data.code, data.message || ERROR_MESSAGES[data.code], data.field);
            }
        }).catch(function () {
            showError('NETWORK_ERROR', '网络连接异常，请检查网络后重试。', null);
        }).then(function () {
            setLoading(false);
        });
    });
})();
