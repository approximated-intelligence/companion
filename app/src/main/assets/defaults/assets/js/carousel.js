/* =============================================================================
   carousel.js - progressive enhancement, zero dependencies
   No-JS: stacked images/videos with native controls, all visible.
   JS: single-item view with fade transitions and lazy loading.
   Modes: "full" (dots + prev/next), "card" (dots + count badge only).
   ============================================================================= */
(function () {
  'use strict';

  function initCarousel(el) {
    try {
      var slides = Array.prototype.slice.call(el.querySelectorAll('.carousel-slide'));
      if (slides.length === 0) return;

      var mode     = el.getAttribute('data-mode') || 'card';
      var dotsWrap = el.querySelector('.carousel-dots');
      var prevBtn  = el.querySelector('.carousel-prev');
      var nextBtn  = el.querySelector('.carousel-next');
      var countEl  = el.querySelector('.carousel-count');
      var current  = 0;
      var total    = slides.length;

      el.classList.add('carousel-enhanced');

      /* ---- build dot buttons ---- */
      var dots = slides.map(function (_, i) {
        var dot = document.createElement('button');
        dot.className = 'carousel-dot';
        dot.setAttribute('aria-label', 'Item ' + (i + 1) + ' of ' + total);
        dot.addEventListener('click', function (e) {
          e.preventDefault(); e.stopPropagation(); goTo(i);
        });
        dotsWrap.appendChild(dot);
        return dot;
      });

      /* ---- lazy src swap: defer loading until slide is activated ---- */
      function activateMedia(slide) {
        var img = slide.querySelector('img[data-src]');
        if (img && !img.src) {
          img.src = img.getAttribute('data-src');
        }
        var video = slide.querySelector('video[data-src]');
        if (video && !video.src) {
          video.src = video.getAttribute('data-src');
          video.preload = 'metadata';
        }
      }

      /* ---- update count badge ---- */
      function updateCount() {
        if (countEl) {
          countEl.textContent = (current + 1) + '\u2009/\u2009' + total;
        }
      }

      /* ---- navigate ---- */
      function goTo(index) {
        try {
          var prev = slides[current];
          var prevVideo = prev.querySelector('video');
          if (prevVideo) prevVideo.pause();
          prev.classList.remove('is-active');
          dots[current].classList.remove('is-active');
          dots[current].removeAttribute('aria-current');

          current = ((index % total) + total) % total;

          var next = slides[current];
          activateMedia(next);
          next.classList.add('is-active');
          dots[current].classList.add('is-active');
          dots[current].setAttribute('aria-current', 'true');
          updateCount();
        } catch (e) { /* silent */ }
      }

      /* ---- set non-first slides to lazy load instead of stripping src ---- */
      slides.forEach(function (slide, i) {
        if (i === 0) return;
        var img = slide.querySelector('img');
        if (img) {
          img.removeAttribute('src');
          img.loading = 'lazy';
        }
        var video = slide.querySelector('video');
        if (video) {
          video.removeAttribute('src');
          video.preload = 'none';
        }
      });

      goTo(0);

      /* ---- prev / next (full mode only) ---- */
      if (prevBtn) prevBtn.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation(); goTo(current - 1);
      });
      if (nextBtn) nextBtn.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation(); goTo(current + 1);
      });

      /* ---- keyboard: listen on carousel and parent link ---- */
      function handleKey(e) {
        if (e.key === 'ArrowLeft')  { goTo(current - 1); e.preventDefault(); e.stopPropagation(); }
        if (e.key === 'ArrowRight') { goTo(current + 1); e.preventDefault(); e.stopPropagation(); }
      }

      el.setAttribute('tabindex', '0');
      el.addEventListener('keydown', handleKey);
       
      /* focus on mouseover */
      el.addEventListener('mouseover', function () {
        el.focus();
      });

      /* also listen on the card link wrapper if present */
      var cardLink = el.closest('.post-card-link');
      if (cardLink) {
        cardLink.addEventListener('keydown', handleKey);
      }

      /* ---- touch swipe ---- */
      var tx = null, ty = null, swiped = false;

      el.addEventListener('touchstart', function (e) {
        try { tx = e.touches[0].clientX; ty = e.touches[0].clientY; swiped = false; }
        catch (err) { /* silent */ }
      }, { passive: true });

      el.addEventListener('touchmove', function (e) {
        if (tx === null) return;
        try {
          var dx = Math.abs(e.touches[0].clientX - tx);
          var dy = Math.abs(e.touches[0].clientY - ty);
          if (dx > 10 && dx > dy) swiped = true;
        } catch (err) { /* silent */ }
      }, { passive: true });

      el.addEventListener('touchend', function (e) {
        try {
          if (tx === null) return;
          var dx = e.changedTouches[0].clientX - tx;
          var dy = e.changedTouches[0].clientY - ty;
          if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 40) {
            goTo(dx < 0 ? current + 1 : current - 1);
            swiped = true;
          }
        } catch (err) { /* silent */ }
        finally { tx = null; ty = null; }
      }, { passive: true });

      /* ---- prevent card navigation on swipe ---- */
      el.addEventListener('click', function (e) {
        if (swiped) { e.preventDefault(); e.stopPropagation(); swiped = false; }
      }, true);

    } catch (e) { /* silent fail - no-JS layout intact */ }
  }

  function init() {
    try {
      Array.prototype.slice.call(document.querySelectorAll('.carousel'))
           .forEach(initCarousel);
    } catch (e) { /* silent */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
}());
