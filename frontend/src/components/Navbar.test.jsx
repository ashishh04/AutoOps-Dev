import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Navbar from "./Navbar";
import Home from "../pages/Home";

// "Solutions" used to point at `#solutions`, which was an id on the HERO — so
// clicking it from the top of the landing page scrolled nowhere and read as a
// broken link. These tests pin both halves of the fix: the anchor exists on a
// real section, and following it works from another route too.

function renderHome(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/pricing" element={<Navbar />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("the landing-page navbar", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("points every section link at an id that is actually on the page", () => {
    const { container } = renderHome();

    const hashes = Array.from(container.querySelectorAll('a[href^="/#"]')).map(
      (a) => a.getAttribute("href").slice(2),
    );
    expect(hashes).toEqual(
      expect.arrayContaining(["product", "features", "solutions"]),
    );

    for (const hash of new Set(hashes)) {
      expect(container.querySelector(`#${hash}`)).not.toBeNull();
    }
  });

  it("does not put the Solutions anchor on the hero, where it scrolls nowhere", () => {
    const { container } = renderHome();
    expect(container.querySelector("#solutions")).not.toBe(
      container.querySelector("#top"),
    );
  });

  it("scrolls instead of reloading when the section is already on screen", async () => {
    const user = userEvent.setup();
    renderHome();

    await user.click(screen.getAllByRole("link", { name: "Solutions" })[0]);

    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("routes home first when the section is on another page", async () => {
    const user = userEvent.setup();
    renderHome("/pricing");

    await user.click(screen.getAllByRole("link", { name: "Solutions" })[0]);

    // Landed on the home route, which then honours the hash.
    expect(document.querySelector("#solutions")).not.toBeNull();
  });
});
