(function() {
    // ═══════════════════════════════════
    // DOM 引用
    // ═══════════════════════════════════
    const canvas = document.getElementById('canvas');
    const ctx = canvas.getContext('2d');
    const scoreValueEl = document.getElementById('scoreValue');
    const speedDot = document.getElementById('speedDot');
    const speedText = document.getElementById('speedText');
    const hint = document.getElementById('hint');
    const explosionOverlay = document.getElementById('explosionOverlay');
    const finalScoreEl = document.getElementById('finalScore');
    const restartBtn = document.getElementById('restartBtn');

    // ═══════════════════════════════════
    // 画布尺寸
    // ═══════════════════════════════════
    let W, H;

    function resize() {
        W = canvas.width = window.innerWidth;
        H = canvas.height = window.innerHeight;
    }

    // ═══════════════════════════════════
    // 球的状态
    // ═══════════════════════════════════
    const BALL_RADIUS = 28;
    const POINTS_MULTIPLIER = 35;
    const TARGET_SCORE = 3000;
    const MIN_SPEED = 3;
    const MAX_SPEED = 22;

    const ball = {
        x: 0,
        y: 0,
        vx: 0,
        vy: 0,
        radius: BALL_RADIUS,
        grabbed: false,
        grabSpeed: 0,
    };

    function initBall() {
        ball.x = W / 2;
        ball.y = H / 2;
        const angle = Math.random() * Math.PI * 2;
        const speed = 4 + Math.random() * 3;
        ball.vx = Math.cos(angle) * speed;
        ball.vy = Math.sin(angle) * speed;
        ball.grabbed = false;
        ball.grabSpeed = 0;
    }

    // ═══════════════════════════════════
    // 鼠标状态
    // ═══════════════════════════════════
    const mouse = { x: 0, y: 0 };
    const prevMouse = { x: 0, y: 0 };
    const mouseVel = { x: 0, y: 0 };

    // ═══════════════════════════════════
    // 游戏状态
    // ═══════════════════════════════════
    let score = 0;
    let exploded = false;
    let explosionTimer = 0;
    const EXPLOSION_DURATION = 150;

    // ═══════════════════════════════════
    // 粒子系统
    // ═══════════════════════════════════
    const particles = [];
    const MAX_PARTICLES = 400;

    function spawnParticles(x, y, count, hue, speedBase, sizeRange) {
        const actual = Math.min(count, MAX_PARTICLES - particles.length);
        for (let i = 0; i < actual; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = speedBase * (0.3 + Math.random() * 1.4);
            const life = 25 + Math.random() * 55;
            particles.push({
                x,
                y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                life,
                maxLife: life,
                radius: sizeRange[0] + Math.random() * (sizeRange[1] - sizeRange[0]),
                hue: hue + (Math.random() - 0.5) * 40,
                saturation: 70 + Math.random() * 30,
                lightness: 50 + Math.random() * 30,
            });
        }
    }

    function spawnExplosion(x, y) {
        const colors = [
            { h: 15, s: 90, l: 55 },
            { h: 40, s: 95, l: 55 },
            { h: 0, s: 85, l: 50 },
            { h: 55, s: 90, l: 60 },
            { h: 10, s: 80, l: 70 },
        ];
        const count = 120;
        const actual = Math.min(count, MAX_PARTICLES - particles.length);
        for (let i = 0; i < actual; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = 3 + Math.random() * 18;
            const life = 40 + Math.random() * 100;
            const color = colors[Math.floor(Math.random() * colors.length)];
            particles.push({
                x,
                y,
                vx: Math.cos(angle) * speed * (0.6 + Math.random()),
                vy: Math.sin(angle) * speed * (0.6 + Math.random()),
                life,
                maxLife: life,
                radius: 2 + Math.random() * 7,
                hue: color.h,
                saturation: color.s,
                lightness: color.l,
            });
        }
        for (let i = 0; i < 30; i++) {
            const angle = (i / 30) * Math.PI * 2;
            const speed = 14 + Math.random() * 4;
            particles.push({
                x,
                y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                life: 20 + Math.random() * 15,
                maxLife: 35,
                radius: 1 + Math.random() * 3,
                hue: 50,
                saturation: 100,
                lightness: 70,
            });
        }
    }

    function updateParticles() {
        for (let i = particles.length - 1; i >= 0; i--) {
            const p = particles[i];
            p.x += p.vx;
            p.y += p.vy;
            p.vy += 0.06;
            p.vx *= 0.995;
            p.vy *= 0.995;
            p.life--;
            if (p.life <= 0) particles.splice(i, 1);
        }
    }

    function drawParticles(ctx) {
        for (const p of particles) {
            const alpha = Math.max(0, p.life / p.maxLife);
            const size = p.radius * (0.3 + alpha * 0.7);
            ctx.beginPath();
            ctx.arc(p.x, p.y, size, 0, Math.PI * 2);
            ctx.fillStyle = `hsla(${p.hue}, ${p.saturation}%, ${p.lightness}%, ${alpha * 0.85})`;
            ctx.fill();
            if (size > 2 && alpha > 0.3) {
                ctx.beginPath();
                ctx.arc(p.x, p.y, size * 2.2, 0, Math.PI * 2);
                ctx.fillStyle = `hsla(${p.hue}, ${p.saturation}%, ${p.lightness}%, ${alpha * 0.1})`;
                ctx.fill();
            }
        }
    }

    // ═══════════════════════════════════
    // 飘字系统
    // ═══════════════════════════════════
    const popups = [];

    function spawnPopup(x, y, text, hue) {
        popups.push({
            x,
            y,
            text,
            life: 40,
            maxLife: 40,
            vy: -2.8 - Math.random() * 2.2,
            hue: hue || 40,
            scale: 0.8 + Math.random() * 0.4,
        });
        if (popups.length > 20) popups.shift();
    }

    function updatePopups() {
        for (let i = popups.length - 1; i >= 0; i--) {
            const p = popups[i];
            p.y += p.vy;
            p.vy *= 0.95;
            p.life--;
            if (p.life <= 0) popups.splice(i, 1);
        }
    }

    function drawPopups(ctx) {
        for (const p of popups) {
            const alpha = Math.min(1, p.life / (p.maxLife * 0.18));
            const progress = 1 - p.life / p.maxLife;
            const py = p.y - progress * 24;
            ctx.save();
            ctx.globalAlpha = alpha;
            const size = Math.round(20 * p.scale);
            ctx.font = `bold ${size}px "Inter", "PingFang SC", "Microsoft YaHei", sans-serif`;
            ctx.textAlign = 'center';
            ctx.fillStyle = `hsl(${p.hue}, 85%, 48%)`;
            ctx.shadowColor = `hsla(${p.hue}, 90%, 55%, 0.5)`;
            ctx.shadowBlur = 8;
            ctx.fillText(p.text, p.x, py);
            ctx.restore();
        }
    }

    // ═══════════════════════════════════
    // 尾迹
    // ═══════════════════════════════════
    const trail = [];
    const MAX_TRAIL = 16;

    function updateTrail() {
        if (!ball.grabbed && !exploded &&
            (Math.abs(ball.vx) > 0.3 || Math.abs(ball.vy) > 0.3)) {
            trail.push({ x: ball.x, y: ball.y, life: 14, maxLife: 14 });
        }
        for (let i = trail.length - 1; i >= 0; i--) {
            trail[i].life--;
            if (trail[i].life <= 0) trail.splice(i, 1);
        }
        while (trail.length > MAX_TRAIL) trail.shift();
    }

    function drawTrail(ctx) {
        if (ball.grabbed) return;
        for (let i = 0; i < trail.length; i++) {
            const t = trail[i];
            const alpha = (t.life / t.maxLife) * 0.22;
            const r = ball.radius * (0.35 + (t.life / t.maxLife) * 0.65);
            const speedRatio = getSpeedRatio();
            const hue = 210 - speedRatio * 195;
            ctx.beginPath();
            ctx.arc(t.x, t.y, r, 0, Math.PI * 2);
            ctx.fillStyle = `hsla(${hue}, 70%, 55%, ${alpha})`;
            ctx.fill();
        }
    }

    // ═══════════════════════════════════
    // 辅助函数
    // ═══════════════════════════════════
    function getBallSpeed() {
        return Math.sqrt(ball.vx * ball.vx + ball.vy * ball.vy);
    }

    function getSpeedRatio() {
        const speed = ball.grabbed ? ball.grabSpeed : getBallSpeed();
        return Math.min(1, Math.max(0, (speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED)));
    }

    function dist(a, b) {
        const dx = a.x - b.x;
        const dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ═══════════════════════════════════
    // 绘制背景
    // ═══════════════════════════════════
    function drawBackground(ctx) {
        const grad = ctx.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.max(W, H) * 0.7);
        grad.addColorStop(0, '#fefefe');
        grad.addColorStop(0.5, '#f7f9fc');
        grad.addColorStop(1, '#e8edf3');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, W, H);

        ctx.fillStyle = 'rgba(0,0,0,0.025)';
        const dotSpacing = 30;
        for (let x = dotSpacing; x < W; x += dotSpacing) {
            for (let y = dotSpacing; y < H; y += dotSpacing) {
                ctx.beginPath();
                ctx.arc(x, y, 1, 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }

    // ═══════════════════════════════════
    // 绘制球
    // ═══════════════════════════════════
    function drawBall(ctx) {
        if (exploded && explosionTimer > 10) return;

        const { x, y, radius: r } = ball;
        const speedRatio = getSpeedRatio();
        const hue = 210 - speedRatio * 195;
        const isGrabbed = ball.grabbed;

        ctx.save();
        ctx.shadowColor = isGrabbed ?
            `hsla(${hue}, 80%, 50%, 0.4)` :
            'rgba(0,0,0,0.12)';
        ctx.shadowBlur = isGrabbed ? 30 : 14;
        ctx.shadowOffsetX = 0;
        ctx.shadowOffsetY = isGrabbed ? 0 : 3;

        const glowSize = r * (isGrabbed ? 2.0 : (1.5 + speedRatio * 1.2));
        const glowGrad = ctx.createRadialGradient(x, y, r * 0.25, x, y, glowSize);
        glowGrad.addColorStop(0, `hsla(${hue}, 80%, 65%, ${isGrabbed ? 0.45 : 0.25 + speedRatio * 0.2})`);
        glowGrad.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.fillStyle = glowGrad;
        ctx.beginPath();
        ctx.arc(x, y, glowSize, 0, Math.PI * 2);
        ctx.fill();

        const bodyGrad = ctx.createRadialGradient(x - r * 0.25, y - r * 0.3, r * 0.05, x, y, r);
        bodyGrad.addColorStop(0, `hsl(${hue}, 85%, 78%)`);
        bodyGrad.addColorStop(0.3, `hsl(${hue}, 75%, 60%)`);
        bodyGrad.addColorStop(0.7, `hsl(${hue}, 60%, 42%)`);
        bodyGrad.addColorStop(1, `hsl(${hue}, 50%, 28%)`);

        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fillStyle = bodyGrad;
        ctx.fill();
        ctx.restore();

        ctx.beginPath();
        ctx.arc(x - r * 0.28, y - r * 0.32, r * 0.28, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fill();

        if (isGrabbed) {
            ctx.beginPath();
            ctx.arc(x, y, r + 3, 0, Math.PI * 2);
            ctx.strokeStyle = `hsla(${hue}, 70%, 55%, 0.6)`;
            ctx.lineWidth = 2.5;
            ctx.setLineDash([6, 4]);
            ctx.lineDashOffset = performance.now() / 100;
            ctx.stroke();
            ctx.setLineDash([]);
            ctx.lineWidth = 1;
        }
    }

    // ═══════════════════════════════════
    // 绘制鼠标光标
    // ═══════════════════════════════════
    function drawCursor(ctx) {
        if (ball.grabbed || exploded) return;
        const d = dist(mouse, ball);
        if (d < ball.radius + 20) {
            const alpha = Math.max(0, 1 - d / (ball.radius + 20));
            ctx.beginPath();
            ctx.arc(ball.x, ball.y, ball.radius + 8, 0, Math.PI * 2);
            ctx.strokeStyle = `rgba(100,140,200,${alpha * 0.5})`;
            ctx.lineWidth = 2;
            ctx.setLineDash([4, 4]);
            ctx.stroke();
            ctx.setLineDash([]);
        }
    }

    // ═══════════════════════════════════
    // 更新速度指示器
    // ═══════════════════════════════════
    function updateSpeedIndicator() {
        const speed = ball.grabbed ? ball.grabSpeed : getBallSpeed();
        const sr = Math.min(1, Math.max(0, (speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED)));

        speedDot.className = 'speed-dot';
        if (sr >= 0.7) {
            speedDot.classList.add('extreme');
            speedText.textContent = `极限速度 ${speed.toFixed(1)}`;
        } else if (sr >= 0.4) {
            speedDot.classList.add('fast');
            speedText.textContent = `高速 ${speed.toFixed(1)}`;
        } else if (sr >= 0.15) {
            speedDot.classList.add('medium');
            speedText.textContent = `中速 ${speed.toFixed(1)}`;
        } else if (speed > 0.3) {
            speedText.textContent = `低速 ${speed.toFixed(1)}`;
        } else {
            speedText.textContent = '等待中…';
        }
    }

    // ═══════════════════════════════════
    // 更新积分显示
    // ═══════════════════════════════════
    function updateScoreDisplay() {
        scoreValueEl.textContent = score;
        if (score >= TARGET_SCORE * 0.75) {
            scoreValueEl.style.color = '#e53e3e';
        } else if (score >= TARGET_SCORE * 0.4) {
            scoreValueEl.style.color = '#ed8936';
        } else {
            scoreValueEl.style.color = '#2d3748';
        }
    }

    function triggerScorePop() {
        scoreValueEl.classList.remove('pop');
        void scoreValueEl.offsetWidth;
        scoreValueEl.classList.add('pop');
        setTimeout(() => scoreValueEl.classList.remove('pop'), 180);
    }

    // ═══════════════════════════════════
    // 抓球逻辑
    // ═══════════════════════════════════
    function grabBall() {
        if (exploded) return;
        if (ball.grabbed) return;

        const d = dist(mouse, ball);
        if (d > ball.radius + 12) return;

        ball.grabbed = true;
        const speed = getBallSpeed();
        ball.grabSpeed = speed;

        const points = Math.floor(speed * POINTS_MULTIPLIER);
        if (points > 0) {
            score += points;
            triggerScorePop();
            updateScoreDisplay();

            const sr = getSpeedRatio();
            const popupHue = 40 - sr * 30;
            spawnPopup(ball.x, ball.y - ball.radius - 6, '+' + points, popupHue);

            const particleCount = 8 + Math.floor(speed * 2);
            spawnParticles(ball.x, ball.y, particleCount, 200 - sr * 190, speed * 0.4, [1, 4]);
        }

        ball.vx = 0;
        ball.vy = 0;

        hint.classList.add('fade');

        if (score >= TARGET_SCORE) {
            triggerExplosion();
        }
    }

    function releaseBall() {
        if (!ball.grabbed) return;
        ball.grabbed = false;

        const throwSpeed = Math.sqrt(mouseVel.x ** 2 + mouseVel.y ** 2);
        const maxThrow = MAX_SPEED * 1.2;

        if (throwSpeed > 0.5) {
            const throwScale = Math.min(throwSpeed * 1.1, maxThrow) / Math.max(throwSpeed, 0.01);
            ball.vx = mouseVel.x * Math.min(throwScale, maxThrow / Math.max(Math.abs(mouseVel.x), 0.01));
            ball.vy = mouseVel.y * Math.min(throwScale, maxThrow / Math.max(Math.abs(mouseVel.y), 0.01));
            const totalSpeed = Math.sqrt(ball.vx ** 2 + ball.vy ** 2);
            if (totalSpeed > maxThrow) {
                const clamp = maxThrow / totalSpeed;
                ball.vx *= clamp;
                ball.vy *= clamp;
            }
        } else {
            const angle = Math.random() * Math.PI * 2;
            ball.vx = Math.cos(angle) * MIN_SPEED;
            ball.vy = Math.sin(angle) * MIN_SPEED;
        }

        ball.grabSpeed = 0;
        hint.classList.remove('fade');
    }

    // ═══════════════════════════════════
    // 爆炸
    // ═══════════════════════════════════
    function triggerExplosion() {
        exploded = true;
        explosionTimer = 0;
        ball.grabbed = false;
        ball.grabSpeed = 0;
        spawnExplosion(ball.x, ball.y);
        finalScoreEl.textContent = '最终积分：' + score;
        explosionOverlay.classList.add('active');
    }

    function restartGame() {
        score = 0;
        exploded = false;
        explosionTimer = 0;
        particles.length = 0;
        popups.length = 0;
        trail.length = 0;
        explosionOverlay.classList.remove('active');
        updateScoreDisplay();
        updateSpeedIndicator();
        hint.classList.remove('fade');
        initBall();
        scoreValueEl.style.color = '#2d3748';
    }

    // ═══════════════════════════════════
    // 更新球的物理
    // ═══════════════════════════════════
    function updateBallPhysics() {
        if (ball.grabbed || exploded) return;

        ball.x += ball.vx;
        ball.y += ball.vy;

        if (ball.x - ball.radius < 0) {
            ball.x = ball.radius;
            ball.vx = Math.abs(ball.vx);
            spawnParticles(ball.x, ball.y, 4, 210, 1.5, [1, 2.5]);
        } else if (ball.x + ball.radius > W) {
            ball.x = W - ball.radius;
            ball.vx = -Math.abs(ball.vx);
            spawnParticles(ball.x, ball.y, 4, 210, 1.5, [1, 2.5]);
        }

        if (ball.y - ball.radius < 0) {
            ball.y = ball.radius;
            ball.vy = Math.abs(ball.vy);
            spawnParticles(ball.x, ball.y, 4, 210, 1.5, [1, 2.5]);
        } else if (ball.y + ball.radius > H) {
            ball.y = H - ball.radius;
            ball.vy = -Math.abs(ball.vy);
            spawnParticles(ball.x, ball.y, 4, 210, 1.5, [1, 2.5]);
        }

        const speed = getBallSpeed();
        if (speed < MIN_SPEED && speed > 0.01) {
            const scale = MIN_SPEED / speed;
            ball.vx *= scale;
            ball.vy *= scale;
        }
    }

    // ═══════════════════════════════════
    // 主循环
    // ═══════════════════════════════════
    function loop() {
        ctx.clearRect(0, 0, W, H);

        if (exploded) {
            explosionTimer++;
        }

        updateBallPhysics();
        updateTrail();
        updateParticles();
        updatePopups();
        updateSpeedIndicator();

        drawBackground(ctx);
        drawTrail(ctx);
        drawParticles(ctx);
        drawBall(ctx);
        drawCursor(ctx);
        drawPopups(ctx);

        requestAnimationFrame(loop);
    }

    // ═══════════════════════════════════
    // 事件处理
    // ═══════════════════════════════════
    canvas.addEventListener('mousedown', (e) => {
        if (exploded) return;
        if (ball.grabbed) return;
        mouse.x = e.clientX;
        mouse.y = e.clientY;
        grabBall();
    });

    canvas.addEventListener('mousemove', (e) => {
        prevMouse.x = mouse.x;
        prevMouse.y = mouse.y;
        mouse.x = e.clientX;
        mouse.y = e.clientY;
        mouseVel.x = mouse.x - prevMouse.x;
        mouseVel.y = mouse.y - prevMouse.y;

        if (ball.grabbed) {
            ball.x = mouse.x;
            ball.y = mouse.y;
        }

        if (!ball.grabbed && !exploded) {
            const d = dist(mouse, ball);
            canvas.style.cursor = d < ball.radius + 12 ? 'grab' : 'default';
        } else if (ball.grabbed) {
            canvas.style.cursor = 'grabbing';
        }
    });

    canvas.addEventListener('mouseup', () => {
        if (ball.grabbed) {
            releaseBall();
            canvas.style.cursor = 'default';
        }
    });

    canvas.addEventListener('mouseleave', () => {
        if (ball.grabbed) {
            releaseBall();
        }
        canvas.style.cursor = 'default';
    });

    // 触摸事件
    canvas.addEventListener('touchstart', (e) => {
        if (exploded) return;
        e.preventDefault();
        const touch = e.touches[0];
        mouse.x = touch.clientX;
        mouse.y = touch.clientY;
        prevMouse.x = mouse.x;
        prevMouse.y = mouse.y;
        mouseVel.x = 0;
        mouseVel.y = 0;
        if (!ball.grabbed) {
            grabBall();
        }
    }, { passive: false });

    canvas.addEventListener('touchmove', (e) => {
        e.preventDefault();
        const touch = e.touches[0];
        prevMouse.x = mouse.x;
        prevMouse.y = mouse.y;
        mouse.x = touch.clientX;
        mouse.y = touch.clientY;
        mouseVel.x = mouse.x - prevMouse.x;
        mouseVel.y = mouse.y - prevMouse.y;
        if (ball.grabbed) {
            ball.x = mouse.x;
            ball.y = mouse.y;
        }
    }, { passive: false });

    canvas.addEventListener('touchend', (e) => {
        e.preventDefault();
        if (ball.grabbed) {
            releaseBall();
        }
    }, { passive: false });

    restartBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        restartGame();
    });

    window.addEventListener('keydown', (e) => {
        if (e.code === 'Space' && exploded) {
            e.preventDefault();
            restartGame();
        }
    });

    let resizeTimeout;
    window.addEventListener('resize', () => {
        clearTimeout(resizeTimeout);
        resizeTimeout = setTimeout(() => {
            const oldW = W;
            const oldH = H;
            resize();
            if (!ball.grabbed && !exploded) {
                ball.x = Math.min(W - ball.radius, Math.max(ball.radius, ball.x * W / oldW));
                ball.y = Math.min(H - ball.radius, Math.max(ball.radius, ball.y * H / oldH));
            }
        }, 200);
    });

    // ═══════════════════════════════════
    // 初始化
    // ═══════════════════════════════════
    function init() {
        resize();
        initBall();
        mouse.x = W / 2;
        mouse.y = H / 2;
        prevMouse.x = mouse.x;
        prevMouse.y = mouse.y;
        updateScoreDisplay();
        updateSpeedIndicator();
    }

    init();
    requestAnimationFrame(loop);

    console.log('🔵 弹球捕捉已就绪！');
    console.log('   - 球在窗口内自由弹跳');
    console.log('   - 点击球来抓住它（速度越快积分越多）');
    console.log('   - 松开鼠标投掷球');
    console.log('   - 积分达到 3000 球会爆炸！💥');
})();
