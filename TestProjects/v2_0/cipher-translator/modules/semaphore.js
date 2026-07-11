/**
 * 旗语 (Semaphore) 模块
 * 使用 Canvas 绘制旗语信号人物：圆形头部 + 身体 + 双臂持旗
 *
 * 8个基本方向 (索引 0-7)，信号员自身视角（Canvas 坐标系）：
 *   0: 上(N), 1: 右上(NE), 2: 右(E), 3: 右下(SE),
 *   4: 下(S), 5: 左下(SW), 6: 左(W), 7: 左上(NW)
 *
 * 映射数据来自 cipher-data.js 中的 CipherData.semaphore（信号员原形态）
 */
const SemaphoreCipher = (() => {
    // @anchor: semaphore_dir_angles
    // 方向索引 → 弧度（0=上，顺时针）
    const DIR_ANGLES = [
        -Math.PI / 2,       // 0: 上 (N)
        -Math.PI / 4,       // 1: 右上 (NE)
        0,                   // 2: 右 (E)
        Math.PI / 4,         // 3: 右下 (SE)
        Math.PI / 2,         // 4: 下 (S)
        Math.PI * 3 / 4,     // 5: 左下 (SW)
        Math.PI,             // 6: 左 (W)
        -Math.PI * 3 / 4     // 7: 左上 (NW)
    ];

    // @anchor: semaphore_bitmask
    // 将一对方向索引转为 8-bit 掩码（忽略顺序）
    function toMask(dirA, dirB) {
        return (1 << dirA) | (1 << dirB);
    }

    // @anchor: semaphore_reverse_map
    // 8-bit 掩码 → 字母 的逆向映射（忽略双手顺序）
    const reverseMap = {};
    for (const [letter, arms] of Object.entries(CipherData.semaphore)) {
        const mask = toMask(arms[0], arms[1]);
        reverseMap[mask] = letter;
    }

    // @anchor: semaphore_encode
    /** 编码：返回方向描述（两方向按从小到大排序输出） */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            if (CipherData.semaphore[ch]) {
                const [a, b] = CipherData.semaphore[ch];
                const minDir = Math.min(a, b);
                const maxDir = Math.max(a, b);
                results.push(minDir + ',' + maxDir);
            } else if (ch === ' ') {
                results.push('·');
            } else {
                results.push(ch);
            }
        }
        return results.join(' ');
    }

    // @anchor: semaphore_decode
    /** 解码：接受任意顺序的方向对，使用掩码查表 */
    function decode(encoded) {
        const parts = encoded.split(/\s+/);
        const results = [];
        for (const part of parts) {
            if (part === '·') {
                results.push(' ');
                continue;
            }
            const match = part.match(/^(\d)\s*,\s*(\d)$/);
            if (match) {
                const d1 = parseInt(match[1], 10);
                const d2 = parseInt(match[2], 10);
                if (d1 >= 0 && d1 <= 7 && d2 >= 0 && d2 <= 7 && d1 !== d2) {
                    const mask = toMask(d1, d2);
                    if (reverseMap[mask]) {
                        results.push(reverseMap[mask]);
                    } else {
                        results.push(part);
                    }
                } else {
                    results.push(part);
                }
            } else {
                results.push(part);
            }
        }
        return results.join('');
    }

    /**
     * 在单个 Canvas 上绘制旗语人物
     * @param {Canvas} canvas
     * @param {number} rightDir - 右手方向索引 0-7（信号员自身视角）
     * @param {number} leftDir  - 左手方向索引 0-7（信号员自身视角）
     */
    function drawFigure(canvas, rightDir, leftDir) {
        const ctx = canvas.getContext('2d');
        const W = canvas.width;
        const H = canvas.height;

        ctx.clearRect(0, 0, W, H);

        const cx = W / 2;
        const cy = H * 0.28;
        const headR = W * 0.12;
        const bodyLen = W * 0.35;
        const armLen = W * 0.32;
        const flagSize = W * 0.1;
        const shoulderY = cy + headR + 2;

        // --- 身体 ---
        ctx.strokeStyle = '#3a3a5c';
        ctx.lineWidth = 2.5;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.moveTo(cx, shoulderY);
        ctx.lineTo(cx, shoulderY + bodyLen);
        ctx.stroke();

        // --- 头部 ---
        ctx.fillStyle = '#ffe0c0';
        ctx.strokeStyle = '#3a3a5c';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(cx, cy, headR, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        // --- 双臂 + 旗子 ---
        drawArm(ctx, cx, shoulderY, DIR_ANGLES[rightDir], armLen, flagSize, '#d94535');  // 右手红色
        drawArm(ctx, cx, shoulderY, DIR_ANGLES[leftDir], armLen, flagSize, '#3567b8');   // 左手蓝色
    }

    // @anchor: semaphore_draw_arm
    function drawArm(ctx, sx, sy, angle, armLen, flagSize, flagColor) {
        const ex = sx + Math.cos(angle) * armLen;
        const ey = sy + Math.sin(angle) * armLen;

        // 手臂
        ctx.strokeStyle = '#3a3a5c';
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        ctx.lineTo(ex, ey);
        ctx.stroke();

        // 旗子（三角形）
        const fx = ex + Math.cos(angle) * flagSize * 0.6;
        const fy = ey + Math.sin(angle) * flagSize * 0.6;

        ctx.fillStyle = flagColor;
        ctx.beginPath();
        const perpAngle = angle + Math.PI / 2;
        ctx.moveTo(ex, ey);
        ctx.lineTo(
            fx + Math.cos(perpAngle) * flagSize * 0.7,
            fy + Math.sin(perpAngle) * flagSize * 0.7
        );
        ctx.lineTo(
            fx - Math.cos(perpAngle) * flagSize * 0.7,
            fy - Math.sin(perpAngle) * flagSize * 0.7
        );
        ctx.closePath();
        ctx.fill();
    }

    // @anchor: semaphore_render
    /** 在容器中渲染旗语可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看旗语信号</div>';
            return;
        }

        const displayChars = chars.slice(0, 16);

        const figuresRow = document.createElement('div');
        figuresRow.className = 'semaphore-figures-row';

        for (const ch of displayChars) {
            const wrapper = document.createElement('div');
            wrapper.className = 'semaphore-figure-wrapper';

            if (CipherData.semaphore[ch]) {
                const canvas = document.createElement('canvas');
                canvas.width = 75;
                canvas.height = 95;
                canvas.className = 'semaphore-canvas';
                const [r, l] = CipherData.semaphore[ch];
                drawFigure(canvas, r, l);
                wrapper.appendChild(canvas);

                const label = document.createElement('span');
                label.className = 'semaphore-char-label';
                label.textContent = ch;
                wrapper.appendChild(label);
            } else if (ch === ' ') {
                wrapper.classList.add('semaphore-space');
                wrapper.textContent = '␣';
            } else {
                wrapper.classList.add('semaphore-unknown');
                wrapper.textContent = ch;
            }

            figuresRow.appendChild(wrapper);
        }

        if (chars.length > 16) {
            const more = document.createElement('span');
            more.className = 'semaphore-more';
            more.textContent = '…(+' + (chars.length - 16) + '个字符)';
            figuresRow.appendChild(more);
        }

        container.appendChild(figuresRow);
    }

    return { encode, decode, render, toMask, reverseMap };
})();
