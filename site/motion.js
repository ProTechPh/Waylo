/* Waylo site motion. Progressive enhancement only: the styles that hide an element before it
   reveals are scoped to .motion, which this file adds, so a page without scripting stays complete.
   Under reduced motion the file adds nothing that moves. */
(() => {
  const top = document.querySelector("body > .top");
  if (top) {
    const onScroll = () => top.classList.toggle("scrolled", window.scrollY > 6);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // The strip is one track plus a copy of it; a third copy is what makes the loop seamless.
  // Without scripting the one track simply sits still.
  const track = document.querySelector(".marquee-track");
  if (track) {
    const copy = track.cloneNode(true);
    copy.setAttribute("aria-hidden", "true");
    track.after(copy);
    track.parentElement.classList.add("ready");
  }

  if (reduced) return;

  // Everything that follows the scroll shares one rAF pass.
  const stage = document.querySelector(".stage");
  const rail = stage && stage.querySelector(".rail");
  const map = document.querySelector(".mapcard img");
  const bar = document.createElement("div");
  let travel = 0;

  // Reading progress.
  bar.className = "progress";
  bar.setAttribute("aria-hidden", "true");
  document.body.append(bar);

  // The screenshot stage only pins when the strip is wider than the screen. Otherwise it stays a
  // plain horizontal scroller, which is also what a phone gets.
  const measure = () => {
    if (!stage || !rail) return;
    stage.classList.remove("driven");
    stage.style.height = "";
    travel = 0;
    const over = rail.scrollWidth - rail.clientWidth;
    if (innerWidth < 896 || over < 140) return;
    travel = over;
    stage.classList.add("driven");
    stage.style.height = `${innerHeight + travel}px`;
    rail.scrollLeft = 0;
  };

  const clamp = (n) => (n < 0 ? 0 : n > 1 ? 1 : n);

  const paint = () => {
    const max = document.documentElement.scrollHeight - innerHeight;
    bar.style.setProperty("--p", max > 0 ? String(clamp(scrollY / max)) : "0");

    if (travel) {
      const box = stage.getBoundingClientRect();
      const p = clamp(-box.top / travel);
      rail.style.setProperty("--shift", String(Math.round(p * travel)));
    }

    // A shallow lift on the hero map, so the top of the page moves at its own pace.
    if (map && scrollY < innerHeight * 1.5) {
      map.style.transform = `translate3d(0, ${(scrollY * 0.045).toFixed(1)}px, 0) scale(1.06)`;
    }
  };

  let queued = false;
  const request = () => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => { queued = false; paint(); });
  };

  measure();
  paint();
  addEventListener("scroll", request, { passive: true });
  addEventListener("resize", () => { measure(); request(); }, { passive: true });
  addEventListener("load", () => { measure(); request(); });

  if (!("IntersectionObserver" in window)) return;

  document.documentElement.classList.add("motion");

  const io = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      entry.target.classList.add("in");
      io.unobserve(entry.target);
    }
  }, { rootMargin: "0px 0px -6% 0px", threshold: 0.12 });

  const reveal = (el, i) => {
    el.classList.add("reveal");
    if (i) el.style.setProperty("--i", String(Math.min(i, 6)));
    io.observe(el);
  };

  // Cards stagger in by their place in the group. The stage is left alone: its cards sit in a
  // sideways strip, so off-screen ones would stay hidden until the strip moved.
  for (const group of document.querySelectorAll(".bento, .ways, .docs, .say, .facts, .kit")) {
    Array.from(group.children).forEach((el, i) => reveal(el, i));
  }

  // Section headings and their opening paragraph, including the pinned ones.
  for (const block of document.querySelectorAll("section > .col, section > .wide, .split .spine")) {
    for (const el of block.querySelectorAll(":scope > h2, :scope > p, :scope > .kicker")) reveal(el, 0);
  }
})();
