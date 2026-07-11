/**
 * 盲文 (Braille) 模块
 * 支持英文到6点盲文Unicode字符的编码/解码，以及点阵可视化。
 */
const BrailleCipher = (() => {
    // 字母 → [dot1, dot2, dot3, dot4, dot5, dot6] 是否激活
    // 点阵布局:  1 4
    //            2 5
    //            3 6
    const letterToDots = {
        'A': [1,0,0,0,0,0], 'B': [1,1,0,0,0,0], 'C': [1,0,0,1,0,0],
        'D': [1,0,0,1,1,0], 'E': [1,0,0,0,1,0], 'F': [1,1,0,1,0,0],
        'G': [1,1,0,1,1,0], 'H': [1,1,0,0,1,0], 'I': [0,1,0,1,0,0],
        'J': [0,1,0,1,1,0], 'K': [1,0,1,0,0,0], 'L': [1,1,1,0,0,0],
        'M': [1,0,1,1,0,0], 'N': [1,0,1,1,1,0], 'O': [1,0,1,0,1,0],
        'P': [1,1,1,1,0,0], 'Q': [1,1,1,1,1,0], 'R': [1,1,1,0,1,0],
        'S': [0,1,1,1,0,0], 'T': [0,1,1,1,1,0], 'U': [1,0,1,0,0,1],
        'V': [1,1,1,0,0,1], 'W': [0,1,0,1,1,1], 'X': [1,0,1,1,0,1],
        'Y': [1,0,1,1,1,1], 'Z': [1,0,1,0,1,1]
    };

    // 预计算 braille Unicode 码点 → 字母 的反向映射
    const brailleToLetter = {};
    for (const [letter, dots] of Object.entries(letterToDots)) {
        let codePoint = 0x2800;
        if (dots[0]) codePoint |= 0x01;  // dot1
        if (dots[1]) codePoint |= 0x02;  // dot2
        if (dots[2]) codePoint |= 0x04;  // dot3
        if (dots[3]) codePoint |= 0x08;  // dot4
        if (dots[4]) codePoint |= 0x10;  // dot5
        if (dots[5]) codePoint |= 0x20;  // dot6
        brailleToLetter[String.fromCodePoint(codePoint)] = letter;
    }

    /** 将文本编码为盲文Unicode字符串 */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            if (letterToDots[ch]) {
                const dots = letterToDots[ch];
                let cp = 0x2800;
                if (dots[0]) cp |= 0x01;
                if (dots[1]) cp |= 0x02;
                if (dots[2]) cp |= 0x04;
                if (dots[3]) cp |= 0x08;
                if (dots[4]) cp |= 0x10;
                if (dots[5]) cp |= 0x20;
                results.push(String.fromCodePoint(cp));
            } else if (ch === ' ') {
                results.push(' ');  // 空格保留
            } else {
                results.push(ch);   // 非字母原样保留
            }
        }
        return results.join('');
    }

    /** 将盲文Unicode字符串解码为英文 */
    function decode(braille) {
        const results = [];
        for (const ch of braille) {
            if (ch === ' ') {
                results.push(' ');
            } else if (brailleToLetter[ch]) {
                results.push(brailleToLetter[ch]);
            } else {
                results.push(ch);
            }
        }
        return results.join('');
    }

    /** 获取字母的点阵数组 */
    function getDots(letter) {
        return letterToDots[letter.toUpperCase()] || null;
    }

    /** 在容器中渲染盲文可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看盲文编码</div>';
            return;
        }

        // 编码行：显示盲文Unicode字符
        const brailleLine = document.createElement('div');
        brailleLine.className = 'braille-unicode-row';
        const encoded = encode(upperText);
        for (const ch of encoded) {
            const span = document.createElement('span');
            span.className = 'braille-char';
            span.textContent = ch;
            brailleLine.appendChild(span);
        }
        container.appendChild(brailleLine);

        // 点阵可视化行
        const dotsRow = document.createElement('div');
        dotsRow.className = 'braille-dots-row';

        for (const ch of upperText) {
            const wrapper = document.createElement('div');
            wrapper.className = 'braille-dot-cell';

            const dots = getDots(ch);
            if (dots) {
                // 3行×2列的点阵
                const grid = document.createElement('div');
                grid.className = 'braille-dot-grid';

                // 布局: [dot1, dot4; dot2, dot5; dot3, dot6]
                const layout = [
                    [dots[0], dots[3]],  // row 0: dot1, dot4
                    [dots[1], dots[4]],  // row 1: dot2, dot5
                    [dots[2], dots[5]]   // row 2: dot3, dot6
                ];

                for (const row of layout) {
                    for (const active of row) {
                        const dot = document.createElement('span');
                        dot.className = 'braille-dot' + (active ? ' active' : '');
                        grid.appendChild(dot);
                    }
                }
                wrapper.appendChild(grid);

                const label = document.createElement('span');
                label.className = 'braille-letter-label';
                label.textContent = ch;
                wrapper.appendChild(label);
            } else if (ch === ' ') {
                const spaceMarker = document.createElement('span');
                spaceMarker.className = 'braille-space-marker';
                spaceMarker.textContent = '␣';
                wrapper.appendChild(spaceMarker);
            } else {
                const unknown = document.createElement('span');
                unknown.className = 'braille-unknown';
                unknown.textContent = ch;
                wrapper.appendChild(unknown);
            }

            dotsRow.appendChild(wrapper);
        }
        container.appendChild(dotsRow);
    }

    return { encode, decode, getDots, render };
})();
