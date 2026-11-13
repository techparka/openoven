/* Strona OpenOven. Bez zależności, bez zewnętrznych żądań. */
(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const fmtTime = (m) => `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`;

  /* ============================================================ 1. Motyw */
  const KEY = 'openoven.theme';
  const html = document.documentElement;
  const isPolish = html.lang === 'pl';
  const copy = isPolish ? {
    openMenu: 'Otwórz menu', closeMenu: 'Zamknij menu', yes: 'tak', no: 'nie',
    duration: (value) => `${value} min`,
    temperature: (value) => `${value} stopni Celsjusza`,
    noTemperature: 'Bez temperatury', temperatureNotApplicable: 'Brak',
    defrost: 'Rozmrażanie pomija nastawy temperatury i sondy.',
    range: (max) => `Zakres tej funkcji: 30–${max} °C.`,
    stage: (n) => `Etap ${n}`, stageCount: (n) => `${n} z 3 etapów`,
    cooler: (n) => `Temperatura niższa w etapie ${n}`,
    warmer: (n) => `Temperatura wyższa w etapie ${n}`,
    shorter: (n) => `Minuta mniej w etapie ${n}`,
    longer: (n) => `Minuta więcej w etapie ${n}`,
    removeStage: (n) => `Usuń etap ${n}`, addedStage: (n) => `Dodano etap ${n}`,
    total: (n, time) => `${n} ${n === 1 ? 'etap' : 'etapy'} · łącznie ${time}`,
  } : {
    openMenu: 'Open menu', closeMenu: 'Close menu', yes: 'Yes', no: 'No',
    duration: (value) => `${value} min`,
    temperature: (value) => `${value} degrees Celsius`,
    noTemperature: 'No temperature', temperatureNotApplicable: 'N/A',
    defrost: 'Defrost skips temperature and probe settings.',
    range: (max) => `Temperature range for this mode: 30–${max} °C.`,
    stage: (n) => `Stage ${n}`, stageCount: (n) => `${n} of 3 stages`,
    cooler: (n) => `Lower temperature in stage ${n}`,
    warmer: (n) => `Raise temperature in stage ${n}`,
    shorter: (n) => `Subtract one minute from stage ${n}`,
    longer: (n) => `Add one minute to stage ${n}`,
    removeStage: (n) => `Remove stage ${n}`, addedStage: (n) => `Added stage ${n}`,
    total: (n, time) => `${n} ${n === 1 ? 'stage' : 'stages'} · ${time} total`,
  };
  const mqDark = matchMedia('(prefers-color-scheme: dark)');
  let choice = 'system';
  try {
    const saved = localStorage.getItem(KEY);
    if (['system', 'light', 'dark'].includes(saved)) choice = saved;
  } catch {
    // Theme controls also work when browser storage is unavailable.
  }
  const saveTheme = () => {
    try { localStorage.setItem(KEY, choice); } catch { /* Session-only choice. */ }
  };

  const applyTheme = () => {
    const dark = choice === 'system' ? mqDark.matches : choice === 'dark';
    html.dataset.theme = dark ? 'dark' : 'light';
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.content = dark ? '#070d13' : '#eef3f6';
  };
  mqDark.addEventListener('change', () => choice === 'system' && applyTheme());
  $('#themeToggle')?.addEventListener('click', () => {
    choice = html.dataset.theme === 'dark' ? 'light' : 'dark';
    saveTheme();
    applyTheme();
  });
  applyTheme();

  /* ==================================================== 2. Nawigacja / scroll */
  const topbar = $('#topbar');
  const bar = $('#progress');
  const onScroll = () => {
    const y = scrollY;
    topbar.classList.toggle('is-stuck', y > 12);
    const h = document.documentElement.scrollHeight - innerHeight;
    if (bar) bar.style.transform = `scaleX(${h > 0 ? clamp(y / h, 0, 1) : 0})`;
  };
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const msheet = $('#msheet');
  const menuBtn = $('#menuBtn');
  const desktop = matchMedia('(min-width: 1080px)');
  const setMenu = (open) => {
    msheet.hidden = !open;
    msheet.classList.toggle('is-open', open);
    menuBtn.setAttribute('aria-expanded', String(open));
    menuBtn.setAttribute('aria-label', open ? copy.closeMenu : copy.openMenu);
    menuBtn.firstElementChild.innerHTML = `<use href="#${open ? 'i-close' : 'i-menu'}"/>`;
    document.body.style.overflow = open ? 'hidden' : '';
    if (open) msheet.querySelector('a')?.focus();
  };
  menuBtn.addEventListener('click', () => setMenu(msheet.hidden));
  $$('#msheet a').forEach((a) => a.addEventListener('click', () => {
    setMenu(false);
    const target = a.hash && document.getElementById(a.hash.slice(1));
    if (target) {
      target.setAttribute('tabindex', '-1');
      target.focus({ preventScroll: true });
    }
  }));
  addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || msheet.hidden) return;
    setMenu(false);
    menuBtn.focus();
  });
  desktop.addEventListener('change', () => { if (desktop.matches) setMenu(false); });

  const navLinks = $$('#nav a');
  const sections = navLinks
    .map((a) => $(a.getAttribute('href')))
    .filter(Boolean);
  if (sections.length && 'IntersectionObserver' in window) {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((en) => {
          if (!en.isIntersecting) return;
          navLinks.forEach((a) =>
            a.classList.toggle('is-current', a.getAttribute('href') === `#${en.target.id}`)
          );
        });
      },
      { rootMargin: '-45% 0px -50% 0px' }
    );
    sections.forEach((s) => io.observe(s));
  }

  if ('IntersectionObserver' in window && !reduce.matches) {
    const reveal = new IntersectionObserver((entries, observer) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-in');
        observer.unobserve(entry.target);
      }
    }, { threshold: 0.08 });
    document.documentElement.classList.add('motion-ready');
    $$('[data-reveal]').forEach((element) => reveal.observe(element));
  }

  /* podświetlenie kursorami w kartach (poświata) */
  $$('.feature').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty('--mx', `${e.clientX - r.left}px`);
      card.style.setProperty('--my', `${e.clientY - r.top}px`);
    });
  });

  const FUNCTIONS = [
    { id: 1, name: isPolish ? 'Konwencjonalne' : 'Conventional', max: 280 },
    { id: 2, name: isPolish ? 'Grzałka dolna + górna + wentylator' : 'Top and bottom heat with fan', max: 280 },
    { id: 3, name: isPolish ? 'Grill' : 'Grill', max: 280 },
    { id: 4, name: isPolish ? 'Turbo grill' : 'Turbo grill', max: 280 },
    { id: 5, name: isPolish ? 'Super grill' : 'Super grill', max: 280 },
    { id: 6, name: isPolish ? 'Termoobieg' : 'Fan cooking', max: 280 },
    { id: 7, name: isPolish ? 'Termoobieg + dolna grzałka' : 'Fan cooking with bottom heat', max: 280 },
    { id: 8, name: isPolish ? 'Zarumienienie' : 'Browning', max: 230 },
    { id: 9, name: isPolish ? 'Przypiekanie' : 'Crisping', max: 240 },
    { id: 10, name: isPolish ? 'Rozmrażanie' : 'Defrost', max: 0, noTemp: true },
    { id: 11, name: isPolish ? 'Grill + rożen' : 'Grill with rotisserie', max: 280 },
    { id: 12, name: isPolish ? 'Super grill + rożen' : 'Super grill with rotisserie', max: 280 },
  ];
  /* ================================================ 7. Playground nastaw */
  const fnList = $('#fnList');
  if (fnList) {
    const state = {
      fn: 6,
      temp: 180,
      minutes: 35,
      rapid: true,
      light: false,
      stages: [{ fn: 6, temp: 180, minutes: 35 }],
    };
    const WHEEL_H = 44;

    /* --- karuzela --- */
    class Wheel {
      constructor(el, onChange) {
        this.el = el;
        this.out = $('[data-out]', el);
        this.reel = $('[data-reel]', el);
        this.strip = $('.wheel__strip', el);
        this.area = el;
        this.onChange = onChange;
        this.min = +el.dataset.min;
        this.max = +el.dataset.max;
        this.step = +el.dataset.step;
        this.value = +el.dataset.value;
        this.isTime = el.dataset.variant === 'time';
        this.drag = null;
        this.render(true);
        this.el.addEventListener('pointerdown', (e) => this.down(e));
        el.addEventListener('keydown', (e) => this.key(e));
      }
      labels() {
        const out = [];
        for (let v = this.min; v <= this.max; v += this.step) out.push(v);
        if (out[out.length - 1] !== this.max) out.push(this.max);
        return out;
      }
      setMax(max) {
        this.max = max;
        this.el.setAttribute('aria-valuemax', max);
        this.el.dataset.max = max;
        this.render();
        this.setValue(this.value);
      }
      render(snap = false) {
        const vals = this.labels();
        this.reel.innerHTML = vals
          .map((v) => `<div class="wheel__item">${this.isTime ? fmtTime(v) : v + (this.el.dataset.unit || '')}</div>`)
          .join('');
        this.index = Math.max(0, vals.indexOf(this.snap(this.value)));
        this.reel.style.transition = snap ? 'none' : '';
        this.paint();
      }
      snap(v) {
        const vals = this.labels();
        return vals.reduce((a, b) => (Math.abs(b - v) < Math.abs(a - v) ? b : a), vals[0]);
      }
      paint() {
        const vals = this.labels();
        const offset = 44 - this.index * WHEEL_H;
        this.reel.style.transform = `translateY(${offset}px)`;
        this.value = vals[this.index];
        this.out.textContent = this.isTime ? fmtTime(this.value) : this.value;
        this.el.setAttribute('aria-valuenow', this.value);
        this.el.setAttribute('aria-valuetext', this.isTime ? copy.duration(this.value) : copy.temperature(this.value));
        this.onChange(this.value);
      }
      move(delta) {
        if (this.el.getAttribute('aria-disabled') === 'true') return;
        const vals = this.labels();
        const next = clamp(this.index + delta, 0, vals.length - 1);
        if (next === this.index) return;
        this.index = next;
        this.paint();
      }
      setValue(v) {
        const t = this.snap(v);
        const i = this.labels().indexOf(t);
        if (i === this.index) return this.paint();
        this.index = i;
        this.paint();
      }
      down(e) {
        if (this.el.getAttribute('aria-disabled') === 'true') return;
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        this.el.setPointerCapture(e.pointerId);
        this.drag = { y: e.clientY, last: e.clientY, moved: 0, t: performance.now(), vel: 0 };
        this.reel.classList.add('dragging');
        this.el.focus({ preventScroll: true });
        const vals = this.labels();
        this.startIndex = this.index;
        const onMove = (ev) => {
          const d = ev.clientY - this.drag.y;
          const speed = Math.min(Math.abs(ev.clientY - this.drag.last) / 16, 3.2);
          const accel = 1 + speed * 1.9;
          this.drag.moved = d;
          this.drag.vel = (ev.clientY - this.drag.last) / Math.max(1, ev.timeStamp - this.drag.t);
          this.drag.t = ev.timeStamp;
          this.drag.last = ev.clientY;
          const shift = Math.round((-d / WHEEL_H) * accel);
          this.index = clamp(this.startIndex + shift, 0, vals.length - 1);
          this.paint();
        };
        const onUp = () => {
          this.reel.classList.remove('dragging');
          this.drag = null;
          this.el.removeEventListener('pointermove', onMove);
          this.el.removeEventListener('pointerup', onUp);
          this.el.removeEventListener('pointercancel', onUp);
        };
        this.el.addEventListener('pointermove', onMove);
        this.el.addEventListener('pointerup', onUp);
        this.el.addEventListener('pointercancel', onUp);
      }
      key(e) {
        if (this.el.getAttribute('aria-disabled') === 'true') return;
        const k = e.key;
        if (k === 'ArrowUp' || k === 'ArrowRight') { this.move(1); e.preventDefault(); }
        else if (k === 'ArrowDown' || k === 'ArrowLeft') { this.move(-1); e.preventDefault(); }
        else if (k === 'Home') { this.index = 0; this.paint(); e.preventDefault(); }
        else if (k === 'End') { this.index = this.labels().length - 1; this.paint(); e.preventDefault(); }
        else if (k === 'PageUp') { this.move(10); e.preventDefault(); }
        else if (k === 'PageDown') { this.move(-10); e.preventDefault(); }
      }
    }

    const syncAll = () => {
      const f = FUNCTIONS.find((x) => x.id === state.fn);
      const sum = state.stages.reduce((a, s) => a + s.minutes, 0);
      $('#fnCaption').textContent = f.name;
      $('#sumFn').textContent = f.name;
      const tempLabel = f.noTemp ? copy.noTemperature : `${state.temp} °C`;
      $('#sumTemp').textContent = tempLabel;
      $('#sumTime').textContent = fmtTime(state.minutes);
      $('#sumRapid').textContent = state.rapid ? copy.yes : copy.no;
      $('#sumLight').textContent = state.light ? copy.yes : copy.no;
      $('#sumTotal').textContent = fmtTime(sum);
      $('#sumCount').textContent = String(state.stages.length);
      const hint = $('#fnHint');
      hint.textContent = f.noTemp
        ? copy.defrost : copy.range(f.max);
      const temperatureControl = $('#wheelTemp');
      temperatureControl.classList.toggle('wheel--off', !!f.noTemp);
      temperatureControl.setAttribute('aria-disabled', String(!!f.noTemp));
      temperatureControl.tabIndex = f.noTemp ? -1 : 0;
      $$('#karuzela [data-temperature-step]').forEach((button) => {
        button.disabled = !!f.noTemp;
      });
      $('#swRapid').disabled = !!f.noTemp;
      $('#swRapid').setAttribute('aria-pressed', String(state.rapid));
      $$('#stageList .stagecard').forEach((card, i) => {
        const s = state.stages[i];
        if (!s) return;
        $('.t', card).textContent = f.noTemp ? copy.temperatureNotApplicable : `${s.temp} °C`;
        $('.m', card).textContent = fmtTime(s.minutes);
      });
      $$('#stageList [data-act^="t"]').forEach((b) => {
        b.disabled = !!f.noTemp;
        b.style.opacity = f.noTemp ? 0.4 : 1;
      });
      $('#stageHint').textContent = copy.stageCount(state.stages.length);
    };

    const tEl = $('#wheelTemp');
    tEl.dataset.originMax = tEl.dataset.max;
    const wheelTemp = new Wheel(tEl, (v) => {
      state.temp = v;
      if (state.stages.length === 1) state.stages[0].temp = v;
      syncAll();
    });
    const wheelTime = new Wheel($('#wheelTime'), (v) => {
      state.minutes = v;
      if (state.stages.length === 1) state.stages[0].minutes = v;
      syncAll();
    });

    /* chipy funkcji */
    fnList.innerHTML = FUNCTIONS.map(
      (f) =>
        `<button class="fn" type="button" data-fn="${f.id}" aria-pressed="${f.id === state.fn}">
           ${f.name}<small>${String(f.id).padStart(2, '0')}</small>
         </button>`
    ).join('');
    fnList.addEventListener('click', (e) => {
      const b = e.target.closest('.fn');
      if (!b) return;
      state.fn = +b.dataset.fn;
      if (state.fn === 10) state.rapid = false;
      const f = FUNCTIONS.find((x) => x.id === state.fn);
      $$('.fn', fnList).forEach((x) => x.setAttribute('aria-pressed', String(+x.dataset.fn === state.fn)));
      wheelTemp.setMax(f.noTemp ? +wheelTemp.el.dataset.originMax : f.max);
      if (!f.noTemp && state.temp > f.max) wheelTemp.setValue(f.max);
      state.stages.forEach((s) => { s.fn = state.fn; if (!f.noTemp && s.temp > f.max) s.temp = f.max; });
      syncAll();
    });


    /* przyciski korekty przy karuzelach */
    $$('.stepbtns [data-nudge]').forEach((b) => {
      const field = b.closest('.field');
      const w = [wheelTemp, wheelTime].find((wheel) => field.contains(wheel.el));
      if (!w) return;
      b.addEventListener('click', () => w.move(+b.dataset.nudge));
    });

    /* przełączniki */
    const bindSwitch = (id, key) => {
      const el = $(id);
      el.addEventListener('click', () => {
        state[key] = !state[key];
        el.setAttribute('aria-pressed', String(state[key]));
        syncAll();
      });
    };
    bindSwitch('#swRapid', 'rapid');
    bindSwitch('#swLight', 'light');

    /* etapy */
    const stageList = $('#stageList');
    const drawStages = () => {
      stageList.innerHTML = state.stages
        .map(
          (s, i) => `
        <div class="stagecard">
          <span class="n">${i + 1}</span>
          <b>${copy.stage(i + 1)}</b>
          <div class="stagecard__ctl">
            <button type="button" class="iconbtn iconbtn--xs" data-act="t-" data-i="${i}" aria-label="${copy.cooler(i + 1)}">−</button>
            <span class="t">180 °C</span>
            <button type="button" class="iconbtn iconbtn--xs" data-act="t+" data-i="${i}" aria-label="${copy.warmer(i + 1)}">+</button>
            <button type="button" class="iconbtn iconbtn--xs" data-act="m-" data-i="${i}" aria-label="${copy.shorter(i + 1)}">−1m</button>
            <span class="m">0:35</span>
            <button type="button" class="iconbtn iconbtn--xs" data-act="m+" data-i="${i}" aria-label="${copy.longer(i + 1)}">+1m</button>
            ${state.stages.length > 1 ? `<button type="button" class="iconbtn iconbtn--xs" data-act="del" data-i="${i}" aria-label="${copy.removeStage(i + 1)}"><svg width="13" height="13"><use href="#i-close"/></svg></button>` : ''}
          </div>
        </div>`
        )
        .join('');
      syncAll();
    };
    stageList.addEventListener('click', (e) => {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const i = +b.dataset.i;
      const f = FUNCTIONS.find((x) => x.id === state.fn);
      const s = state.stages[i];
      const act = b.dataset.act;
      if (act === 't+') s.temp = f.noTemp ? s.temp : clamp(s.temp + 5, 30, f.max);
      if (act === 't-') s.temp = clamp(s.temp - 5, 30, f.max);
      if (act === 'm+') s.minutes = clamp(s.minutes + 1, 1, 600);
      if (act === 'm-') s.minutes = clamp(s.minutes - 1, 1, 600);
      if (act === 'del') state.stages.splice(i, 1);
      if (state.stages.length === 1) {
        state.temp = state.stages[0].temp;
        state.minutes = state.stages[0].minutes;
        wheelTemp.setValue(state.temp);
        wheelTime.setValue(state.minutes);
      }
      if (act === 'del') drawStages(); else syncAll();
    });
    $('#addStage').addEventListener('click', () => {
      if (state.stages.length >= 3) return;
      const last = state.stages[state.stages.length - 1];
      state.stages.push({ fn: state.fn, temp: last.temp, minutes: Math.max(1, last.minutes - 5) });
      drawStages();
      toast(copy.addedStage(state.stages.length));
    });
    $('#resetPlay').addEventListener('click', () => {
      state.fn = 6; state.temp = 180; state.minutes = 35; state.rapid = true; state.light = false;
      state.stages = [{ fn: 6, temp: 180, minutes: 35 }];
      wheelTemp.setValue(180); wheelTime.setValue(35);
      $('#swRapid').setAttribute('aria-pressed', 'true');
      $('#swLight').setAttribute('aria-pressed', 'false');
      $$('.fn', fnList).forEach((x) => x.setAttribute('aria-pressed', String(x.dataset.fn === '6')));
      drawStages();
    });
    drawStages();

    $('#btnSend').addEventListener('click', () => {
      const f = FUNCTIONS.find((x) => x.id === state.fn);
      const time = fmtTime(state.stages.reduce((total, stage) => total + stage.minutes, 0));
      toast(`${f.name} · ${copy.total(state.stages.length, time)}`);
    });
  }

  const toast = (msg) => {
    const t = $('#toast');
    $('#toastText').textContent = msg;
    t.classList.add('is-on');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('is-on'), 3200);
  };

  const lightbox = $('#lightbox');
  const shots = $$('#galRail .gal__item');
  if (lightbox?.showModal && shots.length) {
    let current = 0;
    let opener;
    const show = (index) => {
      current = (index + shots.length) % shots.length;
      const shot = shots[current];
      $('#lbImg').src = shot.href;
      $('#lbImg').alt = $('img', shot).alt;
      $('#lbCap').textContent = `${current + 1} / ${shots.length} · ${$('.gal__cap', shot).textContent}`;
    };
    shots.forEach((shot, index) => shot.addEventListener('click', (event) => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      opener = shot;
      show(index);
      lightbox.showModal();
      $('#lbClose').focus();
    }));
    $('#lbClose').addEventListener('click', () => lightbox.close());
    $('#lbNext').addEventListener('click', () => show(current + 1));
    $('#lbPrev').addEventListener('click', () => show(current - 1));
    lightbox.addEventListener('close', () => opener?.focus());
    lightbox.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
        event.preventDefault();
        show(current + (event.key === 'ArrowRight' ? 1 : -1));
      }
    });
  }
})();
