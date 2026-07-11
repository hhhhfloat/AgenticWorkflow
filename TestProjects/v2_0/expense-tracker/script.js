// @anchor: constants
const STORAGE_KEY = 'family_expenses';
const CATEGORIES = ['餐饮', '交通', '住房', '娱乐', '购物', '医疗', '教育', '其他'];

// @anchor: state
let expenses = [];

// @anchor: dom_refs
const expenseForm = document.getElementById('expenseForm');
const expenseName = document.getElementById('expenseName');
const expenseAmount = document.getElementById('expenseAmount');
const expenseCategory = document.getElementById('expenseCategory');
const expenseDate = document.getElementById('expenseDate');
const expenseTableBody = document.getElementById('expenseTableBody');
const chartContainer = document.getElementById('chartContainer');
const totalExpenseEl = document.getElementById('totalExpense');
const recordCountEl = document.getElementById('recordCount');
const avgExpenseEl = document.getElementById('avgExpense');
const clearAllBtn = document.getElementById('clearAllBtn');
const recordHint = document.getElementById('recordHint');

// @anchor: init
function init() {
    loadExpenses();
    setDefaultDate();
    renderAll();
    bindEvents();
}

function setDefaultDate() {
    const today = new Date();
    const yyyy = today.getFullYear();
    const mm = String(today.getMonth() + 1).padStart(2, '0');
    const dd = String(today.getDate()).padStart(2, '0');
    expenseDate.value = `${yyyy}-${mm}-${dd}`;
}

// @anchor: storage
function loadExpenses() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
            expenses = JSON.parse(raw);
            if (!Array.isArray(expenses)) expenses = [];
        }
    } catch (e) {
        expenses = [];
    }
}

function saveExpenses() {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(expenses));
    } catch (e) {
        alert('存储空间不足，请清理部分数据。');
    }
}

// @anchor: event_binding
function bindEvents() {
    expenseForm.addEventListener('submit', handleAddExpense);
    clearAllBtn.addEventListener('click', handleClearAll);
}

// @anchor: add_expense
function handleAddExpense(e) {
    e.preventDefault();

    const name = expenseName.value.trim();
    const amount = parseFloat(expenseAmount.value);
    const category = expenseCategory.value;
    const date = expenseDate.value;

    if (!name || isNaN(amount) || amount <= 0 || !category || !date) {
        alert('请填写所有字段，且金额必须大于 0。');
        return;
    }

    const expense = {
        id: Date.now(),
        name,
        amount,
        category,
        date
    };

    expenses.unshift(expense);
    saveExpenses();
    renderAll();
    expenseForm.reset();
    setDefaultDate();
    expenseName.focus();
}

// @anchor: delete_expense
function handleDeleteExpense(id) {
    if (!confirm('确定要删除这条记录吗？')) return;
    expenses = expenses.filter(e => e.id !== id);
    saveExpenses();
    renderAll();
}

// @anchor: clear_all
function handleClearAll() {
    if (expenses.length === 0) {
        alert('没有数据可清空。');
        return;
    }
    if (!confirm(`确定要清空全部 ${expenses.length} 条记录吗？此操作不可恢复！`)) return;
    expenses = [];
    saveExpenses();
    renderAll();
}

// @anchor: render_all
function renderAll() {
    renderSummary();
    renderTable();
    renderChart();
    updateClearButton();
    updateRecordHint();
}

// @anchor: render_summary
function renderSummary() {
    const total = expenses.reduce((sum, e) => sum + e.amount, 0);
    const count = expenses.length;
    const avg = count > 0 ? total / count : 0;

    totalExpenseEl.textContent = `¥${total.toFixed(2)}`;
    recordCountEl.textContent = count;
    avgExpenseEl.textContent = `¥${avg.toFixed(2)}`;
}

// @anchor: render_table
function renderTable() {
    if (expenses.length === 0) {
        expenseTableBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="5">暂无记录，快来添加第一笔开销吧！</td>
            </tr>`;
        return;
    }

    expenseTableBody.innerHTML = expenses.map((e, idx) => `
        <tr class="fade-in" style="animation-delay: ${idx * 0.03}s">
            <td>${escapeHtml(e.name)}</td>
            <td class="amount-cell">¥${e.amount.toFixed(2)}</td>
            <td><span class="category-badge">${escapeHtml(e.category)}</span></td>
            <td>${escapeHtml(e.date)}</td>
            <td>
                <button class="delete-btn" data-id="${e.id}" title="删除此记录">🗑️</button>
            </td>
        </tr>
    `).join('');

    // Bind delete buttons
    expenseTableBody.querySelectorAll('.delete-btn').forEach(btn => {
        btn.addEventListener('click', function() {
            const id = parseInt(this.getAttribute('data-id'));
            handleDeleteExpense(id);
        });
    });
}

// @anchor: render_chart
function renderChart() {
    if (expenses.length === 0) {
        chartContainer.innerHTML = '<p class="empty-hint">暂无数据，添加记录后将显示柱状图</p>';
        return;
    }

    // Aggregate by category
    const categoryTotals = {};
    CATEGORIES.forEach(cat => { categoryTotals[cat] = 0; });
    expenses.forEach(e => {
        if (categoryTotals.hasOwnProperty(e.category)) {
            categoryTotals[e.category] += e.amount;
        } else {
            categoryTotals[e.category] = e.amount;
        }
    });

    // Filter categories with data
    const entries = Object.entries(categoryTotals).filter(([, v]) => v > 0);

    if (entries.length === 0) {
        chartContainer.innerHTML = '<p class="empty-hint">暂无数据，添加记录后将显示柱状图</p>';
        return;
    }

    const maxValue = Math.max(...entries.map(([, v]) => v));

    chartContainer.innerHTML = entries.map(([cat, val], idx) => {
        const heightPercent = maxValue > 0 ? (val / maxValue) * 100 : 0;
        return `
            <div class="chart-bar-wrapper fade-in" style="animation-delay: ${idx * 0.06}s">
                <span class="chart-bar-amount">¥${val.toFixed(0)}</span>
                <div class="chart-bar bar-color-${idx % 8}"
                     style="height: ${heightPercent}%"
                     title="${cat}: ¥${val.toFixed(2)}">
                </div>
                <span class="chart-bar-label">${escapeHtml(cat)}</span>
            </div>`;
    }).join('');
}

// @anchor: update_clear_button
function updateClearButton() {
    clearAllBtn.disabled = expenses.length === 0;
    if (expenses.length === 0) {
        clearAllBtn.style.opacity = '0.5';
        clearAllBtn.style.cursor = 'not-allowed';
    } else {
        clearAllBtn.style.opacity = '1';
        clearAllBtn.style.cursor = 'pointer';
    }
}

// @anchor: update_record_hint
function updateRecordHint() {
    if (expenses.length === 0) {
        recordHint.textContent = '';
    } else {
        recordHint.textContent = `共 ${expenses.length} 条记录`;
    }
}

// @anchor: escape_html
function escapeHtml(str) {
    const div = document.createElement('div');
    div.appendChild(document.createTextNode(str));
    return div.innerHTML;
}

// @anchor: bootstrap
document.addEventListener('DOMContentLoaded', init);
