/**
 * 凯撒移位 — 主脚本
 * 输入文本后，自动展示 ROT1 ~ ROT25 全部移位结果。
 */
(function () {
    'use strict';

    // @anchor: caesar_dom_refs
    const textInput = document.getElementById('textInput');
    const clearBtn = document.getElementById('clearBtn');
    const charCount = document.getElementById('charCount');
    const placeholder = document.getElementById('placeholder');
    const resultsTableWrapper = document.getElementById('resultsTableWrapper');
    const resultsBody = document.getElementById('resultsBody');

    // @anchor: caesar_shift_char
    /**
     * 对单个字母进行凯撒移位
     * @param {string} ch - 单个字符
     * @param {number} shift - 移位量 (1-25)
     * @returns {string} 移位后的字符
     */
    function shiftChar(ch, shift) {
        const code = ch.charCodeAt(0);
        if (code >= 65 && code <= 90) {
            // 大写字母
            return String.fromCharCode(((code - 65 + shift) % 26) + 65);
        }
        if (code >= 97 && code <= 122) {
            // 小写字母
            return String.fromCharCode(((code - 97 + shift) % 26) + 97);
        }
        return ch;
    }

    // @anchor: caesar_shift_text
    /**
     * 对整个文本进行凯撒移位
     * @param {string} text - 原始文本
     * @param {number} shift - 移位量 (1-25)
     * @returns {string} 移位后的文本
     */
    function shiftText(text, shift) {
        var result = '';
        for (var i = 0; i < text.length; i++) {
            result += shiftChar(text[i], shift);
        }
        return result;
    }

    // @anchor: caesar_render
    /**
     * 渲染所有 25 种移位结果到表格
     * @param {string} text - 原始文本
     */
    function renderResults(text) {
        var trimmed = text.trim();

        if (trimmed === '') {
            placeholder.style.display = '';
            resultsTableWrapper.style.display = 'none';
            return;
        }

        placeholder.style.display = 'none';
        resultsTableWrapper.style.display = '';

        resultsBody.innerHTML = '';

        for (var shift = 1; shift <= 25; shift++) {
            var tr = document.createElement('tr');

            // ROT3 和 ROT13 特殊标记
            if (shift === 3) {
                tr.className = 'row-rot3';
            } else if (shift === 13) {
                tr.className = 'row-rot13';
            }

            // 移位量
            var tdNum = document.createElement('td');
            var spanNum = document.createElement('span');
            spanNum.className = 'shift-num';
            spanNum.textContent = shift;
            tdNum.appendChild(spanNum);
            tr.appendChild(tdNum);

            // 简称
            var tdName = document.createElement('td');
            var spanName = document.createElement('span');
            spanName.className = 'shift-name';
            spanName.textContent = 'ROT' + shift;
            if (shift === 13) {
                spanName.textContent += ' ⚡';
            }
            if (shift === 3) {
                spanName.textContent += ' ★';
            }
            tdName.appendChild(spanName);
            tr.appendChild(tdName);

            // 结果
            var tdResult = document.createElement('td');
            var spanResult = document.createElement('span');
            spanResult.className = 'shift-result';
            var shifted = shiftText(trimmed, shift);
            spanResult.textContent = shifted;
            tdResult.appendChild(spanResult);
            tr.appendChild(tdResult);

            resultsBody.appendChild(tr);
        }
    }

    // @anchor: caesar_input_handler
    function onInputChange() {
        var value = textInput.value;
        var len = value.length;
        charCount.textContent = len + ' / 60';

        if (len > 60) {
            textInput.value = value.slice(0, 60);
            charCount.textContent = '60 / 60';
        }

        renderResults(textInput.value);
    }

    function onClear() {
        textInput.value = '';
        charCount.textContent = '0 / 60';
        renderResults('');
        textInput.focus();
    }

    // @anchor: caesar_event_bindings
    textInput.addEventListener('input', onInputChange);
    clearBtn.addEventListener('click', onClear);

    // @anchor: caesar_init
    renderResults('');

    // 加载后自动展示示例
    setTimeout(function () {
        if (textInput.value === '') {
            textInput.value = 'HELLO';
            charCount.textContent = '5 / 60';
            renderResults('HELLO');
        }
    }, 300);

})();
