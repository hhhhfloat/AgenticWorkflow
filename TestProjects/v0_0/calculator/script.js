/**
 * 简易计算器 - 核心逻辑
 * 支持：加减乘除、小数点、正负切换、开根号、键盘输入
 */
(function() {
    'use strict';

    // ==================== 状态管理 ====================
    const state = {
        currentInput: '0',       // 当前显示的数字字符串
        previousInput: '',       // 前一个操作数
        operator: null,          // 当前运算符
        shouldResetDisplay: false, // 是否在下次输入数字时重置显示
        expressionText: '',      // 表达式显示文本
        lastResult: null         // 上次计算结果
    };

    // ==================== DOM 引用 ====================
    const expressionEl = document.getElementById('expression');
    const resultEl = document.getElementById('result');

    // ==================== 渲染函数 ====================
    function updateDisplay() {
        // 更新结果行
        let displayText = state.currentInput;
        resultEl.textContent = displayText;

        // 根据长度调整字号
        resultEl.classList.remove('small', 'xsmall');
        if (displayText.length > 12) {
            resultEl.classList.add('xsmall');
        } else if (displayText.length > 9) {
            resultEl.classList.add('small');
        }

        // 更新表达式行
        expressionEl.textContent = state.expressionText;

        // 高亮当前选中的运算符按钮
        highlightOperator(state.operator);
    }

    function highlightOperator(op) {
        document.querySelectorAll('.btn-operator').forEach(btn => {
            btn.classList.remove('active');
        });
        if (op) {
            const activeBtn = document.querySelector(`.btn-operator[data-value="${op}"]`);
            if (activeBtn && activeBtn.dataset.action === 'operator') {
                activeBtn.classList.add('active');
            }
        }
    }

    // ==================== 输入处理 ====================
    function inputNumber(num) {
        if (state.shouldResetDisplay) {
            state.currentInput = num;
            state.shouldResetDisplay = false;
        } else {
            if (state.currentInput === '0' && num !== '0') {
                state.currentInput = num;
            } else if (state.currentInput === '0' && num === '0') {
                // 不允许多个零
                return;
            } else {
                // 限制长度
                if (state.currentInput.replace(/[^0-9]/g, '').length >= 12) return;
                state.currentInput += num;
            }
        }
        updateDisplay();
    }

    function inputDecimal() {
        if (state.shouldResetDisplay) {
            state.currentInput = '0.';
            state.shouldResetDisplay = false;
        } else {
            if (!state.currentInput.includes('.')) {
                state.currentInput += '.';
            }
        }
        updateDisplay();
    }

    function handleOperator(op) {
        const current = parseFloat(state.currentInput);

        if (state.operator && !state.shouldResetDisplay) {
            // 链式计算：先算前一步再设置新运算符
            const prev = parseFloat(state.previousInput);
            const result = calculate(prev, current, state.operator);
            state.currentInput = formatResult(result);
            state.previousInput = state.currentInput;
            state.expressionText = state.currentInput + ' ' + op;
        } else if (state.operator && state.shouldResetDisplay) {
            // 用户改变运算符
            state.expressionText = state.previousInput + ' ' + op;
        } else {
            // 首次输入运算符
            state.previousInput = state.currentInput;
            state.expressionText = state.currentInput + ' ' + op;
        }

        state.operator = op;
        state.shouldResetDisplay = true;
        state.lastResult = null;
        updateDisplay();
    }

    function handleEquals() {
        if (state.operator) {
            const current = parseFloat(state.currentInput);
            const prev = parseFloat(state.previousInput);
            const result = calculate(prev, current, state.operator);

            state.expressionText = state.previousInput + ' ' + state.operator + ' ' + state.currentInput + ' =';
            state.currentInput = formatResult(result);
            state.previousInput = '';
            state.operator = null;
            state.shouldResetDisplay = true;
            state.lastResult = state.currentInput;
            updateDisplay();
        }
    }

    // ==================== 运算逻辑 ====================
    function calculate(a, b, op) {
        switch (op) {
            case '+': return a + b;
            case '−': return a - b;
            case '×': return a * b;
            case '÷':
                if (b === 0) return NaN;
                return a / b;
            default: return b;
        }
    }

    /**
     * 格式化计算结果：
     * - 在 [0.001, 100000] 范围内使用普通小数表示
     * - 超出该范围使用科学记数法
     */
    function formatResult(num) {
        if (isNaN(num) || !isFinite(num)) return '错误';

        const absNum = Math.abs(num);
        const inNormalRange = absNum === 0 || (absNum >= 0.001 && absNum <= 100000);

        if (inNormalRange) {
            // 通过 toPrecision(12) 消除浮点误差
            let str = parseFloat(num.toPrecision(12)).toString();

            // 如果字符串过长（>12字符），则截断小数位
            if (str.length > 12) {
                const dotIndex = str.indexOf('.');
                if (dotIndex !== -1) {
                    const intPart = str.substring(0, dotIndex + 1);
                    const maxDecimalLen = 12 - intPart.length;
                    if (maxDecimalLen > 0) {
                        str = parseFloat(num.toFixed(maxDecimalLen)).toString();
                    } else {
                        str = Math.round(num).toString();
                    }
                } else {
                    str = Math.round(num).toString();
                }

                // 二次兜底
                if (str.length > 12) {
                    return trimExpStr(parseFloat(num).toExponential(6));
                }
            }

            return str;
        }

        // 超出范围，使用科学记数法
        return trimExpStr(parseFloat(num).toExponential(6));
    }

    /** 截断科学记数法字符串，确保不超过 12 字符 */
    function trimExpStr(expStr) {
        if (expStr.length <= 12) return expStr;
        const match = expStr.match(/^(-?[\d.]+)e([+-]\d+)$/i);
        if (match) {
            const num = parseFloat(expStr);
            const shorter = num.toExponential(3);
            if (shorter.length <= 12) return shorter;
            return num.toExponential(2);
        }
        return expStr;
    }

    // ==================== 特殊功能 ====================
    function clearAll() {
        state.currentInput = '0';
        state.previousInput = '';
        state.operator = null;
        state.shouldResetDisplay = false;
        state.expressionText = '';
        state.lastResult = null;
        updateDisplay();
    }

    function toggleSign() {
        if (state.currentInput === '0' || state.currentInput === '错误') return;
        if (state.currentInput.startsWith('-')) {
            state.currentInput = state.currentInput.substring(1);
        } else {
            state.currentInput = '-' + state.currentInput;
        }
        updateDisplay();
    }

    function handleSqrt() {
        const current = parseFloat(state.currentInput);

        if (isNaN(current) || !isFinite(current)) return;

        if (state.operator && state.shouldResetDisplay) {
            // 刚按了运算符还没输入新数字，对 previousInput 开根号
            const prev = parseFloat(state.previousInput);
            if (prev < 0) {
                state.currentInput = '错误';
                state.expressionText = '√(' + state.previousInput + ') = 错误';
            } else {
                const result = Math.sqrt(prev);
                state.currentInput = formatResult(result);
                state.previousInput = state.currentInput;
                state.expressionText = '√(' + prev + ')';
            }
        } else if (state.operator && !state.shouldResetDisplay) {
            // 已有运算符且已输入数字，对当前数字开根号
            if (current < 0) {
                state.currentInput = '错误';
                state.expressionText = state.previousInput + ' ' + state.operator + ' √(' + current + ') = 错误';
            } else {
                const sqrtResult = Math.sqrt(current);
                const formatted = formatResult(sqrtResult);
                state.expressionText = state.previousInput + ' ' + state.operator + ' √(' + state.currentInput + ')';
                state.currentInput = formatted;
            }
        } else {
            // 没有待处理的运算符
            if (current < 0) {
                state.currentInput = '错误';
                state.expressionText = '√(' + state.currentInput + ') = 错误';
            } else {
                const sqrtResult = Math.sqrt(current);
                const formatted = formatResult(sqrtResult);
                state.expressionText = '√(' + state.currentInput + ')';
                state.currentInput = formatted;
            }
        }

        state.shouldResetDisplay = true;
        state.lastResult = null;
        updateDisplay();
    }

    function handleBackspace() {
        if (state.shouldResetDisplay) return;
        if (state.currentInput.length === 1 ||
            (state.currentInput.length === 2 && state.currentInput.startsWith('-'))) {
            state.currentInput = '0';
        } else {
            state.currentInput = state.currentInput.slice(0, -1);
        }
        updateDisplay();
    }

    // ==================== 按钮事件绑定 ====================
    function bindButtonEvents() {
        document.querySelector('.buttons').addEventListener('click', function(e) {
            const btn = e.target.closest('button');
            if (!btn) return;

            // 点击动画反馈
            btn.style.transform = 'scale(0.93)';
            setTimeout(() => { btn.style.transform = ''; }, 120);

            const action = btn.dataset.action;
            const value = btn.dataset.value;

            switch (action) {
                case 'number':
                    inputNumber(value);
                    break;
                case 'decimal':
                    inputDecimal();
                    break;
                case 'operator':
                    handleOperator(value);
                    break;
                case 'equals':
                    handleEquals();
                    break;
                case 'clear':
                    clearAll();
                    break;
                case 'toggle':
                    toggleSign();
                    break;
                case 'sqrt':
                    handleSqrt();
                    break;
            }
        });
    }

    // ==================== 键盘事件绑定 ====================
    function bindKeyboardEvents() {
        document.addEventListener('keydown', function(e) {
            const key = e.key;

            if (key >= '0' && key <= '9') {
                e.preventDefault();
                inputNumber(key);
            } else if (key === '.') {
                e.preventDefault();
                inputDecimal();
            } else if (key === '+') {
                e.preventDefault();
                handleOperator('+');
            } else if (key === '-') {
                e.preventDefault();
                handleOperator('−');
            } else if (key === '*') {
                e.preventDefault();
                handleOperator('×');
            } else if (key === '/') {
                e.preventDefault();
                handleOperator('÷');
            } else if (key === 'Enter' || key === '=') {
                e.preventDefault();
                handleEquals();
            } else if (key === 'Escape' || key === 'c' || key === 'C') {
                e.preventDefault();
                clearAll();
            } else if (key === 'r' || key === 'R') {
                e.preventDefault();
                handleSqrt();
            } else if (key === 'Backspace') {
                e.preventDefault();
                handleBackspace();
            }
        });
    }

    // ==================== 初始化 ====================
    function init() {
        bindButtonEvents();
        bindKeyboardEvents();
        updateDisplay();
    }

    init();
})();
