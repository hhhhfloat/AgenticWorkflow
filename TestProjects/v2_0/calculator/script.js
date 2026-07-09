// @anchor: state
let currentInput = '0';       // 当前正在输入的数字
let previousInput = '';       // 前一个数字
let operator = null;          // 当前运算符
let shouldResetInput = false; // 是否需要重置输入
let expressionText = '';      // 表达式显示文本

// @anchor: dom_refs
const resultDisplay = document.getElementById('result');
const expressionDisplay = document.getElementById('expression');

// @anchor: button_event_listener
document.querySelector('.buttons').addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;

    const action = btn.dataset.action;
    const value = btn.dataset.value;

    switch (action) {
        case 'digit':
            handleDigit(value);
            break;
        case 'decimal':
            handleDecimal();
            break;
        case 'add':
        case 'subtract':
        case 'multiply':
        case 'divide':
            handleOperator(action);
            break;
        case 'sqrt':
            handleSqrt();
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
    }

    updateDisplay();
});

// @anchor: keyboard_listener
document.addEventListener('keydown', (e) => {
    const key = e.key;

    if (key >= '0' && key <= '9') {
        handleDigit(key);
    } else if (key === '.') {
        handleDecimal();
    } else if (key === '+') {
        handleOperator('add');
    } else if (key === '-') {
        handleOperator('subtract');
    } else if (key === '*') {
        handleOperator('multiply');
    } else if (key === '/') {
        e.preventDefault();
        handleOperator('divide');
    } else if (key === 'Enter' || key === '=') {
        e.preventDefault();
        handleEquals();
    } else if (key === 'Backspace') {
        handleBackspace();
    } else if (key === 'Escape' || key === 'c' || key === 'C') {
        handleClear();
    } else if (key === 'r' || key === 'R') {
        // @anchor: sqrt_keyboard
        handleSqrt();
    }

    updateDisplay();
});

// @anchor: handleDigit
function handleDigit(digit) {
    if (shouldResetInput) {
        currentInput = '';
        shouldResetInput = false;
    }

    if (currentInput === '0' && digit !== '0') {
        currentInput = digit;
    } else if (currentInput === '0' && digit === '0') {
        // 已经是 0，不再追加
    } else {
        if (currentInput.replace(/[.-]/g, '').length >= 12) return; // 限制 12 位
        currentInput += digit;
    }
}

// @anchor: handleDecimal
function handleDecimal() {
    if (shouldResetInput) {
        currentInput = '0';
        shouldResetInput = false;
    }

    if (!currentInput.includes('.')) {
        currentInput += '.';
    }
}

// @anchor: handleOperator
function handleOperator(op) {
    if (operator !== null && !shouldResetInput) {
        // 连续运算：先计算结果再设置新运算符
        const result = calculate(parseFloat(previousInput), parseFloat(currentInput), operator);
        currentInput = formatResult(result);
        previousInput = currentInput;
        expressionText = currentInput;
    } else {
        previousInput = currentInput;
    }

    operator = op;
    shouldResetInput = true;
    updateExpression();
}

// @anchor: handleSqrt
function handleSqrt() {
    const num = parseFloat(currentInput);
    if (num < 0) {
        currentInput = '错误';
        expressionText = '√(' + currentInput + ')';
    } else {
        const result = Math.sqrt(num);
        expressionText = '√(' + formatDisplay(num) + ')';
        currentInput = formatResult(result);
    }
    operator = null;
    shouldResetInput = true;
}

// @anchor: handleEquals
function handleEquals() {
    if (operator === null) return;

    const prev = parseFloat(previousInput);
    const curr = parseFloat(currentInput);

    expressionText = formatDisplay(prev) + ' ' + getOpSymbol(operator) + ' ' + formatDisplay(curr);

    const result = calculate(prev, curr, operator);
    currentInput = formatResult(result);
    previousInput = '';
    operator = null;
    shouldResetInput = true;
}

// @anchor: handleClear
function handleClear() {
    currentInput = '0';
    previousInput = '';
    operator = null;
    shouldResetInput = false;
    expressionText = '';
}

// @anchor: handleBackspace
function handleBackspace() {
    if (shouldResetInput) return;

    if (currentInput.length === 1 || (currentInput.length === 2 && currentInput.startsWith('-'))) {
        currentInput = '0';
    } else {
        currentInput = currentInput.slice(0, -1);
    }
}

// @anchor: calculate
function calculate(a, b, op) {
    switch (op) {
        case 'add':
            return a + b;
        case 'subtract':
            return a - b;
        case 'multiply':
            return a * b;
        case 'divide':
            if (b === 0) return NaN;
            return a / b;
        default:
            return b;
    }
}

// @anchor: formatResult
function formatResult(num) {
    if (!isFinite(num)) return '错误';

    // 避免浮点数精度问题：限制 10 位有效数字
    const str = parseFloat(num.toPrecision(10)).toString();

    // 结果太长则用科学计数法
    if (str.length > 14) {
        return parseFloat(num.toPrecision(8)).toString();
    }

    return str;
}

// @anchor: formatDisplay
function formatDisplay(num) {
    const str = num.toString();
    if (str.length > 14) {
        return parseFloat(num.toPrecision(8)).toString();
    }
    return str;
}

// @anchor: getOpSymbol
function getOpSymbol(op) {
    switch (op) {
        case 'add': return '+';
        case 'subtract': return '−';
        case 'multiply': return '×';
        case 'divide': return '÷';
        default: return '';
    }
}

// @anchor: updateExpression
function updateExpression() {
    expressionText = formatDisplay(previousInput) + ' ' + getOpSymbol(operator);
}

// @anchor: updateDisplay
function updateDisplay() {
    // 更新结果区
    let displayText = currentInput;

    // 调整字体大小
    if (displayText.length > 10) {
        resultDisplay.classList.add('small');
    } else {
        resultDisplay.classList.remove('small');
    }

    resultDisplay.textContent = displayText;

    // 更新表达式区
    if (expressionText) {
        expressionDisplay.textContent = expressionText;
    } else if (operator) {
        expressionDisplay.textContent = formatDisplay(previousInput) + ' ' + getOpSymbol(operator);
    } else {
        expressionDisplay.textContent = '';
    }
}
