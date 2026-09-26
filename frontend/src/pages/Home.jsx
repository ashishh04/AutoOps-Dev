import React, { useEffect } from "react";
import { useLocation } from "react-router-dom";
import Navbar from "../components/Navbar";
import Footer from "../components/Footer";
import Hero from "../sections/Hero";
import OrchestrateMarquee from "../sections/OrchestrateMarquee";
import WorkflowDesigner from "../sections/WorkflowDesigner";
import Observability from "../sections/Observability";
import Capabilities from "../sections/Capabilities";
import Enterprise from "../sections/Enterprise";
import FinalCTA from "../sections/FinalCTA";

export default function Home() {
  const { hash } = useLocation();

  // Arriving from another route with a section hash — the navbar and the footer
  // both do this. The frame wait matters: the sections below have not been laid
  // out yet on the first paint, so scrolling immediately lands short.
  useEffect(() => {
    if (!hash) return;
    const id = hash.slice(1);
    const frame = requestAnimationFrame(() => {
      document
        .getElementById(id)
        ?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    return () => cancelAnimationFrame(frame);
  }, [hash]);

  return (
    <div className="min-h-screen bg-white text-slate-700">
      <Navbar />
      <main>
        <Hero />
        <OrchestrateMarquee />
        <WorkflowDesigner />
        <Observability />
        <Capabilities />
        <Enterprise />
        <FinalCTA />
      </main>
      <Footer />
    </div>
  );
}
