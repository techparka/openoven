/* OpenOven — strona prezentująca. Bez zależności, bez zewnętrznych żądań. */
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
  const mqDark = matchMedia('(prefers-color-scheme: dark)');
  let choice = localStorage.getItem(KEY) || 'system';

  const applyTheme = () => {
    const dark = choice === 'system' ? mqDark.matches : choice === 'dark';
    html.dataset.theme = dark ? 'dark' : 'light';
    $$('[data-theme-choice]').forEach((b) =>
      b.setAttribute('aria-pressed', String(b.dataset.themeChoice === choice))
    );
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.content = dark ? '#070d13' : '#eef3f6';
    const note = $('#themeNote');
    if (note) {
      note.textContent =
        choice === 'system'
          ? `Motyw podąża za ustawieniem systemowym — teraz ${dark ? 'ciemny' : 'jasny'}.`
          : `Motyw wymuszony na ${choice === 'dark' ? 'ciemny' : 'jasny'}. Wybór zapisany w tej przeglądarce.`;
    }
  };
  mqDark.addEventListener('change', () => choice === 'system' && applyTheme());
  $$('[data-theme-choice]').forEach((b) =>
    b.addEventListener('click', () => {
      choice = b.dataset.themeChoice;
      localStorage.setItem(KEY, choice);
      applyTheme();
    })
  );
  $('#themeToggle')?.addEventListener('click', () => {
    choice = html.dataset.theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem(KEY, choice);
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
  const setMenu = (open) => {
    msheet.classList.toggle('is-open', open);
    menuBtn.setAttribute('aria-expanded', String(open));
    menuBtn.firstElementChild.innerHTML = `<use href="#${open ? 'i-close' : 'i-menu'}"/>`;
    document.body.style.overflow = open ? 'hidden' : '';
  };
  menuBtn?.addEventListener('click', () => setMenu(!msheet.classList.contains('is-open')));
  $$('#msheet a').forEach((a) => a.addEventListener('click', () => setMenu(false)));
  addEventListener('keydown', (e) => e.key === 'Escape' && setMenu(false));

  const navLinks = $$('#nav a');
  const sections = navLinks
    .map((a) => $(a.getAttribute('href')))
    .filter(Boolean);
  if (sections.length) {
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

  /* ========================================================= 3. Rewelacje */
  const revealIO = new IntersectionObserver(
    (entries) =>
      entries.forEach((en) => {
        if (!en.isIntersecting) return;
        en.target.classList.add('is-in');
        if (!en.target.querySelector('[data-count]')) return;
      }),
    { rootMargin: '0px 0px -8% 0px', threshold: 0.12 }
  );
  $$('[data-reveal]').forEach((el) => revealIO.observe(el));
  $$('.feature, .card, .step, .themecard, .timeline__row, .tablewrap, .stats').forEach((el) =>
    revealIO.observe(el)
  );

  /* liczniki */
  const countIO = new IntersectionObserver(
    (entries) =>
      entries.forEach((en) => {
        if (!en.isIntersecting || en.target.dataset.done) return;
        en.target.dataset.done = '1';
        const end = +en.target.dataset.count || 0;
        const suffix = en.target.dataset.suffix || '';
        if (reduce.matches || end === 0) {
          en.target.textContent = end.toLocaleString('pl-PL') + suffix;
          return;
        }
        const t0 = performance.now();
        const dur = 1500;
        const tick = (now) => {
          const p = clamp((now - t0) / dur, 0, 1);
          const eased = 1 - Math.pow(1 - p, 3);
          en.target.textContent = Math.round(end * eased).toLocaleString('pl-PL') + suffix;
          if (p < 1) requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      }),
    { threshold: 0.4 }
  );
  $$('[data-count]').forEach((el) => countIO.observe(el));

  /* podświetlenie kursorami w kartach (poświata) */
  $$('.feature').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty('--mx', `${e.clientX - r.left}px`);
      card.style.setProperty('--my', `${e.clientY - r.top}px`);
    });
  });

  /* słupki odczytu w makiecie */
  $$('[data-bars]').forEach((box) => {
    box.innerHTML = box.dataset.bars
      .split(',')
      .map((h) => `<span style="height:${h}%"></span>`)
      .join('');
  });

  /* ================================================ 4. Marquee funkcji grzania */
  const FUNCTIONS = [
    { id: 1, name: 'Konwencjonalne', max: 280 },
    { id: 2, name: 'Grzałka dolna + górna + wentylator', max: 280 },
    { id: 3, name: 'Grill', max: 280 },
    { id: 4, name: 'Turbo grill', max: 280 },
    { id: 5, name: 'Super grill', max: 280 },
    { id: 6, name: 'Termoobieg', max: 280 },
    { id: 7, name: 'Termoobieg + dolna grzałka', max: 280 },
    { id: 8, name: 'Zarumienienie', max: 230 },
    { id: 9, name: 'Przypiekanie', max: 240 },
    { id: 10, name: 'Rozmrażanie', max: 0, noTemp: true },
    { id: 11, name: 'Grill + rożen', max: 280 },
    { id: 12, name: 'Super grill + rożen', max: 280 },
  ];
  const track = $('#fnTrack');
  if (track) {
    const cell = (f) =>
      `<span class="marquee__item"><i>${String(f.id).padStart(2, '0')}</i>${f.name}</span>`;
    track.innerHTML = FUNCTIONS.map(cell).join('') + FUNCTIONS.map(cell).join('');
  }

  /* ====================================== 5. Makieta w hero: animacja pieczenia */
  const hero = {
    ring: $('#heroRing'),
    temp: $('#heroTemp'),
    time: $('#heroTime'),
    bar: $('#heroBar'),
    stage: $('#heroStage'),
  };
  if (hero.ring && !reduce.matches) {
    const C = 263.9;
    const stages = ['Etap 1 / 3', 'Etap 2 / 3', 'Etap 3 / 3'];
    let temp = 25, minute = 41, sIdx = 1, t0 = performance.now();
    const step = (now) => {
      const el = (now - t0) / 1000;
      const heat = clamp(el / 6, 0, 1);
      temp = Math.round(25 + heat * 155);
      const progress = clamp((41 - (minute - Math.floor(el / 4))) / 41, 0.04, 0.96);
      hero.temp.textContent = temp;
      hero.ring.style.strokeDashoffset = String(C * (1 - (temp - 20) / 270));
      const m = clamp(41 - Math.floor(el / 4), 1, 41);
      hero.time.textContent = `0:${String(m).padStart(2, '0')}`;
      hero.bar.style.width = `${(1 - m / 41) * 100}%`;
      hero.stage.textContent = stages[clamp(Math.floor(el / 9), 0, 2)];
      if (el > 46) { t0 = now; }
      requestAnimationFrame(step);
    };
    const startHero = () => requestAnimationFrame(step);
    const heroIO = new IntersectionObserver((e) => {
      if (e[0].isIntersecting) { startHero(); heroIO.disconnect(); }
    });
    heroIO.observe(hero.ring.closest('.device'));
  }

  /* ================================================= 6. Przewodnik (sticky tour) */
  const screens = $$('#tourScreens .ui__scr');
  const steps = $$('#tourSteps .step');
  const dotsBox = $('#dots');
  if (screens.length && steps.length) {
    if (dotsBox) dotsBox.innerHTML = steps.map(() => '<i></i>').join('');
    const dots = dotsBox ? $$('i', dotsBox) : [];
    let current = -1;
    const show = (i) => {
      if (i === current) return;
      current = i;
      screens.forEach((s, k) => s.classList.toggle('is-on', k === i));
      steps.forEach((s, k) => s.classList.toggle('is-active', k === i));
      dots.forEach((d, k) => d.classList.toggle('on', k === i));
    };
    const stepIO = new IntersectionObserver(
      (entries) => {
        entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)
          .slice(0, 1)
          .forEach((e) => show(steps.indexOf(e.target)));
      },
      { rootMargin: '-30% 0px -30% 0px', threshold: [0.05, 0.35, 0.75] }
    );
    steps.forEach((s) => stepIO.observe(s));
    show(0);
  }

  /* ================================================ 7. Playground nastaw */
  let wheels = [];
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
        this.el.setAttribute('aria-valuetext', this.isTime ? `${this.value} minut` : `${this.value} stopni Celsjusza`);
        this.onChange(this.value);
      }
      move(delta) {
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
      const tempLabel = f.noTemp ? 'bez temperatury' : `${state.temp} °C`;
      $('#sumTemp').textContent = tempLabel;
      $('#sumTime').textContent = fmtTime(state.minutes);
      $('#sumRapid').textContent = state.rapid ? 'tak' : 'nie';
      $('#sumLight').textContent = state.light ? 'tak' : 'nie';
      $('#sumTotal').textContent = fmtTime(sum);
      $('#sumCount').textContent = String(state.stages.length);
      const hint = $('#fnHint');
      hint.textContent = f.noTemp
        ? 'Rozmrażanie pomija nastawy temperatury i sondy — piekarnik nie przyjmuje tu stopni.'
        : `Zakres tej funkcji: 30–${f.max} °C. Wartości poza progiem nie da się wystartować.`;
      const warn = $('#sumWarn');
      const problems = [];
      if (!f.noTemp && state.temp > f.max) problems.push(`${f.name} kończy się na ${f.max} °C.`);
      if (sum > 600) problems.push('Suma etapów przekracza 600 minut.');
      if (f.noTemp && state.stages.some((s) => s.temp > 0)) problems.push('Przy rozmrażaniu pomijamy temperaturę.');
      warn.hidden = !problems.length;
      warn.textContent = problems.join(' ');
      warn.classList.toggle('hint--warn', true);
      $('#wheelTemp').classList.toggle('wheel--off', !!f.noTemp);
      $$('#stageList .stagecard').forEach((card, i) => {
        const s = state.stages[i];
        if (!s) return;
        $('.t', card).textContent = f.noTemp ? '—' : `${s.temp} °C`;
        $('.m', card).textContent = fmtTime(s.minutes);
      });
      $$('#stageList [data-act^="t"]').forEach((b) => {
        b.disabled = !!f.noTemp;
        b.style.opacity = f.noTemp ? 0.4 : 1;
      });
      $('#stageHint').textContent = state.stages.length >= 3 ? 'limit trzech etapów' : `etap ${state.stages.length} z 3`;
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
      const f = FUNCTIONS.find((x) => x.id === state.fn);
      $$('.fn', fnList).forEach((x) => x.setAttribute('aria-pressed', String(+x.dataset.fn === state.fn)));
      wheelTemp.setMax(f.noTemp ? +wheelTemp.el.dataset.originMax : f.max);
      if (!f.noTemp && state.temp > f.max) wheelTemp.setValue(f.max);
      state.stages.forEach((s) => { s.fn = state.fn; if (!f.noTemp && s.temp > f.max) s.temp = f.max; });
      syncAll();
    });

    wheels = [wheelTemp, wheelTime];

    /* przyciski korekty przy karuzelach */
    $$('.stepbtns [data-nudge]').forEach((b) => {
      const field = b.closest('.field');
      const w = wheels.find((x) => field.contains(x.el));
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
          <b>Etap ${i + 1}</b>
          <div class="stagecard__ctl">
            <button type="button" class="iconbtn iconbtn--xs" data-act="t-" data-i="${i}" aria-label="Temperatura niższa w etapie ${i + 1}">−</button>
            <span class="t">180 °C</span>
            <button type="button" class="iconbtn iconbtn--xs" data-act="t+" data-i="${i}" aria-label="Temperatura wyższa w etapie ${i + 1}">+</button>
            <button type="button" class="iconbtn iconbtn--xs" data-act="m-" data-i="${i}" aria-label="Minuta mniej w etapie ${i + 1}">−1m</button>
            <span class="m">0:35</span>
            <button type="button" class="iconbtn iconbtn--xs" data-act="m+" data-i="${i}" aria-label="Minuta więcej w etapie ${i + 1}">+1m</button>
            ${state.stages.length > 1 ? `<button type="button" class="iconbtn iconbtn--xs" data-act="del" data-i="${i}" aria-label="Usuń etap ${i + 1}"><svg width="13" height="13"><use href="#i-close"/></svg></button>` : ''}
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
      toast(`Etap ${state.stages.length} dołożony`);
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
      toast(`Symulator: ${f.name}, ${f.noTemp ? 'bez temperatury' : state.temp + ' °C'}, ${fmtTime(state.minutes)}`);
    });
  }

  const toast = (msg) => {
    const t = $('#toast');
    $('#toastText').textContent = msg;
    t.classList.add('is-on');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('is-on'), 3200);
  };

  /* =========================================== 8. Galeria + lightbox */
  const rail = $('#galRail');
  if (rail) {
    const items = $$('.gal__item', rail);
    const step = () => (items[0]?.offsetWidth || 260) + 24;
    let moved = 0;
    $('#galNext').addEventListener('click', () => rail.scrollBy({ left: step(), behavior: 'smooth' }));
    $('#galPrev').addEventListener('click', () => rail.scrollBy({ left: -step(), behavior: 'smooth' }));
    rail.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight') { rail.scrollBy({ left: step(), behavior: 'smooth' }); e.preventDefault(); }
      if (e.key === 'ArrowLeft') { rail.scrollBy({ left: -step(), behavior: 'smooth' }); e.preventDefault(); }
    });
    let down = false, startX = 0, startLeft = 0;
    rail.addEventListener('pointerdown', (e) => {
      down = true; moved = 0; startX = e.clientX; startLeft = rail.scrollLeft;
    });
    rail.addEventListener('pointermove', (e) => {
      if (!down) return;
      moved = Math.max(moved, Math.abs(e.clientX - startX));
      if (moved > 10) rail.classList.add('is-drag');
      rail.scrollLeft = startLeft - (e.clientX - startX);
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) =>
      rail.addEventListener(ev, () => {
        down = false;
        rail.classList.remove('is-drag');
      })
    );

    /* lightbox */
    const lb = $('#lightbox');
    const shots = items.map((it) => ({
      src: $('img', it).src,
      alt: $('img', it).alt,
      cap: ($('b', $('.gal__cap', it)) ? $('.gal__cap', it).lastChild.textContent : $('.gal__cap', it).textContent).trim(),
    }));
    let li = 0;
    const open = (i) => {
      li = clamp(i, 0, shots.length - 1);
      $('#lbImg').src = shots[li].src;
      $('#lbImg').alt = shots[li].alt;
      $('#lbCap').textContent = `${li + 1} / ${shots.length} · ${shots[li].cap}`;
      if (!lb.open) lb.showModal();
      const it = items[li];
      rail.scrollTo({ left: Math.max(0, it.offsetLeft - (rail.clientWidth - it.offsetWidth) / 2), behavior: 'auto' });
    };
    items.forEach((it, i) => {
      it.tabIndex = 0;
      it.setAttribute('role', 'button');
      it.setAttribute('aria-label', `Powiększ: ${$('.gal__cap', it).textContent.trim()}`);
      const go = () => open(i);
      it.addEventListener('click', () => { if (moved < 10) go(); });
      it.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); }
      });
      $('img', it).loading = 'lazy';
    });
    $('#lbClose').addEventListener('click', () => lb.close());
    $('#lbNext').addEventListener('click', () => open(li + 1));
    $('#lbPrev').addEventListener('click', () => open(li - 1));
    lb.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight') open(li + 1);
      if (e.key === 'ArrowLeft') open(li - 1);
    });
  }

  /* ==================================== 9. Kroki parowania — auto podświetlenie */
  const pair = $$('#pairSteps .pstep');
  if (pair.length && !reduce.matches) {
    let pi = 0, timer = null;
    const cycle = () => {
      const i = pi % pair.length;
      pair.forEach((p, k) => p.classList.toggle('is-active', k === i));
      pi = i + 1;
    };
    const play = () => { clearInterval(timer); timer = setInterval(cycle, 2400); };
    cycle(); play();
    pair.forEach((p, idx) =>
      p.addEventListener('pointerenter', () => {
        clearInterval(timer);
        pair.forEach((q, k) => q.classList.toggle('is-active', k === idx));
        pi = idx + 1;
      })
    );
    $('#pairSteps').addEventListener('pointerleave', play);
  }

  /* ============================== 10. Waga strony (bez analityki, bez CDN) */
  addEventListener('load', () => {
    const el = $('#pageWeight');
    if (!el || !performance.getEntriesByType) return;
    const bytes = performance
      .getEntriesByType('resource')
      .concat(performance.getEntriesByType('navigation'))
      .reduce((a, r) => a + (r.decodedBodySize || 0), 0);
    if (!bytes) return;
    const kb = bytes / 1024;
    el.textContent = `cała strona: ${kb > 1024 ? (kb / 1024).toFixed(2) + ' MB' : Math.round(kb) + ' kB'} transferu, zero zewnętrznych żądań`;
  });
})();
