// ==============================
// 实数计算模块 - RealCalc
// 包含普通模式下的所有输入处理与运算逻辑
// ==============================

var RealCalc = (function() {
    'use strict';

    // @anchor: real_handleNumber
    function handleNumber(num) {
        if (state.shouldResetScreen) {
            state.currentOperand = num;
            state.shouldResetScreen = false;
        } else {
            if (state.currentOperand === '0' && num !== '0') {
                state.currentOperand = num;
            } else if (state.currentOperand !== '0') {
                state.currentOperand += num;
            }
        }
    }

    // @anchor: real_handleDecimal
    function handleDecimal() {
        if (state.shouldResetScreen) {
            state.currentOperand = '0.';
            state.shouldResetScreen = false;
            return;
        }
        if (!state.currentOperand.includes('.')) {
            state.currentOperand += '.';
        }
    }

    // @anchor: real_handleOperator
    function handleOperator(op) {
        if (state.operator !== null && !state.shouldResetScreen) {
            const result = calculate(state.previousOperand, state.currentOperand, state.operator);
            state.currentOperand = result;
            state.previousOperand = result;
        } else {
            state.previousOperand = state.currentOperand;
        }
        state.operator = op;
        state.expressionText = state.previousOperand + ' ' + op;
        state.shouldResetScreen = true;
    }

    // @anchor: real_handleEquals
    function handleEquals() {
        if (state.operator === null) return;
        if (state.shouldResetScreen) {
            state.shouldResetScreen = false;
        }
        const expression = state.previousOperand + ' ' + state.operator + ' ' + state.currentOperand;
        const result = calculate(state.previousOperand, state.currentOperand, state.operator);
        state.expressionText = expression + ' =';
        state.currentOperand = result;
        state.previousOperand = '';
        state.operator = null;
        state.shouldResetScreen = true;
    }

    // @anchor: real_handleBackspace
    function handleBackspace() {
        if (state.shouldResetScreen) return;
        if (state.currentOperand.length === 1) {
            state.currentOperand = '0';
        } else {
            state.currentOperand = state.currentOperand.slice(0, -1);
        }
    }

    // @anchor: real_handlePercent
    function handlePercent() {
        if (state.currentOperand === '0') return;
        const num = parseFloat(state.currentOperand);
        state.currentOperand = (num / 100).toString();
    }

    // @anchor: real_handleScientific
    function handleScientific(func) {
        // 一元三角函数 → 委托给 handleTrigonometric
        if (['sin', 'cos', 'tan', 'asin', 'acos', 'atan'].includes(func)) {
            handleTrigonometric(func);
            return;
        }

        const num = parseFloat(state.currentOperand);
        if (isNaN(num)) return;

        let result;
        switch (func) {
            case 'log':
                if (num <= 0) {
                    state.expressionText = 'log(' + state.currentOperand + ') 错误';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = Math.log10(num);
                state.expressionText = 'log(' + state.currentOperand + ') =';
                break;
            case 'ln':
                if (num <= 0) {
                    state.expressionText = 'ln(' + state.currentOperand + ') 错误';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = Math.log(num);
                state.expressionText = 'ln(' + state.currentOperand + ') =';
                break;
            case '√':
                if (num < 0) {
                    state.expressionText = '√(' + state.currentOperand + ') 错误';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = Math.sqrt(num);
                state.expressionText = '√(' + state.currentOperand + ') =';
                break;
            case '^':
                if (state.operator !== null && !state.shouldResetScreen) {
                    const prevResult = calculate(state.previousOperand, state.currentOperand, state.operator);
                    state.currentOperand = prevResult;
                    state.previousOperand = prevResult;
                } else {
                    state.previousOperand = state.currentOperand;
                }
                state.operator = '^';
                state.expressionText = state.previousOperand + ' ^';
                state.shouldResetScreen = true;
                return;
            default:
                return;
        }

        if (!Number.isInteger(result)) {
            result = parseFloat(result.toPrecision(12));
        }
        state.currentOperand = result.toString();
        state.previousOperand = '';
        state.operator = null;
        state.shouldResetScreen = true;
    }

    // @anchor: real_handleTrigonometric
    function handleTrigonometric(func) {
        const num = parseFloat(state.currentOperand);
        if (isNaN(num)) return;

        let result;
        const funcNames = {
            'sin': 'sin', 'cos': 'cos', 'tan': 'tan',
            'asin': 'arcsin', 'acos': 'arccos', 'atan': 'arctan'
        };

        const displayInput = state.currentOperand;
        const angleLabel = state.angleMode === 'deg' ? '°' : '';

        switch (func) {
            case 'sin':
                result = Math.sin(toRadians(num));
                break;
            case 'cos':
                result = Math.cos(toRadians(num));
                break;
            case 'tan':
                const radInput = toRadians(num);
                if (Math.abs(Math.cos(radInput)) < 1e-15) {
                    state.expressionText = 'tan(' + displayInput + angleLabel + ') 无定义';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = Math.tan(radInput);
                break;
            case 'asin':
                if (num < -1 || num > 1) {
                    state.expressionText = 'arcsin(' + displayInput + ') 错误';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = fromRadians(Math.asin(num));
                break;
            case 'acos':
                if (num < -1 || num > 1) {
                    state.expressionText = 'arccos(' + displayInput + ') 错误';
                    state.currentOperand = '错误';
                    state.shouldResetScreen = true;
                    return;
                }
                result = fromRadians(Math.acos(num));
                break;
            case 'atan':
                result = fromRadians(Math.atan(num));
                break;
            default:
                return;
        }

        if (!Number.isInteger(result)) {
            result = parseFloat(result.toPrecision(12));
        }
        state.expressionText = funcNames[func] + '(' + displayInput + angleLabel + ') =';
        state.currentOperand = result.toString();
        state.previousOperand = '';
        state.operator = null;
        state.shouldResetScreen = true;
    }

    // @anchor: real_calculate
    function calculate(a, b, operator) {
        const numA = parseFloat(a);
        const numB = parseFloat(b);

        if (isNaN(numA) || isNaN(numB)) return '错误';

        let result;
        switch (operator) {
            case '+':
                result = numA + numB;
                break;
            case '−':
                result = numA - numB;
                break;
            case '×':
                result = numA * numB;
                break;
            case '÷':
                if (numB === 0) return '错误';
                result = numA / numB;
                break;
            case '^':
                result = Math.pow(numA, numB);
                if (!isFinite(result) || isNaN(result)) return '错误';
                break;
            default:
                return b;
        }

        if (!Number.isInteger(result)) {
            result = parseFloat(result.toPrecision(12));
        }

        return result.toString();
    }

    // 公开 API
    return {
        handleNumber: handleNumber,
        handleDecimal: handleDecimal,
        handleOperator: handleOperator,
        handleEquals: handleEquals,
        handleBackspace: handleBackspace,
        handlePercent: handlePercent,
        handleScientific: handleScientific,
        handleTrigonometric: handleTrigonometric,
        calculate: calculate
    };
})();
