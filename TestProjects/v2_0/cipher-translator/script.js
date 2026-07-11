/**
 * 古典密码互译器 — 主脚本
 * 协调输入事件，将文本分发给五个密码模块进行渲染。
 * 同时管理输入模式切换：文本输入 / 盲文点阵输入 / A1Z26数字输入 / 旗语九宫格输入。
 */
(function () {
    'use strict';

    // --- DOM 引用 ---
    const textInput = document.getElementById('textInput');
    const clearBtn = document.getElementById('clearBtn');
    const charCount = document.getElementById('charCount');

    // 四个输入区域
    // @anchor: js_inputSections
    const textInputSection = document.getElementById('textInputSection');
    const brailleInputSection = document.getElementById('brailleInputSection');
    const a1z26InputSection = document.getElementById('a1z26InputSection');
    const semaphoreInputSection = document.getElementById('semaphoreInputSection');

    // 模式选择器
    const modeSelector = document.getElementById('modeSelector');

    // 盲文点阵输入 DOM
    const brailleDotInput = document.getElementById('brailleDotInput');
    const brailleInputChar = document.getElementById('brailleInputChar');
    const brailleInputLetter = document.getElementById('brailleInputLetter');
    const brailleAddBtn = document.getElementById('brailleAddBtn');
    const brailleClearDotsBtn = document.getElementById('brailleClearDotsBtn');
    const brailleClearTextBtn = document.getElementById('brailleClearTextBtn');
    const brailleOutputTextbox = document.getElementById('brailleOutputTextbox');

    // A1Z26 数字输入 DOM
    // @anchor: js_a1z26InputDom
    const a1z26NumberInput = document.getElementById('a1z26NumberInput');
    const a1z26InputNumbers = document.getElementById('a1z26InputNumbers');
    const a1z26InputLetters = document.getElementById('a1z26InputLetters');
    const a1z26AddBtn = document.getElementById('a1z26AddBtn');
    const a1z26ClearBtn = document.getElementById('a1z26ClearBtn');
    const a1z26OutputTextbox = document.getElementById('a1z26OutputTextbox');

    // 旗语九宫格输入 DOM
    const semaphoreGrid = document.getElementById('semaphoreGrid');
    const semaphoreCenterLetter = document.getElementById('semaphoreCenterLetter');
    const semaphoreAddBtn = document.getElementById('semaphoreAddBtn');
    const semaphoreClearBtn = document.getElementById('semaphoreClearBtn');
    const semaphoreClearTextBtn = document.getElementById('semaphoreClearTextBtn');
    const semaphoreOutputTextbox = document.getElementById('semaphoreOutputTextbox');

    // 密码模块输出容器
    const containers = {
        braille:   document.getElementById('braille-output'),
        a1z26:     document.getElementById('a1z26-output'),
        tapcode:   document.getElementById('tapcode-output'),
        semaphore: document.getElementById('semaphore-output'),
        nato:      document.getElementById('nato-output')
    };

    // 密码模块引用（由外部脚本定义）
    const modules = {
        braille:   typeof BrailleCipher !== 'undefined'   ? BrailleCipher   : null,
        a1z26:     typeof A1Z26Cipher !== 'undefined'     ? A1Z26Cipher     : null,
        tapcode:   typeof TapCodeCipher !== 'undefined'   ? TapCodeCipher   : null,
        semaphore: typeof SemaphoreCipher !== 'undefined' ? SemaphoreCipher : null,
        nato:      typeof NATOPhoneticCipher !== 'undefined' ? NATOPhoneticCipher : null
    };

    // --- 当前输入模式 ---
    var currentMode = 'text'; // 'text' | 'braille' | 'a1z26' | 'semaphore'

    // --- 盲文点阵输入状态 ---
    var brailleDotsState = [false, false, false, false, false, false];
    var brailleAccumulatedText = '';

    // --- 旗语九宫格输入状态 ---
    var semaphoreRightArm = null; // 右手方向索引 (0-7)，null 表示未选
    var semaphoreLeftArm = null;  // 左手方向索引 (0-7)，null 表示未选
    var semaphoreAccumulatedText = '';

    // --- A1Z26 累积翻译文本 ---
    var a1z26AccumulatedText = '';

    // ============================================================
    //  模式切换
    // ============================================================

    /**
     * 切换到指定输入模式
     * @param {string} mode - 'text' | 'braille' | 'a1z26' | 'semaphore'
     */
    function switchMode(mode) {
        if (currentMode === mode) return;
        currentMode = mode;

        // 更新模式按钮高亮
        var btns = modeSelector.querySelectorAll('.mode-btn');
        btns.forEach(function (btn) {
            if (btn.getAttribute('data-mode') === mode) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });

        // 切换输入区域显示
        // @anchor: js_switchModeDisplay
        textInputSection.style.display = (mode === 'text') ? '' : 'none';
        brailleInputSection.style.display = (mode === 'braille') ? '' : 'none';
        a1z26InputSection.style.display = (mode === 'a1z26') ? '' : 'none';
        semaphoreInputSection.style.display = (mode === 'semaphore') ? '' : 'none';

        // 切换模式时重置对应输入状态
        if (mode === 'braille') {
            clearBrailleDots();
        } else if (mode === 'semaphore') {
            clearSemaphoreSelection();
        } else if (mode === 'a1z26') {
            clearA1Z26Input();
        }
    }

    // 模式按钮点击事件
    if (modeSelector) {
        modeSelector.addEventListener('click', function (e) {
            var btn = e.target.closest('.mode-btn');
            if (!btn) return;
            var mode = btn.getAttribute('data-mode');
            if (mode) {
                switchMode(mode);
            }
        });
    }

    // ============================================================
    //  输出文本框更新
    // ============================================================

    function updateBrailleOutputTextbox() {
        if (brailleOutputTextbox) {
            brailleOutputTextbox.value = brailleAccumulatedText;
        }
    }

    function clearBrailleAccumulatedText() {
        brailleAccumulatedText = '';
        updateBrailleOutputTextbox();
    }

    function updateA1Z26OutputTextbox() {
        if (a1z26OutputTextbox) {
            a1z26OutputTextbox.value = a1z26AccumulatedText;
        }
    }

    function clearA1Z26AccumulatedText() {
        a1z26AccumulatedText = '';
        updateA1Z26OutputTextbox();
    }

    function updateSemaphoreOutputTextbox() {
        if (semaphoreOutputTextbox) {
            semaphoreOutputTextbox.value = semaphoreAccumulatedText;
        }
    }

    function clearSemaphoreAccumulatedText() {
        semaphoreAccumulatedText = '';
        updateSemaphoreOutputTextbox();
    }

    // ============================================================
    //  A1Z26 数字输入
    // ============================================================

    /**
     * 根据 A1Z26 输入框内容解码为字母串
     * @returns {{ numbers: string, letters: string, isValid: boolean }}
     */
    // @anchor: js_computeA1Z26
    function computeA1Z26Result() {
        var raw = a1z26NumberInput.value.trim();
        if (!raw) {
            return { numbers: '-', letters: '-', isValid: false };
        }

        // 分割：空格或连字符
        var parts = raw.split(/[\-\s]+/);
        var decodedLetters = [];
        var allValid = true;

        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (part === '') continue;
            var num = parseInt(part, 10);
            if (!isNaN(num) && num >= 1 && num <= 26) {
                decodedLetters.push(String.fromCharCode(num + 64));
            } else {
                // 无效数字，保留原始内容
                decodedLetters.push('?');
                allValid = false;
            }
        }

        var letters = decodedLetters.length > 0 ? decodedLetters.join('') : '-';

        return {
            numbers: raw,
            letters: letters,
            isValid: allValid && decodedLetters.length > 0
        };
    }

    /**
     * 更新 A1Z26 输入 UI
     */
    function updateA1Z26InputUI() {
        var result = computeA1Z26Result();
        a1z26InputNumbers.textContent = result.numbers;
        a1z26InputLetters.textContent = result.letters;

        if (result.isValid) {
            a1z26AddBtn.disabled = false;
            a1z26InputLetters.classList.add('result-ready');
        } else {
            a1z26AddBtn.disabled = true;
            a1z26InputLetters.classList.remove('result-ready');
        }

        // 实时预览翻译结果到输出文本框
        if (result.isValid && result.letters !== '-') {
            a1z26OutputTextbox.value = result.letters;
        } else if (!a1z26NumberInput.value.trim()) {
            a1z26OutputTextbox.value = a1z26AccumulatedText;
        }
    }

    /**
     * 清除 A1Z26 输入
     */
    function clearA1Z26Input() {
        a1z26NumberInput.value = '';
        a1z26AccumulatedText = '';
        updateA1Z26InputUI();
        updateA1Z26OutputTextbox();
    }

    /**
     * 将 A1Z26 解码结果添加到文本输入框
     */
    function addA1Z26ToText() {
        var result = computeA1Z26Result();
        if (result.isValid) {
            var letters = result.letters;
            var current = textInput.value;
            if (current.length + letters.length > 60) {
                letters = letters.slice(0, 60 - current.length);
            }
            if (letters.length > 0) {
                textInput.value = current + letters;
                textInput.dispatchEvent(new Event('input', { bubbles: true }));
                // 累积到输出文本框
                a1z26AccumulatedText += letters;
                updateA1Z26OutputTextbox();
                clearA1Z26Input();
            }
        }
    }

    // --- A1Z26 输入事件绑定 ---
    if (a1z26NumberInput) {
        a1z26NumberInput.addEventListener('input', updateA1Z26InputUI);
    }

    if (a1z26AddBtn) {
        a1z26AddBtn.addEventListener('click', addA1Z26ToText);
    }

    if (a1z26ClearBtn) {
        a1z26ClearBtn.addEventListener('click', clearA1Z26Input);
    }

    // ============================================================
    //  盲文点阵输入
    // ============================================================

    /**
     * 根据当前点阵状态计算盲文 Unicode 字符和对应字母
     * @returns {{ brailleChar: string, letter: string }}
     */
    function computeBrailleFromDots() {
        var cp = 0x2800;
        if (brailleDotsState[0]) cp |= 0x01;
        if (brailleDotsState[1]) cp |= 0x02;
        if (brailleDotsState[2]) cp |= 0x04;
        if (brailleDotsState[3]) cp |= 0x08;
        if (brailleDotsState[4]) cp |= 0x10;
        if (brailleDotsState[5]) cp |= 0x20;
        var brailleChar = String.fromCodePoint(cp);

        var letter = '';
        if (modules.braille && cp !== 0x2800) {
            letter = modules.braille.decode(brailleChar);
            if (letter === brailleChar || letter.length !== 1) {
                letter = '';
            }
        }

        return { brailleChar: brailleChar, letter: letter };
    }

    function updateBrailleInputUI() {
        var dotEls = brailleDotInput.querySelectorAll('.braille-input-dot');
        dotEls.forEach(function (el) {
            var idx = parseInt(el.getAttribute('data-index'), 10);
            if (brailleDotsState[idx]) {
                el.classList.add('active');
            } else {
                el.classList.remove('active');
            }
        });

        var result = computeBrailleFromDots();
        brailleInputChar.textContent = result.brailleChar;
        brailleInputLetter.textContent = result.letter || '-';

        if (result.letter && result.letter.length === 1 && /[A-Z]/.test(result.letter)) {
            brailleAddBtn.disabled = false;
        } else {
            brailleAddBtn.disabled = true;
        }
    }

    function toggleBrailleDot(index) {
        brailleDotsState[index] = !brailleDotsState[index];
        updateBrailleInputUI();
    }

    function clearBrailleDots() {
        for (var i = 0; i < 6; i++) {
            brailleDotsState[i] = false;
        }
        updateBrailleInputUI();
    }

    function addBrailleToText() {
        var result = computeBrailleFromDots();
        if (result.letter && result.letter.length === 1 && /[A-Z]/.test(result.letter)) {
            appendToTextInput(result.letter);
            // 累积到输出文本框
            brailleAccumulatedText += result.letter;
            updateBrailleOutputTextbox();
            clearBrailleDots();
        }
    }

    // --- 盲文点阵事件绑定 ---
    if (brailleDotInput) {
        brailleDotInput.addEventListener('click', function (e) {
            var dot = e.target.closest('.braille-input-dot');
            if (!dot) return;
            var idx = parseInt(dot.getAttribute('data-index'), 10);
            if (!isNaN(idx) && idx >= 0 && idx <= 5) {
                toggleBrailleDot(idx);
            }
        });

        brailleDotInput.addEventListener('keydown', function (e) {
            if (e.key === ' ' || e.key === 'Enter') {
                e.preventDefault();
                var dot = e.target.closest('.braille-input-dot');
                if (dot) {
                    var idx = parseInt(dot.getAttribute('data-index'), 10);
                    if (!isNaN(idx) && idx >= 0 && idx <= 5) {
                        toggleBrailleDot(idx);
                    }
                }
            }
        });
    }

    if (brailleAddBtn) {
        brailleAddBtn.addEventListener('click', addBrailleToText);
    }

    if (brailleClearDotsBtn) {
        brailleClearDotsBtn.addEventListener('click', clearBrailleDots);
    }

    if (brailleClearTextBtn) {
        brailleClearTextBtn.addEventListener('click', clearBrailleAccumulatedText);
    }

    // ============================================================
    //  旗语九宫格输入
    // ============================================================

    /**
     * 根据当前选择的双臂方向查找对应的字母
     * 利用 SemaphoreCipher.decode 进行反向查找
     * @returns {string} 字母，或空字符串
     */
    function computeSemaphoreLetter() {
        if (semaphoreRightArm === null || semaphoreLeftArm === null) {
            return '';
        }
        if (!modules.semaphore) {
            return '';
        }
        // 使用 decode 方法：传入 "rightDir,leftDir" 格式
        var encoded = semaphoreRightArm + ',' + semaphoreLeftArm;
        var decoded = modules.semaphore.decode(encoded);
        if (decoded && decoded.length === 1 && /[A-Z]/.test(decoded)) {
            return decoded;
        }
        return '';
    }

    /**
     * 更新旗语九宫格 UI
     */
    function updateSemaphoreUI() {
        // 清除所有格子的高亮
        var cells = semaphoreGrid.querySelectorAll('.semaphore-cell');
        cells.forEach(function (cell) {
            cell.classList.remove('right-arm', 'left-arm');
        });

        // 高亮右手选择
        if (semaphoreRightArm !== null) {
            var rightCell = semaphoreGrid.querySelector('.semaphore-cell[data-dir="' + semaphoreRightArm + '"]');
            if (rightCell) {
                rightCell.classList.add('right-arm');
            }
        }

        // 高亮左手选择
        if (semaphoreLeftArm !== null) {
            var leftCell = semaphoreGrid.querySelector('.semaphore-cell[data-dir="' + semaphoreLeftArm + '"]');
            if (leftCell) {
                leftCell.classList.add('left-arm');
            }
        }

        // 更新中心字母显示
        var letter = computeSemaphoreLetter();
        if (letter) {
            semaphoreCenterLetter.textContent = letter;
            semaphoreCenterLetter.classList.add('result-ready');
            semaphoreAddBtn.disabled = false;
        } else {
            semaphoreCenterLetter.textContent = '-';
            semaphoreCenterLetter.classList.remove('result-ready');
            semaphoreAddBtn.disabled = true;
        }
    }

    /**
     * 处理旗语格子点击
     * 第一次点击 → 右手（红），第二次点击 → 左手（蓝）
     * 如果点击已选中的格子则取消选择
     * @param {number} dir - 方向索引 0-7
     */
    function handleSemaphoreCellClick(dir) {
        // 如果点击的是右手已选中的格子 → 取消右手
        if (semaphoreRightArm === dir) {
            semaphoreRightArm = null;
            updateSemaphoreUI();
            return;
        }
        // 如果点击的是左手已选中的格子 → 取消左手
        if (semaphoreLeftArm === dir) {
            semaphoreLeftArm = null;
            updateSemaphoreUI();
            return;
        }

        // 如果右手未选 → 先选右手
        if (semaphoreRightArm === null) {
            semaphoreRightArm = dir;
            updateSemaphoreUI();
            return;
        }

        // 如果左手未选 → 选左手
        if (semaphoreLeftArm === null) {
            // 不能和右手选同一个方向
            if (dir === semaphoreRightArm) return;
            semaphoreLeftArm = dir;
            updateSemaphoreUI();
            return;
        }

        // 两手都已选 → 重新开始：新点击变为右手，清除左手
        semaphoreRightArm = dir;
        semaphoreLeftArm = null;
        updateSemaphoreUI();
    }

    /**
     * 清除旗语选择
     */
    function clearSemaphoreSelection() {
        semaphoreRightArm = null;
        semaphoreLeftArm = null;
        updateSemaphoreUI();
    }

    /**
     * 将当前旗语对应的字母添加到文本输入框
     */
    function addSemaphoreToText() {
        var letter = computeSemaphoreLetter();
        if (letter) {
            appendToTextInput(letter);
            // 累积到输出文本框
            semaphoreAccumulatedText += letter;
            updateSemaphoreOutputTextbox();
            clearSemaphoreSelection();
        }
    }

    // --- 旗语九宫格事件绑定 ---
    if (semaphoreGrid) {
        semaphoreGrid.addEventListener('click', function (e) {
            var cell = e.target.closest('.semaphore-cell');
            if (!cell) return;
            var dir = parseInt(cell.getAttribute('data-dir'), 10);
            if (!isNaN(dir) && dir >= 0 && dir <= 7) {
                handleSemaphoreCellClick(dir);
            }
        });
    }

    if (semaphoreAddBtn) {
        semaphoreAddBtn.addEventListener('click', addSemaphoreToText);
    }

    if (semaphoreClearBtn) {
        semaphoreClearBtn.addEventListener('click', clearSemaphoreSelection);
    }

    if (semaphoreClearTextBtn) {
        semaphoreClearTextBtn.addEventListener('click', clearSemaphoreAccumulatedText);
    }

    // ============================================================
    //  通用文本追加
    // ============================================================

    /**
     * 向文本输入框追加字符并触发更新
     * @param {string} char - 要追加的单个字母
     */
    function appendToTextInput(char) {
        var current = textInput.value;
        if (current.length >= 60) return;
        textInput.value = current + char;
        textInput.dispatchEvent(new Event('input', { bubbles: true }));
    }

    // ============================================================
    //  更新所有密码面板
    // ============================================================

    function updateAllCiphers(text) {
        var trimmed = text.trim();

        if (modules.braille) {
            modules.braille.render(containers.braille, trimmed);
        }
        if (modules.a1z26) {
            modules.a1z26.render(containers.a1z26, trimmed);
        }
        if (modules.tapcode) {
            modules.tapcode.render(containers.tapcode, trimmed);
        }
        if (modules.semaphore) {
            modules.semaphore.render(containers.semaphore, trimmed);
        }
        if (modules.nato) {
            modules.nato.render(containers.nato, trimmed);
        }
    }

    // ============================================================
    //  文本输入事件处理
    // ============================================================

    function onInputChange() {
        var value = textInput.value;
        var len = value.length;
        charCount.textContent = len + ' / 60';

        if (len > 60) {
            textInput.value = value.slice(0, 60);
            charCount.textContent = '60 / 60';
        }

        updateAllCiphers(textInput.value);
    }

    function onClear() {
        textInput.value = '';
        charCount.textContent = '0 / 60';
        updateAllCiphers('');
        textInput.focus();
    }

    // --- 绑定文本输入事件 ---
    textInput.addEventListener('input', onInputChange);
    clearBtn.addEventListener('click', onClear);

    // ============================================================
    //  初始化
    // ============================================================

    // 默认显示文本输入模式
    switchMode('text');
    updateAllCiphers('');
    updateBrailleInputUI();
    updateA1Z26InputUI();
    updateSemaphoreUI();

    // 加载后自动展示示例
    setTimeout(function () {
        if (textInput.value === '') {
            textInput.value = 'HELLO';
            charCount.textContent = '5 / 60';
            updateAllCiphers('HELLO');
        }
    }, 300);

})();
