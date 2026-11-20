/* Waylo site motion. Progressive enhancement only: the styles that hide an element before it
   reveals are scoped to .motion, which this file adds, so a page without scripting stays complete.
   Under reduced motion the file adds nothing. */
(() => {
  const top = document.querySelector("body > .top");
  if (top) {
    const onScroll = () => top.classList.toggle("scrolled", window.scrollY > 6);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
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

  // Cards, tiles and definition rows stagger in by their place in the group. The screenshot strip
  // is left alone: its cards sit in a horizontal scroller, so off-screen ones would stay hidden
  // until the strip itself is scrolled.
  for (const group of document.querySelectorAll(".things, .ways, .docs, .say, .facts, .kit")) {
    Array.from(group.children).forEach((el, i) => reveal(el, i));
  }

  // Section headings and their opening paragraph.
  for (const block of document.querySelectorAll("section > .col, section > .wide")) {
    for (const el of block.querySelectorAll(":scope > h2, :scope > p")) reveal(el, 0);
  }
})();
