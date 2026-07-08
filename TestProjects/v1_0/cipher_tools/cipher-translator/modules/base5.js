/**
 * 五进制 / 敲击码 (Tap Code / Polybius Square) 模块
 * 使用 5×5 Polybius 方格，I/J 合并
 *    1 2 3 4 5
 * 1  A B C D E
 * 2  F G H I/J K
 * 3  L M N O P
 * 4  Q R S T U
 * 5  V W X Y Z
 */
const Base5Cipher = (() => {
    // Polybius 方格：row,col → letter（1-based）
    const grid = [
        ['A','B','C','D','E'],
        ['F','G','H','I','K'],
        ['L','M','N','O','P'],
        ['Q','R','S','T','U'],
        ['V','W','X','Y','Z']
    ];

    // letter → [row, col] 映射
    const letterToPos = {};
    for (let r = 0; r < 5; r++) {
        for (let c = 0; c < 5; c++) {
            const letter = grid[r][c];
            if (letter === 'I') {
                letterToPos['I'] = [r + 1, c + 1];
                letterToPos['J'] = [r + 1, c + 1];
            } else {
                letterToPos[letter] = [r + 1, c + 1];
            }
        }
    }

    /** 编码：文本 → 数字对（如 "23 15 31 31 34"） */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            const pos = letterToPos[ch];
            if (pos) {
                results.push(pos[0] + '' + pos[1]);
            } else if (ch === ' ') {
                results.push('  ');  // 空格用双空格表示
            } else {
                results.push(ch);
            }
        }
        return results.join(' ');
    }

    /** 解码：数字对 → 文本 */
    function decode(encoded) {
        const parts = encoded.trim().split(/\s+/);
        const results = [];
        for (const part of parts) {
            if (part.length === 2) {
                const row = parseInt(part[0], 10);
                const col = parseInt(part[1], 10);
                if (row >= 1 && row <= 5 && col >= 1 && col <= 5) {
                    const letter = grid[row - 1][col - 1];
                    results.push(letter === 'I' ? 'I/J' : letter);
                } else {
                    results.push(part);
                }
            } else if (part === '') {
                results.push(' ');
            } else {
                results.push(part);
            }
        }
        return results.join('');
    }

    /** 在容器中渲染五进制可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看五进制编码</div>';
            return;
        }

        // 编码数字对显示
        const codeRow = document.createElement('div');
        codeRow.className = 'base5-code-row';
        const encoded = encode(upperText);
        codeRow.textContent = encoded;
        container.appendChild(codeRow);

        // 每个字母的Polybius方格高亮
        const visualRow = document.createElement('div');
        visualRow.className = 'base5-visual-row';

        const displayChars = chars.slice(0, 10); // 最多显示10个字符的详细方格
        for (const ch of displayChars) {
            const cellWrapper = document.createElement('div');
            cellWrapper.className = 'base5-char-wrapper';

            const miniGrid = document.createElement('div');
            miniGrid.className = 'base5-mini-grid';

            const pos = letterToPos[ch];
            const targetRow = pos ? pos[0] - 1 : -1;
            const targetCol = pos ? pos[1] - 1 : -1;

            // 绘制5×5网格
            for (let r = 0; r < 5; r++) {
                for (let c = 0; c < 5; c++) {
                    const cell = document.createElement('span');
                    cell.className = 'base5-grid-cell';
                    cell.textContent = grid[r][c];

                    // 处理 I/J
                    if (r === 1 && c === 3) {
                        cell.textContent = 'I/J';
                    }

                    if (r === targetRow && c === targetCol) {
                        cell.classList.add('highlight');
                    }

                    miniGrid.appendChild(cell);
                }
            }

            cellWrapper.appendChild(miniGrid);

            const label = document.createElement('span');
            label.className = 'base5-char-label';
            label.textContent = ch + ' → ' + (pos ? pos[0] + ',' + pos[1] : '?');
            cellWrapper.appendChild(label);

            visualRow.appendChild(cellWrapper);
        }

        if (chars.length > 10) {
            const more = document.createElement('span');
            more.className = 'base5-more';
            more.textContent = '…(+' + (chars.length - 10) + '个字符)';
            visualRow.appendChild(more);
        }

        container.appendChild(visualRow);
    }

    return { encode, decode, render };
})();
