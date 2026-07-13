// @anchor: modules_sidebar
// ===== 侧边栏折叠切换 =====
const sidebar = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggleSidebarBtn');

if (toggleBtn && sidebar) {
    toggleBtn.addEventListener('click', function() {
        sidebar.classList.toggle('collapsed');
        this.textContent = sidebar.classList.contains('collapsed') ? '▶' : '◀';
    });
}
