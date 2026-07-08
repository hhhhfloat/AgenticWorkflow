(function() {
  // ========== 盲文映射表 ==========
  // 键为激活点位的排序字符串（如 "1" -> 'A', "1,2" -> 'B'）
  var brailleToLetter = {
    '1':       'A',
    '1,2':     'B',
    '1,4':     'C',
    '1,4,5':   'D',
    '1,5':     'E',
    '1,2,4':   'F',
    '1,2,4,5': 'G',
    '1,2,5':   'H',
    '2,4':     'I',
    '2,4,5':   'J',
    '1,3':     'K',
    '1,2,3':   'L',
    '1,3,4':   'M',
    '1,3,4,5': 'N',
    '1,3,5':   'O',
    '1,2,3,4': 'P',
    '1,2,3,4,5':'Q',
    '1,2,3,5': 'R',
    '2,3,4':   'S',
    '2,3,4,5': 'T',
    '1,3,6':   'U',
    '1,2,3,6': 'V',
    '2,4,5,6': 'W',
    '1,3,4,6': 'X',
    '1,3,4,5,6':'Y',
    '1,3,5,6': 'Z',
  };

  // Unicode 盲文（基础偏移 U+2800）
  // 盲文 Unicode: 点1=0x01, 点2=0x02, 点3=0x04, 点4=0x08, 点5=0x10, 点6=0x20
  var dotToBit = { 1: 0x01, 2: 0x02, 3: 0x04, 4: 0x08, 5: 0x10, 6: 0x20 };

  // ========== DOM 元素 ==========
  var dots = document.querySelectorAll('.dot');
  var letterDisplay = document.getElementById('letterDisplay');
  var brailleUnicode = document.getElementById('brailleUnicode');
  var dotPattern = document.getElementById('dotPattern');
  var btnClear = document.getElementById('btnClear');
  var btnFillAll = document.getElementById('btnFillAll');

  // ========== 获取当前激活点 ==========
  function getActiveDots() {
    var active = [];
    dots.forEach(function(dot) {
      if (dot.classList.contains('active')) {
        active.push(parseInt(dot.dataset.dot));
      }
    });
    active.sort(function(a, b) { return a - b; });
    return active;
  }

  // ========== 更新右侧结果 ==========
  function updateResult() {
    var active = getActiveDots();
    var key = active.join(',');

    if (active.length === 0) {
      letterDisplay.textContent = '—';
      letterDisplay.classList.remove('has-letter');
      brailleUnicode.textContent = '';
      dotPattern.textContent = '';
      return;
    }

    var letter = brailleToLetter[key] || '?';
    letterDisplay.textContent = letter;
    letterDisplay.classList.add('has-letter');

    // Unicode 盲文字符
    var brailleCode = 0;
    active.forEach(function(d) { brailleCode |= dotToBit[d]; });
    var brailleChar = String.fromCodePoint(0x2800 + brailleCode);
    brailleUnicode.textContent = '⠿ ' + brailleChar + '  U+' + (0x2800 + brailleCode).toString(16).toUpperCase();

    // 点模式文字
    dotPattern.textContent = active.length > 0 ? '点位: ' + active.join(' · ') : '';
  }

  // ========== 点点击事件 ==========
  dots.forEach(function(dot) {
    dot.addEventListener('click', function() {
      dot.classList.toggle('active');
      updateResult();
    });
  });

  // ========== 清空 ==========
  btnClear.addEventListener('click', function() {
    dots.forEach(function(d) { d.classList.remove('active'); });
    updateResult();
  });

  // ========== 全选 ==========
  btnFillAll.addEventListener('click', function() {
    dots.forEach(function(d) { d.classList.add('active'); });
    updateResult();
  });

  // ========== 键盘快捷键 ==========
  document.addEventListener('keydown', function(e) {
    // 数字键 1-6 切换对应点位
    var digit = parseInt(e.key);
    if (digit >= 1 && digit <= 6) {
      e.preventDefault();
      dots.forEach(function(d) {
        if (parseInt(d.dataset.dot) === digit) {
          d.classList.toggle('active');
        }
      });
      updateResult();
    }
    // Escape 清空
    if (e.key === 'Escape') {
      dots.forEach(function(d) { d.classList.remove('active'); });
      updateResult();
    }
    // A 全选
    if (e.key === 'a' || e.key === 'A') {
      dots.forEach(function(d) { d.classList.add('active'); });
      updateResult();
    }
  });

  // 初始化
  updateResult();
})();
