/**
 * 北约音标 (NATO Phonetic Alphabet) 模块
 * A=Alfa, B=Bravo, C=Charlie, ...
 */
const NATOPhoneticCipher = (() => {
    const natoMap = {
        'A': 'Alfa',    'B': 'Bravo',    'C': 'Charlie',
        'D': 'Delta',   'E': 'Echo',     'F': 'Foxtrot',
        'G': 'Golf',    'H': 'Hotel',    'I': 'India',
        'J': 'Juliett', 'K': 'Kilo',     'L': 'Lima',
        'M': 'Mike',    'N': 'November', 'O': 'Oscar',
        'P': 'Papa',    'Q': 'Quebec',   'R': 'Romeo',
        'S': 'Sierra',  'T': 'Tango',    'U': 'Uniform',
        'V': 'Victor',  'W': 'Whiskey',  'X': 'X-ray',
        'Y': 'Yankee',  'Z': 'Zulu'
    };

    // 反向映射（小写key）
    const reverseMap = {};
    for (const [letter, word] of Object.entries(natoMap)) {
        reverseMap[word.toLowerCase()] = letter;
    }

    /** 编码：文本 → NATO音标词 */
    function encode(text) {
        const results = [];
        for (const ch of text.toUpperCase()) {
            if (natoMap[ch]) {
                results.push(natoMap[ch]);
            } else if (ch === ' ') {
                results.push('(space)');
            } else {
                results.push(ch);
            }
        }
        return results.join(' ');
    }

    /** 解码：NATO音标词 → 文本 */
    function decode(phonetic) {
        const words = phonetic.split(/[\s,;]+/);
        const results = [];
        for (const word of words) {
            const lower = word.toLowerCase().trim();
            if (lower === '(space)' || lower === '') {
                results.push(' ');
            } else if (reverseMap[lower]) {
                results.push(reverseMap[lower]);
            } else {
                results.push(word);
            }
        }
        return results.join('');
    }

    /** 在容器中渲染北约音标可视化 */
    function render(container, text) {
        container.innerHTML = '';
        const upperText = text.toUpperCase();
        const chars = upperText.split('');

        if (chars.length === 0 || (chars.length === 1 && chars[0] === '')) {
            container.innerHTML = '<div class="placeholder">输入文本以查看北约音标</div>';
            return;
        }

        // 音标词卡片行
        const cardsRow = document.createElement('div');
        cardsRow.className = 'nato-cards-row';

        for (const ch of chars) {
            const card = document.createElement('div');
            card.className = 'nato-card';

            if (natoMap[ch]) {
                const letterBadge = document.createElement('span');
                letterBadge.className = 'nato-letter-badge';
                letterBadge.textContent = ch;

                const wordEl = document.createElement('span');
                wordEl.className = 'nato-word';
                wordEl.textContent = natoMap[ch];

                card.appendChild(letterBadge);
                card.appendChild(wordEl);
            } else if (ch === ' ') {
                card.classList.add('nato-space');
                card.textContent = '␣';
            } else {
                card.classList.add('nato-other');
                card.textContent = ch;
            }

            cardsRow.appendChild(card);
        }
        container.appendChild(cardsRow);
    }

    return { encode, decode, render };
})();
