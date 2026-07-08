/**
 * A1Z26 模块
 * 字母↔数字编码：A=1, B=2, ..., Z=26
 */
const A1Z26Cipher = (() => {

    /** 编码：文本 → 数字串（用 "-" 分隔） */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            const code = ch.charCodeAt(0);
            if (code >= 65 && code <= 90) {
                results.push(String(code - 64));
            } else if (ch === ' ') {
                results.push(' ');  // 空格保留
            } else {
                results.push(ch);
            }
        }
        return results.join('-');
    }

    /** 解码：数字串 → 文本 */
    function decode(encoded) {
        // 按 "-" 或空格分割
        const parts = encoded.split(/[\-\s]+/);
        const results = [];
        for (const part of parts) {
            const num = parseInt(part, 10);
            if (num >= 1 && num <= 26) {
                results.push(String.fromCharCode(num + 64));
            } else if (part === '') {
                results.push(' ');
            } else {
                results.push(part);
            }
        }
        return results.join('');
    }

    /** 在容器中渲染 A1Z26 可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看 A1Z26 编码</div>';
            return;
        }

        // 编码结果行
        const codeRow = document.createElement('div');
        codeRow.className = 'a1z26-code-row';
        const encodedParts = [];
        for (const ch of chars) {
            const code = ch.charCodeAt(0);
            if (code >= 65 && code <= 90) {
                encodedParts.push(String(code - 64));
            } else if (ch === ' ') {
                encodedParts.push('·');
            } else {
                encodedParts.push(ch);
            }
        }
        codeRow.textContent = encodedParts.join(' - ');
        container.appendChild(codeRow);

        // 每个字母的可视化卡片
        const cardsRow = document.createElement('div');
        cardsRow.className = 'a1z26-cards-row';

        for (const ch of chars) {
            const card = document.createElement('div');
            card.className = 'a1z26-card';

            const code = ch.charCodeAt(0);
            if (code >= 65 && code <= 90) {
                const letterEl = document.createElement('span');
                letterEl.className = 'a1z26-letter';
                letterEl.textContent = ch;

                const numEl = document.createElement('span');
                numEl.className = 'a1z26-number';
                numEl.textContent = code - 64;

                card.appendChild(letterEl);
                card.appendChild(numEl);
            } else if (ch === ' ') {
                card.classList.add('a1z26-space');
                card.textContent = '␣';
            } else {
                card.classList.add('a1z26-other');
                card.textContent = ch;
            }

            cardsRow.appendChild(card);
        }
        container.appendChild(cardsRow);
    }

    return { encode, decode, render };
})();
