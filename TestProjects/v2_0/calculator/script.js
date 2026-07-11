// ==============================
// 计算器主调度器
// 负责：状态管理、DOM 引用、UI 更新、模式切换、键盘处理、事件分发
// 实数逻辑 → RealCalc（real-calc.js）
// 复数逻辑 → ComplexCalc（complex-calc.js）
// ==============================

// @anchor: js_state
var state = {
    mode: 'normal',           // 'normal' | 'complex'
    angleMode: 'rad',         // 'rad' | 'deg'

    // 普通模式
    currentOperand: '0',
    previousOperand: '',
    operator: null,
    shouldResetScreen: false,
    expressionText: '',

    // 复数模式
    editingPart: 'real',      // 'real' | 'imag'
    realStr: '0',
    imagStr: '0',
    prevRealStr: '',
    prevImagStr: '',
};

// @anchor: js_domRefs
var resultDisplay = document.getElementById('result');
var expressionDisplay = document.getElementById('expression');
var realDisplay = document.getElementById('realDisplay');
var imagDisplay = document.getElementById('imagDisplay');
var imagSign = document.getElementById('imagSign');
var complexIndicator = document.getElementById('complexIndicator');
var modeIndicator = document.getElementById('modeIndicator');
var modeToggle = document.getElementById('modeToggle');
var btnImag = document.getElementById('btnImag');
var angleToggle = document.getElementById('angleToggle');

// ==============================
// 共享工具函数
// ==============================

// @anchor: js_angleConvert
function toRadians(value) {
    return state.angleMode === 'deg' ? value * Math.PI / 180 : value;
}

function fromRadians(value) {
    return state.angleMode === 'deg' ? value * 180 / Math.PI : value;
}

// @anchor: js_formatNumber
function formatNumber(num) {
    if (!isFinite(num)) return '错误';
    if (Number.isInteger(num)) return num.toString();
    var formatted = parseFloat(num.toPrecision(12));
    return formatted.toString();
}

// @anchor: js_getCurrentNumber
function getCurrentNumberStr() {
    if (state.mode === 'complex') {
        return state.editingPart === 'real' ? state.realStr : state.imagStr;
    }
    return state.currentOperand;
}

// @anchor: js_setCurrentNumber
function setCurrentNumberStr(value) {
    if (state.mode === 'complex') {
        if (state.editingPart === 'real') {
            state.realStr = value;
        } else {
            state.imagStr = value;
        }
    } else {
        state.currentOperand = value;
    }
}

// ==============================
// 调度函数（根据 mode 分发）
// ==============================

// @anchor: js_handleNumber
function handleNumber(num) {
    if (state.mode === 'complex') {
        ComplexCalc.handleNumber(num);
    } else {
        RealCalc.handleNumber(num);
    }
}

// @anchor: js_handleDecimal
function handleDecimal() {
    if (state.mode === 'complex') {
        ComplexCalc.handleDecimal();
    } else {
        RealCalc.handleDecimal();
    }
}

// @anchor: js_handleOperator
function handleOperator(op) {
    if (state.mode === 'complex') {
        ComplexCalc.handleOperator(op);
    } else {
        RealCalc.handleOperator(op);
    }
}

// @anchor: js_handleEquals
function handleEquals() {
    if (state.mode === 'complex') {
        ComplexCalc.handleEquals();
    } else {
        RealCalc.handleEquals();
    }
}

// @anchor: js_handleBackspace
function handleBackspace() {
    if (state.mode === 'complex') {
        ComplexCalc.handleBackspace();
    } else {
        RealCalc.handleBackspace();
    }
}

// @anchor: js_handlePercent
function handlePercent() {
    if (state.mode === 'complex') {
        ComplexCalc.handlePercent();
    } else {
        RealCalc.handlePercent();
    }
}

// @anchor: js_handleScientific
function handleScientific(func) {
    if (state.mode === 'complex') {
        ComplexCalc.handleScientific(func);
    } else {
        RealCalc.handleScientific(func);
    }
}

// ==============================
// 清空（两种模式共享）
// @anchor: js_handleClear
// ==============================
function handleClear() {
    if (state.mode === 'complex') {
        state.realStr = '0';
        state.imagStr = '0';
        state.prevRealStr = '';
        state.prevImagStr = '';
        state.editingPart = 'real';
        btnImag.classList.remove('active-imag');
    }
    state.currentOperand = '0';
    state.previousOperand = '';
    state.operator = null;
    state.shouldResetScreen = false;
    state.expressionText = '';
}

// ==============================
// 模式切换
// ==============================

// @anchor: js_toggleAngleMode
function toggleAngleMode() {
    state.angleMode = state.angleMode === 'rad' ? 'deg' : 'rad';
    if (state.angleMode === 'deg') {
        angleToggle.textContent = 'DEG';
        angleToggle.classList.add('deg');
    } else {
        angleToggle.textContent = 'RAD';
        angleToggle.classList.remove('deg');
    }
}

// @anchor: js_switchMode
function switchMode(newMode) {
    handleClear();
    state.mode = newMode;

    // 更新模式切换按钮
    document.querySelectorAll('.mode-option').forEach(function(opt) {
        opt.classList.toggle('active', opt.dataset.mode === newMode);
    });

    // 更新模式指示器
    if (newMode === 'complex') {
        modeIndicator.textContent = '复数模式';
        modeIndicator.classList.add('complex');
        complexIndicator.classList.remove('hidden');
        btnImag.style.display = '';
        btnImag.classList.remove('active-imag');
    } else {
        modeIndicator.textContent = '普通模式';
        modeIndicator.classList.remove('complex');
        complexIndicator.classList.add('hidden');
        btnImag.style.display = 'none';
        btnImag.classList.remove('active-imag');
    }

    updateDisplay();
}

// @anchor: js_handleImagToggle
function handleImagToggle() {
    if (state.mode !== 'complex') return;

    state.editingPart = state.editingPart === 'real' ? 'imag' : 'real';

    if (state.editingPart === 'imag') {
        btnImag.classList.add('active-imag');
    } else {
        btnImag.classList.remove('active-imag');
    }
}

// ==============================
// 显示更新
// @anchor: js_updateDisplay
// ==============================
function updateDisplay() {
    if (state.mode === 'complex') {
        updateComplexDisplay();
    } else {
        updateNormalDisplay();
    }
    expressionDisplay.textContent = state.expressionText;
}

function updateNormalDisplay() {
    complexIndicator.classList.add('hidden');
    realDisplay.textContent = state.currentOperand;
    realDisplay.classList.remove('editing');
    imagDisplay.classList.remove('editing');

    if (state.currentOperand.length > 10) {
        resultDisplay.classList.add('small');
    } else {
        resultDisplay.classList.remove('small');
    }
}

function updateComplexDisplay() {
    resultDisplay.classList.remove('small');

    var imagNum = parseFloat(state.imagStr) || 0;

    complexIndicator.classList.remove('hidden');

    realDisplay.textContent = state.realStr;

    // 符号与虚部数值
    if (imagNum >= 0) {
        imagSign.textContent = '+';
        imagDisplay.textContent = state.imagStr;
    } else {
        imagSign.textContent = '−';
        imagDisplay.textContent = formatNumber(Math.abs(imagNum));
    }

    // 高亮当前编辑部分
    if (state.editingPart === 'real') {
        realDisplay.classList.add('editing');
        imagDisplay.classList.remove('editing');
    } else {
        imagDisplay.classList.add('editing');
        realDisplay.classList.remove('editing');
    }

    // 调整字体大小
    var totalLen = state.realStr.length + state.imagStr.length + 4;
    if (totalLen > 16) {
        resultDisplay.classList.add('small');
    }
}

// @anchor: js_updateActiveOperator
function updateActiveOperator() {
    document.querySelectorAll('.btn-operator, .btn-scientific[data-value="^"]').forEach(function(btn) {
        btn.classList.remove('active');
        if (btn.dataset.value === state.operator) {
            btn.classList.add('active');
        }
    });
}

// ==============================
// 键盘支持
// @anchor: js_handleKeyboard
// ==============================
function handleKeyboard(e) {
    var key = e.key;

    // 模式切换快捷键 Ctrl+M
    if (e.ctrlKey && key === 'm') {
        e.preventDefault();
        var newMode = state.mode === 'normal' ? 'complex' : 'normal';
        switchMode(newMode);
        updateDisplay();
        updateActiveOperator();
        return;
    }

    // 角度模式切换快捷键 Ctrl+D
    if (e.ctrlKey && key === 'd') {
        e.preventDefault();
        toggleAngleMode();
        return;
    }

    if (key >= '0' && key <= '9') {
        handleNumber(key);
    } else if (key === '.') {
        handleDecimal();
    } else if (key === 'i' || key === 'I') {
        if (state.mode === 'complex') {
            handleImagToggle();
        }
    } else if (key === '+') {
        handleOperator('+');
    } else if (key === '-') {
        handleOperator('−');
    } else if (key === '*') {
        handleOperator('×');
    } else if (key === '/') {
        e.preventDefault();
        handleOperator('÷');
    } else if (key === '^') {
        e.preventDefault();
        handleScientific('^');
    } else if (key === 'Enter' || key === '=') {
        e.preventDefault();
        handleEquals();
    } else if (key === 'Escape' || key === 'c' || key === 'C') {
        handleClear();
    } else if (key === 'Backspace') {
        handleBackspace();
    } else if (key === '%') {
        handlePercent();
    } else if (key === 's' || key === 'S') {
        handleScientific('sin');
    } else if (key === 'o' || key === 'O') {
        handleScientific('cos');
    } else if (key === 't' || key === 'T') {
        handleScientific('tan');
    }

    updateDisplay();
    updateActiveOperator();
}

// ==============================
// 初始化
// @anchor: js_init
// ==============================
function init() {
    // 按钮点击
    document.querySelectorAll('.btn').forEach(function(btn) {
        btn.addEventListener('click', function() {
            var action = btn.dataset.action;
            var value = btn.dataset.value;

            switch (action) {
                case 'number':
                    handleNumber(value);
                    break;
                case 'decimal':
                    handleDecimal();
                    break;
                case 'operator':
                    handleOperator(value);
                    break;
                case 'equals':
                    handleEquals();
                    break;
                case 'clear':
                    handleClear();
                    break;
                case 'backspace':
                    handleBackspace();
                    break;
                case 'percent':
                    handlePercent();
                    break;
                case 'scientific':
                    handleScientific(value);
                    break;
                case 'imag':
                    handleImagToggle();
                    break;
            }
            updateDisplay();
            updateActiveOperator();
        });
    });

    // 模式切换
    modeToggle.addEventListener('click', function(e) {
        var option = e.target.closest('.mode-option');
        if (!option) return;
        var newMode = option.dataset.mode;
        if (newMode !== state.mode) {
            switchMode(newMode);
        }
    });

    // 角度模式切换
    angleToggle.addEventListener('click', function() {
        toggleAngleMode();
    });

    // 键盘支持
    document.addEventListener('keydown', handleKeyboard);
}

// @anchor: js_start
init();
