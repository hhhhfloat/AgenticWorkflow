// ==============================
// 复数计算模块 - ComplexCalc
// 包含复数模式下的所有输入处理、运算与数学函数
// ==============================

var ComplexCalc = (function() {
    'use strict';

    // ==============================
    // 输入处理
    // ==============================

    // @anchor: complex_handleNumber
    function handleNumber(num) {
        // 如果刚完成计算，先重置
        if (state.shouldResetScreen) {
            state.shouldResetScreen = false;
            state.realStr = '0';
            state.imagStr = '0';
            state.editingPart = 'real';
            btnImag.classList.remove('active-imag');
        }

        const current = state.editingPart === 'real' ? state.realStr : state.imagStr;
        let newVal;
        if (current === '0' && num !== '0') {
            newVal = num;
        } else if (current === '0' && num === '0') {
            newVal = '0';
        } else {
            newVal = current + num;
        }
        setCurrentNumberStr(newVal);
    }

    // @anchor: complex_handleDecimal
    function handleDecimal() {
        const current = getCurrentNumberStr();
        if (!current.includes('.')) {
            setCurrentNumberStr(current + '.');
        }
    }

    // @anchor: complex_handleOperator
    function handleOperator(op) {
        const real = parseFloat(state.realStr) || 0;
        const imag = parseFloat(state.imagStr) || 0;

        if (state.operator !== null && !state.shouldResetScreen) {
            const prevReal = parseFloat(state.prevRealStr) || 0;
            const prevImag = parseFloat(state.prevImagStr) || 0;
            const res = complexCalculate(prevReal, prevImag, real, imag, state.operator);
            state.realStr = formatNumber(res.real);
            state.imagStr = formatNumber(res.imag);
            state.prevRealStr = state.realStr;
            state.prevImagStr = state.imagStr;
        } else {
            state.prevRealStr = state.realStr;
            state.prevImagStr = state.imagStr;
        }
        state.operator = op;
        state.expressionText = formatComplexStr(parseFloat(state.prevRealStr)||0, parseFloat(state.prevImagStr)||0) + ' ' + op;
        state.shouldResetScreen = true;
        state.editingPart = 'real';
        btnImag.classList.remove('active-imag');
    }

    // @anchor: complex_handleEquals
    function handleEquals() {
        if (state.operator === null) return;

        const real = parseFloat(state.realStr) || 0;
        const imag = parseFloat(state.imagStr) || 0;
        const prevReal = parseFloat(state.prevRealStr) || 0;
        const prevImag = parseFloat(state.prevImagStr) || 0;

        const exprLeft = formatComplexStr(prevReal, prevImag);
        const exprRight = formatComplexStr(real, imag);
        state.expressionText = exprLeft + ' ' + state.operator + ' ' + exprRight + ' =';

        const res = complexCalculate(prevReal, prevImag, real, imag, state.operator);
        state.realStr = formatNumber(res.real);
        state.imagStr = formatNumber(res.imag);
        state.prevRealStr = '';
        state.prevImagStr = '';
        state.operator = null;
        state.shouldResetScreen = true;
        state.editingPart = 'real';
        btnImag.classList.remove('active-imag');
    }

    // @anchor: complex_handleBackspace
    function handleBackspace() {
        if (state.shouldResetScreen) return;
        const current = state.editingPart === 'real' ? state.realStr : state.imagStr;
        if (current.length === 1 || (current.length === 2 && current.startsWith('-'))) {
            setCurrentNumberStr('0');
        } else {
            setCurrentNumberStr(current.slice(0, -1));
        }
    }

    // @anchor: complex_handlePercent
    function handlePercent() {
        const current = state.editingPart === 'real' ? state.realStr : state.imagStr;
        const num = parseFloat(current);
        if (num === 0) return;
        setCurrentNumberStr((num / 100).toString());
    }

    // @anchor: complex_handleScientific
    function handleScientific(func) {
        // 一元三角函数 → 委托给 handleTrig
        if (['sin', 'cos', 'tan', 'asin', 'acos', 'atan'].includes(func)) {
            handleTrig(func);
            return;
        }

        const real = parseFloat(state.realStr) || 0;
        const imag = parseFloat(state.imagStr) || 0;

        let res;
        const inputStr = formatComplexStr(real, imag);

        switch (func) {
            case 'log':
                res = complexLog(real, imag, 10);
                state.expressionText = 'log(' + inputStr + ') =';
                break;
            case 'ln':
                res = complexLog(real, imag, Math.E);
                state.expressionText = 'ln(' + inputStr + ') =';
                break;
            case '√':
                res = complexSqrt(real, imag);
                state.expressionText = '√(' + inputStr + ') =';
                break;
            case '^':
                if (state.operator !== null && !state.shouldResetScreen) {
                    const prevReal = parseFloat(state.prevRealStr) || 0;
                    const prevImag = parseFloat(state.prevImagStr) || 0;
                    const mid = complexCalculate(prevReal, prevImag, real, imag, state.operator);
                    state.realStr = formatNumber(mid.real);
                    state.imagStr = formatNumber(mid.imag);
                    state.prevRealStr = state.realStr;
                    state.prevImagStr = state.imagStr;
                } else {
                    state.prevRealStr = state.realStr;
                    state.prevImagStr = state.imagStr;
                }
                state.operator = '^';
                state.expressionText = formatComplexStr(parseFloat(state.prevRealStr)||0, parseFloat(state.prevImagStr)||0) + ' ^';
                state.shouldResetScreen = true;
                state.editingPart = 'real';
                btnImag.classList.remove('active-imag');
                return;
            default:
                return;
        }

        state.realStr = formatNumber(res.real);
        state.imagStr = formatNumber(res.imag);
        state.prevRealStr = '';
        state.prevImagStr = '';
        state.operator = null;
        state.shouldResetScreen = true;
        state.editingPart = 'real';
        btnImag.classList.remove('active-imag');
    }

    // @anchor: complex_handleTrig
    function handleTrig(func) {
        // 复数三角函数始终使用弧度
        const real = parseFloat(state.realStr) || 0;
        const imag = parseFloat(state.imagStr) || 0;

        const inputStr = formatComplexStr(real, imag);
        let res;

        const funcNames = {
            'sin': 'sin', 'cos': 'cos', 'tan': 'tan',
            'asin': 'arcsin', 'acos': 'arccos', 'atan': 'arctan'
        };

        switch (func) {
            case 'sin':
                res = complexSin(real, imag);
                break;
            case 'cos':
                res = complexCos(real, imag);
                break;
            case 'tan':
                res = complexTan(real, imag);
                break;
            case 'asin':
                res = complexArcsin(real, imag);
                break;
            case 'acos':
                res = complexArccos(real, imag);
                break;
            case 'atan':
                res = complexArctan(real, imag);
                break;
            default:
                return;
        }

        state.expressionText = funcNames[func] + '(' + inputStr + ') =';
        state.realStr = formatNumber(res.real);
        state.imagStr = formatNumber(res.imag);
        state.prevRealStr = '';
        state.prevImagStr = '';
        state.operator = null;
        state.shouldResetScreen = true;
        state.editingPart = 'real';
        btnImag.classList.remove('active-imag');
    }

    // ==============================
    // 复数四则运算
    // @anchor: complex_calculate
    // ==============================
    function complexCalculate(aReal, aImag, bReal, bImag, operator) {
        switch (operator) {
            case '+':
                return { real: aReal + bReal, imag: aImag + bImag };
            case '−':
                return { real: aReal - bReal, imag: aImag - bImag };
            case '×':
                return {
                    real: aReal * bReal - aImag * bImag,
                    imag: aReal * bImag + aImag * bReal
                };
            case '÷':
                const denom = bReal * bReal + bImag * bImag;
                if (denom === 0) return { real: NaN, imag: NaN };
                return {
                    real: (aReal * bReal + aImag * bImag) / denom,
                    imag: (aImag * bReal - aReal * bImag) / denom
                };
            case '^':
                return complexPow(aReal, aImag, bReal, bImag);
            default:
                return { real: bReal, imag: bImag };
        }
    }

    // ==============================
    // 复数数学函数
    // ==============================

    // @anchor: complex_pow
    function complexPow(aReal, aImag, bReal, bImag) {
        // z^w = exp(w * ln(z))
        const lnZ = complexLog(aReal, aImag, Math.E);
        const prod = {
            real: bReal * lnZ.real - bImag * lnZ.imag,
            imag: bReal * lnZ.imag + bImag * lnZ.real
        };
        const expReal = Math.exp(prod.real);
        return {
            real: expReal * Math.cos(prod.imag),
            imag: expReal * Math.sin(prod.imag)
        };
    }

    // @anchor: complex_log
    function complexLog(real, imag, base) {
        // ln(z) = ln|z| + i·arg(z)
        const modulus = Math.sqrt(real * real + imag * imag);
        if (modulus === 0) return { real: -Infinity, imag: 0 };
        const argument = Math.atan2(imag, real);
        const lnMod = Math.log(modulus);
        const divisor = Math.log(base);
        return {
            real: lnMod / divisor,
            imag: argument / divisor
        };
    }

    // @anchor: complex_sqrt
    function complexSqrt(real, imag) {
        // √(a+bi) = √((r+a)/2) + sign(b)·√((r-a)/2)·i
        const r = Math.sqrt(real * real + imag * imag);
        const sqrtRPlusA = Math.sqrt((r + real) / 2);
        const sqrtRMinusA = Math.sqrt((r - real) / 2);
        return {
            real: sqrtRPlusA,
            imag: imag >= 0 ? sqrtRMinusA : -sqrtRMinusA
        };
    }

    // @anchor: complex_sin
    function complexSin(real, imag) {
        // sin(a+bi) = sin(a)cosh(b) + i·cos(a)sinh(b)
        return {
            real: Math.sin(real) * Math.cosh(imag),
            imag: Math.cos(real) * Math.sinh(imag)
        };
    }

    // @anchor: complex_cos
    function complexCos(real, imag) {
        // cos(a+bi) = cos(a)cosh(b) - i·sin(a)sinh(b)
        return {
            real: Math.cos(real) * Math.cosh(imag),
            imag: -Math.sin(real) * Math.sinh(imag)
        };
    }

    // @anchor: complex_tan
    function complexTan(real, imag) {
        // tan(z) = sin(z) / cos(z)
        const sinZ = complexSin(real, imag);
        const cosZ = complexCos(real, imag);
        const denom = cosZ.real * cosZ.real + cosZ.imag * cosZ.imag;
        if (denom < 1e-30) return { real: NaN, imag: NaN };
        return {
            real: (sinZ.real * cosZ.real + sinZ.imag * cosZ.imag) / denom,
            imag: (sinZ.imag * cosZ.real - sinZ.real * cosZ.imag) / denom
        };
    }

    // @anchor: complex_arcsin
    function complexArcsin(real, imag) {
        // arcsin(z) = -i·ln(i·z + √(1-z²))
        const z2Real = real * real - imag * imag;
        const z2Imag = 2 * real * imag;
        const oneMinusZ2Real = 1 - z2Real;
        const oneMinusZ2Imag = -z2Imag;
        const sqrtTerm = complexSqrt(oneMinusZ2Real, oneMinusZ2Imag);
        const sumReal = -imag + sqrtTerm.real;
        const sumImag = real + sqrtTerm.imag;
        const lnSum = complexLog(sumReal, sumImag, Math.E);
        return {
            real: lnSum.imag,
            imag: -lnSum.real
        };
    }

    // @anchor: complex_arccos
    function complexArccos(real, imag) {
        // arccos(z) = π/2 - arcsin(z)
        const asinZ = complexArcsin(real, imag);
        return {
            real: Math.PI / 2 - asinZ.real,
            imag: -asinZ.imag
        };
    }

    // @anchor: complex_arctan
    function complexArctan(real, imag) {
        // arctan(z) = (i/2)·[ln(1-iz) - ln(1+iz)]
        const oneMinusIzReal = 1 + imag;
        const oneMinusIzImag = -real;
        const onePlusIzReal = 1 - imag;
        const onePlusIzImag = real;

        const ln1 = complexLog(oneMinusIzReal, oneMinusIzImag, Math.E);
        const ln2 = complexLog(onePlusIzReal, onePlusIzImag, Math.E);

        const diffReal = ln1.real - ln2.real;
        const diffImag = ln1.imag - ln2.imag;
        return {
            real: -diffImag / 2,
            imag: diffReal / 2
        };
    }

    // @anchor: complex_formatComplexStr
    function formatComplexStr(real, imag) {
        if (imag === 0) return formatNumber(real);
        if (real === 0) {
            if (imag === 1) return 'i';
            if (imag === -1) return '-i';
            return formatNumber(imag) + 'i';
        }
        const sign = imag >= 0 ? '+' : '';
        if (imag === 1) return formatNumber(real) + '+i';
        if (imag === -1) return formatNumber(real) + '-i';
        return formatNumber(real) + sign + formatNumber(imag) + 'i';
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
        handleTrig: handleTrig,
        complexCalculate: complexCalculate,
        formatComplexStr: formatComplexStr
    };
})();
