const menuButton = document.querySelector('.menu-button');
const mobileNav = document.querySelector('#mobile-nav');

menuButton?.addEventListener('click', () => {
  const isOpen = menuButton.getAttribute('aria-expanded') === 'true';
  menuButton.setAttribute('aria-expanded', String(!isOpen));
  menuButton.setAttribute('aria-label', isOpen ? '打开导航' : '关闭导航');
  mobileNav.hidden = isOpen;
});

mobileNav?.querySelectorAll('a').forEach((link) => {
  link.addEventListener('click', () => {
    mobileNav.hidden = true;
    menuButton.setAttribute('aria-expanded', 'false');
    menuButton.setAttribute('aria-label', '打开导航');
  });
});

const copyButton = document.querySelector('#copy-command');
const copyFeedback = document.querySelector('#copy-feedback');
const cloneCommand = document.querySelector('#clone-command').textContent;

copyButton?.addEventListener('click', async () => {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(cloneCommand);
    } else {
      const field = document.createElement('textarea');
      field.value = cloneCommand;
      field.style.position = 'fixed';
      field.style.opacity = '0';
      document.body.appendChild(field);
      field.select();
      const copied = document.execCommand('copy');
      field.remove();
      if (!copied) throw new Error('Copy unavailable');
    }
    copyButton.querySelector('.copy-label').textContent = '已复制';
    copyFeedback.textContent = '克隆命令已复制到剪贴板。';
  } catch {
    copyFeedback.textContent = '无法自动复制，请选中左侧命令手动复制。';
  }
  window.setTimeout(() => {
    copyButton.querySelector('.copy-label').textContent = '复制';
    copyFeedback.textContent = '';
  }, 3000);
});

const motionDrawing = document.querySelector('#geneva-drawing');
const motionSvg = motionDrawing?.querySelector('.geneva-motion svg');
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
let drawingInView = true;

const syncPlayback = () => {
  if (!motionDrawing || !motionSvg) return;
  const shouldMove = !reducedMotion.matches && drawingInView && !document.hidden;
  motionDrawing.toggleAttribute('data-live', shouldMove);
  if (shouldMove) motionSvg.unpauseAnimations();
  else motionSvg.pauseAnimations();
};

if (motionSvg) {
  motionSvg.pauseAnimations();
  reducedMotion.addEventListener('change', syncPlayback);
  document.addEventListener('visibilitychange', syncPlayback);
  if ('IntersectionObserver' in window) {
    const motionObserver = new IntersectionObserver(([entry]) => {
      drawingInView = entry.isIntersecting;
      syncPlayback();
    }, { threshold: 0.05 });
    motionObserver.observe(motionDrawing);
  }
  syncPlayback();
}

if (!reducedMotion.matches && 'IntersectionObserver' in window) {
  document.documentElement.classList.add('has-reveal');
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12 });
  document.querySelectorAll('.reveal').forEach((element) => observer.observe(element));
}