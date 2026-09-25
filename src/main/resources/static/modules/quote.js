// @anchor: modules_quote
// 从文案库中随机抽取一条迭代提示语

// ===== 随机文案抽取 =====
function getRandomQuote() {
    if (!QUOTES || QUOTES.length === 0) return '';
    const idx = Math.floor(Math.random() * QUOTES.length);
    return QUOTES[idx];
}
