/**
 * 旗语 (Semaphore) 模块
 * 使用 Canvas 绘制旗语信号人物：圆形头部 + 身体 + 双臂持旗
 *
 * 8个基本方向 (索引 0-7)：
 *   0: 上(N), 1: 右上(NE), 2: 右(E), 3: 右下(SE),
 *   4: 下(S), 5: 左下(SW), 6: 左(W), 7: 左上(NW)
 *
 * 标准旗语字母表（26个字母各对应唯一的双臂方向对，忽略顺序）：
 *   第一圈 A-G：右手固定在 S(4)，左手逆时针从 SW 到 SE
 *   第二圈 H-N：右手固定在 SW(5)，左手逆时针从 W 到 S
 *   第三圈 O-T：右手固定在 W(6)，左手逆时针从 NW 到 S
 *   第四圈 U-Y：右手固定在 NW(7)，左手逆时针从 N 到 S
 *   Z：SE(3) + S(4)
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

    // @anchor: semaphore_letter_map
    // 字母 → [方向1, 方向2]（标准旗语编码，忽略双手顺序）
    // 标准：从信号员视角，1=下(S), 顺时针：2=左下(SW),3=左(W),4=左上(NW),5=上(N),6=右上(NE),7=右(E),8=右下(SE)
    // 映射到本系统方向索引：1→4, 2→5, 3→6, 4→7, 5→0, 6→1, 7→2, 8→3
    const semaphoreMap = {
        // 第一圈：右手 S(4)，左手逆时针 SW→W→NW→N→NE→E→SE
        'A': [4, 5],  // S + SW
        'B': [4, 6],  // S + W
        'C': [4, 7],  // S + NW
        'D': [4, 0],  // S + N
        'E': [4, 1],  // S + NE
        'F': [4, 2],  // S + E
        'G': [4, 3],  // S + SE
        // 第二圈：右手 SW(5)，左手逆时针 W→NW→N→NE→E→SE→S
        'H': [5, 6],  // SW + W
        'I': [5, 7],  // SW + NW
        'J': [5, 0],  // SW + N
        'K': [5, 1],  // SW + NE
        'L': [5, 2],  // SW + E
        'M': [5, 3],  // SW + SE
        'N': [5, 4],  // SW + S
        // 第三圈：右手 W(6)，左手逆时针 NW→N→NE→E→SE→S
        'O': [6, 7],  // W + NW
        'P': [6, 0],  // W + N
        'Q': [6, 1],  // W + NE
        'R': [6, 2],  // W + E
        'S': [6, 3],  // W + SE
        'T': [6, 4],  // W + S
        // 第四圈：右手 NW(7)，左手逆时针 N→NE→E→SE→S
        'U': [7, 0],  // NW + N
        'V': [7, 1],  // NW + NE
        'W': [7, 2],  // NW + E
        'X': [7, 3],  // NW + SE
        'Y': [7, 4],  // NW + S
        // Z
        'Z': [3, 4]   // SE + S
    };

    // @anchor: semaphore_bitmask
    // 将一对方向索引转为 8-bit 掩码（忽略顺序）
    function toMask(dirA, dirB) {
        return (1 << dirA) | (1 << dirB);
    }

    // @anchor: semaphore_reverse_map
    // 8-bit 掩码 → 字母 的逆向映射（忽略双手顺序）
    const reverseMap = {};
    for (const [letter, arms] of Object.entries(semaphoreMap)) {
        const mask = toMask(arms[0], arms[1]);
        reverseMap[mask] = letter;
    }

    /** 编码：返回方向描述（两方向按从小到大排序输出） */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            if (semaphoreMap[ch]) {
                const [a, b] = semaphoreMap[ch];
                // 输出时按小→大排序，保证顺序无关
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

    /** 解码：接受任意顺序的方向对，使用掩码查表 */
    function decode(encoded) {
        const parts = encoded.split(/\s+/);
        const results = [];
        for (const part of parts) {
            if (part === '·') {
                results.push(' ');
                continue;
            }
            // 尝试解析 "dir1,dir2"
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
     * @param {number} rightDir - 右手方向索引 0-7
     * @param {number} leftDir  - 左手方向索引 0-7
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
        // 旗杆末端，画一个小三角旗
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

    /** 在容器中渲染旗语可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看旗语信号</div>';
            return;
        }

        const displayChars = chars.slice(0, 16); // 最多显示16个

        const figuresRow = document.createElement('div');
        figuresRow.className = 'semaphore-figures-row';

        for (const ch of displayChars) {
            const wrapper = document.createElement('div');
            wrapper.className = 'semaphore-figure-wrapper';

            if (semaphoreMap[ch]) {
                const canvas = document.createElement('canvas');
                canvas.width = 75;
                canvas.height = 95;
                canvas.className = 'semaphore-canvas';
                const [r, l] = semaphoreMap[ch];
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
